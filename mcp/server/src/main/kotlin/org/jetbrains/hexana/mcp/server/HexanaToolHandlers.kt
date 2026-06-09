package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.*
import org.jetbrains.hexana.analytics.HexanaAnalytics
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.mcp.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private const val LEGACY_SEARCH_EXPORTS_LIMIT = 50
private const val LEGACY_LIST_FUNCTIONS_OFFSET = 0
private const val LEGACY_LIST_FUNCTIONS_LIMIT = 200
private const val LEGACY_SEARCH_FUNCTIONS_BY_NAME_LIMIT = 50
private const val LEGACY_LIST_DATA_SEGMENTS_INCLUDE_EXPORTS = false
private const val LEGACY_LIST_DATA_SEGMENTS_INCLUDE_GLOBALS = true

class HexanaToolHandlers(
    private val analysisService: HexanaAnalysisService = HexanaAnalysisServices.default,
    private val maxInlineInputBytes: Int = HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES,
    private val transport: HexanaEvent.McpTransport = HexanaEvent.McpTransport.STDIO,
    private val logger: Logger = LoggerFactory.getLogger(HexanaToolHandlers::class.java)
) {
    fun queryArtifacts(arguments: JsonObject?): CallToolResult {
        return execute("query_artifacts") {
            analysisService.queryArtifacts(
                input = wasmInput(arguments),
                baselineInput = arguments.optionalNonBlankString("baselinePath")?.let(WasmInput::FilePath),
                bindingInput = arguments.optionalNonBlankString("bindingPath")?.let(TextInput::FilePath),
                configInput = arguments.optionalNonBlankString("configPath")?.let(TextInput::FilePath),
                operation = arguments.stringOrDefault(
                    "operation",
                    HexanaToolInputDefaults.QUERY_ARTIFACTS_OPERATION
                ),
                entityTypes = arguments.optionalStringList("entityTypes").orEmpty(),
                query = arguments.optionalStringList("query").orEmpty(),
                startName = arguments.optionalNonBlankString("startName"),
                startFuncidx = arguments.nullableInt("startFuncidx"),
                relations = arguments.optionalStringList("relations").orEmpty(),
                limit = arguments.intOrDefault("limit", HexanaToolInputDefaults.QUERY_ARTIFACTS_LIMIT),
                detail = arguments.stringOrDefault("detail", HexanaToolInputDefaults.QUERY_ARTIFACTS_DETAIL),
                maxBytes = arguments.intOrDefault("maxBytes", HexanaToolInputDefaults.QUERY_ARTIFACTS_MAX_BYTES)
            )
        }
    }

    fun summarizeModule(arguments: JsonObject?): CallToolResult {
        return execute("summarize_module") {
            analysisService.summarizeModule(wasmInput(arguments))
        }
    }

    fun getEntrypointPolicy(arguments: JsonObject?): CallToolResult {
        return execute("get_entrypoint_policy") {
            analysisService.getEntrypointPolicy(wasmInput(arguments))
        }
    }

    fun listEnabledFeatures(arguments: JsonObject?): CallToolResult {
        return execute("list_enabled_features") {
            analysisService.listEnabledFeatures(wasmInput(arguments))
        }
    }

    fun listRequiredProposals(arguments: JsonObject?): CallToolResult {
        return execute("list_required_proposals") {
            analysisService.listRequiredProposals(wasmInput(arguments))
        }
    }

    fun diffRequiredProposals(arguments: JsonObject?): CallToolResult {
        return execute("diff_required_proposals") {
            analysisService.diffRequiredProposals(
                input = wasmInput(arguments),
                baselineInput = WasmInput.FilePath(arguments.requireNonBlankString("baselinePath"))
            )
        }
    }

    fun checkRuntimeCompatibility(arguments: JsonObject?): CallToolResult {
        return execute("check_runtime_compatibility") {
            analysisService.checkRuntimeCompatibility(
                input = wasmInput(arguments),
                runtime = arguments.requireNonBlankString("runtime"),
                version = arguments.optionalNonBlankString("version")
            )
        }
    }

    fun listCustomSections(arguments: JsonObject?): CallToolResult {
        return execute("list_custom_sections") {
            analysisService.listCustomSections(wasmInput(arguments))
        }
    }

    fun listImports(arguments: JsonObject?): CallToolResult {
        return execute("list_imports") {
            analysisService.listImports(wasmInput(arguments))
        }
    }

    fun listExports(arguments: JsonObject?): CallToolResult {
        return execute("list_exports") {
            analysisService.listExports(wasmInput(arguments))
        }
    }

    fun searchExports(arguments: JsonObject?): CallToolResult {
        return execute("search_exports") {
            analysisService.searchExports(
                input = wasmInput(arguments),
                query = arguments.requireStringList("query"),
                limit = arguments.intOrDefault("limit", LEGACY_SEARCH_EXPORTS_LIMIT)
            )
        }
    }

    fun listFunctions(arguments: JsonObject?): CallToolResult {
        return execute("list_functions") {
            analysisService.listFunctions(
                input = wasmInput(arguments),
                offset = arguments.intOrDefault("offset", LEGACY_LIST_FUNCTIONS_OFFSET),
                limit = arguments.intOrDefault("limit", LEGACY_LIST_FUNCTIONS_LIMIT)
            )
        }
    }

    fun listCodeSectionOffsets(arguments: JsonObject?): CallToolResult {
        return execute("list_code_section_offsets") {
            analysisService.listCodeSectionOffsets(wasmInput(arguments))
        }
    }

    fun resolveFunctionSignature(arguments: JsonObject?): CallToolResult {
        return execute("resolve_function_signature") {
            analysisService.resolveFunctionSignature(
                input = wasmInput(arguments),
                funcidx = arguments.requireIntArgument("funcidx")
            )
        }
    }

    fun disassembleFunction(arguments: JsonObject?): CallToolResult {
        return execute("disassemble_function") {
            analysisService.disassembleFunction(
                input = wasmInput(arguments),
                funcidx = arguments.requireIntArgument("funcidx")
            )
        }
    }

    fun getFunctionCallGraph(arguments: JsonObject?): CallToolResult {
        return execute("get_function_call_graph") {
            analysisService.getFunctionCallGraph(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun searchFunctionsByName(arguments: JsonObject?): CallToolResult {
        return execute("search_functions_by_name") {
            analysisService.searchFunctionsByName(
                input = wasmInput(arguments),
                query = arguments.requireNonBlankString("query"),
                limit = arguments.intOrDefault("limit", LEGACY_SEARCH_FUNCTIONS_BY_NAME_LIMIT)
            )
        }
    }

    fun listExportedFunctions(arguments: JsonObject?): CallToolResult {
        return execute("list_exported_functions") {
            analysisService.listExportedFunctions(
                input = wasmInput(arguments),
                entriesRange = arguments.requireClosedRange("entriesRange")
            )
        }
    }

    fun functionsForIndices(arguments: JsonObject?): CallToolResult {
        return execute("functions_for_indices") {
            analysisService.functionsForIndices(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun listGlobals(arguments: JsonObject?): CallToolResult {
        return execute("list_globals") {
            analysisService.listGlobals(wasmInput(arguments))
        }
    }

    fun listTypes(arguments: JsonObject?): CallToolResult {
        return execute("list_types") {
            analysisService.listTypes(wasmInput(arguments))
        }
    }

    fun listValueTypes(arguments: JsonObject?): CallToolResult {
        return execute("list_value_types") {
            analysisService.listValueTypes(wasmInput(arguments))
        }
    }

    fun listReferenceTypes(arguments: JsonObject?): CallToolResult {
        return execute("list_reference_types") {
            analysisService.listReferenceTypes(wasmInput(arguments))
        }
    }

    fun listMemory(arguments: JsonObject?): CallToolResult {
        return execute("list_memory") {
            analysisService.listMemory(wasmInput(arguments))
        }
    }

    fun checkMemoryContract(arguments: JsonObject?): CallToolResult {
        return execute("check_memory_contract") {
            analysisService.checkMemoryContract(
                input = wasmInput(arguments),
                baselineInput = WasmInput.FilePath(arguments.requireNonBlankString("baselinePath")),
                policyInput = TextInput.FilePath(arguments.requireNonBlankString("policyPath")),
                logInput = arguments.optionalNonBlankString("logPath")?.let(TextInput::FilePath)
            )
        }
    }

    fun getGlobalsForIndices(arguments: JsonObject?): CallToolResult {
        return execute("get_globals_for_indices") {
            analysisService.getGlobalsForIndices(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun listData(arguments: JsonObject?): CallToolResult {
        return execute("list_data") {
            analysisService.listData(
                input = wasmInput(arguments),
                entriesRange = arguments.requireClosedRange("entriesRange")
            )
        }
    }

    fun listDataSegments(arguments: JsonObject?): CallToolResult {
        return execute("list_data_segments") {
            val (from, toExclusive) = arguments.requireHalfOpenRange("segmentRange")
            analysisService.listDataSegments(
                input = wasmInput(arguments),
                segmentStartInclusive = from,
                segmentEndExclusive = toExclusive,
                includeExports = arguments.booleanOrDefault(
                    "includeExports",
                    LEGACY_LIST_DATA_SEGMENTS_INCLUDE_EXPORTS
                ),
                includeGlobals = arguments.booleanOrDefault(
                    "includeGlobals",
                    LEGACY_LIST_DATA_SEGMENTS_INCLUDE_GLOBALS
                ),
                minByteLength = arguments.nullableInt("minByteLength")
            )
        }
    }

    fun getMemoryForIndices(arguments: JsonObject?): CallToolResult {
        return execute("get_memory_for_indices") {
            analysisService.getMemoryForIndices(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun getTypesForIndices(arguments: JsonObject?): CallToolResult {
        return execute("get_types_for_indices") {
            analysisService.getTypesForIndices(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun getLocalsForFunctions(arguments: JsonObject?): CallToolResult {
        return execute("get_locals_for_functions") {
            analysisService.getLocalsForFunctions(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun getInstructionsForFunctions(arguments: JsonObject?): CallToolResult {
        return execute("get_instructions_for_functions") {
            analysisService.getInstructionsForFunctions(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun listElementSegments(arguments: JsonObject?): CallToolResult {
        return execute("list_element_segments") {
            analysisService.listElementSegments(wasmInput(arguments))
        }
    }

    private inline fun <reified T> execute(toolName: String, block: () -> T): CallToolResult {
        val startTime = System.currentTimeMillis()
        return try {
            val result = block()
            val jsonResult = encodeHexanaJson(result)
            val structuredContent = encodeHexanaJsonElement(result) as? JsonObject
            captureToolEvent(toolName, startTime, success = true)
            CallToolResult(
                content = listOf(TextContent(jsonResult)),
                structuredContent = structuredContent
            )
        } catch (error: HexanaAnalysisException) {
            captureToolEvent(toolName, startTime, success = false)
            logAnalysisFailure(toolName, error)
            errorResult(error.code, error.message)
        } catch (error: ToolInputException) {
            captureToolEvent(toolName, startTime, success = false)
            errorResult(HexanaAnalysisErrorCode.INVALID_INPUT, error.message ?: "Invalid input")
        } catch (error: Exception) {
            captureToolEvent(toolName, startTime, success = false)
            logger.error("Unexpected failure while executing MCP tool `{}`", toolName, error)
            errorResult(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Internal error while executing `$toolName`. See server logs for details."
            )
        }
    }

    private fun captureToolEvent(toolName: String, startTime: Long, success: Boolean) {
        HexanaAnalytics.client.capture(
            HexanaEvent.McpToolInvoked(
                toolName = toolName,
                transport = transport,
                durationMs = System.currentTimeMillis() - startTime,
                success = success
            )
        )
    }

    private fun errorResult(code: HexanaAnalysisErrorCode, message: String): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(message)),
            isError = true,
            structuredContent = buildJsonObject {
                put("code", JsonPrimitive(code.name))
                put("message", JsonPrimitive(message))
            }
        )
    }

    private fun logAnalysisFailure(toolName: String, error: HexanaAnalysisException) {
        if (error.code == HexanaAnalysisErrorCode.INTERNAL_ERROR || error.cause != null) {
            logger.error(
                "MCP tool `{}` failed with {}: {}",
                toolName,
                error.code.name,
                error.message,
                error
            )
        }
    }

    private fun wasmInput(arguments: JsonObject?): WasmInput {
        val path = arguments.optionalNonBlankString("path")
        val bytesBase64Decoded = arguments.optionalNonBlankString("bytesBase64")?.let(::decodeInlineBase64)
        val bytes = arguments.optionalByteArray("bytes")

        val providedInputs = listOf(path, bytesBase64Decoded, bytes).count { it != null }
        if (providedInputs == 0) {
            throw ToolInputException("Exactly one of `path`, `bytesBase64`, or `bytes` is required")
        }
        if (providedInputs > 1) {
            throw ToolInputException("Provide exactly one of `path`, `bytesBase64`, or `bytes`")
        }

        return when {
            path != null -> WasmInput.FilePath(path)
            bytesBase64Decoded != null -> WasmInput.RawBytes(bytesBase64Decoded)
            bytes != null -> WasmInput.RawBytes(bytes)
            else -> error("Unreachable input state")
        }
    }

    private fun decodeInlineBase64(encoded: String): ByteArray {
        val nonWhitespaceLen = encoded.count { !it.isWhitespace() }
        val upperBoundDecoded = (nonWhitespaceLen.toLong() * 3L + 3L) / 4L
        if (upperBoundDecoded > maxInlineInputBytes.toLong()) {
            throw ToolInputException(
                "`bytesBase64` exceeds maximum decoded size of $maxInlineInputBytes bytes"
            )
        }
        val decoded = try {
            java.util.Base64.getMimeDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw ToolInputException("`bytesBase64` must be valid base64")
        }
        if (decoded.size > maxInlineInputBytes) {
            throw ToolInputException(
                "`bytesBase64` exceeds maximum decoded size of $maxInlineInputBytes bytes"
            )
        }
        return decoded
    }

    private fun JsonObject?.optionalNonBlankString(name: String): String? {
        val element = this?.get(name) ?: return null
        if (element == JsonNull) return null
        val value = element.jsonPrimitive.contentOrNull
            ?: throw ToolInputException("`$name` must be a string")
        if (value.isBlank()) {
            throw ToolInputException("`$name` must not be blank")
        }
        return value
    }

    private fun JsonObject?.optionalByteArray(name: String): ByteArray? {
        val element = this?.get(name) ?: return null
        if (element == JsonNull) return null
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        if (array.size > maxInlineInputBytes) {
            throw ToolInputException("`$name` exceeds maximum size of $maxInlineInputBytes bytes")
        }
        return ByteArray(array.size) { index ->
            val value = array[index].jsonPrimitive.intOrNull
                ?: throw ToolInputException("`$name[$index]` must be an integer")
            if (value !in 0..255) {
                throw ToolInputException("`$name[$index]` must be between 0 and 255")
            }
            value.toByte()
        }
    }

    private fun JsonObject?.intOrDefault(name: String, default: Int): Int {
        val element = this?.get(name) ?: return default
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
    }

    private fun JsonObject?.requireIntArgument(name: String): Int {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
    }

    private fun JsonObject?.requireNonBlankString(name: String): String {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        val value = element.jsonPrimitive.contentOrNull
            ?: throw ToolInputException("`$name` must be a string")
        if (value.isBlank()) {
            throw ToolInputException("`$name` must not be blank")
        }
        return value
    }

    private fun JsonObject?.stringOrDefault(name: String, default: String): String {
        val element = this?.get(name) ?: return default
        if (element == JsonNull) return default
        val value = element.jsonPrimitive.contentOrNull
            ?: throw ToolInputException("`$name` must be a string")
        if (value.isBlank()) {
            throw ToolInputException("`$name` must not be blank")
        }
        return value
    }

    private fun JsonObject?.requireObject(name: String): JsonObject {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        return element as? JsonObject ?: throw ToolInputException("`$name` must be an object")
    }

    private fun JsonObject.requireInt(name: String): Int {
        val element = get(name) ?: throw ToolInputException("`$name` is required")
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
    }

    private fun JsonObject.optionalInt(name: String): Int? {
        val element = get(name) ?: return null
        if (element == JsonNull) return null
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
    }

    private fun JsonObject?.nullableInt(name: String): Int? {
        val element = this?.get(name) ?: return null
        if (element == JsonNull) return null
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
    }

    private fun JsonObject?.booleanOrDefault(name: String, default: Boolean): Boolean {
        val element = this?.get(name) ?: return default
        return element.jsonPrimitive.booleanOrNull
            ?: throw ToolInputException("`$name` must be a boolean")
    }

    private fun JsonObject?.requireIntList(name: String): List<Int> {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        return array.mapIndexed { index, value ->
            value.jsonPrimitive.intOrNull
                ?: throw ToolInputException("`$name[$index]` must be an integer")
        }
    }

    private fun JsonObject?.requireStringList(name: String): List<String> {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        return array.mapIndexed { index, value ->
            value.jsonPrimitive.contentOrNull
                ?: throw ToolInputException("`$name[$index]` must be a string")
        }
    }

    private fun JsonObject?.optionalStringList(name: String): List<String>? {
        val element = this?.get(name) ?: return null
        if (element == JsonNull) return null
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        return array.mapIndexed { index, value ->
            value.jsonPrimitive.contentOrNull
                ?: throw ToolInputException("`$name[$index]` must be a string")
        }
    }

    private fun JsonObject?.requireClosedRange(name: String): IntRange {
        val value = requireObject(name)
        val from = value.requireRangeInt(name, "from")
        val to = value.requireRangeInt(name, "to")
        if (to < from) {
            throw ToolInputException("`$name.to` must be >= `$name.from`")
        }
        return from..to
    }

    private fun JsonObject?.requireHalfOpenRange(name: String): Pair<Int, Int> {
        val value = requireObject(name)
        val from = value.requireInt("from")
        val toExclusive = value.optionalInt("to") ?: Int.MAX_VALUE
        return from to toExclusive
    }

    private fun JsonObject.requireRangeInt(rangeName: String, fieldName: String): Int {
        val element = get(fieldName) ?: throw ToolInputException("`$rangeName.$fieldName` is required")
        return (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw ToolInputException("`$rangeName.$fieldName` must be an integer")
    }
}

private class ToolInputException(
    override val message: String
) : RuntimeException(message)
