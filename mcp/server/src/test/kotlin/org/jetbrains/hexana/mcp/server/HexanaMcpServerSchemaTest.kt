package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    fun defaultRegistryExposesProductionHexanaTools() {
        assertEquals(
            listOf(
                "audit_global_state_policy",
                "check_component_compatibility",
                "check_memory_contract",
                "check_runtime_compatibility",
                "diff_required_proposals",
                "disassemble_function",
                "get_entrypoint_policy",
                "get_function_call_graph",
                "list_component_surface",
                "list_data_segments",
                "list_element_segments",
                "list_enabled_features",
                "list_exported_functions",
                "list_exports",
                "list_functions",
                "list_globals",
                "list_imports",
                "list_memory",
                "list_required_proposals",
                "plan_abi_binding_regeneration",
                "resolve_component_dependencies",
                "resolve_function_signature",
                "resolve_function_symbols",
                "resolve_source_locations",
                "resolve_wasm_frames",
                "search_exports",
                "search_functions_by_name",
                "summarize_module",
            ),
            createHexanaMcpServer().tools.keys.sorted(),
        )
    }

    @Test
    fun advancedRegistryExposesAllGeneratedHexanaTools() {
        assertEquals(
            listOf(
                "audit_global_state_policy",
                "check_component_compatibility",
                "check_memory_contract",
                "check_runtime_compatibility",
                "diff_required_proposals",
                "disassemble_function",
                "functions_for_indices",
                "get_entrypoint_policy",
                "get_function_call_graph",
                "get_globals_for_indices",
                "get_instructions_for_functions",
                "get_locals_for_functions",
                "get_memory_for_indices",
                "get_types_for_indices",
                "list_code_section_offsets",
                "list_component_surface",
                "list_custom_sections",
                "list_data",
                "list_data_segments",
                "list_element_segments",
                "list_enabled_features",
                "list_exported_functions",
                "list_exports",
                "list_functions",
                "list_globals",
                "list_imports",
                "list_memory",
                "list_reference_types",
                "list_required_proposals",
                "list_types",
                "list_value_types",
                "plan_abi_binding_regeneration",
                "query_artifacts",
                "resolve_component_dependencies",
                "resolve_function_signature",
                "resolve_function_symbols",
                "resolve_source_locations",
                "resolve_wasm_frames",
                "search_exports",
                "search_functions_by_name",
                "summarize_module",
            ),
            createHexanaMcpServer(toolProfile = HexanaMcpToolProfile.ADVANCED).tools.keys.sorted(),
        )
    }

    @Test
    fun productionRegistryPresentsFocusedAuditToolsBeforeGenericViews() {
        val names = createHexanaMcpServer().tools.keys.toList()

        assertBefore(names, "plan_abi_binding_regeneration", "list_exports")
        assertBefore(names, "check_memory_contract", "list_memory")
        assertBefore(names, "resolve_component_dependencies", "list_imports")
        assertBefore(names, "check_component_compatibility", "list_exports")
        assertBefore(names, "audit_global_state_policy", "list_globals")
        assertBefore(names, "audit_global_state_policy", "list_exports")
    }

    @Test
    fun getEntrypointPolicyDescriptorDescribesDomainBehavior() {
        val tool = createHexanaMcpServer()
            .tools
            .getValue("get_entrypoint_policy")
            .tool

        val description = tool.description ?: error("Missing get_entrypoint_policy description")
        assertTrue(description.contains("Return policy-ready direct host-call facts"))
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
    fun responseBoundsArePresentInGeneratedSchema() {
        val limit = queryArtifactsInputSchema().getValue("limit").jsonObject
        val maxBytes = queryArtifactsInputSchema().getValue("maxBytes").jsonObject

        assertEquals(1, limit.getValue("minimum").jsonPrimitive.int)
        assertEquals(100, limit.getValue("maximum").jsonPrimitive.int)
        assertEquals(2_000, maxBytes.getValue("minimum").jsonPrimitive.int)
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
        assertTrue("recommendedReport" in output)
        assertTrue("targetedGlobalIndices" in output)
        assertTrue("violations" in output)
        assertFalse(output.containsSchemaDescription())
    }

    private fun queryArtifactsInputSchema(): JsonObject {
        return createHexanaMcpServer(toolProfile = HexanaMcpToolProfile.ADVANCED)
            .tools
            .getValue("query_artifacts")
            .tool
            .inputSchema
            .properties
            ?.jsonObject
            ?: error("Missing query_artifacts input schema properties")
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
