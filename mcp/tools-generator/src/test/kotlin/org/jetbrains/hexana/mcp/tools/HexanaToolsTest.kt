package org.jetbrains.hexana.mcp.tools

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val CURRENT_DEFAULT_MCP_TOOL_NAMES = listOf(
    "summarize_binary",
    "summarize_module",
    "list_enabled_features",
    "get_entrypoint_policy",
    "list_required_proposals",
    "diff_required_proposals",
    "check_runtime_compatibility",
    "check_component_compatibility",
    "plan_abi_binding_regeneration",
    "check_memory_contract",
    "audit_global_state_policy",
    "audit_local_stack_budget",
    "list_imports",
    "list_exports",
    "search_exports",
    "list_exported_functions",
    "list_functions",
    "resolve_function_signature",
    "disassemble_function",
    "get_function_call_graph",
    "search_functions_by_name",
    "resolve_wasm_frames",
    "resolve_function_symbols",
    "resolve_source_locations",
    "list_memory",
    "list_globals",
    "list_element_segments",
    "list_data_segments",
    "audit_data_payload_policy",
)

private val CURRENT_ADVANCED_MCP_TOOL_NAMES = listOf(
    "list_binary_functions",
    "search_binary_functions",
    "query_binary",
    "resolve_component_dependencies",
    "list_component_surface",
    "list_custom_sections",
    "list_code_section_offsets",
    "functions_for_indices",
    "get_locals_for_functions",
    "get_instructions_for_functions",
    "get_memory_for_indices",
    "get_globals_for_indices",
    "list_types",
    "list_value_types",
    "list_reference_types",
    "get_types_for_indices",
    "list_data",
    "query_artifacts",
)

class HexanaToolsTest {

    @Test
    fun `catalog rejects unknown and duplicate typed IDs before resolution`() {
        val known = HexanaTools.first().id

        val duplicate = assertFailsWith<IllegalArgumentException> {
            HexanaToolCatalog.resolve(listOf(known, known), "test selection")
        }
        assertTrue(duplicate.message.orEmpty().contains("duplicate tool IDs"))

        val unknown = assertFailsWith<IllegalArgumentException> {
            HexanaToolCatalog.resolve(listOf(ToolId("missing_tool")), "test selection")
        }
        assertTrue(unknown.message.orEmpty().contains("unknown tool IDs"))
    }

    @Test
    fun `catalog rejects duplicate contract IDs`() {
        val duplicate = HexanaTools.first().copy()
        val error = assertFailsWith<IllegalArgumentException> { ToolCatalog(listOf(HexanaTools.first(), duplicate)) }

        assertTrue(error.message.orEmpty().contains("Duplicate tool contract IDs"))
    }

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
                "run_binary_script",
                "wasm_to_wat",
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
    fun `surface authorities preserve approved inventories and bindings`() {
        assertEquals(CURRENT_DEFAULT_MCP_TOOL_NAMES, HexanaIdeaTools.map { it.name })
        assertEquals(29, HexanaIdeaTools.size)
        assertEquals(48, HexanaStandaloneTools.size)
        assertEquals(CURRENT_DEFAULT_MCP_TOOL_NAMES, HexanaStandaloneDefaultToolIds.map { it.value })
        assertEquals(CURRENT_ADVANCED_MCP_TOOL_NAMES, HexanaStandaloneAdvancedToolIds.map { it.value })
        assertEquals(listOf("run_binary_script"), HexanaStandaloneScriptingToolIds.map { it.value })
        assertEquals(29, HexanaStandaloneDefaultToolIds.size)
        assertEquals(18, HexanaStandaloneAdvancedToolIds.size)
        assertTrue(HexanaStandaloneDefaultToolIds.intersect(HexanaStandaloneAdvancedToolIds.toSet()).isEmpty())
        assertTrue(HexanaStandaloneDefaultToolIds.intersect(HexanaStandaloneScriptingToolIds.toSet()).isEmpty())
        assertTrue(HexanaStandaloneAdvancedToolIds.intersect(HexanaStandaloneScriptingToolIds.toSet()).isEmpty())
        assertEquals(31, HexanaCliTools.size)
        assertTrue(HexanaStandaloneTools.all {
            it.standaloneHandler.methodName.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*$"))
        })
    }

