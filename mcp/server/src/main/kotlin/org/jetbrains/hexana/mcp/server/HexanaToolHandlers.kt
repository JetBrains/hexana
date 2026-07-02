package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import org.jetbrains.hexana.analytics.HexanaAnalytics
import org.jetbrains.hexana.analytics.HexanaEvent
import org.jetbrains.hexana.mcp.*
import org.jetbrains.hexana.mcp.model.artifacts.*
import org.jetbrains.hexana.mcp.model.binaries.*
import org.jetbrains.hexana.mcp.model.components.*
import org.jetbrains.hexana.mcp.model.data.*
import org.jetbrains.hexana.mcp.model.elements.*
import org.jetbrains.hexana.mcp.model.entrypoints.*
import org.jetbrains.hexana.mcp.model.exports.*
import org.jetbrains.hexana.mcp.model.features.*
import org.jetbrains.hexana.mcp.model.functions.*
import org.jetbrains.hexana.mcp.model.globals.*
import org.jetbrains.hexana.mcp.model.imports.*
import org.jetbrains.hexana.mcp.model.memory.*
import org.jetbrains.hexana.mcp.model.runtime.*
import org.jetbrains.hexana.mcp.model.sections.*
import org.jetbrains.hexana.mcp.model.summary.*
import org.jetbrains.hexana.mcp.model.types.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private const val LEGACY_SEARCH_EXPORTS_LIMIT = 50
private const val LEGACY_LIST_FUNCTIONS_OFFSET = 0
private const val LEGACY_LIST_FUNCTIONS_LIMIT = 200
private const val LEGACY_SEARCH_FUNCTIONS_BY_NAME_LIMIT = 50
private const val LEGACY_LIST_DATA_SEGMENTS_INCLUDE_EXPORTS = false
private const val LEGACY_LIST_DATA_SEGMENTS_INCLUDE_GLOBALS = true

