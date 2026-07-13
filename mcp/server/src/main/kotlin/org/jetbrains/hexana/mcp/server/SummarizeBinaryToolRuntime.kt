package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.HexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryResult
import org.jetbrains.hexana.mcp.tool.runtime.RenderedToolOutput
import org.jetbrains.hexana.mcp.tool.runtime.ToolExecutionRequest
import org.jetbrains.hexana.mcp.tool.runtime.ToolExecutionResult
import org.jetbrains.hexana.mcp.tool.runtime.ToolExecutor
import org.jetbrains.hexana.mcp.tool.runtime.ToolInputConversionRequest
import org.jetbrains.hexana.mcp.tool.runtime.ToolInputConverter
import org.jetbrains.hexana.mcp.tool.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.mcp.tool.runtime.ToolInputSources
import org.jetbrains.hexana.mcp.tool.runtime.ToolOutputFormat
import org.jetbrains.hexana.mcp.tool.runtime.ToolOutputRenderRequest
import org.jetbrains.hexana.mcp.tool.runtime.ToolOutputRenderer
import org.jetbrains.hexana.mcp.tool.runtime.ToolRenderOptions
import org.jetbrains.hexana.mcp.tool.runtime.ToolRuntimeContext

internal const val SUMMARIZE_BINARY_TOOL_NAME = "summarize_binary"

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
        return when (source) {
            is ToolInputSourceSelection.Path -> JsonObject(mapOf("path" to JsonPrimitive(source.path)))
            is ToolInputSourceSelection.Base64 ->
                JsonObject(mapOf("bytesBase64" to JsonPrimitive(source.bytesBase64)))
            is ToolInputSourceSelection.StdinBytes -> JsonObject(
                mapOf("bytes" to source.bytes().toUnsignedJsonArray())
            )
            is ToolInputSourceSelection.ByteList -> JsonObject(
                mapOf("bytes" to JsonArray(source.bytes.map(::JsonPrimitive)))
            )
        }
    }
}

internal class SummarizeBinaryExecutor(
    private val summarize: (BinaryInput) -> BinarySummaryResult,
) : ToolExecutor {
    constructor(analysisService: HexanaAnalysisService) : this(analysisService::summarizeBinary)

    override fun execute(request: ToolExecutionRequest): ToolExecutionResult {
        require(request.context.toolName == SUMMARIZE_BINARY_TOOL_NAME)
        val result = summarize(request.arguments.toBinaryInput())
        return ToolExecutionResult(
            HexanaJson.compact.encodeToJsonElement(BinarySummaryResult.serializer(), result)
        )
    }
}

internal object SummarizeBinaryJsonRenderer : ToolOutputRenderer {
    const val CONTENT_TYPE = "application/json; charset=utf-8"

    override fun render(request: ToolOutputRenderRequest): RenderedToolOutput {
        require(request.context.toolName == SUMMARIZE_BINARY_TOOL_NAME)
        require(request.options.format == ToolOutputFormat.Json) {
            "summarize-binary supports only json output."
        }
        return RenderedToolOutput.Text(
            text = HexanaJson.compact.encodeToString(JsonElement.serializer(), request.result),
            contentType = CONTENT_TYPE,
        )
    }
}

internal data class ServerToolRuntimeBinding(
    val converter: ToolInputConverter,
    val executor: ToolExecutor,
    val renderer: ToolOutputRenderer,
)

