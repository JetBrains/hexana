package org.jetbrains.hexana.cli.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryResult
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
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

public const val SUMMARIZE_BINARY_TOOL_NAME: String = "summarize_binary"

public data class SummarizeBinaryCliRequest(
    val sourceSelections: List<ToolInputSourceSelection>,
    val params: Map<String, JsonElement> = emptyMap(),
    val format: ToolOutputFormat = ToolOutputFormat.JSON,
    val toolName: String = SUMMARIZE_BINARY_TOOL_NAME,
)

internal class SummarizeBinaryInputConverter : ToolInputConverter {
    override fun convert(request: ToolInputConversionRequest): JsonObject {
        require(request.context.toolName == SUMMARIZE_BINARY_TOOL_NAME)
        require(request.rawArguments.isEmpty()) { "summarize-binary does not accept semantic parameters." }
        require(request.sources.pathArguments.isEmpty()) { "summarize-binary requires a selected binary source." }
        require(request.sources.stdinText == null && request.sources.stdinBytesCopy() == null) {
            "summarize-binary requires a selected binary source."
        }
        val source = request.sources.sourceSelections.singleOrNull()
            ?: throw IllegalArgumentException("summarize-binary requires exactly one binary source selection.")
        require(source.slotName == "binary") {
            "summarize-binary source must use slot 'binary', got '${source.slotName}'."
        }
        return JsonObject(emptyMap())
    }
}

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

internal object SummarizeBinaryJsonRenderer : ToolOutputRenderer {
    const val CONTENT_TYPE = "application/json; charset=utf-8"

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

public fun summarizeBinaryCli(
    request: SummarizeBinaryCliRequest,
    analysisService: HexanaAnalysisService,
): RenderedToolOutput {
    val context = ToolRuntimeContext(toolName = request.toolName)
    val registry = summarizeBinaryRuntimeRegistry(analysisService)
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
    return binding.renderer.render(
        ToolOutputRenderRequest(
            context = context,
            arguments = arguments,
            result = execution.semanticResult,
            options = ToolRenderOptions(format = request.format),
        )
    )
}

private fun summarizeBinaryRuntimeRegistry(analysisService: HexanaAnalysisService): CliToolRuntimeRegistry =
    CliToolRuntimeRegistry.create(
        expectedTools = setOf(SUMMARIZE_BINARY_TOOL_NAME),
        converters = mapOf(SUMMARIZE_BINARY_TOOL_NAME to SummarizeBinaryInputConverter()),
        executors = mapOf(SUMMARIZE_BINARY_TOOL_NAME to SummarizeBinaryExecutor(analysisService)),
        renderers = mapOf(SUMMARIZE_BINARY_TOOL_NAME to SummarizeBinaryJsonRenderer),
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
