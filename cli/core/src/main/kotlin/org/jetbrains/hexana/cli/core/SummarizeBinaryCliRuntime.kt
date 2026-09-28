package org.jetbrains.hexana.cli.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.hexana.graalwasm.wasmprinter.WatWithWarnings
import org.jetbrains.hexana.graalwasm.wasmprinter.printWat
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.WasmInputBytesLoader
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryResult
import org.jetbrains.hexana.mcp.model.converters.WasmToWatResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.CliTable
import org.jetbrains.hexana.cli.runtime.FormatAwareToolOutputRenderer
import org.jetbrains.hexana.cli.runtime.TypedToolOutput
import org.jetbrains.hexana.cli.runtime.TypedToolOutputProjection
import org.jetbrains.hexana.cli.runtime.TypedToolOutputPlan
import org.jetbrains.hexana.cli.runtime.ToolExecutionDiagnostic
import org.jetbrains.hexana.cli.runtime.ToolExecutionDiagnosticSeverity
import org.jetbrains.hexana.cli.runtime.ToolExecutionRequest
import org.jetbrains.hexana.cli.runtime.ToolExecutionResult
import org.jetbrains.hexana.cli.runtime.ToolExecutor
import org.jetbrains.hexana.cli.runtime.ToolInputConversionRequest
import org.jetbrains.hexana.cli.runtime.ToolInputConverter
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.runtime.ToolInputSources
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderRequest
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderer
import org.jetbrains.hexana.cli.runtime.ToolRenderOptions
import org.jetbrains.hexana.cli.runtime.ToolRuntimeContext
import org.jetbrains.hexana.cli.metadata.SummarizeModuleCliInputConverter
import java.util.concurrent.CancellationException

public const val SUMMARIZE_BINARY_TOOL_NAME: String = "summarize_binary"
public const val SUMMARIZE_MODULE_TOOL_NAME: String = "summarize_module"
public const val WASM_TO_WAT_TOOL_NAME: String = "wasm_to_wat"

public data class SummarizeBinaryCliRequest(
    val sourceSelections: List<ToolInputSourceSelection>,
    val params: Map<String, JsonElement> = emptyMap(),
    val format: ToolOutputFormat = ToolOutputFormat.JSON,
    val toolName: String = SUMMARIZE_BINARY_TOOL_NAME,
)

public data class StandardCliRequest(
    val toolName: String,
    val sourceSelections: List<ToolInputSourceSelection>,
    val params: Map<String, JsonElement> = emptyMap(),
    val format: ToolOutputFormat,
    val terminalWidth: Int? = null,
    val maxInlineWasmBytes: Int = HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES,
    val maxFileWasmBytes: Int = HexanaAnalysisLimits.MAX_FILE_WASM_BYTES,
    val allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
)

internal class SingleSourceInputConverter(
    private val toolName: String,
    private val commandName: String,
    private val sourceSlotName: String,
) : ToolInputConverter {
    override fun convert(request: ToolInputConversionRequest): JsonObject {
        require(request.context.toolName == toolName)
        require(request.rawArguments.isEmpty()) { "$commandName does not accept semantic parameters." }
        require(request.sources.pathArguments.isEmpty()) { "$commandName requires a selected $sourceSlotName source." }
        require(request.sources.stdinText == null && request.sources.stdinBytesCopy() == null) {
            "$commandName requires a selected $sourceSlotName source."
        }
        val source = request.sources.sourceSelections.singleOrNull()
            ?: throw IllegalArgumentException("$commandName requires exactly one $sourceSlotName source selection.")
        require(source.slotName == sourceSlotName) {
            "$commandName source must use slot '$sourceSlotName', got '${source.slotName}'."
        }
        return JsonObject(emptyMap())
    }
}

internal class SummarizeBinaryInputConverter : ToolInputConverter by SingleSourceInputConverter(
    toolName = SUMMARIZE_BINARY_TOOL_NAME,
    commandName = "summarize-binary",
    sourceSlotName = "binary",
)

internal class WasmToWatInputConverter : ToolInputConverter by SingleSourceInputConverter(
    toolName = WASM_TO_WAT_TOOL_NAME,
    commandName = "wasm-to-wat",
    sourceSlotName = "wasm",
)

internal class SummarizeBinaryExecutor(
    private val summarize: (BinaryInput) -> BinarySummaryResult,
) : ToolExecutor {
    constructor(analysisService: HexanaAnalysisService) : this(analysisService::summarizeBinary)

    override fun execute(request: ToolExecutionRequest): ToolExecutionResult {
        require(request.context.toolName == SUMMARIZE_BINARY_TOOL_NAME)
        val result = summarize(request.sources.singleBinaryInput())
        return ToolExecutionResult(
            HexanaJson.compact.encodeToJsonElement(BinarySummaryResult.serializer(), result)
        )
    }
}