class HexanaToolHandlers(
    resourceLimits: HexanaResourceLimits = HexanaResourceLimits(),
    maxInlineInputBytes: Int? = null,
    private val analysisService: HexanaAnalysisService = DefaultHexanaAnalysisService(
        maxInlineInputBytes?.let {
            resourceLimits.copy(
                maxInlineWasmBytes = it,
                maxInlineBinaryBytes = it,
            )
        } ?: resourceLimits
    ),
    private val transport: HexanaEvent.McpTransport = HexanaEvent.McpTransport.STDIO,
    private val logger: Logger = LoggerFactory.getLogger(HexanaToolHandlers::class.java)
) {
    private val resourceLimits = maxInlineInputBytes?.let {
        resourceLimits.copy(
            maxInlineWasmBytes = it,
            maxInlineBinaryBytes = it,
        )
    } ?: resourceLimits

    fun queryArtifacts(arguments: JsonObject?): CallToolResult {
        return execute("query_artifacts", QueryArtifactsResult.serializer()) {
            analysisService.queryArtifacts(
                input = wasmInput(arguments),
                baselineInput = arguments.optionalNonBlankString("baselinePath")?.let(WasmInput::FilePath),
                bindingInput = arguments.optionalNonBlankString("bindingPath")?.let(TextInput::FilePath),
                operation = arguments.stringOrDefault(
                    "operation",
                    HexanaToolInputDefaults.QUERY_ARTIFACTS_OPERATION
                ),
                entityTypes = arguments.optionalStringList("entityTypes").orEmpty(),
                query = arguments.optionalStringList("query").orEmpty(),
                startName = arguments.optionalNonBlankString("startName"),
                startFuncidx = arguments.nullableInt("startFuncidx"),
                relations = arguments.optionalStringList("relations").orEmpty(),
                limit = arguments.limitOrResourceDefault("limit", HexanaToolInputDefaults.QUERY_ARTIFACTS_LIMIT),
                detail = arguments.stringOrDefault("detail", HexanaToolInputDefaults.QUERY_ARTIFACTS_DETAIL),
                maxBytes = arguments.intOrDefault("maxBytes", HexanaToolInputDefaults.QUERY_ARTIFACTS_MAX_BYTES)
            )
        }
    }

    fun planAbiBindingRegeneration(arguments: JsonObject?): CallToolResult {
        return execute("plan_abi_binding_regeneration", PlanAbiBindingRegenerationResult.serializer()) {
            analysisService.planAbiBindingRegeneration(
                input = wasmInput(arguments),
                bindingInput = TextInput.FilePath(arguments.requireNonBlankString("bindingPath"))
            )
        }
    }

    fun summarizeModule(arguments: JsonObject?): CallToolResult {
        return execute("summarize_module", SummarizeModuleResult.serializer()) {
            analysisService.summarizeModule(wasmInput(arguments))
        }
    }

    fun summarizeBinary(arguments: JsonObject?): CallToolResult {
        return execute("summarize_binary", BinarySummaryResult.serializer()) {
            analysisService.summarizeBinary(binaryInput(arguments))
        }
    }

    fun queryBinary(arguments: JsonObject?): CallToolResult {
        return execute("query_binary", QueryBinaryResult.serializer()) {
            analysisService.queryBinary(
                input = binaryInput(arguments),
                operation = arguments.stringOrDefault(
                    "operation",
                    HexanaToolInputDefaults.QUERY_BINARY_OPERATION
                ),
                entityTypes = arguments.optionalStringList("entityTypes").orEmpty(),
                query = arguments.optionalStringList("query").orEmpty(),
                offset = arguments.intOrDefault("offset", HexanaToolInputDefaults.QUERY_BINARY_OFFSET),
                limit = arguments.limitOrResourceDefault("limit", HexanaToolInputDefaults.QUERY_BINARY_LIMIT),
                detail = arguments.stringOrDefault("detail", HexanaToolInputDefaults.QUERY_BINARY_DETAIL),
                maxBytes = arguments.intOrDefault("maxBytes", HexanaToolInputDefaults.QUERY_BINARY_MAX_BYTES)
            )
        }
    }

    fun listBinaryFunctions(arguments: JsonObject?): CallToolResult {
        return execute("list_binary_functions", BinaryFunctionsResult.serializer()) {
            analysisService.listBinaryFunctions(
                input = binaryInput(arguments),
                offset = arguments.intOrDefault("offset", HexanaToolInputDefaults.LIST_BINARY_FUNCTIONS_OFFSET),
                limit = arguments.limitOrResourceDefault("limit", HexanaToolInputDefaults.LIST_BINARY_FUNCTIONS_LIMIT)
            )
        }
    }

    fun searchBinaryFunctions(arguments: JsonObject?): CallToolResult {
        return execute("search_binary_functions", BinaryFunctionsResult.serializer()) {
            analysisService.searchBinaryFunctions(
                input = binaryInput(arguments),
                query = arguments.requireStringList("query"),
                offset = arguments.intOrDefault("offset", HexanaToolInputDefaults.SEARCH_BINARY_FUNCTIONS_OFFSET),
                limit = arguments.limitOrResourceDefault("limit", HexanaToolInputDefaults.SEARCH_BINARY_FUNCTIONS_LIMIT)
            )
        }
    }

    fun getEntrypointPolicy(arguments: JsonObject?): CallToolResult {
        return execute("get_entrypoint_policy", EntrypointPolicyResult.serializer()) {
            analysisService.getEntrypointPolicy(wasmInput(arguments))
        }
    }

    fun listEnabledFeatures(arguments: JsonObject?): CallToolResult {
        return execute("list_enabled_features", ListEnabledFeaturesResult.serializer()) {
            analysisService.listEnabledFeatures(wasmInput(arguments))
        }
    }

    fun listRequiredProposals(arguments: JsonObject?): CallToolResult {
        return execute("list_required_proposals", ListRequiredProposalsResult.serializer()) {
            analysisService.listRequiredProposals(wasmInput(arguments))
        }
    }

    fun diffRequiredProposals(arguments: JsonObject?): CallToolResult {
        return execute("diff_required_proposals", DiffRequiredProposalsResult.serializer()) {
            analysisService.diffRequiredProposals(
                input = wasmInput(arguments),
                baselineInput = WasmInput.FilePath(arguments.requireNonBlankString("baselinePath"))
            )
        }
    }

    fun checkRuntimeCompatibility(arguments: JsonObject?): CallToolResult {
        return execute("check_runtime_compatibility", CheckRuntimeCompatibilityResult.serializer()) {
            analysisService.checkRuntimeCompatibility(
                input = wasmInput(arguments),
                runtime = arguments.requireNonBlankString("runtime"),
                version = arguments.optionalNonBlankString("version")
            )
        }
    }

    fun listComponentSurface(arguments: JsonObject?): CallToolResult {
        return execute("list_component_surface", ListComponentSurfaceResult.serializer()) {
            analysisService.listComponentSurface(wasmInput(arguments))
        }
    }

    fun resolveComponentDependencies(arguments: JsonObject?): CallToolResult {
        return execute("resolve_component_dependencies", ResolveComponentDependenciesResult.serializer()) {
            analysisService.resolveComponentDependencies(
                input = wasmInput(arguments),
                projectRoot = arguments.optionalNonBlankString("projectRoot"),
                scanRoots = arguments.optionalStringList("scanRoots").orEmpty()
            )
        }
    }

    fun checkComponentCompatibility(arguments: JsonObject?): CallToolResult {
        return execute("check_component_compatibility", CheckComponentCompatibilityResult.serializer()) {
            analysisService.checkComponentCompatibility(
                input = wasmInput(arguments),
                dependencyPaths = arguments.optionalStringList("dependencyPaths").orEmpty(),
                projectRoot = arguments.optionalNonBlankString("projectRoot"),
                previousPath = arguments.optionalNonBlankString("previousPath"),
                dependencyLockPath = arguments.optionalNonBlankString("dependencyLockPath")
            )
        }
    }

    fun listCustomSections(arguments: JsonObject?): CallToolResult {
        return execute("list_custom_sections", ListCustomSectionsResult.serializer()) {
            analysisService.listCustomSections(wasmInput(arguments))
        }
    }

    fun listImports(arguments: JsonObject?): CallToolResult {
        return execute("list_imports", ListImportsResult.serializer()) {
            analysisService.listImports(wasmInput(arguments))
        }
    }

    fun listExports(arguments: JsonObject?): CallToolResult {
        return execute("list_exports", ListExportsResult.serializer()) {
            analysisService.listExports(wasmInput(arguments))
        }
    }

    fun searchExports(arguments: JsonObject?): CallToolResult {
        return execute("search_exports", SearchExportsResult.serializer()) {
            analysisService.searchExports(
                input = wasmInput(arguments),
                query = arguments.requireStringList("query"),
                limit = arguments.limitOrResourceDefault("limit", LEGACY_SEARCH_EXPORTS_LIMIT)
            )
        }
    }

    fun listFunctions(arguments: JsonObject?): CallToolResult {
        return execute("list_functions", FunctionsResult.serializer()) {
            analysisService.listFunctions(
                input = wasmInput(arguments),
                offset = arguments.intOrDefault("offset", LEGACY_LIST_FUNCTIONS_OFFSET),
                limit = arguments.limitOrResourceDefault("limit", LEGACY_LIST_FUNCTIONS_LIMIT)
            )
        }
    }

    fun listCodeSectionOffsets(arguments: JsonObject?): CallToolResult {
        return execute("list_code_section_offsets", ListCodeSectionOffsetsResult.serializer()) {
            analysisService.listCodeSectionOffsets(wasmInput(arguments))
        }
    }

    fun resolveFunctionSignature(arguments: JsonObject?): CallToolResult {
        return execute("resolve_function_signature", ResolveFunctionSignatureResult.serializer()) {
            analysisService.resolveFunctionSignature(
                input = wasmInput(arguments),
                funcidx = arguments.requireIntArgument("funcidx")
            )
        }
    }

    fun disassembleFunction(arguments: JsonObject?): CallToolResult {
        return execute("disassemble_function", DisassembleFunctionResult.serializer()) {
            analysisService.disassembleFunction(
                input = wasmInput(arguments),
                funcidx = arguments.requireIntArgument("funcidx")
            )
        }
    }

    fun getFunctionCallGraph(arguments: JsonObject?): CallToolResult {
        return execute("get_function_call_graph", GetFunctionCallGraphResult.serializer()) {
            analysisService.getFunctionCallGraph(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun auditLocalStackBudget(arguments: JsonObject?): CallToolResult {
        return execute("audit_local_stack_budget", AuditLocalStackBudgetResult.serializer()) {
            analysisService.auditLocalStackBudget(
                input = wasmInput(arguments),
                policyInput = arguments.optionalNonBlankString("policyPath")?.let(TextInput::FilePath),
                logInput = arguments.optionalNonBlankString("logPath")?.let(TextInput::FilePath),
                checkedExports = arguments.optionalStringList("checkedExports").orEmpty(),
                maxLocals = arguments.nullableInt("maxLocals"),
                countParams = arguments.booleanOrDefault(
                    "countParams",
                    HexanaToolInputDefaults.AUDIT_LOCAL_STACK_BUDGET_COUNT_PARAMS
                )
            )
        }
    }

    fun searchFunctionsByName(arguments: JsonObject?): CallToolResult {
        return execute("search_functions_by_name", SearchFunctionsByNameResult.serializer()) {
            analysisService.searchFunctionsByName(
                input = wasmInput(arguments),
                query = arguments.requireNonBlankString("query"),
                limit = arguments.limitOrResourceDefault("limit", LEGACY_SEARCH_FUNCTIONS_BY_NAME_LIMIT)
            )
        }
    }

    fun resolveWasmFrames(arguments: JsonObject?): CallToolResult {
        return execute("resolve_wasm_frames", ResolveWasmFramesResult.serializer()) {
            analysisService.resolveWasmFrames(
                input = wasmInput(arguments),
                frames = arguments.optionalFrameInputs("frames"),
                sourceMapInput = arguments.optionalNonBlankString("sourceMapPath")?.let(TextInput::FilePath),
                crashReportInput = arguments.optionalNonBlankString("crashReportPath")?.let(TextInput::FilePath),
            )
        }
    }

    fun resolveFunctionSymbols(arguments: JsonObject?): CallToolResult {
        return execute("resolve_function_symbols", ResolveFunctionSymbolsResult.serializer()) {
            analysisService.resolveFunctionSymbols(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices"),
                sourceMapInput = arguments.optionalNonBlankString("sourceMapPath")?.let(TextInput::FilePath)
            )
        }
    }

    fun resolveSourceLocations(arguments: JsonObject?): CallToolResult {
        return execute("resolve_source_locations", ResolveSourceLocationsResult.serializer()) {
            analysisService.resolveSourceLocations(
                input = wasmInput(arguments),
                locations = arguments.requireSourceLocationInputs("locations"),
                sourceMapInput = arguments.optionalNonBlankString("sourceMapPath")?.let(TextInput::FilePath)
            )
        }
    }

    fun listExportedFunctions(arguments: JsonObject?): CallToolResult {
        return execute("list_exported_functions", ListExportedFunctionsResult.serializer()) {
            analysisService.listExportedFunctions(
                input = wasmInput(arguments),
                entriesRange = arguments.requireClosedRange("entriesRange")
            )
        }
    }

    fun functionsForIndices(arguments: JsonObject?): CallToolResult {
        return execute("functions_for_indices", FunctionsResult.serializer()) {
            analysisService.functionsForIndices(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun listGlobals(arguments: JsonObject?): CallToolResult {
        return execute("list_globals", ListGlobalsResult.serializer()) {
            analysisService.listGlobals(wasmInput(arguments))
        }
    }

    fun auditGlobalStatePolicy(arguments: JsonObject?): CallToolResult {
        return execute("audit_global_state_policy", AuditGlobalStatePolicyResult.serializer()) {
            analysisService.auditGlobalStatePolicy(
                input = wasmInput(arguments),
                baselineInput = WasmInput.FilePath(arguments.requireNonBlankString("baselinePath")),
                policyInput = TextInput.FilePath(arguments.requireNonBlankString("policyPath")),
                logInput = arguments.optionalNonBlankString("logPath")?.let(TextInput::FilePath),
                detail = arguments.optionalNonBlankString("detail")
                    ?: HexanaToolInputDefaults.AUDIT_GLOBAL_STATE_POLICY_DETAIL
            )
        }
    }

    fun listTypes(arguments: JsonObject?): CallToolResult {
        return execute("list_types", TypesResult.serializer()) {
            analysisService.listTypes(wasmInput(arguments))
        }
    }

    fun listValueTypes(arguments: JsonObject?): CallToolResult {
        return execute("list_value_types", ListValueTypesResult.serializer()) {
            analysisService.listValueTypes(wasmInput(arguments))
        }
    }

    fun listReferenceTypes(arguments: JsonObject?): CallToolResult {
        return execute("list_reference_types", ListReferenceTypesResult.serializer()) {
            analysisService.listReferenceTypes(wasmInput(arguments))
        }
    }

    fun listMemory(arguments: JsonObject?): CallToolResult {
        return execute("list_memory", ListMemoryResult.serializer()) {
            analysisService.listMemory(wasmInput(arguments))
        }
    }

    fun checkMemoryContract(arguments: JsonObject?): CallToolResult {
        return execute("check_memory_contract", CheckMemoryContractResult.serializer()) {
            analysisService.checkMemoryContract(
                input = wasmInput(arguments),
                baselineInput = WasmInput.FilePath(arguments.requireNonBlankString("baselinePath")),
                policyInput = TextInput.FilePath(arguments.requireNonBlankString("policyPath")),
                logInput = arguments.optionalNonBlankString("logPath")?.let(TextInput::FilePath)
            )
        }
    }

    fun getGlobalsForIndices(arguments: JsonObject?): CallToolResult {
        return execute("get_globals_for_indices", ListGlobalsResult.serializer()) {
            analysisService.getGlobalsForIndices(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun listData(arguments: JsonObject?): CallToolResult {
        return execute("list_data", ListDataSectionsResult.serializer()) {
            analysisService.listData(
                input = wasmInput(arguments),
                entriesRange = arguments.requireClosedRange("entriesRange")
            )
        }
    }

    fun listDataSegments(arguments: JsonObject?): CallToolResult {
        return execute("list_data_segments", ListDataSegmentsResult.serializer()) {
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

    fun auditDataPayloadPolicy(arguments: JsonObject?): CallToolResult {
        return execute("audit_data_payload_policy", AuditDataPayloadPolicyResult.serializer()) {
            analysisService.auditDataPayloadPolicy(
                input = wasmInput(arguments),
                policyInput = TextInput.FilePath(arguments.requireNonBlankString("policyPath"))
            )
        }
    }

    fun getMemoryForIndices(arguments: JsonObject?): CallToolResult {
        return execute("get_memory_for_indices", ListMemoryResult.serializer()) {
            analysisService.getMemoryForIndices(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun getTypesForIndices(arguments: JsonObject?): CallToolResult {
        return execute("get_types_for_indices", TypesResult.serializer()) {
            analysisService.getTypesForIndices(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun getLocalsForFunctions(arguments: JsonObject?): CallToolResult {
        return execute("get_locals_for_functions", GetLocalsForFunctionsResult.serializer()) {
            analysisService.getLocalsForFunctions(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun getInstructionsForFunctions(arguments: JsonObject?): CallToolResult {
        return execute("get_instructions_for_functions", GetInstructionsForFunctionsResult.serializer()) {
            analysisService.getInstructionsForFunctions(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices")
            )
        }
    }

    fun listElementSegments(arguments: JsonObject?): CallToolResult {
        return execute("list_element_segments", ListElementSegmentsResult.serializer()) {
            analysisService.listElementSegments(wasmInput(arguments))
        }
    }

    private fun <T> execute(toolName: String, serializer: KSerializer<T>, block: () -> T): CallToolResult {
        val startTime = System.currentTimeMillis()
        return try {
            val result = block()
            val jsonResult = HexanaJson.compact.encodeToString(serializer, result)
            val structuredContent = HexanaJson.compact.encodeToJsonElement(serializer, result) as? JsonObject
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
            errorResult(error.code, error.message ?: "Invalid input")
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
        val bytesBase64Decoded = arguments.optionalNonBlankString("bytesBase64")
            ?.let { decodeInlineBase64(it, resourceLimits.maxInlineWasmBytes) }
        val bytes = arguments.optionalByteArray("bytes", resourceLimits.maxInlineWasmBytes)

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

    private fun binaryInput(arguments: JsonObject?): BinaryInput {
        val path = arguments.optionalNonBlankString("path")
        val bytesBase64 = arguments.optionalNonBlankString("bytesBase64")
        val bytes = arguments.optionalByteArray("bytes", resourceLimits.maxInlineBinaryBytes)

        val providedInputs = listOf(path, bytesBase64, bytes).count { it != null }
        if (providedInputs == 0) {
            throw ToolInputException("Exactly one of `path`, `bytesBase64`, or `bytes` is required")
        }
        if (providedInputs > 1) {
            throw ToolInputException("Provide exactly one of `path`, `bytesBase64`, or `bytes`")
        }

        return when {
            path != null -> BinaryInput.FilePath(path)
            bytesBase64 != null -> BinaryInput.InlineBytes(bytesBase64)
            bytes != null -> BinaryInput.RawBytes(bytes)
            else -> error("Unreachable input state")
        }
    }

    private fun decodeInlineBase64(encoded: String, maxInlineBytes: Int): ByteArray {
        var nonWhitespaceLen = 0
        var trailingPadding = 0
        var scanningTrailingPadding = true
        for (index in encoded.indices.reversed()) {
            val char = encoded[index]
            if (char.isWhitespace()) continue
            nonWhitespaceLen++
            if (scanningTrailingPadding && char == '=' && trailingPadding < 2) {
                trailingPadding++
            } else {
                scanningTrailingPadding = false
            }
        }
        val upperBoundDecoded = maxOf(0L, (nonWhitespaceLen.toLong() * 3L + 3L) / 4L - trailingPadding)
        if (upperBoundDecoded > maxInlineBytes.toLong()) {
            throw ToolInputException(
                "`bytesBase64` exceeds maximum decoded size of $maxInlineBytes bytes. Send a smaller inline payload or increase the configured MCP inline-size limit.",
                code = HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
            )
        }
        val decoded = try {
            java.util.Base64.getMimeDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw ToolInputException("`bytesBase64` must be valid base64")
        }
        if (decoded.size > maxInlineBytes) {
            throw ToolInputException(
                "`bytesBase64` exceeds maximum decoded size of $maxInlineBytes bytes. Send a smaller inline payload or increase the configured MCP inline-size limit.",
                code = HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
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

    private fun JsonObject?.optionalByteArray(name: String, maxInlineBytes: Int): ByteArray? {
        val element = this?.get(name) ?: return null
        if (element == JsonNull) return null
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        if (array.size > maxInlineBytes) {
            throw ToolInputException(
                "`$name` exceeds maximum size of $maxInlineBytes bytes. Send a smaller inline payload or increase the configured MCP inline-size limit.",
                code = HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
            )
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

    private fun JsonObject?.limitOrResourceDefault(name: String, default: Int): Int {
        if (this?.containsKey(name) == true) return intOrDefault(name, default)
        return minOf(default, resourceLimits.maxCandidateCount)
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

    private fun JsonObject?.optionalFrameInputs(name: String): List<WasmFrameInput> {
        val array = optionalObjectArray(name) ?: return emptyList()
        return parseFrameInputs(name, array)
    }

    private fun parseFrameInputs(name: String, array: List<JsonObject>): List<WasmFrameInput> {
        return array.mapIndexed { index, value ->
            WasmFrameInput(
                funcidx = value.requireInt("$name[$index]", "funcidx"),
                codeOffset = value.optionalLong("$name[$index]", "codeOffset"),
                module = value.optionalString("$name[$index]", "module"),
                label = value.optionalString("$name[$index]", "label"),
            )
        }
    }

    private fun JsonObject?.requireSourceLocationInputs(name: String): List<SourceLocationInput> {
        val array = requireObjectArray(name)
        return array.mapIndexed { index, value ->
            SourceLocationInput(
                funcidx = value.optionalInt("$name[$index]", "funcidx"),
                codeOffset = value.optionalLong("$name[$index]", "codeOffset"),
            )
        }
    }

    private fun JsonObject?.requireObjectArray(name: String): List<JsonObject> {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        return array.mapIndexed { index, value ->
            value as? JsonObject ?: throw ToolInputException("`$name[$index]` must be an object")
        }
    }

    private fun JsonObject?.optionalObjectArray(name: String): List<JsonObject>? {
        val element = this?.get(name) ?: return null
        if (element == JsonNull) return null
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        return array.mapIndexed { index, value ->
            value as? JsonObject ?: throw ToolInputException("`$name[$index]` must be an object")
        }
    }

    private fun JsonObject.requireInt(owner: String, name: String): Int {
        val element = get(name) ?: throw ToolInputException("`$owner.$name` is required")
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$owner.$name` must be an integer")
    }

    private fun JsonObject.optionalInt(owner: String, name: String): Int? {
        val element = get(name) ?: return null
        if (element == JsonNull) return null
        return element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$owner.$name` must be an integer")
    }

    private fun JsonObject.optionalLong(owner: String, name: String): Long? {
        val element = get(name) ?: return null
        if (element == JsonNull) return null
        return element.jsonPrimitive.longOrNull
            ?: throw ToolInputException("`$owner.$name` must be an integer")
    }

    private fun JsonObject.optionalString(owner: String, name: String): String? {
        val element = get(name) ?: return null
        if (element == JsonNull) return null
        return element.jsonPrimitive.contentOrNull
            ?: throw ToolInputException("`$owner.$name` must be a string")
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
    override val message: String,
    val code: HexanaAnalysisErrorCode = HexanaAnalysisErrorCode.INVALID_INPUT,
) : RuntimeException(message)
