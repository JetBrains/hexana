package org.jetbrains.hexana.mcp.server

import org.jetbrains.hexana.mcp.model.inspections.InspectBinaryResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
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
import org.jetbrains.hexana.mcp.model.scripting.*
import org.jetbrains.hexana.mcp.model.summary.*
import org.jetbrains.hexana.mcp.model.types.*
import org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnostic
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaScriptOutput
import org.jetbrains.hexana.mcp.scripting.HexanaScriptResult
import org.jetbrains.hexana.mcp.scripting.HexanaScriptRunner
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSource

private const val QUERY_FILTER_MAX_ITEMS = 20
private const val INDICES_MAX_ITEMS = 10_000
private const val DEPENDENCY_PATHS_MAX_ITEMS = 64
private const val CHECKED_EXPORTS_MAX_ITEMS = 500
private const val RESOLVE_WASM_FRAMES_MAX_ITEMS = 200
private const val SOURCE_LOCATIONS_MAX_ITEMS = 200
private const val SEARCH_EXPORTS_LIMIT_MAX = 200
private const val FUNCTION_NAME_SEARCH_LIMIT_MAX = 200

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
    private val allowedFilePolicy: AllowedFilePolicy = AllowedFilePolicy.allowAll,
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
                entityTypes = arguments.optionalStringList("entityTypes", maxItems = QUERY_FILTER_MAX_ITEMS).orEmpty(),
                query = arguments.optionalStringList("query", maxItems = QUERY_FILTER_MAX_ITEMS).orEmpty(),
                startName = arguments.optionalNonBlankString("startName"),
                startFuncidx = arguments.nullableInt("startFuncidx", minimum = 0),
                relations = arguments.optionalStringList("relations", maxItems = QUERY_FILTER_MAX_ITEMS).orEmpty(),
                limit = arguments.limitOrResourceDefault(
                    "limit",
                    HexanaToolInputDefaults.QUERY_ARTIFACTS_LIMIT,
                    maximum = HexanaAnalysisLimits.MAX_QUERY_LIMIT
                ),
                detail = arguments.stringOrDefault("detail", HexanaToolInputDefaults.QUERY_ARTIFACTS_DETAIL),
                maxBytes = arguments.intOrDefault(
                    "maxBytes",
                    HexanaToolInputDefaults.QUERY_ARTIFACTS_MAX_BYTES,
                    minimum = 1,
                    maximum = HexanaAnalysisLimits.MAX_OUTPUT_BYTES
                )
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

    fun inspectBinary(arguments: JsonObject?): CallToolResult {
        return execute("inspect_binary", InspectBinaryResult.serializer()) {
            analysisService.inspectBinary(
                input = binaryInput(arguments),
                runtimeTargetsInput = arguments.optionalNonBlankString("runtimeTargetsPath")?.let(TextInput::FilePath),
            )
        }
    }

    fun runBinaryScript(arguments: JsonObject?): CallToolResult {
        return execute("run_binary_script", RunBinaryScriptResult.serializer()) {
            runBinaryScriptResult(
                input = binaryInput(arguments),
                source = scriptSource(arguments),
            )
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
                entityTypes = arguments.optionalStringList("entityTypes", maxItems = QUERY_FILTER_MAX_ITEMS).orEmpty(),
                query = arguments.optionalStringList("query", maxItems = QUERY_FILTER_MAX_ITEMS).orEmpty(),
                offset = arguments.intOrDefault("offset", HexanaToolInputDefaults.QUERY_BINARY_OFFSET, minimum = 0),
                limit = arguments.limitOrResourceDefault(
                    "limit",
                    HexanaToolInputDefaults.QUERY_BINARY_LIMIT,
                    maximum = HexanaAnalysisLimits.MAX_QUERY_LIMIT
                ),
                detail = arguments.stringOrDefault("detail", HexanaToolInputDefaults.QUERY_BINARY_DETAIL),
                maxBytes = arguments.intOrDefault(
                    "maxBytes",
                    HexanaToolInputDefaults.QUERY_BINARY_MAX_BYTES,
                    minimum = 1,
                    maximum = HexanaAnalysisLimits.MAX_OUTPUT_BYTES
                )
            )
        }
    }

    fun listBinaryFunctions(arguments: JsonObject?): CallToolResult {
        return execute("list_binary_functions", BinaryFunctionsResult.serializer()) {
            analysisService.listBinaryFunctions(
                input = binaryInput(arguments),
                offset = arguments.intOrDefault(
                    "offset",
                    HexanaToolInputDefaults.LIST_BINARY_FUNCTIONS_OFFSET,
                    minimum = 0
                ),
                limit = arguments.limitOrResourceDefault(
                    "limit",
                    HexanaToolInputDefaults.LIST_BINARY_FUNCTIONS_LIMIT,
                    maximum = HexanaAnalysisLimits.MAX_QUERY_LIMIT
                )
            )
        }
    }

    fun searchBinaryFunctions(arguments: JsonObject?): CallToolResult {
        return execute("search_binary_functions", BinaryFunctionsResult.serializer()) {
            analysisService.searchBinaryFunctions(
                input = binaryInput(arguments),
                query = arguments.requireStringList("query", maxItems = QUERY_FILTER_MAX_ITEMS),
                offset = arguments.intOrDefault(
                    "offset",
                    HexanaToolInputDefaults.SEARCH_BINARY_FUNCTIONS_OFFSET,
                    minimum = 0
                ),
                limit = arguments.limitOrResourceDefault(
                    "limit",
                    HexanaToolInputDefaults.SEARCH_BINARY_FUNCTIONS_LIMIT,
                    maximum = HexanaAnalysisLimits.MAX_QUERY_LIMIT
                )
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
                scanRoots = arguments.optionalStringList(
                    "scanRoots",
                    maxItems = resourceLimits.maxScanRoots
                ).orEmpty()
            )
        }
    }

    fun checkComponentCompatibility(arguments: JsonObject?): CallToolResult {
        return execute("check_component_compatibility", CheckComponentCompatibilityResult.serializer()) {
            analysisService.checkComponentCompatibility(
                input = wasmInput(arguments),
                dependencyPaths = arguments.optionalStringList(
                    "dependencyPaths",
                    maxItems = DEPENDENCY_PATHS_MAX_ITEMS
                ).orEmpty(),
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
                query = arguments.requireStringList("query", maxItems = QUERY_FILTER_MAX_ITEMS),
                limit = arguments.limitOrResourceDefault(
                    "limit",
                    HexanaToolInputDefaults.SEARCH_EXPORTS_LIMIT,
                    maximum = SEARCH_EXPORTS_LIMIT_MAX
                )
            )
        }
    }

    fun listFunctions(arguments: JsonObject?): CallToolResult {
        return try {
            listFunctions(decodeListFunctionsRequest(arguments))
        } catch (error: ToolInputException) {
            listFunctionsInputFailure(error)
        }
    }

    internal fun listFunctions(request: ListFunctionsRequest): CallToolResult {
        return execute("list_functions", FunctionsResult.serializer()) { listFunctionsResult(request) }
    }

    internal fun listFunctionsInputFailure(error: ToolInputException): CallToolResult {
        return execute("list_functions", FunctionsResult.serializer()) { throw error }
    }

    private fun listFunctionsResult(request: ListFunctionsRequest): FunctionsResult {
        val input = wasmInput(request)
        val scalars = decodeListFunctionsRequestScalars(request)
        return analysisService.listFunctions(
            input = input,
            offset = scalars.offset ?: HexanaToolInputDefaults.LIST_FUNCTIONS_OFFSET,
            limit = scalars.limit ?: minOf(HexanaToolInputDefaults.LIST_FUNCTIONS_LIMIT, resourceLimits.maxCandidateCount),
        )
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
                funcidx = arguments.requireIntArgument("funcidx", minimum = 0)
            )
        }
    }

    fun disassembleFunction(arguments: JsonObject?): CallToolResult {
        return execute("disassemble_function", DisassembleFunctionResult.serializer()) {
            analysisService.disassembleFunction(
                input = wasmInput(arguments),
                funcidx = arguments.requireIntArgument("funcidx", minimum = 0)
            )
        }
    }

    fun getFunctionCallGraph(arguments: JsonObject?): CallToolResult {
        return execute("get_function_call_graph", GetFunctionCallGraphResult.serializer()) {
            analysisService.getFunctionCallGraph(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices", maxItems = INDICES_MAX_ITEMS, minimum = 0)
            )
        }
    }

    fun auditLocalStackBudget(arguments: JsonObject?): CallToolResult {
        return execute("audit_local_stack_budget", AuditLocalStackBudgetResult.serializer()) {
            analysisService.auditLocalStackBudget(
                input = wasmInput(arguments),
                policyInput = arguments.optionalNonBlankString("policyPath")?.let(TextInput::FilePath),
                logInput = arguments.optionalNonBlankString("logPath")?.let(TextInput::FilePath),
                checkedExports = arguments.optionalStringList(
                    "checkedExports",
                    maxItems = CHECKED_EXPORTS_MAX_ITEMS
                ).orEmpty(),
                maxLocals = arguments.nullableInt("maxLocals", minimum = 0),
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
                limit = arguments.limitOrResourceDefault(
                    "limit",
                    HexanaToolInputDefaults.SEARCH_FUNCTIONS_BY_NAME_LIMIT,
                    maximum = FUNCTION_NAME_SEARCH_LIMIT_MAX
                )
            )
        }
    }

    fun resolveWasmFrames(arguments: JsonObject?): CallToolResult {
        return execute("resolve_wasm_frames", ResolveWasmFramesResult.serializer()) {
            analysisService.resolveWasmFrames(
                input = wasmInput(arguments),
                frames = arguments.optionalFrameInputs("frames", maxItems = RESOLVE_WASM_FRAMES_MAX_ITEMS),
                sourceMapInput = arguments.optionalNonBlankString("sourceMapPath")?.let(TextInput::FilePath),
                crashReportInput = arguments.optionalNonBlankString("crashReportPath")?.let(TextInput::FilePath),
            )
        }
    }

    fun resolveFunctionSymbols(arguments: JsonObject?): CallToolResult {
        return execute("resolve_function_symbols", ResolveFunctionSymbolsResult.serializer()) {
            analysisService.resolveFunctionSymbols(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices", maxItems = INDICES_MAX_ITEMS, minimum = 0),
                sourceMapInput = arguments.optionalNonBlankString("sourceMapPath")?.let(TextInput::FilePath)
            )
        }
    }

    fun resolveSourceLocations(arguments: JsonObject?): CallToolResult {
        return execute("resolve_source_locations", ResolveSourceLocationsResult.serializer()) {
            analysisService.resolveSourceLocations(
                input = wasmInput(arguments),
                locations = arguments.requireSourceLocationInputs("locations", maxItems = SOURCE_LOCATIONS_MAX_ITEMS),
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
                indices = arguments.requireIntList("indices", maxItems = INDICES_MAX_ITEMS, minimum = 0)
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
                indices = arguments.requireIntList("indices", maxItems = INDICES_MAX_ITEMS, minimum = 0)
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
        return try {
            listDataSegments(decodeListDataSegmentsRequest(arguments))
        } catch (error: ToolInputException) {
            listDataSegmentsInputFailure(error)
        }
    }

    internal fun listDataSegments(request: ListDataSegmentsRequest): CallToolResult =
        execute("list_data_segments", ListDataSegmentsResult.serializer()) {
            val fields = decodeListDataSegmentsRequestScalars(request)
            val input = wasmInput(request)
            analysisService.listDataSegments(
                input = input,
                segmentStartInclusive = fields.segmentRange.first,
                segmentEndExclusive = fields.segmentRange.second ?: Int.MAX_VALUE,
                includeExports = fields.includeExports ?: HexanaToolInputDefaults.LIST_DATA_SEGMENTS_INCLUDE_EXPORTS,
                includeGlobals = fields.includeGlobals ?: HexanaToolInputDefaults.LIST_DATA_SEGMENTS_INCLUDE_GLOBALS,
                minByteLength = fields.minByteLength,
            )
        }

    internal fun listDataSegmentsInputFailure(error: ToolInputException): CallToolResult =
        execute("list_data_segments", ListDataSegmentsResult.serializer()) { throw error }

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
                indices = arguments.requireIntList("indices", maxItems = INDICES_MAX_ITEMS, minimum = 0)
            )
        }
    }

    fun getTypesForIndices(arguments: JsonObject?): CallToolResult {
        return execute("get_types_for_indices", TypesResult.serializer()) {
            analysisService.getTypesForIndices(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices", maxItems = INDICES_MAX_ITEMS, minimum = 0)
            )
        }
    }

    fun getLocalsForFunctions(arguments: JsonObject?): CallToolResult {
        return execute("get_locals_for_functions", GetLocalsForFunctionsResult.serializer()) {
            analysisService.getLocalsForFunctions(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices", maxItems = INDICES_MAX_ITEMS, minimum = 0)
            )
        }
    }

    fun getInstructionsForFunctions(arguments: JsonObject?): CallToolResult {
        return execute("get_instructions_for_functions", GetInstructionsForFunctionsResult.serializer()) {
            analysisService.getInstructionsForFunctions(
                input = wasmInput(arguments),
                indices = arguments.requireIntList("indices", maxItems = INDICES_MAX_ITEMS, minimum = 0)
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
            val encodedResult = HexanaJson.compact.encodeToJsonElement(serializer, result)
            val jsonResult = encodedResult.toString()
            val encodedBytes = jsonResult.encodeToByteArray().size
            if (encodedBytes > resourceLimits.maxOutputBytes) {
                throw HexanaAnalysisException(
                    HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED,
                    "Encoded result for `$toolName` is $encodedBytes bytes, exceeding the configured " +
                        "maximum of ${resourceLimits.maxOutputBytes} bytes."
                )
            }
            val structuredContent = encodedResult as? JsonObject
            captureToolEvent(toolName, startTime, success = true)
            CallToolResult(
                content = listOf(TextContent(jsonResult)),
                structuredContent = structuredContent
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: HexanaAnalysisException) {
            captureToolEvent(toolName, startTime, success = false)
            val diagnostic = reportAnalysisFailure(toolName, error)
            errorResult(
                error.code,
                error.message + (diagnostic?.let { " Diagnostic id ${it.id}." } ?: "")
            )
        } catch (error: ToolInputException) {
            captureToolEvent(toolName, startTime, success = false)
            errorResult(error.code, error.message ?: "Invalid input")
        } catch (error: Exception) {
            captureToolEvent(toolName, startTime, success = false)
            val diagnostic = emitDiagnosticFailure(
                HexanaFailureCategory.TOOL_INTERNAL,
                "Unexpected internal failure while executing MCP tool `$toolName`.",
                error,
            )
            errorResult(
                HexanaAnalysisErrorCode.INTERNAL_ERROR,
                "Internal error while executing `$toolName`." +
                    (diagnostic.takeIf { it.available }?.let { " Diagnostic id ${it.id}." } ?: "")
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

    private fun reportAnalysisFailure(
        toolName: String,
        error: HexanaAnalysisException,
    ): HexanaDiagnosticReference? {
        return if (error.code == HexanaAnalysisErrorCode.INTERNAL_ERROR || error.cause != null) {
            emitDiagnosticFailure(
                HexanaFailureCategory.TOOL_INTERNAL,
                "MCP tool `$toolName` failed with ${error.code.name}.",
                error,
            ).takeIf { it.available }
        } else {
            null
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

    private fun wasmInput(request: ListFunctionsRequest): WasmInput {
        val path = request.path.optionalNonBlankString("path")
        val bytesBase64Decoded = request.bytesBase64.optionalNonBlankString("bytesBase64")
            ?.let { decodeInlineBase64(it, resourceLimits.maxInlineWasmBytes) }
        val bytes = request.bytes.optionalByteArray("bytes", resourceLimits.maxInlineWasmBytes)

        requireListFunctionsRequestSourceGroup0(
            path != null,
            bytesBase64Decoded != null,
            bytes != null,
        )

        return when {
            path != null -> WasmInput.FilePath(path)
            bytesBase64Decoded != null -> WasmInput.RawBytes(bytesBase64Decoded)
            bytes != null -> WasmInput.RawBytes(bytes)
            else -> error("Generated list_functions source selection did not select a source")
        }
    }

    private fun wasmInput(request: ListDataSegmentsRequest): WasmInput {
        val path = request.path.optionalNonBlankString("path")
        val bytesBase64Decoded = request.bytesBase64.optionalNonBlankString("bytesBase64")
            ?.let { decodeInlineBase64(it, resourceLimits.maxInlineWasmBytes) }
        val bytes = request.bytes.optionalByteArray("bytes", resourceLimits.maxInlineWasmBytes)

        requireListDataSegmentsRequestSourceGroup0(path != null, bytesBase64Decoded != null, bytes != null)
        return when {
            path != null -> WasmInput.FilePath(path)
            bytesBase64Decoded != null -> WasmInput.RawBytes(bytesBase64Decoded)
            bytes != null -> WasmInput.RawBytes(bytes)
            else -> error("Generated list_data_segments source selection did not select a source")
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

    private fun scriptSource(arguments: JsonObject?): HexanaScriptSource {
        val scriptPath = arguments.optionalNonBlankString("scriptPath")
        val script = arguments.optionalNonBlankString("script")
        val providedInputs = listOf(scriptPath, script).count { it != null }
        if (providedInputs == 0) {
            throw ToolInputException("Exactly one of `scriptPath` or `script` is required")
        }
        if (providedInputs > 1) {
            throw ToolInputException("Provide exactly one of `scriptPath` or `script`")
        }

        return when {
            scriptPath != null -> HexanaScriptSource.FilePath(scriptPath)
            script != null -> HexanaScriptSource.Inline(script)
            else -> error("Unreachable script source state")
        }
    }

    private fun runBinaryScriptResult(input: BinaryInput, source: HexanaScriptSource): RunBinaryScriptResult {
        return HexanaScriptRunner(
            limits = HexanaScriptLimits(
                maxOutputBytes = resourceLimits.maxOutputBytes,
                maxInlineScriptBytes = resourceLimits.maxTextFileBytes,
                maxScriptFileBytes = resourceLimits.maxTextFileBytes,
                maxInlineBinaryBytes = resourceLimits.maxInlineBinaryBytes,
                maxFileBinaryBytes = resourceLimits.maxFileBinaryBytes,
            ),
            allowedFilePolicy = allowedFilePolicy,
        ).run(input, source).toRunBinaryScriptResult()
    }

    private fun HexanaScriptResult.toRunBinaryScriptResult(): RunBinaryScriptResult =
        RunBinaryScriptResult(
            status = status.name,
            success = success,
            output = output.toRunBinaryScriptOutputResult(),
            diagnostics = diagnostics.map { it.toRunBinaryScriptDiagnosticResult() },
            binary = binary,
            sourceName = sourceName,
            sourcePath = sourcePath,
            durationMillis = durationMillis,
        )

    private fun HexanaScriptOutput.toRunBinaryScriptOutputResult(): RunBinaryScriptOutputResult =
        RunBinaryScriptOutputResult(
            lines = lines,
            truncated = truncated,
            emittedBytes = emittedBytes,
            maxLines = maxLines,
            maxBytes = maxBytes,
        )

    private fun HexanaScriptDiagnostic.toRunBinaryScriptDiagnosticResult(): RunBinaryScriptDiagnosticResult =
        RunBinaryScriptDiagnosticResult(
            code = code,
            message = message,
            severity = severity.name,
            exceptionClass = exceptionClass,
            sourceName = sourceName,
            line = line,
            column = column,
        )

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

    private fun JsonElement?.optionalNonBlankString(name: String): String? {
        val element = this ?: return null
        if (element == JsonNull) return null
        val value = element.jsonPrimitive.contentOrNull
            ?: throw ToolInputException("`$name` must be a string")
        if (value.isBlank()) throw ToolInputException("`$name` must not be blank")
        return value
    }

    private fun JsonElement?.optionalByteArray(name: String, maxInlineBytes: Int): ByteArray? {
        val element = this ?: return null
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

    private fun JsonObject?.intOrDefault(
        name: String,
        default: Int,
        minimum: Int? = null,
        maximum: Int? = null,
    ): Int {
        val element = this?.get(name) ?: return default
        val value = element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
        return value.validateIntBounds(name, minimum, maximum)
    }

    private fun JsonObject?.limitOrResourceDefault(name: String, default: Int, maximum: Int? = null): Int {
        if (this?.containsKey(name) == true) return intOrDefault(name, default, minimum = 1, maximum = maximum)
        return minOf(default, resourceLimits.maxCandidateCount)
    }

    private fun JsonObject?.requireIntArgument(name: String, minimum: Int? = null, maximum: Int? = null): Int {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        val value = element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
        return value.validateIntBounds(name, minimum, maximum)
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

    private fun JsonObject?.nullableInt(name: String, minimum: Int? = null, maximum: Int? = null): Int? {
        val element = this?.get(name) ?: return null
        if (element == JsonNull) return null
        val value = element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$name` must be an integer")
        return value.validateIntBounds(name, minimum, maximum)
    }

    private fun JsonObject?.booleanOrDefault(name: String, default: Boolean): Boolean {
        val element = this?.get(name) ?: return default
        return element.jsonPrimitive.booleanOrNull
            ?: throw ToolInputException("`$name` must be a boolean")
    }

    private fun JsonObject?.requireIntList(name: String, maxItems: Int? = null, minimum: Int? = null): List<Int> {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        array.requireMaxItems(name, maxItems)
        return array.mapIndexed { index, value ->
            val item = value.jsonPrimitive.intOrNull
                ?: throw ToolInputException("`$name[$index]` must be an integer")
            item.validateIntBounds("$name[$index]", minimum, maximum = null)
        }
    }

    private fun JsonObject?.requireStringList(name: String, maxItems: Int? = null): List<String> {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        array.requireMaxItems(name, maxItems)
        return array.mapIndexed { index, value ->
            value.jsonPrimitive.contentOrNull
                ?: throw ToolInputException("`$name[$index]` must be a string")
        }
    }

    private fun JsonObject?.optionalFrameInputs(name: String, maxItems: Int? = null): List<WasmFrameInput> {
        val array = optionalObjectArray(name, maxItems) ?: return emptyList()
        return parseFrameInputs(name, array)
    }

    private fun parseFrameInputs(name: String, array: List<JsonObject>): List<WasmFrameInput> {
        return array.mapIndexed { index, value ->
            WasmFrameInput(
                funcidx = value.requireInt("$name[$index]", "funcidx", minimum = 0),
                codeOffset = value.optionalLong("$name[$index]", "codeOffset"),
                module = value.optionalString("$name[$index]", "module"),
                label = value.optionalString("$name[$index]", "label"),
            )
        }
    }

    private fun JsonObject?.requireSourceLocationInputs(name: String, maxItems: Int? = null): List<SourceLocationInput> {
        val array = requireObjectArray(name, maxItems)
        return array.mapIndexed { index, value ->
            SourceLocationInput(
                funcidx = value.optionalInt("$name[$index]", "funcidx", minimum = 0),
                codeOffset = value.optionalLong("$name[$index]", "codeOffset"),
            )
        }
    }

    private fun JsonObject?.requireObjectArray(name: String, maxItems: Int? = null): List<JsonObject> {
        val element = this?.get(name) ?: throw ToolInputException("`$name` is required")
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        array.requireMaxItems(name, maxItems)
        return array.mapIndexed { index, value ->
            value as? JsonObject ?: throw ToolInputException("`$name[$index]` must be an object")
        }
    }

    private fun JsonObject?.optionalObjectArray(name: String, maxItems: Int? = null): List<JsonObject>? {
        val element = this?.get(name) ?: return null
        if (element == JsonNull) return null
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        array.requireMaxItems(name, maxItems)
        return array.mapIndexed { index, value ->
            value as? JsonObject ?: throw ToolInputException("`$name[$index]` must be an object")
        }
    }

    private fun JsonObject.requireInt(owner: String, name: String, minimum: Int? = null, maximum: Int? = null): Int {
        val element = get(name) ?: throw ToolInputException("`$owner.$name` is required")
        val value = element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$owner.$name` must be an integer")
        return value.validateIntBounds("$owner.$name", minimum, maximum)
    }

    private fun JsonObject.optionalInt(owner: String, name: String, minimum: Int? = null, maximum: Int? = null): Int? {
        val element = get(name) ?: return null
        if (element == JsonNull) return null
        val value = element.jsonPrimitive.intOrNull
            ?: throw ToolInputException("`$owner.$name` must be an integer")
        return value.validateIntBounds("$owner.$name", minimum, maximum)
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

    private fun JsonObject?.optionalStringList(name: String, maxItems: Int? = null): List<String>? {
        val element = this?.get(name) ?: return null
        if (element == JsonNull) return null
        val array = element as? JsonArray ?: throw ToolInputException("`$name` must be an array")
        array.requireMaxItems(name, maxItems)
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

    private fun JsonObject.requireRangeInt(rangeName: String, fieldName: String): Int {
        val element = get(fieldName) ?: throw ToolInputException("`$rangeName.$fieldName` is required")
        val value = (element as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?: throw ToolInputException("`$rangeName.$fieldName` must be an integer")
        return value.validateIntBounds("$rangeName.$fieldName", minimum = 0, maximum = null)
    }

    private fun JsonArray.requireMaxItems(name: String, maxItems: Int?) {
        if (maxItems != null && size > maxItems) {
            throw ToolInputException("`$name` must contain at most $maxItems items")
        }
    }

    private fun Int.validateIntBounds(name: String, minimum: Int?, maximum: Int?): Int {
        if (minimum != null && maximum != null && (this < minimum || this > maximum)) {
            throw ToolInputException("`$name` must be between $minimum and $maximum")
        }
        if (minimum != null && this < minimum) {
            throw ToolInputException("`$name` must be >= $minimum")
        }
        if (maximum != null && this > maximum) {
            throw ToolInputException("`$name` must be <= $maximum")
        }
        return this
    }
}

internal class ToolInputException(
    override val message: String,
    val code: HexanaAnalysisErrorCode = HexanaAnalysisErrorCode.INVALID_INPUT,
) : RuntimeException(message)
