package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.encodeToString
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
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import java.nio.charset.StandardCharsets

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
    val result = analysisService.summarizeModule(WasmInput.FilePath(command.path))
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
    val result = analysisService.summarizeBinary(BinaryInput.FilePath(command.path))
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
    ).load(WasmInput.FilePath(command.path))
    val watWithWarnings = wasmPrinter(loaded.bytes)
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

private fun List<String>.toStderrWarnings(): String {
    if (isEmpty()) {
        return ""
    }
    return joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()) { warning ->
        "wasm-to-wat warning: $warning"
    }
}

private fun String.byteSize(): Int = toByteArray(StandardCharsets.UTF_8).size
