package org.jetbrains.hexana.cli.hexanacliscript

import org.jetbrains.hexana.cli.product.HexanaProductToolExecutionRequest
import org.jetbrains.hexana.cli.product.HexanaProductToolExecutorBinding
import org.jetbrains.hexana.cli.product.HexanaProductToolExecutor
import org.jetbrains.hexana.cli.product.ProductCliResult
import org.jetbrains.hexana.cli.scripting.RUN_BINARY_SCRIPT_TOOL_NAME
import org.jetbrains.hexana.cli.scripting.RunScriptCliRequest
import org.jetbrains.hexana.cli.scripting.runScriptCli
import org.jetbrains.hexana.cli.scripting.runScriptSupportedOutputFormats
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import java.nio.file.Path

internal object HexanaScriptProductExecutor : HexanaProductToolExecutor {
    override val bindings: List<HexanaProductToolExecutorBinding> = listOf(
        HexanaProductToolExecutorBinding(RUN_BINARY_SCRIPT_TOOL_NAME, runScriptSupportedOutputFormats(), ::executeRunScript),
    )

    private fun executeRunScript(request: HexanaProductToolExecutionRequest): ProductCliResult {
        val rendered = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(request.sourceSelection),
                params = request.params,
                format = request.format,
                limits = HexanaScriptLimits(
                    maxOutputBytes = request.resourceLimits.maxOutputBytes,
                    maxInlineScriptBytes = request.resourceLimits.maxTextFileBytes,
                    maxScriptFileBytes = request.resourceLimits.maxTextFileBytes,
                    maxInlineBinaryBytes = request.resourceLimits.maxInlineBinaryBytes,
                    maxFileBinaryBytes = request.resourceLimits.maxFileBinaryBytes,
                ),
                allowedFilePolicy = allowedFilePolicy(request.allowedRoots),
                toolName = request.spec.toolName,
                scriptArgs = request.scriptArgs,
                extraInputPaths = request.extraInputPaths,
                baselinesDir = request.baselinesDir ?: Path.of(".hexana/baselines"),
                updateBaselines = request.updateBaselines,
                sarifOut = request.sarifOut,
                toolVersion = HEXANA_CLI_SCRIPT_VERSION,
            )
        )
        return ProductCliResult(stdout = rendered.stdout, stderr = rendered.stderr, exitCode = rendered.exitCode)
    }
}

private fun allowedFilePolicy(allowedRoots: List<String>): AllowedFilePolicy =
    if (allowedRoots.none { it.isNotBlank() }) {
        AllowedFilePolicy.allowAll
    } else {
        AllowedFilePolicy.requiringAllowedRoots(allowedRoots)
    }
