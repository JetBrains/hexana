package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.hexana.graalwasm.wasmprinter.WatWithWarnings
import org.jetbrains.hexana.graalwasm.wasmprinter.printWat
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.WasmInputBytesLoader
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryResult
import org.jetbrains.hexana.mcp.model.converters.WasmToWatResult
import org.jetbrains.hexana.mcp.model.scripting.RunBinaryScriptDiagnosticResult
import org.jetbrains.hexana.mcp.model.scripting.RunBinaryScriptOutputResult
import org.jetbrains.hexana.mcp.model.scripting.RunBinaryScriptResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaScriptRunner
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSource
import org.jetbrains.hexana.mcp.tool.runtime.ToolInputSourceSelection
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

internal data class HexanaCliCommandOutput(
    val stdout: String,
    val stderr: String = "",
)

internal fun renderSummarizeModuleOutput(
    command: HexanaCommand.ExecutableTool,
    analysisService: HexanaAnalysisService = createStandaloneHexanaAnalysisService(
        command.allowedRoots,
        command.resourceLimits
    ),
): String = renderSummarizeModuleCommandOutput(command, analysisService).stdout

internal fun renderSummarizeModuleCommandOutput(
    command: HexanaCommand.ExecutableTool,
    analysisService: HexanaAnalysisService = createStandaloneHexanaAnalysisService(
        command.allowedRoots,
        command.resourceLimits
    ),
): HexanaCliCommandOutput {
    requireExecutableTool(command, toolName = "summarize_module")
    val result = analysisService.summarizeModule(command.wasmInputSource())
    val output = when (command.format) {
        HexanaCliOutputFormat.JSON -> HexanaJson.compact.encodeToString(SummarizeModuleResult.serializer(), result)
        HexanaCliOutputFormat.PRETTY -> renderSummarizeModulePretty(result)
        HexanaCliOutputFormat.RAW -> error("summarize-module does not support raw output")
    }
    return requireOutputWithinLimit(
        output = HexanaCliCommandOutput(stdout = output),
        maxOutputBytes = command.resourceLimits.maxOutputBytes,
        commandName = command.spec.commandName,
    )
}

internal fun renderSummarizeBinaryOutput(
    command: HexanaCommand.ExecutableTool,
    analysisService: HexanaAnalysisService = createStandaloneHexanaAnalysisService(
        command.allowedRoots,
        command.resourceLimits
    ),
): String = renderSummarizeBinaryCommandOutput(command, analysisService).stdout

internal fun renderSummarizeBinaryCommandOutput(
    command: HexanaCommand.ExecutableTool,
    analysisService: HexanaAnalysisService = createStandaloneHexanaAnalysisService(
        command.allowedRoots,
        command.resourceLimits
    ),
): HexanaCliCommandOutput {
    requireExecutableTool(command, toolName = "summarize_binary")
    val result = analysisService.summarizeBinary(command.binaryInputSource())
    val output = when (command.format) {
        HexanaCliOutputFormat.JSON -> HexanaJson.compact.encodeToString(BinarySummaryResult.serializer(), result)
        HexanaCliOutputFormat.PRETTY -> error("summarize-binary does not support pretty output")
        HexanaCliOutputFormat.RAW -> error("summarize-binary does not support raw output")
    }
    return requireOutputWithinLimit(
        output = HexanaCliCommandOutput(stdout = output),
        maxOutputBytes = command.resourceLimits.maxOutputBytes,
        commandName = command.spec.commandName,
    )
}

internal fun renderWasmToWatOutput(command: HexanaCommand.ExecutableTool): String =
    renderWasmToWatCommandOutput(command).stdout