    @Test
    fun `MCPB inventory is exactly closed over standalone default membership`() {
        assertEquals(HexanaStandaloneDefaultToolIds, HexanaMcpbTools.map { it.id })
        assertEquals(CURRENT_DEFAULT_MCP_TOOL_NAMES, HexanaMcpbTools.map { it.name })
        assertEquals(29, HexanaMcpbTools.size)
    }

    @Test
    fun `standalone authority rejects overlapping profile memberships`() {
        val registered = HexanaStandaloneTools.first()

        val error = assertFailsWith<IllegalArgumentException> {
            StandaloneToolAuthority(
                registrations = listOf(registered),
                defaultToolIds = listOf(registered.id),
                advancedToolIds = listOf(registered.id),
                scriptingToolIds = emptyList(),
            )
        }

        assertTrue(error.message.orEmpty().contains("default and advanced profiles must be disjoint"))
        assertTrue(error.message.orEmpty().contains(registered.name))
    }

    @Test
    fun `CLI release products and executable models preserve approved inventories`() {
        val products = CliReleaseProduct.entries.associateWith { product ->
            HexanaCliTools.filter { product in it.binding.releaseProducts }.map { it.name }
        }
        assertEquals(listOf("summarize_binary", "wasm_to_wat", "summarize_module"), products.getValue(CliReleaseProduct.HexanaCli))
        assertEquals(listOf("run_binary_script"), products.getValue(CliReleaseProduct.HexanaCliScript))
        val executable = HexanaCliTools.filter { it.binding.executionStatus == CliExecutionStatus.Executable }
        assertEquals(4, executable.size)
        assertTrue(executable.all { it.inputModel != null })
        assertEquals(OutputConversionCapability.Pretty, HexanaCliTools.single { it.name == "summarize_module" }.binding.defaultOutput)
    }

    @Test
    fun `ClassSpec with inherits conversion capability metadata`() {
        val base = class_("Base") {
            inputCapabilities(InputConversionCapability.JsonObject, InputConversionCapability.WasmPath)
            outputCapabilities(OutputConversionCapability.SemanticJson, OutputConversionCapability.RawText)
            string("path").describe("Input path.")
        }

        val derived = base.with("Derived") {
            int("limit", default = 10).describe("Maximum items.")
        }

        assertEquals(base.inputCapabilities, derived.inputCapabilities)
        assertEquals(base.outputCapabilities, derived.outputCapabilities)

        val overridden = base.with("Overridden") {
            inputCapabilities(InputConversionCapability.Stdin)
            outputCapabilities(OutputConversionCapability.RawBytes)
        }

        assertEquals(setOf(InputConversionCapability.Stdin), overridden.inputCapabilities)
        assertEquals(setOf(OutputConversionCapability.RawBytes), overridden.outputCapabilities)
    }

    @Test
    fun `surface binding selectors reject duplicate missing and unknown bindings`() {
        data class Binding(val id: ToolId)
        val selected = HexanaTools.take(2).map { it.id }
        val bindings = selected.map(::Binding)
        assertFailsWith<IllegalArgumentException> {
            HexanaToolCatalog.resolveBindings(selected, bindings + bindings.first(), "test", Binding::id) { contract, _ -> contract }
        }
        assertFailsWith<IllegalArgumentException> {
            HexanaToolCatalog.resolveBindings(selected, bindings.take(1), "test", Binding::id) { contract, _ -> contract }
        }
        assertFailsWith<IllegalArgumentException> {
            HexanaToolCatalog.resolveBindings(selected, bindings + Binding(ToolId("unknown_tool")), "test", Binding::id) { contract, _ -> contract }
        }
    }

