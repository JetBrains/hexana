package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.encodeToString
import org.jetbrains.hexana.cli.core.SUMMARIZE_BINARY_TOOL_NAME
import org.jetbrains.hexana.cli.core.SUMMARIZE_MODULE_TOOL_NAME
import org.jetbrains.hexana.cli.core.SummarizeBinaryCliRequest
import org.jetbrains.hexana.cli.core.StandardCliRequest
import org.jetbrains.hexana.cli.core.WASM_TO_WAT_TOOL_NAME
import org.jetbrains.hexana.cli.core.standardCli
import org.jetbrains.hexana.cli.core.summarizeBinaryCli
import org.jetbrains.hexana.cli.runtime.ToolExecutionDiagnostic
import org.jetbrains.hexana.cli.runtime.ToolExecutionDiagnosticSeverity
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.scripting.RUN_BINARY_SCRIPT_TOOL_NAME
import org.jetbrains.hexana.cli.scripting.RunScriptCliRequest
import org.jetbrains.hexana.cli.scripting.runScriptCli
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import java.nio.charset.StandardCharsets

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
    requireExecutableTool(command, toolName = SUMMARIZE_MODULE_TOOL_NAME)
    val standardOutput = standardCli(
        StandardCliRequest(
            toolName = command.spec.toolName,
            sourceSelections = command.sourceSelections,
            params = command.params,
            format = command.format,
        ),
        analysisService,
    )
    val output = when (val rendered = standardOutput.rendered) {
        is RenderedToolOutput.Text -> rendered.text
        is RenderedToolOutput.Binary -> error("summarize-module does not support binary output")
    }
    return requireOutputWithinLimit(
        output = HexanaCliCommandOutput(stdout = output, stderr = standardOutput.diagnostics.toStderrWarnings("summarize-module")),
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

internal fun renderWasmToWatCommandOutput(command: HexanaCommand.ExecutableTool): HexanaCliCommandOutput {
    requireExecutableTool(command, toolName = WASM_TO_WAT_TOOL_NAME)
    val standardOutput = standardCli(
        StandardCliRequest(
            toolName = command.spec.toolName,
            sourceSelections = command.sourceSelections,
            params = command.params,
            format = command.format,
            maxInlineWasmBytes = command.resourceLimits.maxInlineWasmBytes,
            maxFileWasmBytes = command.resourceLimits.maxFileWasmBytes,
            allowedFilePolicy = standaloneAllowedFilePolicy(command.allowedRoots),
        ),
        createStandaloneHexanaAnalysisService(command.allowedRoots, command.resourceLimits),
    )
    val output = when (val rendered = standardOutput.rendered) {
        is RenderedToolOutput.Text -> HexanaCliCommandOutput(
            stdout = rendered.text,
            stderr = if (command.format == HexanaCliOutputFormat.RAW) {
                standardOutput.diagnostics.toStderrWarnings(command.spec.commandName)
            } else {
                ""
            },
        )
        is RenderedToolOutput.Binary -> error("wasm-to-wat does not support binary output")
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

private fun HexanaCommand.ExecutableTool.scriptLimits(): HexanaScriptLimits =
    HexanaScriptLimits(
        maxOutputBytes = resourceLimits.maxOutputBytes,
        maxInlineScriptBytes = resourceLimits.maxTextFileBytes,
        maxScriptFileBytes = resourceLimits.maxTextFileBytes,
        maxInlineBinaryBytes = resourceLimits.maxInlineBinaryBytes,
        maxFileBinaryBytes = resourceLimits.maxFileBinaryBytes,
    )

private fun List<ToolExecutionDiagnostic>.toStderrWarnings(commandName: String): String {
    if (isEmpty()) {
        return ""
    }
    return filter { it.severity == ToolExecutionDiagnosticSeverity.WARNING }
        .joinToString(separator = System.lineSeparator(), postfix = System.lineSeparator()) { warning ->
            "$commandName warning: ${warning.message}"
        }
}

private fun String.byteSize(): Int = toByteArray(StandardCharsets.UTF_8).size