internal fun renderWasmToWatCommandOutput(
    command: HexanaCommand.ExecutableTool,
    wasmPrinter: (ByteArray) -> WatWithWarnings = ::printWat,
): HexanaCliCommandOutput {
    requireExecutableTool(command, toolName = "wasm_to_wat")
    val allowedFilePolicy = if (command.allowedRoots.none { it.isNotBlank() }) {
        AllowedFilePolicy.allowAll
    } else {
        AllowedFilePolicy.requiringAllowedRoots(command.allowedRoots)
    }
    val loaded = WasmInputBytesLoader(
        maxInlineInputBytes = command.resourceLimits.maxInlineWasmBytes,
        maxFileInputBytes = command.resourceLimits.maxFileWasmBytes,
        allowedFilePolicy = allowedFilePolicy,
    ).load(command.wasmInputSource())
    val watWithWarnings = try {
        wasmPrinter(loaded.bytes)
    } catch (error: HexanaAnalysisException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.NOT_WASM,
            "Input is not a valid WebAssembly module.",
            error,
        )
    }
    val result = WasmToWatResult(
        wat = watWithWarnings.wat,
        warnings = watWithWarnings.warnings
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty(),
    )
    val output = when (command.format) {
        HexanaCliOutputFormat.JSON -> HexanaCliCommandOutput(
            stdout = HexanaJson.compact.encodeToString(WasmToWatResult.serializer(), result)
        )
        HexanaCliOutputFormat.RAW -> HexanaCliCommandOutput(
            stdout = result.wat,
            stderr = result.warnings.toStderrWarnings()
        )
        HexanaCliOutputFormat.PRETTY -> error("wasm-to-wat does not support pretty output")
    }
    return requireOutputWithinLimit(
        output = output,
        maxOutputBytes = command.resourceLimits.maxOutputBytes,
        commandName = command.spec.commandName,
    )
}

internal fun renderRunBinaryScriptCommandOutput(
    command: HexanaCommand.ExecutableTool,
    scriptRunner: (BinaryInput, HexanaScriptSource) -> RunBinaryScriptResult = { input, source ->
        HexanaScriptRunner(
            limits = command.scriptLimits(),
            allowedFilePolicy = standaloneAllowedFilePolicy(command.allowedRoots),
        ).run(input, source).toRunBinaryScriptResult()
    },
): HexanaCliCommandOutput {
    requireExecutableTool(command, toolName = "run_binary_script")
    val result = when (val source = command.scriptSourceSelection()) {
        is ScriptSourceSelection.Valid -> scriptRunner(command.binaryInputSource(slotName = "binary"), source.source)
        is ScriptSourceSelection.Invalid -> command.invalidRunBinaryScriptResult(source.message)
    }
    val output = when (command.format) {
        HexanaCliOutputFormat.JSON -> HexanaCliCommandOutput(
            stdout = HexanaJson.compact.encodeToString(RunBinaryScriptResult.serializer(), result)
        )
        HexanaCliOutputFormat.RAW -> result.toRawRunScriptOutput(command.resourceLimits.maxOutputBytes)
        HexanaCliOutputFormat.PRETTY -> error("run-script does not support pretty output")
    }
    return requireOutputWithinLimit(
        output = output,
        maxOutputBytes = command.resourceLimits.maxOutputBytes,
        commandName = command.spec.commandName,
    )
}

internal fun renderTodoToolNotImplementedMessage(command: HexanaCommand.TodoTool): String =
    "Hexana MCP CLI command '${command.spec.commandName}' for tool '${command.spec.toolName}' is not implemented yet (TODO)."

private fun requireOutputWithinLimit(
    output: HexanaCliCommandOutput,
    maxOutputBytes: Int,
    commandName: String,
): HexanaCliCommandOutput {
    val byteSize = output.stdout.byteSize() + output.stderr.byteSize()
    if (byteSize > maxOutputBytes) {
        throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
            "$commandName output is $byteSize bytes, exceeding maxOutputBytes=$maxOutputBytes."
        )
    }
    return output
}

private fun requireExecutableTool(command: HexanaCommand.ExecutableTool, toolName: String) {
    require(command.spec.toolName == toolName) {
        "Expected CLI tool '$toolName', got '${command.spec.toolName}'."
    }
}

internal fun HexanaCommand.ExecutableTool.pathSource(): String {
    return when (val source = sourceSelection) {
        is ToolInputSourceSelection.Path -> source.path
        else -> error("${spec.commandName} requires a path source, got ${source.mcpPayloadFieldName}.")
    }
}

internal val HexanaCommand.ExecutableTool.path: String
    get() = pathSource()