internal class SummarizeModuleExecutor(
    private val summarize: (WasmInput) -> SummarizeModuleResult,
) : ToolExecutor {
    constructor(analysisService: HexanaAnalysisService) : this(analysisService::summarizeModule)

    override fun execute(request: ToolExecutionRequest): ToolExecutionResult {
        require(request.context.toolName == SUMMARIZE_MODULE_TOOL_NAME)
        val result = summarize(request.sources.singleWasmInput("summarize-module"))
        return ToolExecutionResult(
            HexanaJson.compact.encodeToJsonElement(SummarizeModuleResult.serializer(), result)
        )
    }
}

internal class WasmToWatExecutor(
    private val maxInlineInputBytes: Int = HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES,
    private val maxFileInputBytes: Int = HexanaAnalysisLimits.MAX_FILE_WASM_BYTES,
    private val allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
    private val wasmPrinter: (ByteArray) -> WatWithWarnings = ::printWat,
) : ToolExecutor {
    override fun execute(request: ToolExecutionRequest): ToolExecutionResult {
        require(request.context.toolName == WASM_TO_WAT_TOOL_NAME)
        val loaded = WasmInputBytesLoader(
            maxInlineInputBytes = maxInlineInputBytes,
            maxFileInputBytes = maxFileInputBytes,
            allowedFilePolicy = allowedFilePolicy,
        ).load(request.sources.singleWasmInput("wasm-to-wat"))
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
        val warnings = watWithWarnings.warnings
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            .orEmpty()
        return ToolExecutionResult(
            semanticResult = HexanaJson.compact.encodeToJsonElement(
                WasmToWatResult.serializer(),
                WasmToWatResult(wat = watWithWarnings.wat, warnings = warnings),
            ),
            diagnostics = warnings.map { warning ->
                ToolExecutionDiagnostic(ToolExecutionDiagnosticSeverity.WARNING, warning)
            },
        )
    }
}

internal object SummarizeBinaryJsonRenderer : FormatAwareToolOutputRenderer {
    const val CONTENT_TYPE = "application/json; charset=utf-8"

    override val supportedFormats: Set<ToolOutputFormat> = setOf(ToolOutputFormat.JSON)

    override fun render(request: ToolOutputRenderRequest): RenderedToolOutput {
        require(request.context.toolName == SUMMARIZE_BINARY_TOOL_NAME)
        require(request.options.format == ToolOutputFormat.JSON) {
            "summarize-binary supports only json output."
        }
        return RenderedToolOutput.Text(
            text = HexanaJson.compact.encodeToString(JsonElement.serializer(), request.result),
            contentType = CONTENT_TYPE,
        )
    }
}

internal object SummarizeModuleRenderer : FormatAwareToolOutputRenderer {
    const val JSON_CONTENT_TYPE = SummarizeBinaryJsonRenderer.CONTENT_TYPE
    private val delegate = JsonBackedTypedToolOutputRenderer(
        toolName = SUMMARIZE_MODULE_TOOL_NAME,
        plan = TypedToolOutputPlan(
            serializer = SummarizeModuleResult.serializer(),
            projections = listOf(
                TypedToolOutputProjection(ToolOutputFormat.PRETTY) { result, _ ->
                    TypedToolOutput.Text(renderSummarizeModulePretty(result))
                },
                TypedToolOutputProjection(ToolOutputFormat.TABLE) { result, _ ->
                    TypedToolOutput.Table(summarizeModuleTable(result))
                },
            ),
        ),
        renderJson = { result ->
            RenderedToolOutput.Text(
                text = HexanaJson.compact.encodeToString(JsonElement.serializer(), result),
                contentType = JSON_CONTENT_TYPE,
            )
        },
        renderTable = CliTableRenderer::render,
    )

    override val supportedFormats: Set<ToolOutputFormat> = delegate.supportedFormats

    override fun render(request: ToolOutputRenderRequest): RenderedToolOutput = delegate.render(request)
}

internal object WasmToWatRenderer : FormatAwareToolOutputRenderer {
    const val JSON_CONTENT_TYPE = SummarizeBinaryJsonRenderer.CONTENT_TYPE

    override val supportedFormats: Set<ToolOutputFormat> = setOf(ToolOutputFormat.JSON, ToolOutputFormat.RAW)

