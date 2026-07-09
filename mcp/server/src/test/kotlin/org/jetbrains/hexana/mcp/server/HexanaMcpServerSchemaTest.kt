package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaMcpServerSchemaTest {
    private val compactJson = Json { encodeDefaults = false }

    @Test
    fun generatedToolsAreMarkedReadOnly() {
        val annotations = createHexanaMcpServer(toolProfile = HexanaMcpToolProfile.ADVANCED)
            .tools
            .getValue("query_artifacts")
            .tool
            .annotations
            ?: error("Missing query_artifacts annotations")

        assertTrue(annotations.readOnlyHint == true)
        assertFalse(annotations.destructiveHint == true)
        assertTrue(annotations.idempotentHint == true)
    }

    @Test
    fun generatedCompatibilityMarkerIsAvailable() {
        assertEquals(1, HexanaMcpToolCompatibility.SCHEMA_VERSION)
        assertTrue(HexanaMcpToolCompatibility.POLICY.contains("append-compatible within a version"))
        assertTrue(HexanaMcpToolCompatibility.POLICY.contains("schema version bump"))
    }

    @Test
    fun generatedProfileMetadataDrivesDefaultProductionAndAdvancedRegistries() {
        val productionNames = HexanaGeneratedMcpToolProfiles.PRODUCTION_TOOL_NAMES
        assertEquals(29, productionNames.size)
        assertEquals(
            productionNames,
            createHexanaMcpServer(toolProfile = HexanaMcpToolProfile.PRODUCTION).tools.keys.toSet(),
        )
        assertEquals(
            productionNames,
            createHexanaMcpServer().tools.keys.toSet(),
        )

        val advancedNames = HexanaGeneratedMcpToolProfiles.ADVANCED_TOOL_NAMES
        assertEquals(
            advancedNames,
            createHexanaMcpServer(toolProfile = HexanaMcpToolProfile.ADVANCED).tools.keys.toSet(),
        )
        assertTrue(advancedNames.all { HexanaMcpToolProfile.ADVANCED.includesTool(it) })
        assertTrue(productionNames.all { HexanaMcpToolProfile.PRODUCTION.includesTool(it) })
        assertFalse(HexanaMcpToolProfile.PRODUCTION.includesTool("query_artifacts"))
    }

    @Test
    fun directGeneratedRegistrationDefaultsToProductionProfile() {
        val server = createServerWithDirectGeneratedRegistration()

        assertEquals(
            HexanaGeneratedMcpToolProfiles.PRODUCTION_TOOL_NAMES,
            server.tools.keys.toSet(),
        )
        assertFalse(server.tools.containsKey("query_artifacts"))
    }

    @Test
    fun generatedCliRegistryExposesVisibleCatalogMetadataOnly() {
        val tools = HexanaGeneratedCliToolRegistry.TOOLS
        val summarizeModule = tools.single { it.commandName == "summarize-module" }

        assertEquals("summarize_module", summarizeModule.toolName)
        assertEquals(
            "Summarize a WebAssembly module — counts of imports, exports, memories, globals, functions, types.",
            summarizeModule.summary,
        )
        assertEquals(setOf(HexanaCliToolGroup.MODULE), summarizeModule.groups)
        assertEquals(
            setOf(HexanaCliInputMode.WASM_PATH),
            summarizeModule.inputModes,
        )
        assertFalse(HexanaCliInputMode.JSON_OBJECT in summarizeModule.inputModes)
        assertFalse(HexanaCliInputMode.WASM_FROM_STRING in summarizeModule.inputModes)
        assertFalse(HexanaCliInputMode.STDIN in summarizeModule.inputModes)
        assertEquals(setOf(HexanaCliOutputFormat.JSON, HexanaCliOutputFormat.PRETTY), summarizeModule.outputFormats)
        assertEquals(HexanaCliOutputFormat.PRETTY, summarizeModule.defaultOutputFormat)
        assertEquals(HexanaCliOutputPolicy.TOOL_DEFINED, summarizeModule.defaultOutputPolicy)
        assertEquals(HexanaCliExecutionStatus.EXECUTABLE, summarizeModule.executionStatus)

        val summarizeBinary = tools.single { it.commandName == "summarize-binary" }
        assertEquals("summarize_binary", summarizeBinary.toolName)
        assertEquals(setOf(HexanaCliToolGroup.BINARY), summarizeBinary.groups)
        assertEquals(
            setOf(HexanaCliInputMode.BINARY_PATH),
            summarizeBinary.inputModes,
        )
        assertFalse(HexanaCliInputMode.JSON_OBJECT in summarizeBinary.inputModes)
        assertFalse(HexanaCliInputMode.WASM_PATH in summarizeBinary.inputModes)
        assertFalse(HexanaCliInputMode.STDIN in summarizeBinary.inputModes)
        assertEquals(setOf(HexanaCliOutputFormat.JSON), summarizeBinary.outputFormats)
        assertEquals(HexanaCliOutputFormat.JSON, summarizeBinary.defaultOutputFormat)
        assertEquals(HexanaCliOutputPolicy.TOOL_DEFINED, summarizeBinary.defaultOutputPolicy)
        assertEquals(HexanaCliExecutionStatus.EXECUTABLE, summarizeBinary.executionStatus)

        val wasmToWat = tools.single { it.commandName == "wasm-to-wat" }
        assertEquals("wasm_to_wat", wasmToWat.toolName)
        assertEquals(setOf(HexanaCliToolGroup.CONVERTERS), wasmToWat.groups)
        assertEquals(
            setOf(HexanaCliInputMode.WASM_PATH),
            wasmToWat.inputModes,
        )
        assertFalse(HexanaCliInputMode.JSON_OBJECT in wasmToWat.inputModes)
        assertFalse(HexanaCliInputMode.WASM_FROM_STRING in wasmToWat.inputModes)
        assertFalse(HexanaCliInputMode.STDIN in wasmToWat.inputModes)
        assertEquals(setOf(HexanaCliOutputFormat.JSON, HexanaCliOutputFormat.RAW), wasmToWat.outputFormats)
        assertEquals(HexanaCliOutputFormat.RAW, wasmToWat.defaultOutputFormat)
        assertEquals(HexanaCliOutputPolicy.TOOL_DEFINED, wasmToWat.defaultOutputPolicy)
        assertEquals(HexanaCliExecutionStatus.EXECUTABLE, wasmToWat.executionStatus)

        val futureModuleTool = tools.single { it.commandName == "list-enabled-features" }
        assertTrue(futureModuleTool.groups.contains(HexanaCliToolGroup.MODULE))
        assertEquals(emptySet(), futureModuleTool.inputModes)
        assertEquals(HexanaCliExecutionStatus.TODO, futureModuleTool.executionStatus)

        val productionNames = HexanaGeneratedMcpToolProfiles.PRODUCTION_TOOL_NAMES
        val advancedNames = HexanaGeneratedMcpToolProfiles.ADVANCED_TOOL_NAMES
        assertFalse("wasm_to_wat" in productionNames)
        assertFalse("wasm_to_wat" in advancedNames)
        assertFalse(createHexanaMcpServer(toolProfile = HexanaMcpToolProfile.PRODUCTION).tools.containsKey("wasm_to_wat"))
        assertFalse(createHexanaMcpServer(toolProfile = HexanaMcpToolProfile.ADVANCED).tools.containsKey("wasm_to_wat"))

        assertFalse(tools.any { it.commandName == "query-artifacts" })
        assertFalse(tools.any { it.toolName == "query_artifacts" })
    }

    @Test
    fun generatedCliRegistryPreservesProductionParityAndCliOnlyDivergence() {
        val productionNames = HexanaGeneratedMcpToolProfiles.PRODUCTION_TOOL_NAMES
        val cliNames = HexanaGeneratedCliToolRegistry.TOOLS.map { it.toolName }.toSet()

        assertEquals(
            emptySet(),
            productionNames - cliNames,
            "Production MCP tools missing from generated CLI registry",
        )
        assertEquals(
            setOf("wasm_to_wat"),
            cliNames - productionNames,
            "Generated CLI registry should only contain documented CLI-only tools outside production MCP",
        )
    }

    @Test
    fun productionRegistryPresentsFocusedAuditToolsBeforeGenericViews() {
        val names = createHexanaMcpServer().tools.keys.toList()

        assertBefore(names, "plan_abi_binding_regeneration", "list_exports")
        assertBefore(names, "check_memory_contract", "list_memory")
        assertBefore(names, "check_component_compatibility", "list_imports")
        assertBefore(names, "check_component_compatibility", "list_exports")
        assertBefore(names, "audit_global_state_policy", "list_globals")
        assertBefore(names, "audit_global_state_policy", "list_exports")
        assertBefore(names, "audit_local_stack_budget", "list_exported_functions")
        assertBefore(names, "audit_local_stack_budget", "disassemble_function")
    }

    @Test
    fun getEntrypointPolicyDescriptorDescribesDomainBehavior() {
        val tool = createHexanaMcpServer()
            .tools
            .getValue("get_entrypoint_policy")
            .tool

        val description = tool.description ?: error("Missing get_entrypoint_policy description")
        assertTrue(description.contains("Return policy-ready direct host-call facts"))
        assertTrue(description.contains("copy unchanged into both the policy file"))
        assertFalse(description.contains("entrypoint_policy_repair"))
        assertFalse(description.contains("task"))
    }

    @Test
    fun wasmInputPropertiesDescribeRuntimeValidation() {
        val schema = queryArtifactsInputSchema()

        assertEquals(
            "Readable local `.wasm` path. Provide exactly one of `path`, `bytesBase64`, or `bytes`.",
            schema.getValue("path").jsonObject.getValue("description").jsonPrimitive.content
        )
        assertEquals(
            "Base64 `.wasm` bytes. Provide exactly one of `path`, `bytesBase64`, or `bytes`.",
            schema.getValue("bytesBase64").jsonObject.getValue("description").jsonPrimitive.content
        )
        assertEquals(
            "Raw `.wasm` bytes as 0..255 integers. Provide exactly one of `path`, `bytesBase64`, or `bytes`.",
            schema.getValue("bytes").jsonObject.getValue("description").jsonPrimitive.content
        )
    }

    @Test
    fun productionRegistryStaysWithinSchemaBudget() {
        val tools = createHexanaMcpServer(toolProfile = HexanaMcpToolProfile.PRODUCTION)
            .tools
            .values
            .map { it.tool }
            .sortedBy { it.name }

        val schemaBytes = compactJson.encodeToString<List<Tool>>(tools).encodeToByteArray().size
        val estimatedTokens = (schemaBytes + 3) / 4

        assertTrue(
            estimatedTokens <= 13_875,
            "Production MCP schema estimate is $estimatedTokens tokens ($schemaBytes bytes), expected <= 13875",
        )
    }

    @Test
    fun wasmInputPropertiesCarryInlineByteLimits() {
        val schema = queryArtifactsInputSchema()

        val bytesBase64 = schema.getValue("bytesBase64").jsonObject
        assertEquals(
            ((HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES + 2) / 3) * 4,
            bytesBase64.getValue("maxLength").jsonPrimitive.int
        )

        val bytes = schema.getValue("bytes").jsonObject
        assertEquals(HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES, bytes.getValue("maxItems").jsonPrimitive.int)

        val byteItem = bytes.getValue("items").jsonObject
        assertEquals(0, byteItem.getValue("minimum").jsonPrimitive.int)
        assertEquals(255, byteItem.getValue("maximum").jsonPrimitive.int)
    }

    @Test
    fun nullableInputPropertiesAllowNullInGeneratedSchemas() {
        val schema = queryArtifactsInputSchema()

        assertEquals(listOf("string", "null"), schemaTypes(schema.getValue("path").jsonObject))
        assertEquals(listOf("string", "null"), schemaTypes(schema.getValue("bytesBase64").jsonObject))
        assertEquals(listOf("array", "null"), schemaTypes(schema.getValue("bytes").jsonObject))
        assertEquals(listOf("string", "null"), schemaTypes(schema.getValue("baselinePath").jsonObject))
        assertEquals(listOf("array", "null"), schemaTypes(schema.getValue("entityTypes").jsonObject))
        assertEquals(listOf("array", "null"), schemaTypes(schema.getValue("query").jsonObject))
        assertEquals(listOf("string", "null"), schemaTypes(schema.getValue("startName").jsonObject))
        assertEquals(listOf("integer", "null"), schemaTypes(schema.getValue("startFuncidx").jsonObject))
        assertEquals(listOf("array", "null"), schemaTypes(schema.getValue("relations").jsonObject))
    }

    @Test
    fun inputDefaultsArePresentInGeneratedSchemas() {
        val schema = queryArtifactsInputSchema()

        assertEquals("overview", schema.getValue("operation").jsonObject.getValue("default").jsonPrimitive.content)
        assertEquals(25, schema.getValue("limit").jsonObject.getValue("default").jsonPrimitive.int)
        assertEquals("summary", schema.getValue("detail").jsonObject.getValue("default").jsonPrimitive.content)
        assertEquals(24_000, schema.getValue("maxBytes").jsonObject.getValue("default").jsonPrimitive.int)
    }

    @Test
    fun binaryQuerySchemaCarriesInputBoundsAndNullableSources() {
        val schema = toolInputSchema("query_binary")

        assertEquals(listOf("string", "null"), schemaTypes(schema.getValue("path").jsonObject))
        assertEquals(listOf("string", "null"), schemaTypes(schema.getValue("bytesBase64").jsonObject))
        assertEquals(listOf("array", "null"), schemaTypes(schema.getValue("bytes").jsonObject))
        assertEquals("overview", schema.getValue("operation").jsonObject.getValue("default").jsonPrimitive.content)
        assertEquals(0, schema.getValue("offset").jsonObject.getValue("default").jsonPrimitive.int)
        assertEquals(25, schema.getValue("limit").jsonObject.getValue("default").jsonPrimitive.int)
        assertEquals("summary", schema.getValue("detail").jsonObject.getValue("default").jsonPrimitive.content)
        assertEquals(24_000, schema.getValue("maxBytes").jsonObject.getValue("default").jsonPrimitive.int)
        assertEquals(1, schema.getValue("limit").jsonObject.getValue("minimum").jsonPrimitive.int)
        assertEquals(100, schema.getValue("limit").jsonObject.getValue("maximum").jsonPrimitive.int)
        assertEquals(1, schema.getValue("maxBytes").jsonObject.getValue("minimum").jsonPrimitive.int)
        assertEquals(60_000, schema.getValue("maxBytes").jsonObject.getValue("maximum").jsonPrimitive.int)
    }

    @Test
    fun responseBoundsArePresentInGeneratedSchema() {
        val limit = queryArtifactsInputSchema().getValue("limit").jsonObject
        val maxBytes = queryArtifactsInputSchema().getValue("maxBytes").jsonObject

        assertEquals(1, limit.getValue("minimum").jsonPrimitive.int)
        assertEquals(100, limit.getValue("maximum").jsonPrimitive.int)
        assertEquals(1, maxBytes.getValue("minimum").jsonPrimitive.int)
        assertEquals(60_000, maxBytes.getValue("maximum").jsonPrimitive.int)
    }

    @Test
    fun abiBindingPlannerSchemaUsesRequiredBindingPathAndResultsSignatures() {
        val tool = createHexanaMcpServer()
            .tools
            .getValue("plan_abi_binding_regeneration")
            .tool

        val input = tool.inputSchema.properties?.jsonObject ?: error("Missing planner input schema properties")
        assertEquals("string", input.getValue("bindingPath").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("bindingPath"), tool.inputSchema.required)

        val output = tool.outputSchema?.properties?.jsonObject ?: error("Missing planner output schema properties")
        assertTrue("module" in output)
        assertTrue("imports" in output)
        assertTrue("exports" in output)
        assertTrue("replacementBindings" in output)
        assertFalse(output.containsSchemaDescription())
    }

    @Test
    fun wasmFrameResolverSchemaExposesResultsSignatureAlias() {
        val tool = createHexanaMcpServer()
            .tools
            .getValue("resolve_wasm_frames")
            .tool

        val description = tool.description ?: error("Missing frame resolver description")
        assertTrue(description.contains("Prefer `crashReportPath`"))

        val input = tool.inputSchema.properties?.jsonObject ?: error("Missing frame resolver input schema")
        assertTrue(
            input.getValue("crashReportPath")
                .jsonObject
                .getValue("description")
                .jsonPrimitive
                .content
                .contains("Prefer this over manually constructing `frames`")
        )

        val output = tool.outputSchema?.properties?.jsonObject ?: error("Missing frame resolver output schema")
        assertFalse(output.containsSchemaDescription())
        assertTrue("frames" in output)
        assertTrue("crashReport" in output)
        assertTrue("topApplicationFrame" in output)
        assertTrue("skippedFrames" in output)
    }

    @Test
    fun memoryContractSchemaEchoesInputPathsAndReportFields() {
        val tool = createHexanaMcpServer()
            .tools
            .getValue("check_memory_contract")
            .tool

        val output = tool.outputSchema?.properties?.jsonObject ?: error("Missing memory contract output schema")
        assertTrue("module" in output)
        val memories = output.getValue("memories").jsonObject
        assertEquals("array", memories.getValue("type").jsonPrimitive.content)
        assertTrue("violations" in output)
        assertTrue("warnings" in output)
        assertFalse(output.containsSchemaDescription())
    }

    @Test
    fun globalStatePolicySchemaEchoesInputPathsAndReportFields() {
        val tool = createHexanaMcpServer()
            .tools
            .getValue("audit_global_state_policy")
            .tool

        val output = tool.outputSchema?.properties?.jsonObject ?: error("Missing global-state output schema")
        assertTrue("module" in output)
        assertTrue("detail" in output)
        assertTrue("newPublicGlobals" in output)
        assertTrue("allowedPublicGlobals" in output)
        assertTrue("allowedPublicGlobalSummary" in output)
        assertTrue("unchangedGlobalSummary" in output)
        assertTrue("report" in output)
        assertTrue("recommendedReport" in output)
        assertTrue("targetedGlobalIndices" in output)
        assertTrue("violations" in output)
        assertFalse(output.containsSchemaDescription())
    }

    @Test
    fun localStackBudgetSchemaEchoesInputPathsAndReportFields() {
        val tool = createHexanaMcpServer()
            .tools
            .getValue("audit_local_stack_budget")
            .tool

        val input = tool.inputSchema.properties?.jsonObject ?: error("Missing local budget input schema")
        assertTrue("policyPath" in input)
        assertTrue("logPath" in input)
        assertTrue("checkedExports" in input)
        assertTrue("maxLocals" in input)
        assertEquals(false, input.getValue("countParams").jsonObject.getValue("default").jsonPrimitive.boolean)

        val output = tool.outputSchema?.properties?.jsonObject ?: error("Missing local budget output schema")
        assertTrue("module" in output)
        assertTrue("budget" in output)
        assertTrue("functions" in output)
        assertTrue("violations" in output)
        assertTrue("cleanExports" in output)
        assertTrue("evidence" in output)
        assertTrue("minimalFix" in output)
        assertTrue("recommendedReport" in output)
        assertFalse(output.containsSchemaDescription())
    }

    private fun queryArtifactsInputSchema(): JsonObject {
        return toolInputSchema("query_artifacts")
    }

    private fun toolInputSchema(toolName: String): JsonObject {
        return createHexanaMcpServer(toolProfile = HexanaMcpToolProfile.ADVANCED)
            .tools
            .getValue(toolName)
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?: error("Missing $toolName input schema properties")
    }

    private fun createServerWithDirectGeneratedRegistration(): Server {
        val handlers = HexanaToolHandlers()
        return Server(
            serverInfo = Implementation("hexana-test", "test"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        ) {
            registerHexanaTools(handlers)
        }
    }

    private fun schemaTypes(schema: JsonObject): List<String> {
        return schema.getValue("type").jsonArray.map { it.jsonPrimitive.content }
    }

    private fun JsonObject.containsSchemaDescription(): Boolean {
        if ("description" in this) return true
        return values.any { value ->
            when (value) {
                is JsonObject -> value.containsSchemaDescription()
                else -> {
                    val array = value as? JsonArray
                    array?.any { element ->
                        (element as? JsonObject)?.containsSchemaDescription() == true
                    } == true
                }
            }
        }
    }

    private fun assertBefore(names: List<String>, earlier: String, later: String) {
        assertTrue(names.indexOf(earlier) >= 0, "Missing `$earlier` in $names")
        assertTrue(names.indexOf(later) >= 0, "Missing `$later` in $names")
        assertTrue(
            names.indexOf(earlier) < names.indexOf(later),
            "`$earlier` should be registered before `$later`: $names"
        )
    }
}