private fun HexanaCommand.ExecutableTool.wasmInputSource(): WasmInput {
    return when (val source = sourceSelection) {
        is ToolInputSourceSelection.Path -> WasmInput.FilePath(source.path)
        is ToolInputSourceSelection.Base64 -> WasmInput.InlineBytes(source.bytesBase64)
        is ToolInputSourceSelection.StdinBytes -> WasmInput.RawBytes(source.bytes())
        is ToolInputSourceSelection.ByteList -> WasmInput.RawBytes(source.bytes.toByteArray())
    }
}

private fun HexanaCommand.ExecutableTool.binaryInputSource(): BinaryInput {
    return when (val source = sourceSelection) {
        is ToolInputSourceSelection.Path -> BinaryInput.FilePath(source.path)
        is ToolInputSourceSelection.Base64 -> BinaryInput.InlineBytes(source.bytesBase64)
        is ToolInputSourceSelection.StdinBytes -> BinaryInput.RawBytes(source.bytes())
        is ToolInputSourceSelection.ByteList -> BinaryInput.RawBytes(source.bytes.toByteArray())
    }
}

private fun HexanaCommand.ExecutableTool.binaryInputSource(slotName: String): BinaryInput {
    return when (val source = sourceSelection(slotName)) {
        is ToolInputSourceSelection.Path -> BinaryInput.FilePath(source.path)
        is ToolInputSourceSelection.Base64 -> BinaryInput.InlineBytes(source.bytesBase64)
        is ToolInputSourceSelection.StdinBytes -> BinaryInput.RawBytes(source.bytes())
        is ToolInputSourceSelection.ByteList -> BinaryInput.RawBytes(source.bytes.toByteArray())
    }
}

private fun HexanaCommand.ExecutableTool.sourceSelection(slotName: String): ToolInputSourceSelection =
    sourceSelections.singleOrNull { it.slotName == slotName }
        ?: error("${spec.commandName} requires exactly one `$slotName` source selection.")

private fun HexanaCommand.ExecutableTool.scriptLimits(): HexanaScriptLimits =
    HexanaScriptLimits(
        maxOutputBytes = resourceLimits.maxOutputBytes,
        maxInlineScriptBytes = resourceLimits.maxTextFileBytes,
        maxScriptFileBytes = resourceLimits.maxTextFileBytes,
        maxInlineBinaryBytes = resourceLimits.maxInlineBinaryBytes,
        maxFileBinaryBytes = resourceLimits.maxFileBinaryBytes,
    )

private fun HexanaCommand.ExecutableTool.scriptSourceSelection(): ScriptSourceSelection {
    val scriptPath = params.optionalStringParam("scriptPath")
    val script = params.optionalStringParam("script")
    val providedInputs = listOf(scriptPath, script).count { it != null }
    if (providedInputs == 0) {
        return ScriptSourceSelection.Invalid("Exactly one of --script or --script-text is required.")
    }
    if (providedInputs > 1) {
        return ScriptSourceSelection.Invalid("Provide exactly one of --script or --script-text.")
    }
    return when {
        scriptPath != null -> ScriptSourceSelection.Valid(HexanaScriptSource.FilePath(scriptPath))
        script != null -> ScriptSourceSelection.Valid(HexanaScriptSource.Inline(script))
        else -> error("Unreachable script source state")
    }
}

private fun Map<String, JsonPrimitive>.optionalStringParam(name: String): String? {
    val value = this[name]?.contentOrNull ?: return null
    if (value.isBlank()) {
        return null
    }
    return value
}

private fun HexanaCommand.ExecutableTool.invalidRunBinaryScriptResult(message: String): RunBinaryScriptResult =
    RunBinaryScriptResult(
        status = "INVALID_INPUT",
        success = false,
        output = RunBinaryScriptOutputResult(
            lines = emptyList(),
            truncated = false,
            emittedBytes = 0,
            maxLines = 0,
            maxBytes = resourceLimits.maxOutputBytes,
        ),
        diagnostics = listOf(
            RunBinaryScriptDiagnosticResult(
                code = "INVALID_INPUT",
                message = message,
                severity = "ERROR",
            )
        ),
        binary = null,
        sourceName = "<script>",
        sourcePath = null,
        durationMillis = 0,
    )