    override fun render(request: ToolOutputRenderRequest): RenderedToolOutput {
        require(request.context.toolName == WASM_TO_WAT_TOOL_NAME)
        val result = HexanaJson.compact.decodeFromJsonElement(WasmToWatResult.serializer(), request.result)
        return when (request.options.format) {
            ToolOutputFormat.JSON -> RenderedToolOutput.Text(
                text = HexanaJson.compact.encodeToString(WasmToWatResult.serializer(), result),
                contentType = JSON_CONTENT_TYPE,
            )
            ToolOutputFormat.RAW -> RenderedToolOutput.Text(result.wat)
            ToolOutputFormat.SARIF -> error("wasm-to-wat does not support sarif output")
            ToolOutputFormat.PRETTY -> error("wasm-to-wat does not support pretty output")
            ToolOutputFormat.TABLE -> error("wasm-to-wat does not support table output")
        }
    }
}

public fun renderSummarizeModulePretty(result: SummarizeModuleResult): String =
    buildString {
        appendLine("WebAssembly module summary")
        appendLine("Functions: ${result.functions}")
        appendLine("Exports: ${result.exports}")
        appendLine("Memories: ${result.memories}")
        appendLine("Globals: ${result.globals}")
        appendLine("Tables: ${result.tables}")
        appendLine("Types: ${result.types}")
        appendLine("Imports:")
        appendLine("  Functions: ${result.imports.functions}")
        appendLine("  Memories: ${result.imports.memories}")
        appendLine("  Tables: ${result.imports.tables}")
        appendLine("  Globals: ${result.imports.globals}")
        append("Module size: ${result.moduleSize} bytes")
    }

public fun summarizeModuleTable(result: SummarizeModuleResult): CliTable = CliTable(
    headers = listOf("Metric", "Value"),
    rows = listOf(
        listOf("Functions", result.functions.toString()),
        listOf("Exports", result.exports.toString()),
        listOf("Memories", result.memories.toString()),
        listOf("Globals", result.globals.toString()),
        listOf("Tables", result.tables.toString()),
        listOf("Types", result.types.toString()),
        listOf("Imported functions", result.imports.functions.toString()),
        listOf("Imported memories", result.imports.memories.toString()),
        listOf("Imported tables", result.imports.tables.toString()),
        listOf("Imported globals", result.imports.globals.toString()),
        listOf("Module size (bytes)", result.moduleSize.toString()),
    ),
)

public fun standardCliSupportedOutputFormats(toolName: String): Set<ToolOutputFormat> =
    when (toolName) {
        SUMMARIZE_BINARY_TOOL_NAME -> SummarizeBinaryJsonRenderer.supportedFormats
        SUMMARIZE_MODULE_TOOL_NAME -> SummarizeModuleRenderer.supportedFormats
        WASM_TO_WAT_TOOL_NAME -> WasmToWatRenderer.supportedFormats
        else -> error("Standard CLI tool '$toolName' is not registered.")
    }

internal data class CliToolRuntimeBinding(
    val converter: ToolInputConverter,
    val executor: ToolExecutor,
    val renderer: ToolOutputRenderer,
)

internal class CliToolRuntimeRegistry private constructor(
    private val bindings: Map<String, CliToolRuntimeBinding>,
) {
    fun binding(toolName: String): CliToolRuntimeBinding =
        bindings[toolName] ?: error("Tool runtime binding is not registered for '$toolName'.")

    companion object {
        fun create(
            expectedTools: Set<String>,
            converters: Map<String, ToolInputConverter>,
            executors: Map<String, ToolExecutor>,
            renderers: Map<String, ToolOutputRenderer>,
        ): CliToolRuntimeRegistry {
            require(expectedTools.isNotEmpty()) { "Expected tool runtime bindings must not be empty." }
            val registeredTools = converters.keys + executors.keys + renderers.keys
            require(registeredTools == expectedTools) {
                "Tool runtime registry must contain exactly $expectedTools, got $registeredTools."
            }
            val bindings = expectedTools.associateWith { toolName ->
                CliToolRuntimeBinding(
                    converter = requireNotNull(converters[toolName]) { "Missing converter for '$toolName'." },
                    executor = requireNotNull(executors[toolName]) { "Missing executor for '$toolName'." },
                    renderer = requireNotNull(renderers[toolName]) { "Missing renderer for '$toolName'." },
                )
            }
            return CliToolRuntimeRegistry(bindings)
        }
    }
}

public data class RenderedStandardCliOutput(
    val rendered: RenderedToolOutput,
    val diagnostics: List<ToolExecutionDiagnostic> = emptyList(),
)

