package org.jetbrains.hexana.cli.hexanacli

import org.jetbrains.hexana.cli.core.SUMMARIZE_BINARY_TOOL_NAME
import org.jetbrains.hexana.cli.core.SUMMARIZE_MODULE_TOOL_NAME
import org.jetbrains.hexana.cli.core.StandardCliRequest
import org.jetbrains.hexana.cli.core.SummarizeBinaryCliRequest
import org.jetbrains.hexana.cli.core.WASM_TO_WAT_TOOL_NAME
import org.jetbrains.hexana.cli.core.standardCli
import org.jetbrains.hexana.cli.core.summarizeBinaryCli
import org.jetbrains.hexana.cli.product.HexanaProductToolExecutionRequest
import org.jetbrains.hexana.cli.product.HexanaProductToolExecutorBinding
import org.jetbrains.hexana.cli.product.HexanaProductToolExecutor
import org.jetbrains.hexana.cli.product.ProductCliResult
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.ToolExecutionDiagnostic
import org.jetbrains.hexana.cli.runtime.ToolExecutionDiagnosticSeverity
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.DefaultHexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaResourceLimits

internal object HexanaStandardProductExecutor : HexanaProductToolExecutor {
    override val bindings: List<HexanaProductToolExecutorBinding> = listOf(
        HexanaProductToolExecutorBinding(SUMMARIZE_BINARY_TOOL_NAME, ::executeSummarizeBinary),
        HexanaProductToolExecutorBinding(SUMMARIZE_MODULE_TOOL_NAME, ::executeStandard),
        HexanaProductToolExecutorBinding(WASM_TO_WAT_TOOL_NAME, ::executeStandard),
    )

    private fun executeSummarizeBinary(request: HexanaProductToolExecutionRequest): ProductCliResult {
        val sources = listOf(request.sourceSelection)
        val rendered = summarizeBinaryCli(
            SummarizeBinaryCliRequest(
                sourceSelections = sources,
                params = request.params,
                format = request.format,
                toolName = request.spec.toolName,
            ),
            analysisService(request.allowedRoots, request.resourceLimits),
        )
        return ProductCliResult(stdout = rendered.textOutput(request.spec.commandName))
    }

    private fun executeStandard(request: HexanaProductToolExecutionRequest): ProductCliResult {
        val standardOutput = standardCli(
            StandardCliRequest(
                toolName = request.spec.toolName,
                sourceSelections = listOf(request.sourceSelection),
                params = request.params,
                format = request.format,
                maxInlineWasmBytes = request.resourceLimits.maxInlineWasmBytes,
                maxFileWasmBytes = request.resourceLimits.maxFileWasmBytes,
                allowedFilePolicy = allowedFilePolicy(request.allowedRoots),
            ),
            analysisService(request.allowedRoots, request.resourceLimits),
        )
        return ProductCliResult(
            stdout = standardOutput.rendered.textOutput(request.spec.commandName),
            stderr = if (request.format == ToolOutputFormat.RAW) {
                standardOutput.diagnostics.toStderrWarnings(request.spec.commandName)
            } else {
                ""
            },
        )
    }
}

private fun analysisService(allowedRoots: List<String>, resourceLimits: HexanaResourceLimits): DefaultHexanaAnalysisService =
    DefaultHexanaAnalysisService(
        limits = resourceLimits,
        allowedFilePolicy = allowedFilePolicy(allowedRoots),
    )

private fun allowedFilePolicy(allowedRoots: List<String>): AllowedFilePolicy =
    if (allowedRoots.none { it.isNotBlank() }) {
        AllowedFilePolicy.allowAll
    } else {
        AllowedFilePolicy.requiringAllowedRoots(allowedRoots)
    }

private fun RenderedToolOutput.textOutput(commandName: String): String = when (this) {
    is RenderedToolOutput.Text -> text
    is RenderedToolOutput.Binary -> error("$commandName does not support binary output.")
}

private fun List<ToolExecutionDiagnostic>.toStderrWarnings(commandName: String): String {
    if (isEmpty()) return ""
    return filter { it.severity == ToolExecutionDiagnosticSeverity.WARNING }
        .joinToString(separator = "\n", postfix = "\n") { warning ->
            "$commandName warning: ${warning.message}"
        }
}
