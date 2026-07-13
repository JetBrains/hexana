package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.encodeToString
import org.jetbrains.hexana.cli.core.SUMMARIZE_BINARY_TOOL_NAME
import org.jetbrains.hexana.cli.core.SummarizeBinaryCliRequest
import org.jetbrains.hexana.cli.core.summarizeBinaryCli
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.scripting.RUN_BINARY_SCRIPT_TOOL_NAME
import org.jetbrains.hexana.cli.scripting.RunScriptCliRequest
import org.jetbrains.hexana.cli.scripting.runScriptCli
import org.jetbrains.hexana.graalwasm.wasmprinter.WatWithWarnings
import org.jetbrains.hexana.graalwasm.wasmprinter.printWat
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.WasmInputBytesLoader
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.converters.WasmToWatResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

internal data class HexanaCliCommandOutput(
    val stdout: String,
    val stderr: String = "",
    val exitCode: Int = 0,
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
): String = renderSummarizeBinaryCliCommandOutput(command, analysisService).stdout

internal fun renderSummarizeBinaryCliCommandOutput(
    command: HexanaCommand.ExecutableTool,
    analysisService: HexanaAnalysisService = createStandaloneHexanaAnalysisService(
        command.allowedRoots,
        command.resourceLimits
    ),
): HexanaCliCommandOutput {
    requireExecutableTool(command, toolName = SUMMARIZE_BINARY_TOOL_NAME)
    val rendered = summarizeBinaryCli(
        SummarizeBinaryCliRequest(
            sourceSelections = command.sourceSelections,
            params = command.params,
            format = command.format,
            toolName = command.spec.toolName,
        ),
        analysisService,
    )
    val output = when (rendered) {
        is RenderedToolOutput.Text -> rendered.text
        is RenderedToolOutput.Binary -> error("summarize-binary does not support binary output")
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
): HexanaCliCommandOutput {
    requireExecutableTool(command, toolName = RUN_BINARY_SCRIPT_TOOL_NAME)
    val rendered = runScriptCli(
        RunScriptCliRequest(
            sourceSelections = command.sourceSelections,
            params = command.params,
            format = command.format,
            limits = command.scriptLimits(),
            allowedFilePolicy = standaloneAllowedFilePolicy(command.allowedRoots),
            toolName = command.spec.toolName,
        )
    )
    return requireOutputWithinLimit(
        output = HexanaCliCommandOutput(
            stdout = rendered.stdout,
            stderr = rendered.stderr,
            exitCode = rendered.exitCode,
        ),
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

private fun HexanaCommand.ExecutableTool.scriptLimits(): HexanaScriptLimits =
    HexanaScriptLimits(
        maxOutputBytes = resourceLimits.maxOutputBytes,
        maxInlineScriptBytes = resourceLimits.maxTextFileBytes,
        maxScriptFileBytes = resourceLimits.maxTextFileBytes,
        maxInlineBinaryBytes = resourceLimits.maxInlineBinaryBytes,
        maxFileBinaryBytes = resourceLimits.maxFileBinaryBytes,
    )

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
