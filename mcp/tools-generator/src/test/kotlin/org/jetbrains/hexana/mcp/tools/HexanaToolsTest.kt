package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val CURRENT_PRODUCTION_MCP_TOOL_NAMES = setOf(
    "audit_data_payload_policy",
    "audit_global_state_policy",
    "audit_local_stack_budget",
    "check_memory_contract",
    "check_component_compatibility",
    "check_runtime_compatibility",
    "diff_required_proposals",
    "disassemble_function",
    "get_entrypoint_policy",
    "get_function_call_graph",
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
    "resolve_function_symbols",
    "resolve_function_signature",
    "resolve_source_locations",
    "resolve_wasm_frames",
    "search_exports",
    "search_functions_by_name",
    "summarize_binary",
    "summarize_module",
)

class HexanaToolsTest {

    private val queryArtifacts = HexanaTools.single { it.name == "query_artifacts" }
    private val binarySummaryResult = class_(
        "BinarySummaryResult",
        kotlinPackage = "org.jetbrains.hexana.mcp.model.binaries",
    ) {
        string("format").describe("Detected binary format.")
    }
    private val queryBinaryInput = BinaryInputClass.with("QueryBinaryInput") {
        string("operation", default = "overview")
            .describe("Binary query operation.")
        nullable("entityTypes", of = list(of = string()))
            .describe("Binary record kinds used by the selected operation.")
            .maxItems(20)
        nullable("query", of = list(of = string()))
            .describe("Search terms used by the selected operation.")
        int("offset", default = 0)
            .describe("Zero-based result offset.")
            .minimum(0)
        int("limit", default = 25)
            .describe("Maximum number of records to return.")
            .minimum(1)
            .maximum(100)
        string("detail", default = "summary")
            .describe("Detail level for returned records.")
        int("maxBytes", default = 24_000)
            .describe("Maximum serialized record payload bytes.")
            .minimum(1)
            .maximum(60_000)
    }

    @Test
    fun `registry exposes granular tools and the generic artifact query tool`() {
        assertEquals(
            listOf(
                "summarize_binary",
                "list_binary_functions",
                "search_binary_functions",
                "query_binary",
                "summarize_module",
                "list_enabled_features",
                "get_entrypoint_policy",
                "list_required_proposals",
                "diff_required_proposals",
                "check_runtime_compatibility",
                "check_component_compatibility",
                "resolve_component_dependencies",
                "list_component_surface",
                "plan_abi_binding_regeneration",
                "check_memory_contract",
                "audit_global_state_policy",
                "audit_local_stack_budget",
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
                "resolve_wasm_frames",
                "resolve_function_symbols",
                "resolve_source_locations",
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
                "audit_data_payload_policy",
                "query_artifacts",
            ),
            HexanaTools.map { it.name },
        )
    }