    @Test
    fun `CLI output selections are validated against semantic capabilities`() {
        val contract = HexanaTools.single { it.name == "summarize_binary" }
        val unsupported = cliToolBinding(
            "summarize_binary", "summarize-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined,
            outputOverride = OutputConversionCapability.RawBytes,
        )
        val error = assertFailsWith<IllegalArgumentException> { validateCliBinding(contract, unsupported) }
        assertTrue(error.message.orEmpty().contains("CLI output RawBytes is not declared"))
    }

    @Test
    fun `tool builder rejects names outside strict snake_case`() {
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("queryArtifacts") {
                    summary = "s"
                    input = WasmInputClass
                    output = WasmInputClass
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            tools {
                tool("query__artifacts") {
                    summary = "s"
                    input = WasmInputClass
                    output = WasmInputClass
                }
            }
        }
    }

    @Test
    fun `standalone handler and CLI command names validate independently`() {
        assertFailsWith<IllegalArgumentException> { StandaloneHandlerSpec("org.jetbrains.hexana.BadHandler") }
        assertFailsWith<IllegalArgumentException> {
            cliToolBinding("query_artifacts", "query_artifacts", ToolGroup.ArtifactQuery, ToolOutputPolicy.ToolDefined)
        }
        val commands = HexanaCliTools.map { it.binding.commandName }
        assertEquals(commands.toSet().size, commands.size)
        assertFalse(HexanaCliTools.any { it.name == "query_artifacts" })
        assertTrue(HexanaCliTools.any { it.name == "wasm_to_wat" })
    }

    @Test
    fun `every semantic contract declares summary input and output`() {
        for (spec in HexanaTools) {
            assertTrue(spec.summary.isNotBlank(), "summary blank for ${spec.name}")
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
        assertEquals(
            ToolInputPattern.ArtifactQuery,
            HexanaIdeaToolBindings.single { it.id.value == "query_artifacts" }.inputPattern,
        )
    }

    @Test
    fun `plan_abi_binding_regeneration uses the binding file invocation pattern`() {
        val planner = HexanaTools.single { it.name == "plan_abi_binding_regeneration" }

        assertEquals(ToolInputPattern.WasmWithBinding, HexanaIdeaTools.single { it.name == "plan_abi_binding_regeneration" }.inputPattern)
        assertTrue(planner.guidance.orEmpty().contains("current WebAssembly"))
        assertFalse(planner.guidance.orEmpty().contains("task"))
    }

    @Test
    fun `get_entrypoint_policy uses the path label invocation pattern`() {
        assertEquals(
            ToolInputPattern.WasmPathLabel,
            HexanaIdeaTools.single { it.name == "get_entrypoint_policy" }.inputPattern
        )
    }

    @Test
    fun `audit_local_stack_budget uses the local stack budget invocation pattern`() {
        val audit = HexanaTools.single { it.name == "audit_local_stack_budget" }

        assertEquals(ToolInputPattern.LocalStackBudgetAudit, HexanaIdeaTools.single { it.name == "audit_local_stack_budget" }.inputPattern)
        assertTrue(audit.guidance.orEmpty().contains("without full disassembly"))
        assertTrue(audit.guidance.orEmpty().contains("recommendedReport"))
    }

    @Test
    fun `get_entrypoint_policy describes entrypoint policy analysis`() {
        val get = HexanaTools.single { it.name == "get_entrypoint_policy" }

        assertEquals(ToolInputPattern.WasmPathLabel, HexanaIdeaTools.single { it.name == "get_entrypoint_policy" }.inputPattern)
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
            HexanaIdeaTools.single { it.name == "check_runtime_compatibility" }.inputPattern
        )
    }

    @Test
    fun `diff_required_proposals uses the wasm with baseline invocation pattern`() {
        assertEquals(
            ToolInputPattern.WasmWithBaseline,
            HexanaIdeaTools.single { it.name == "diff_required_proposals" }.inputPattern
        )
    }

