package org.jetbrains.hexana.cli.hexanawasmtools

import org.jetbrains.hexana.cli.product.HexanaProductToolExecutionRequest
import org.jetbrains.hexana.cli.product.HexanaProductToolExecutor
import org.jetbrains.hexana.cli.product.HexanaProductToolExecutorBinding
import org.jetbrains.hexana.cli.product.ProductCliResult
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.wasmtools.ComponentWitReport
import org.jetbrains.hexana.cli.wasmtools.MetadataAuditWorkflow
import org.jetbrains.hexana.cli.wasmtools.ReportInputKind
import org.jetbrains.hexana.cli.wasmtools.WasmToolsReportInput
import org.jetbrains.hexana.cli.wasmtools.WasmToolsReportRenderResult
import org.jetbrains.hexana.cli.wasmtools.WasmToolsReportRenderer
import org.jetbrains.hexana.cli.wasmtools.validateAndExplain
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.DefaultHexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.loadWasmBytes
import java.nio.file.Path

internal object HexanaWasmToolsExecutor : HexanaProductToolExecutor {
    private val formats = setOf(ToolOutputFormat.JSON, ToolOutputFormat.PRETTY)

    override val bindings: List<HexanaProductToolExecutorBinding> = listOf(
        HexanaProductToolExecutorBinding("validate_and_explain", formats, ::executeValidate),
        HexanaProductToolExecutorBinding("metadata_audit", formats, ::executeMetadata),
        HexanaProductToolExecutorBinding("component_wit_report", formats, ::executeComponent),
    )

    private fun executeValidate(request: HexanaProductToolExecutionRequest): ProductCliResult =
        execute(request) { bytes, input, _ ->
            validateAndExplain(
                bytes = bytes,
                input = input,
                productVersion = HEXANA_WASM_TOOLS_VERSION,
                runtimeTargets = request.parameterValues["runtimes"].orEmpty(),
                format = request.format,
                maxItems = request.resourceLimits.maxCandidateCount,
                maxOutputBytes = request.resourceLimits.maxOutputBytes.toLong(),
            )
        }

    private fun executeMetadata(request: HexanaProductToolExecutionRequest): ProductCliResult =
        execute(request) { bytes, input, _ ->
            MetadataAuditWorkflow.run(
                bytes = bytes,
                input = input,
                productVersion = HEXANA_WASM_TOOLS_VERSION,
                format = request.format,
                maxItems = request.resourceLimits.maxCandidateCount,
                maxOutputBytes = request.resourceLimits.maxOutputBytes.toLong(),
            )
        }

    private fun executeComponent(request: HexanaProductToolExecutionRequest): ProductCliResult =
        execute(request) { bytes, input, policy ->
            val dependencies = request.parameterValues["dependencyPaths"].orEmpty()
            val normalized = dependencies.map { path -> runCatching { Path.of(path).normalize().toString() }.getOrDefault(path) }
            if (normalized.distinct().size != dependencies.size) {
                return@execute WasmToolsReportRenderResult.UsageError("Dependency paths must be distinct.")
            }
            val limits = request.resourceLimits.copy(
                maxInlineWasmBytes = maxOf(request.resourceLimits.maxInlineWasmBytes, request.resourceLimits.maxFileWasmBytes),
                maxCandidateCount = maxOf(request.resourceLimits.maxCandidateCount, HexanaAnalysisLimits.MAX_CANDIDATE_COUNT),
            )
            WasmToolsReportRenderer.render(ComponentWitReport.compose(
                input = WasmInput.RawBytes(bytes),
                reportInput = input,
                productVersion = HEXANA_WASM_TOOLS_VERSION,
                dependencyPaths = dependencies,
                service = DefaultHexanaAnalysisService(limits = limits, allowedFilePolicy = policy),
                format = request.format,
                maxItems = request.resourceLimits.maxCandidateCount,
                maxOutputBytes = request.resourceLimits.maxOutputBytes.toLong(),
            ))
        }

    private fun execute(
        request: HexanaProductToolExecutionRequest,
        workflow: (ByteArray, WasmToolsReportInput, AllowedFilePolicy) -> WasmToolsReportRenderResult,
    ): ProductCliResult {
        return try {
            val policy = if (request.allowedRoots.isEmpty()) AllowedFilePolicy.allowAll
                else AllowedFilePolicy.requiringAllowedRoots(request.allowedRoots)
            val source = request.sourceSelection
            val wasmInput = when (source) {
                is ToolInputSourceSelection.Path -> WasmInput.FilePath(source.path)
                is ToolInputSourceSelection.StdinBytes -> WasmInput.RawBytes(source.bytes())
                else -> return refusal(request, "Unsupported source kind.", 2)
            }
            val bytes = wasmInput.loadWasmBytes(
                maxInlineInputBytes = request.resourceLimits.maxInlineWasmBytes,
                maxFileInputBytes = request.resourceLimits.maxFileWasmBytes,
                allowedFilePolicy = policy,
            ).bytes
            val input = WasmToolsReportInput(
                kind = if (source is ToolInputSourceSelection.Path) ReportInputKind.PATH else ReportInputKind.STDIN,
                path = (source as? ToolInputSourceSelection.Path)?.path,
                byteCount = bytes.size.toLong(),
            )
            when (val result = workflow(bytes, input, policy)) {
                is WasmToolsReportRenderResult.Rendered -> ProductCliResult(
                    stdout = result.output.text,
                    exitCode = result.exitCode,
                )
                is WasmToolsReportRenderResult.InputRefused -> refusal(request, result.message, result.exitCode)
                is WasmToolsReportRenderResult.UsageError -> refusal(request, result.message, result.exitCode)
                WasmToolsReportRenderResult.OutputLimitRefused -> refusal(request, "Output limit cannot fit a valid report.", 3)
                WasmToolsReportRenderResult.InternalFailure -> internalFailure(request)
            }
        } catch (error: HexanaAnalysisException) {
            if (error.code == HexanaAnalysisErrorCode.INTERNAL_ERROR) internalFailure(request)
            else refusal(request, error.message, 3)
        } catch (_: Exception) {
            internalFailure(request)
        }
    }

    private fun refusal(request: HexanaProductToolExecutionRequest, message: String, exitCode: Int) =
        ProductCliResult(stderr = "${request.spec.commandName}: $message\n", exitCode = exitCode)

    private fun internalFailure(request: HexanaProductToolExecutionRequest) =
        refusal(request, "Internal failure.", 70)
}