internal class ServerToolRuntimeRegistry private constructor(
    private val bindings: Map<String, ServerToolRuntimeBinding>,
) {
    fun binding(toolName: String): ServerToolRuntimeBinding =
        bindings[toolName] ?: error("Tool runtime binding is not registered for '$toolName'.")

    companion object {
        fun create(
            expectedTools: Set<String>,
            converters: Map<String, ToolInputConverter>,
            executors: Map<String, ToolExecutor>,
            renderers: Map<String, ToolOutputRenderer>,
        ): ServerToolRuntimeRegistry {
            require(expectedTools.isNotEmpty()) { "Expected tool runtime bindings must not be empty." }
            val registeredTools = converters.keys + executors.keys + renderers.keys
            require(registeredTools == expectedTools) {
                "Tool runtime registry must contain exactly $expectedTools, got $registeredTools."
            }
            val bindings = expectedTools.associateWith { toolName ->
                ServerToolRuntimeBinding(
                    converter = requireNotNull(converters[toolName]) { "Missing converter for '$toolName'." },
                    executor = requireNotNull(executors[toolName]) { "Missing executor for '$toolName'." },
                    renderer = requireNotNull(renderers[toolName]) { "Missing renderer for '$toolName'." },
                )
            }
            return ServerToolRuntimeRegistry(bindings)
        }
    }
}

internal fun executeSummarizeBinaryRuntime(
    command: HexanaCommand.ExecutableTool,
    analysisService: HexanaAnalysisService,
): RenderedToolOutput {
    val context = ToolRuntimeContext(toolName = command.spec.toolName)
    val registry = summarizeBinaryRuntimeRegistry(analysisService)
    val binding = registry.binding(context.toolName)
    val arguments = binding.converter.convert(
        ToolInputConversionRequest(
            context = context,
            rawArguments = JsonObject(command.params),
            sources = ToolInputSources(sourceSelections = command.sourceSelections),
        )
    )
    val execution = binding.executor.execute(ToolExecutionRequest(context, arguments))
    return binding.renderer.render(
        ToolOutputRenderRequest(
            context = context,
            arguments = arguments,
            result = execution.semanticResult,
            options = ToolRenderOptions(format = command.format.toToolOutputFormat()),
        )
    )
}

private fun summarizeBinaryRuntimeRegistry(analysisService: HexanaAnalysisService): ServerToolRuntimeRegistry =
    ServerToolRuntimeRegistry.create(
        expectedTools = setOf(SUMMARIZE_BINARY_TOOL_NAME),
        converters = mapOf(SUMMARIZE_BINARY_TOOL_NAME to SummarizeBinaryInputConverter()),
        executors = mapOf(SUMMARIZE_BINARY_TOOL_NAME to SummarizeBinaryExecutor(analysisService)),
        renderers = mapOf(SUMMARIZE_BINARY_TOOL_NAME to SummarizeBinaryJsonRenderer),
    )

private fun ByteArray.toUnsignedJsonArray(): JsonArray =
    JsonArray(map { JsonPrimitive(it.toInt() and 0xff) })

private fun JsonObject.toBinaryInput(): BinaryInput {
    val supportedFields = setOf("path", "bytesBase64", "bytes")
    require(keys.all { it in supportedFields }) { "Unsupported summarize-binary fields: ${keys - supportedFields}." }
    val selectedFields = supportedFields.filter(::containsKey)
    require(selectedFields.size == 1) { "summarize-binary requires exactly one semantic input field." }
    return when (val field = selectedFields.single()) {
        "path" -> BinaryInput.FilePath(getValue(field).jsonPrimitive.content)
        "bytesBase64" -> BinaryInput.InlineBytes(getValue(field).jsonPrimitive.content)
        "bytes" -> BinaryInput.RawBytes(
            getValue(field).jsonArray.map {
                it.jsonPrimitive.int.also { value ->
                    require(value in 0..255) { "summarize-binary byte values must be in the 0..255 range." }
                }.toByte()
            }.toByteArray()
        )
        else -> error("Unreachable summarize-binary input field '$field'.")
    }
}

private fun HexanaCliOutputFormat.toToolOutputFormat(): ToolOutputFormat = when (this) {
    HexanaCliOutputFormat.JSON -> ToolOutputFormat.Json
    HexanaCliOutputFormat.PRETTY -> ToolOutputFormat.Pretty
    HexanaCliOutputFormat.RAW -> ToolOutputFormat.Raw
}