private fun RunBinaryScriptResult.toRawRunScriptOutput(maxOutputBytes: Int): HexanaCliCommandOutput {
    val stderr = diagnostics.toRunScriptDiagnosticsStderr()
    val stdoutBudget = maxOutputBytes - stderr.byteSize()
    return HexanaCliCommandOutput(
        stdout = output.lines.joinToString(separator = "\n").takeUtf8Prefix(stdoutBudget),
        stderr = stderr,
        exitCode = runScriptExitCode(),
    )
}

private fun List<RunBinaryScriptDiagnosticResult>.toRunScriptDiagnosticsStderr(): String {
    if (isEmpty()) {
        return ""
    }
    return joinToString(separator = "\n", postfix = "\n") { diagnostic ->
        "${diagnostic.code}: ${diagnostic.message.asSingleLine()}"
    }
}

private fun String.asSingleLine(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")

private fun org.jetbrains.hexana.mcp.scripting.HexanaScriptResult.toRunBinaryScriptResult(): RunBinaryScriptResult =
    RunBinaryScriptResult(
        status = status.name,
        success = success,
        output = RunBinaryScriptOutputResult(
            lines = output.lines,
            truncated = output.truncated,
            emittedBytes = output.emittedBytes,
            maxLines = output.maxLines,
            maxBytes = output.maxBytes,
        ),
        diagnostics = diagnostics.map { diagnostic ->
            RunBinaryScriptDiagnosticResult(
                code = diagnostic.code,
                message = diagnostic.message,
                severity = diagnostic.severity.name,
                exceptionClass = diagnostic.exceptionClass,
                sourceName = diagnostic.sourceName,
                line = diagnostic.line,
                column = diagnostic.column,
            )
        },
        binary = binary,
        sourceName = sourceName,
        sourcePath = sourcePath,
        durationMillis = durationMillis,
    )

private sealed interface ScriptSourceSelection {
    data class Valid(val source: HexanaScriptSource) : ScriptSourceSelection
    data class Invalid(val message: String) : ScriptSourceSelection
}

private fun List<Int>.toByteArray(): ByteArray =
    ByteArray(size) { index -> this[index].toByte() }

private fun List<String>.toStderrWarnings(): String {
    if (isEmpty()) {
        return ""
    }
    return joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()) { warning ->
        "wasm-to-wat warning: $warning"
    }
}

private fun String.byteSize(): Int = toByteArray(StandardCharsets.UTF_8).size

private fun String.takeUtf8Prefix(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    var used = 0
    var index = 0
    var builder: StringBuilder? = null
    while (index < length) {
        val char = this[index]
        val codePoint: Int
        val charCount: Int
        val replacedSurrogate: Boolean
        when {
            Character.isHighSurrogate(char) && index + 1 < length && Character.isLowSurrogate(this[index + 1]) -> {
                codePoint = Character.toCodePoint(char, this[index + 1])
                charCount = 2
                replacedSurrogate = false
            }
            Character.isSurrogate(char) -> {
                codePoint = REPLACEMENT_CHARACTER_CODE_POINT
                charCount = 1
                replacedSurrogate = true
            }
            else -> {
                codePoint = char.code
                charCount = 1
                replacedSurrogate = false
            }
        }

        val codePointBytes = codePoint.utf8ByteCount()
        if (used + codePointBytes > maxBytes) break

        if (builder != null) {
            builder.appendCodePoint(codePoint)
        } else if (replacedSurrogate) {
            builder = StringBuilder(index + charCount).append(this, 0, index).appendCodePoint(codePoint)
        }
        used += codePointBytes
        index += charCount
    }

    return when {
        builder != null -> builder.toString()
        index < length -> substring(0, index)
        else -> this
    }
}

private fun Int.utf8ByteCount(): Int =
    when {
        this <= 0x7F -> 1
        this <= 0x7FF -> 2
        this <= 0xFFFF -> 3
        else -> 4
    }

private const val REPLACEMENT_CHARACTER_CODE_POINT = 0xFFFD
