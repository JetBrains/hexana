package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HexanaToolsTest {

    private val queryArtifacts = HexanaTools.single { it.name == "query_artifacts" }

    @Test
    fun `registry exposes granular tools and the generic artifact query tool`() {
        assertEquals(
            listOf(
                "summarize_module",
                "list_enabled_features",
                "get_entrypoint_policy",
                "list_required_proposals",
                "diff_required_proposals",
                "check_runtime_compatibility",
                "plan_abi_binding_regeneration",
                "repair_host_adapter_config",
                "check_memory_contract",
                "audit_global_state_policy",
                "list_custom_sections",
                "list_imports",
                "list_exports",
                "search_exports",
                "list_exported_functions",
                "list_functions",
                "list_code_section_offsets",
                "functions_for_indices",
                "resolve_function_signature",
                "disassemble_function",
                "get_function_call_graph",
                "search_functions_by_name",
                "get_locals_for_functions",
                "get_instructions_for_functions",
                "list_memory",
                "get_memory_for_indices",
                "list_globals",
                "get_globals_for_indices",
                "list_types",
                "list_value_types",
                "list_reference_types",
                "get_types_for_indices",
                "list_element_segments",
                "list_data",
                "list_data_segments",
                "query_artifacts",
            ),
            HexanaTools.map { it.name },
        )
    }

    @Test
    fun `tool names use snake_case`() {
        val snakeCase = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
        HexanaTools.forEach { spec ->
            assertTrue(snakeCase.matches(spec.name), "tool name `${spec.name}` is not snake_case")
        }
    }

    @Test
    fun `tool builder rejects names outside strict snake_case`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("queryArtifacts") {
                    summary = "s"
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.WasmOnly
                    output = WasmInputClass
                    handlerFqn = queryArtifacts.handlerFqn
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("query__artifacts") {
                    summary = "s"
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.WasmOnly
                    output = WasmInputClass
                    handlerFqn = queryArtifacts.handlerFqn
                }
            }
        }
    }

    @Test
    fun `every tool declares summary, input, output, handler`() {
        for (spec in HexanaTools) {
            assertTrue(spec.summary.isNotBlank(), "summary blank for ${spec.name}")
            assertTrue(spec.handlerFqn.isNotBlank(), "handlerFqn blank for ${spec.name}")
            assertTrue(spec.input.fields.isNotEmpty(), "empty input class for ${spec.name}")
            assertTrue(
                spec.output.kotlinFqn != null,
                "output class ${spec.output.name} missing kotlinPackage for ${spec.name}",
            )
            assertTrue(spec.output.fields.isNotEmpty(), "empty output class for ${spec.name}")
        }
    }

    @Test
    fun `tool output Kotlin FQNs point at matching result classes`() {
        HexanaTools.forEach { spec ->
            val outputFqn = requireNotNull(spec.output.kotlinFqn)
            assertEquals(spec.output.name, outputFqn.substringAfterLast('.'), "bad output FQN for ${spec.name}")
            assertTrue(
                outputFqn.startsWith("org.jetbrains.hexana.mcp.model."),
                "output FQN for ${spec.name} should point at mcp/core model package",
            )
        }
    }

    @Test
    fun `query_artifacts uses the artifact query invocation pattern`() {
        assertEquals(ToolInputPattern.ArtifactQuery, queryArtifacts.inputPattern)
    }

    @Test
    fun `plan_abi_binding_regeneration uses the binding file invocation pattern`() {
        val planner = HexanaTools.single { it.name == "plan_abi_binding_regeneration" }

        assertEquals(ToolInputPattern.WasmWithBinding, planner.inputPattern)
        assertTrue(planner.guidance.orEmpty().contains("current WebAssembly"))
        assertFalse(planner.guidance.orEmpty().contains("task"))
    }

    @Test
    fun `get_entrypoint_policy uses the path label invocation pattern`() {
        assertEquals(
            ToolInputPattern.WasmPathLabel,
            HexanaTools.single { it.name == "get_entrypoint_policy" }.inputPattern
        )
    }

    @Test
    fun `get_entrypoint_policy describes entrypoint policy analysis`() {
        val get = HexanaTools.single { it.name == "get_entrypoint_policy" }

        assertEquals(ToolInputPattern.WasmPathLabel, get.inputPattern)
        assertTrue(get.summary.contains("policy-ready"))
        val guidance = assertNotNull(get.guidance)
        assertTrue(guidance.contains("repairing or validating entrypoint host-call policy JSON"))
        assertTrue(guidance.contains("direct_host_entrypoints"))
        assertTrue(!guidance.contains("entrypoint_policy_repair"))
        assertTrue(!guidance.contains("task"))
    }

    @Test
    fun `check_runtime_compatibility uses the runtime compatibility invocation pattern`() {
        assertEquals(
            ToolInputPattern.RuntimeCompatibility,
            HexanaTools.single { it.name == "check_runtime_compatibility" }.inputPattern
        )
    }

    @Test
    fun `diff_required_proposals uses the wasm with baseline invocation pattern`() {
        assertEquals(
            ToolInputPattern.WasmWithBaseline,
            HexanaTools.single { it.name == "diff_required_proposals" }.inputPattern
        )
    }

    @Test
    fun `policy audit tools use the baseline policy invocation pattern`() {
        assertEquals(
            ToolInputPattern.PolicyWithBaselineAndLog,
            HexanaTools.single { it.name == "check_memory_contract" }.inputPattern
        )
        assertEquals(
            ToolInputPattern.PolicyWithBaselineAndLog,
            HexanaTools.single { it.name == "audit_global_state_policy" }.inputPattern
        )
    }

    @Test
    fun `wasmInputClass carries a oneOf constraint`() {
        assertEquals(listOf("path", "bytesBase64", "bytes"), WasmInputClass.oneOf)
    }

    @Test
    fun `standalone schema constraints are declared in the DSL`() {
        val bytesBase64 = WasmInputClass.fields.single { it.name == "bytesBase64" }
        assertEquals(SchemaIntValue.MaxInlineWasmBase64Length, bytesBase64.maxLength)

        val bytes = WasmInputClass.fields.single { it.name == "bytes" }
        assertEquals(SchemaIntValue.MaxInlineWasmBytes, bytes.maxItems)
        assertEquals(0, bytes.itemMinimum)
        assertEquals(255, bytes.itemMaximum)
    }

    @Test
    fun `query_artifacts input carries defaults and bounds`() {
        val fields = queryArtifacts.input.fields.associateBy { it.name }

        assertEquals("overview", fields.getValue("operation").default)
        assertEquals(25, fields.getValue("limit").default)
        assertEquals("summary", fields.getValue("detail").default)
        assertEquals(24_000, fields.getValue("maxBytes").default)
        assertEquals(1, fields.getValue("limit").minimum)
        assertEquals(100, fields.getValue("limit").maximum)
        assertEquals(2_000, fields.getValue("maxBytes").minimum)
        assertEquals(60_000, fields.getValue("maxBytes").maximum)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("entityTypes").maxItems)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("query").maxItems)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("relations").maxItems)
    }

    @Test
    fun `artifact query pattern validates required input fields`() {
        val fields = queryArtifacts.input.fields.associateBy { it.name }
        listOf(
            "baselinePath",
            "operation",
            "entityTypes",
            "query",
            "startName",
            "startFuncidx",
            "relations",
            "limit",
            "detail",
            "maxBytes"
        ).forEach { fieldName ->
            assertNotNull(fields[fieldName], "Missing query_artifacts input field `$fieldName`")
        }
    }

    @Test
    fun `schema constraints reject incompatible field types`() {
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                string("name").minimum(0)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                int("count").maxItems(1)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                list("names", of = string()).itemsMinimum(0)
            }
        }
    }

    @Test
    fun `field names must be lower camel case`() {
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                int("bad_name")
            }
        }
        assertFailsWith<IllegalArgumentException> {
            class_("Broken") {
                int("1bad")
            }
        }
    }
}