    @Test
    fun `binary tools stay grouped at the start of the catalog`() {
        assertEquals(
            listOf(
                "summarize_binary",
                "list_binary_functions",
                "search_binary_functions",
                "query_binary",
            ),
            HexanaTools.take(4).map { it.name },
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
    fun `MCP production metadata matches current production profile`() {
        assertEquals(
            CURRENT_PRODUCTION_MCP_TOOL_NAMES,
            HexanaTools
                .filter { McpToolAudience.Production in it.surfaces.mcp.audiences }
                .map { it.name }
                .toSet(),
        )
    }

    @Test
    fun `tools declare explicit MCP and CLI surface metadata`() {
        val kebabCase = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
        HexanaTools.forEach { spec ->
            assertTrue(spec.surfaces.mcp.audiences.isNotEmpty(), "missing MCP audience for ${spec.name}")
            val cli = spec.surfaces.cli
            if (cli.visible) {
                val commandName = requireNotNull(cli.commandName) { "missing CLI command for ${spec.name}" }
                assertTrue(kebabCase.matches(commandName), "CLI command `$commandName` is not kebab-case")
                assertTrue(cli.groups.isNotEmpty(), "missing CLI group for ${spec.name}")
                assertEquals(ToolOutputPolicy.ToolDefined, cli.outputPolicy, "bad output policy for ${spec.name}")
            } else {
                assertEquals(null, cli.commandName, "hidden CLI surface has command for ${spec.name}")
                assertTrue(cli.groups.isEmpty(), "hidden CLI surface has groups for ${spec.name}")
                assertEquals(null, cli.outputPolicy, "hidden CLI surface has output policy for ${spec.name}")
            }
        }
    }

    @Test
    fun `production and advanced MCP audiences do not imply CLI visibility`() {
        val production = HexanaTools.single { it.name == "summarize_binary" }
        assertEquals(setOf(McpToolAudience.Production), production.surfaces.mcp.audiences)
        assertTrue(production.surfaces.cli.visible)
        assertEquals("summarize-binary", production.surfaces.cli.commandName)

        val advanced = HexanaTools.single { it.name == "query_artifacts" }
        assertEquals(setOf(McpToolAudience.Advanced), advanced.surfaces.mcp.audiences)
        assertFalse(advanced.surfaces.cli.visible)
        assertEquals(null, advanced.surfaces.cli.commandName)
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
    fun `tool builder requires explicit surface metadata`() {
        val error = assertFailsWith<IllegalArgumentException> {
            tools {
                tool("missing_surfaces") {
                    summary = "s"
                    input = BinaryInputClass
                    inputPattern = ToolInputPattern.BinaryOnly
                    output = binarySummaryResult
                    handlerFqn = queryArtifacts.handlerFqn
                }
            }
        }

        assertTrue(error.message.orEmpty().contains("missing `surfaces = `"))
    }

    @Test
    fun `MCP surface requires at least one audience declaration`() {
        val error = assertFailsWith<IllegalArgumentException> {
            McpSurfaceSpec(emptySet())
        }

        assertTrue(error.message.orEmpty().contains("at least one audience"))
    }

    @Test
    fun `CLI visible surface requires kebab-case command metadata`() {
        assertFailsWith<IllegalArgumentException> {
            productionCliSurface("query_artifacts", ToolGroup.ArtifactQuery, ToolOutputPolicy.ToolDefined)
        }

        val error = assertFailsWith<IllegalArgumentException> {
            CliSurfaceSpec(
                visible = true,
                commandName = null,
                groups = setOf(ToolGroup.ArtifactQuery),
                outputPolicy = ToolOutputPolicy.ToolDefined,
            )
        }

        assertTrue(error.message.orEmpty().contains("must declare a command name"))
    }

    @Test
    fun `tool collection rejects duplicate CLI command names`() {
        val error = assertFailsWith<IllegalArgumentException> {
            tools {
                tool("first_tool") {
                    summary = "s"
                    input = BinaryInputClass
                    inputPattern = ToolInputPattern.BinaryOnly
                    output = binarySummaryResult
                    surfaces = productionCliSurface("same-command", ToolGroup.Binary, ToolOutputPolicy.ToolDefined)
                    handlerFqn = queryArtifacts.handlerFqn
                }
                tool("second_tool") {
                    summary = "s"
                    input = BinaryInputClass
                    inputPattern = ToolInputPattern.BinaryOnly
                    output = binarySummaryResult
                    surfaces = productionCliSurface("same-command", ToolGroup.Binary, ToolOutputPolicy.ToolDefined)
                    handlerFqn = queryArtifacts.handlerFqn
                }
            }
        }

        assertTrue(error.message.orEmpty().contains("Duplicate CLI command names"))
        assertTrue(error.message.orEmpty().contains("same-command"))
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
    fun `audit_local_stack_budget uses the local stack budget invocation pattern`() {
        val audit = HexanaTools.single { it.name == "audit_local_stack_budget" }

        assertEquals(ToolInputPattern.LocalStackBudgetAudit, audit.inputPattern)
        assertTrue(audit.guidance.orEmpty().contains("without full disassembly"))
        assertTrue(audit.guidance.orEmpty().contains("recommendedReport"))
    }

    @Test
    fun `get_entrypoint_policy describes entrypoint policy analysis`() {
        val get = HexanaTools.single { it.name == "get_entrypoint_policy" }

        assertEquals(ToolInputPattern.WasmPathLabel, get.inputPattern)
        assertTrue(get.summary.contains("policy-ready"))
        val guidance = assertNotNull(get.guidance)
        assertTrue(guidance.contains("repairing or validating entrypoint host-call policy JSON"))
        assertTrue(guidance.contains("direct_host_entrypoints"))
        assertTrue(guidance.contains("copy unchanged into both the policy file"))
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
    fun `component tools use component invocation patterns`() {
        assertEquals(
            ToolInputPattern.ComponentSurface,
            HexanaTools.single { it.name == "list_component_surface" }.inputPattern
        )
        assertEquals(
            ToolInputPattern.ComponentDependencies,
            HexanaTools.single { it.name == "resolve_component_dependencies" }.inputPattern
        )
        assertEquals(
            ToolInputPattern.ComponentCompatibility,
            HexanaTools.single { it.name == "check_component_compatibility" }.inputPattern
        )
    }

    @Test
    fun `policy audit tools use the baseline policy invocation pattern`() {
        assertEquals(
            ToolInputPattern.PolicyWithBaselineAndLog,
            HexanaTools.single { it.name == "check_memory_contract" }.inputPattern
        )
        assertEquals(
            ToolInputPattern.GlobalStatePolicyAudit,
            HexanaTools.single { it.name == "audit_global_state_policy" }.inputPattern
        )
    }

    @Test
    fun `wasmInputClass carries a oneOf constraint`() {
        assertEquals(listOf("path", "bytesBase64", "bytes"), WasmInputClass.oneOf)
    }

    @Test
    fun `binaryInputClass carries a oneOf constraint`() {
        assertEquals(listOf("path", "bytesBase64", "bytes"), BinaryInputClass.oneOf)
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
    fun `binary input schema constraints are declared in the DSL`() {
        val bytesBase64 = BinaryInputClass.fields.single { it.name == "bytesBase64" }
        assertEquals(SchemaIntValue.MaxInlineBinaryBase64Length, bytesBase64.maxLength)

        val bytes = BinaryInputClass.fields.single { it.name == "bytes" }
        assertEquals(SchemaIntValue.MaxInlineBinaryBytes, bytes.maxItems)
        assertEquals(0, bytes.itemMinimum)
        assertEquals(255, bytes.itemMaximum)
    }

    @Test
    fun `binary invocation patterns can be declared from binary input classes`() {
        val specs = tools {
            tool("summarize_binary") {
                summary = "Summarize a binary."
                input = BinaryInputClass
                inputPattern = ToolInputPattern.BinaryOnly
                output = binarySummaryResult
                surfaces = productionCliSurface("summarize-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined)
                handlerFqn = "org.jetbrains.hexana.mcp.query.summarizeBinary"
            }
            tool("query_binary") {
                summary = "Query a binary."
                input = queryBinaryInput
                inputPattern = ToolInputPattern.BoundedBinaryQuery
                output = binarySummaryResult
                surfaces = productionCliSurface("query-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined)
                handlerFqn = "org.jetbrains.hexana.mcp.query.queryBinary"
            }
        }

        assertEquals(
            listOf(ToolInputPattern.BinaryOnly, ToolInputPattern.BoundedBinaryQuery),
            specs.map { it.inputPattern },
        )
    }

    @Test
    fun `binary invocation patterns reject wasm input classes`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("summarize_binary") {
                    summary = "Summarize a binary."
                    input = WasmInputClass
                    inputPattern = ToolInputPattern.BinaryOnly
                    output = binarySummaryResult
                    surfaces = productionCliSurface("summarize-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined)
                    handlerFqn = "org.jetbrains.hexana.mcp.query.summarizeBinary"
                }
            }
        }
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
        assertEquals(1, fields.getValue("maxBytes").minimum)
        assertEquals(60_000, fields.getValue("maxBytes").maximum)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("entityTypes").maxItems)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("query").maxItems)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("relations").maxItems)
    }

    @Test
    fun `query_binary input carries defaults and bounds`() {
        val fields = HexanaTools.single { it.name == "query_binary" }.input.fields.associateBy { it.name }

        assertEquals("overview", fields.getValue("operation").default)
        assertEquals(0, fields.getValue("offset").default)
        assertEquals(25, fields.getValue("limit").default)
        assertEquals("summary", fields.getValue("detail").default)
        assertEquals(24_000, fields.getValue("maxBytes").default)
        assertEquals(0, fields.getValue("offset").minimum)
        assertEquals(1, fields.getValue("limit").minimum)
        assertEquals(100, fields.getValue("limit").maximum)
        assertEquals(1, fields.getValue("maxBytes").minimum)
        assertEquals(60_000, fields.getValue("maxBytes").maximum)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("entityTypes").maxItems)
        assertEquals(SchemaIntValue.Literal(20), fields.getValue("query").maxItems)
    }

    @Test
    fun `component inputs carry bounded list schemas`() {
        val dependencyFields = HexanaTools.single { it.name == "resolve_component_dependencies" }
            .input.fields.associateBy { it.name }
        assertEquals(SchemaIntValue.Literal(8), dependencyFields.getValue("scanRoots").maxItems)

        val compatibilityFields = HexanaTools.single { it.name == "check_component_compatibility" }
            .input.fields.associateBy { it.name }
        assertEquals(SchemaIntValue.Literal(64), compatibilityFields.getValue("dependencyPaths").maxItems)
    }

    @Test
    fun `component dependency output carries report-ready serial names`() {
        val dependencyOutput = HexanaTools.single { it.name == "resolve_component_dependencies" }.output
        val topLevelFields = dependencyOutput.fields.associateBy { it.name }
        assertEquals("component_path", topLevelFields.getValue("path").serialName)
        assertEquals("required_non_runtime_imports", topLevelFields.getValue("requiredNonRuntimeImports").serialName)
        assertEquals("ignored_runtime_imports", topLevelFields.getValue("ignoredRuntimeImports").serialName)
        assertEquals("resolved_dependency_paths", topLevelFields.getValue("resolvedDependencyPaths").serialName)
        assertEquals("rejected_candidates", topLevelFields.getValue("rejectedCandidates").serialName)
        assertEquals("minimal_run_build_fix", topLevelFields.getValue("minimalRunBuildFix").serialName)
    }

    @Test
    fun `component compatibility output carries report-ready serial names`() {
        val compatibilityOutput = HexanaTools.single { it.name == "check_component_compatibility" }.output
        val topLevelFields = compatibilityOutput.fields.associateBy { it.name }
        assertEquals("component_path", topLevelFields.getValue("componentPath").serialName)
        assertEquals("previous_component_path", topLevelFields.getValue("previousComponentPath").serialName)
        assertEquals("dependency_lock_path", topLevelFields.getValue("dependencyLockPathForReport").serialName)
        assertEquals("failing_import", topLevelFields.getValue("failingImportForReport").serialName)
        assertEquals("candidate_providers_inspected", topLevelFields.getValue("candidateProvidersInspected").serialName)
        assertEquals("selected_incompatibility_reason", topLevelFields.getValue("selectedIncompatibilityReasonForReport").serialName)
        assertEquals("provider_paths_involved", topLevelFields.getValue("providerPathsInvolvedForReport").serialName)
        assertEquals("minimal_dependency_lock_fix", topLevelFields.getValue("minimalDependencyLockFixForReport").serialName)
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