public fun standardCli(
    request: StandardCliRequest,
    analysisService: HexanaAnalysisService,
): RenderedStandardCliOutput {
    val context = ToolRuntimeContext(toolName = request.toolName)
    val registry = standardRuntimeRegistry(
        analysisService = analysisService,
        maxInlineWasmBytes = request.maxInlineWasmBytes,
        maxFileWasmBytes = request.maxFileWasmBytes,
        allowedFilePolicy = request.allowedFilePolicy,
    )
    val binding = registry.binding(context.toolName)
    val sources = ToolInputSources(sourceSelections = request.sourceSelections)
    val arguments = binding.converter.convert(
        ToolInputConversionRequest(
            context = context,
            rawArguments = JsonObject(request.params),
            sources = sources,
        )
    )
    val execution = binding.executor.execute(ToolExecutionRequest(context, arguments, sources))
    val rendered = binding.renderer.render(
        ToolOutputRenderRequest(
            context = context,
            arguments = arguments,
            result = execution.semanticResult,
            options = ToolRenderOptions(format = request.format, terminalWidth = request.terminalWidth),
        )
    )
    return RenderedStandardCliOutput(rendered = rendered, diagnostics = execution.diagnostics)
}

public fun summarizeBinaryCli(
    request: SummarizeBinaryCliRequest,
    analysisService: HexanaAnalysisService,
): RenderedToolOutput {
    return standardCli(
        StandardCliRequest(
            toolName = request.toolName,
            sourceSelections = request.sourceSelections,
            params = request.params,
            format = request.format,
        ),
        analysisService,
    ).rendered
}

internal fun standardRuntimeRegistry(
    analysisService: HexanaAnalysisService,
    maxInlineWasmBytes: Int = HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES,
    maxFileWasmBytes: Int = HexanaAnalysisLimits.MAX_FILE_WASM_BYTES,
    allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
    wasmPrinter: (ByteArray) -> WatWithWarnings = ::printWat,
): CliToolRuntimeRegistry =
    CliToolRuntimeRegistry.create(
        expectedTools = STANDARD_ANALYSIS_TOOL_NAMES,
        converters = mapOf(
            SUMMARIZE_BINARY_TOOL_NAME to SummarizeBinaryInputConverter(),
            SUMMARIZE_MODULE_TOOL_NAME to SummarizeModuleCliInputConverter,
            WASM_TO_WAT_TOOL_NAME to WasmToWatInputConverter(),
        ),
        executors = mapOf(
            SUMMARIZE_BINARY_TOOL_NAME to SummarizeBinaryExecutor(analysisService),
            SUMMARIZE_MODULE_TOOL_NAME to SummarizeModuleExecutor(analysisService),
            WASM_TO_WAT_TOOL_NAME to WasmToWatExecutor(
                maxInlineInputBytes = maxInlineWasmBytes,
                maxFileInputBytes = maxFileWasmBytes,
                allowedFilePolicy = allowedFilePolicy,
                wasmPrinter = wasmPrinter,
            ),
        ),
        renderers = mapOf(
            SUMMARIZE_BINARY_TOOL_NAME to SummarizeBinaryJsonRenderer,
            SUMMARIZE_MODULE_TOOL_NAME to SummarizeModuleRenderer,
            WASM_TO_WAT_TOOL_NAME to WasmToWatRenderer,
        )
    )

internal val STANDARD_ANALYSIS_TOOL_NAMES: Set<String> = setOf(
    SUMMARIZE_BINARY_TOOL_NAME,
    SUMMARIZE_MODULE_TOOL_NAME,
    WASM_TO_WAT_TOOL_NAME,
)

private fun ToolInputSources.singleBinaryInput(): BinaryInput {
    require(pathArguments.isEmpty() && stdinText == null && stdinBytesCopy() == null)
    return when (val source = sourceSelections.singleOrNull()) {
        is ToolInputSourceSelection.Path -> BinaryInput.FilePath(source.path)
        is ToolInputSourceSelection.Base64 -> BinaryInput.InlineBytes(source.bytesBase64)
        is ToolInputSourceSelection.StdinBytes -> BinaryInput.RawBytes(source.bytes())
        is ToolInputSourceSelection.ByteList -> BinaryInput.RawBytes(source.bytes.map(Int::toByte).toByteArray())
        null -> throw IllegalArgumentException("summarize-binary requires exactly one binary source selection.")
    }
}

private fun ToolInputSources.singleWasmInput(commandName: String): WasmInput {
    require(pathArguments.isEmpty() && stdinText == null && stdinBytesCopy() == null)
    return when (val source = sourceSelections.singleOrNull()) {
        is ToolInputSourceSelection.Path -> WasmInput.FilePath(source.path)
        is ToolInputSourceSelection.Base64 -> WasmInput.InlineBytes(source.bytesBase64)
        is ToolInputSourceSelection.StdinBytes -> WasmInput.RawBytes(source.bytes())
        is ToolInputSourceSelection.ByteList -> WasmInput.RawBytes(source.bytes.map(Int::toByte).toByteArray())
        null -> throw IllegalArgumentException("$commandName requires exactly one wasm source selection.")
    }
}