    @Test
    fun `component tools use component invocation patterns`() {
        assertEquals(
            ToolInputPattern.ComponentSurface,
            HexanaIdeaToolBindings.single { it.id.value == "list_component_surface" }.inputPattern
        )
        assertEquals(
            ToolInputPattern.ComponentDependencies,
            HexanaIdeaToolBindings.single { it.id.value == "resolve_component_dependencies" }.inputPattern
        )
        assertEquals(
            ToolInputPattern.ComponentCompatibility,
            HexanaIdeaTools.single { it.name == "check_component_compatibility" }.inputPattern
        )
    }

    @Test
    fun `policy audit tools use the baseline policy invocation pattern`() {
        assertEquals(
            ToolInputPattern.PolicyWithBaselineAndLog,
            HexanaIdeaTools.single { it.name == "check_memory_contract" }.inputPattern
        )
        assertEquals(
            ToolInputPattern.GlobalStatePolicyAudit,
            HexanaIdeaTools.single { it.name == "audit_global_state_policy" }.inputPattern
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
    fun `wasm and binary input classes declare conversion capabilities`() {
        assertEquals(
            setOf(
                InputConversionCapability.JsonObject,
                InputConversionCapability.WasmPath,
                InputConversionCapability.WasmFromString,
                InputConversionCapability.Stdin,
            ),
            WasmInputClass.inputCapabilities,
        )
        assertEquals(
            setOf(
                InputConversionCapability.JsonObject,
                InputConversionCapability.BinaryPath,
                InputConversionCapability.Stdin,
            ),
            BinaryInputClass.inputCapabilities,
        )
        val sampleIndicesInput = indicesInput("SampleIndicesInput", "Indices.")
        assertEquals(WasmInputClass.inputCapabilities, sampleIndicesInput.inputCapabilities)
        assertEquals(
            SchemaIntValue.Literal(10_000),
            sampleIndicesInput.fields.single { it.name == "indices" }.maxItems,
        )
        assertEquals(
            BinaryInputClass.inputCapabilities,
            BinaryInputClass.with("SampleBinaryInput") {
                int("limit", default = 10).describe("Maximum items.")
            }.inputCapabilities,
        )
    }

    @Test
    fun `existing catalog outputs declare minimal semantic JSON and MCP capabilities`() {
        HexanaTools.forEach { spec ->
            val expectedCapabilities = when (spec.name) {
                "summarize_module" -> setOf(
                    OutputConversionCapability.SemanticJson,
                    OutputConversionCapability.McpWrapper,
                    OutputConversionCapability.Pretty,
                )
                "wasm_to_wat" -> setOf(
                    OutputConversionCapability.SemanticJson,
                    OutputConversionCapability.RawText,
                )
                "run_binary_script" -> setOf(
                    OutputConversionCapability.SemanticJson,
                    OutputConversionCapability.McpWrapper,
                    OutputConversionCapability.RawText,
                )
                else -> setOf(OutputConversionCapability.SemanticJson, OutputConversionCapability.McpWrapper)
            }
            assertEquals(
                expectedCapabilities,
                spec.output.outputCapabilities,
                "bad output capabilities for ${spec.name}",
            )
        }
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
        validateInputPattern("summarize_binary", BinaryInputClass, ToolInputPattern.BinaryOnly)
        validateInputPattern("query_binary", queryBinaryInput, ToolInputPattern.BoundedBinaryQuery)
        assertEquals(ToolInputPattern.BinaryOnly, HexanaIdeaTools.single { it.name == "summarize_binary" }.inputPattern)
        assertEquals(
            ToolInputPattern.BoundedBinaryQuery,
            HexanaIdeaToolBindings.single { it.id.value == "query_binary" }.inputPattern,
        )
    }

    @Test
    fun `binary invocation patterns reject wasm input classes`() {
        assertFailsWith<IllegalArgumentException> {
            validateInputPattern("summarize_binary", WasmInputClass, ToolInputPattern.BinaryOnly)
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
