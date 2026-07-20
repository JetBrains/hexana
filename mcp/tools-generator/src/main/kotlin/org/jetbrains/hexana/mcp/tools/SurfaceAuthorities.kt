package org.jetbrains.hexana.mcp.tools

private val HANDLER_FQN_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")

interface ContractBoundTool {
    val contract: ToolSpec
}

val ContractBoundTool.id: ToolId get() = contract.id
val ContractBoundTool.name: String get() = contract.name
val ContractBoundTool.summary: String get() = contract.summary
val ContractBoundTool.guidance: String? get() = contract.guidance
val ContractBoundTool.input: ClassSpec get() = contract.input
val ContractBoundTool.output: ClassSpec get() = contract.output
val ContractBoundTool.mcpDescription: String get() = contract.mcpDescription

data class IdeaToolBinding(
    val id: ToolId,
    val inputPattern: ToolInputPattern,
    val handlerFqn: String,
) {
    init {
        require(HANDLER_FQN_REGEX.matches(handlerFqn)) {
            "IDEA tool '${id.value}' handler must be a fully-qualified function name, got `$handlerFqn`."
        }
    }
}

data class IdeaTool(
    override val contract: ToolSpec,
    val binding: IdeaToolBinding,
) : ContractBoundTool {
    val inputPattern: ToolInputPattern get() = binding.inputPattern
    val handlerFqn: String get() = binding.handlerFqn
}

data class StandaloneToolBinding(
    val id: ToolId,
    val handler: StandaloneHandlerSpec,
)

data class StandaloneTool(
    override val contract: ToolSpec,
    val binding: StandaloneToolBinding,
) : ContractBoundTool {
    val standaloneHandler: StandaloneHandlerSpec get() = binding.handler
}

data class StandaloneToolAuthority(
    val registrations: List<StandaloneTool>,
    val defaultToolIds: List<ToolId>,
    val advancedToolIds: List<ToolId>,
    val scriptingToolIds: List<ToolId>,
) {
    init {
        val duplicateRegistrations = registrations.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateRegistrations.isEmpty()) {
            "Standalone authority contains duplicate registrations: ${duplicateRegistrations.joinToString()}"
        }
        val registeredIds = registrations.mapTo(mutableSetOf()) { it.id }

        fun validateProfile(profileName: String, ids: List<ToolId>) {
            val duplicates = ids.groupBy { it }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) {
                "Standalone $profileName profile contains duplicate tool IDs: ${duplicates.joinToString()}"
            }
            val unregistered = ids.filterNot(registeredIds::contains)
            require(unregistered.isEmpty()) {
                "Standalone $profileName profile references unregistered tool IDs: ${unregistered.joinToString()}"
            }
        }

        validateProfile("default", defaultToolIds)
        validateProfile("advanced", advancedToolIds)
        validateProfile("scripting", scriptingToolIds)

        val profiles = listOf(
            "default" to defaultToolIds,
            "advanced" to advancedToolIds,
            "scripting" to scriptingToolIds,
        )
        profiles.forEachIndexed { index, (profileName, profileIds) ->
            profiles.drop(index + 1).forEach { (otherProfileName, otherProfileIds) ->
                val overlap = profileIds.intersect(otherProfileIds.toSet())
                require(overlap.isEmpty()) {
                    "Standalone $profileName and $otherProfileName profiles must be disjoint; overlapping tool IDs: ${overlap.joinToString()}"
                }
            }
        }
    }
}

data class CliTool(
    override val contract: ToolSpec,
    val binding: CliToolBinding,
) : ContractBoundTool {
    val inputModel: ToolInputModel?
        get() = binding.inputModel
}

private fun id(value: String) = ToolId(value)

private fun idea(value: String, pattern: ToolInputPattern, handlerFqn: String) =
    IdeaToolBinding(id(value), pattern, handlerFqn)

private fun standalone(value: String, handler: String) =
    StandaloneToolBinding(id(value), StandaloneHandlerSpec(handler))

internal val HexanaIdeaToolBindings = listOf(
    idea("summarize_binary", ToolInputPattern.BinaryOnly, "org.jetbrains.hexana.mcp.query.readBinarySummary"),
    idea("list_binary_functions", ToolInputPattern.BinaryFunctionPage, "org.jetbrains.hexana.mcp.query.readBinaryFunctions"),
    idea("search_binary_functions", ToolInputPattern.BinaryFunctionSearch, "org.jetbrains.hexana.mcp.query.searchBinaryFunctions"),
    idea("query_binary", ToolInputPattern.BoundedBinaryQuery, "org.jetbrains.hexana.mcp.query.readBinaryQuery"),
    idea("summarize_module", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readModuleSummary"),
    idea("list_enabled_features", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readEnabledFeatures"),
    idea("get_entrypoint_policy", ToolInputPattern.WasmPathLabel, "org.jetbrains.hexana.mcp.query.readEntrypointPolicy"),
    idea("list_required_proposals", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readRequiredProposals"),
    idea("diff_required_proposals", ToolInputPattern.WasmWithBaseline, "org.jetbrains.hexana.mcp.query.diffRequiredProposals"),
    idea("check_runtime_compatibility", ToolInputPattern.RuntimeCompatibility, "org.jetbrains.hexana.mcp.query.readRuntimeCompatibility"),
    idea("check_component_compatibility", ToolInputPattern.ComponentCompatibility, "org.jetbrains.hexana.mcp.query.checkComponentCompatibility"),
    idea("resolve_component_dependencies", ToolInputPattern.ComponentDependencies, "org.jetbrains.hexana.mcp.query.resolveComponentDependencies"),
    idea("list_component_surface", ToolInputPattern.ComponentSurface, "org.jetbrains.hexana.mcp.query.readComponentSurface"),
    idea("plan_abi_binding_regeneration", ToolInputPattern.WasmWithBinding, "org.jetbrains.hexana.mcp.query.planAbiBindingRegeneration"),
    idea("check_memory_contract", ToolInputPattern.PolicyWithBaselineAndLog, "org.jetbrains.hexana.mcp.query.checkMemoryContract"),
    idea("audit_global_state_policy", ToolInputPattern.GlobalStatePolicyAudit, "org.jetbrains.hexana.mcp.query.auditGlobalStatePolicy"),
    idea("audit_local_stack_budget", ToolInputPattern.LocalStackBudgetAudit, "org.jetbrains.hexana.mcp.query.auditLocalStackBudget"),
    idea("list_custom_sections", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readCustomSections"),
    idea("list_imports", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readImportFunctions"),
    idea("list_exports", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readExports"),
    idea("search_exports", ToolInputPattern.ExportNameSearch, "org.jetbrains.hexana.mcp.query.readExportsByName"),
    idea("list_exported_functions", ToolInputPattern.ClosedRange("entriesRange"), "org.jetbrains.hexana.mcp.query.readExportedFunctions"),
    idea("list_functions", ToolInputPattern.FunctionPage, "org.jetbrains.hexana.mcp.query.readFunctions"),
    idea("list_code_section_offsets", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readCodeSectionOffsets"),
    idea("functions_for_indices", ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readFunctionsByIndices"),
    idea("resolve_function_signature", ToolInputPattern.Funcidx, "org.jetbrains.hexana.mcp.query.readFunctionSignature"),
    idea("disassemble_function", ToolInputPattern.Funcidx, "org.jetbrains.hexana.mcp.query.readFunctionDisassembly"),
    idea("get_function_call_graph", ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readFunctionCallGraph"),
    idea("search_functions_by_name", ToolInputPattern.FunctionNameSearch, "org.jetbrains.hexana.mcp.query.readFunctionsByName"),
    idea("resolve_wasm_frames", ToolInputPattern.WasmFrames, "org.jetbrains.hexana.mcp.query.resolveWasmFrames"),
    idea("resolve_function_symbols", ToolInputPattern.SourceMappedIndices, "org.jetbrains.hexana.mcp.query.resolveFunctionSymbols"),
    idea("resolve_source_locations", ToolInputPattern.SourceLocations, "org.jetbrains.hexana.mcp.query.resolveSourceLocations"),
    idea("get_locals_for_functions", ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readLocalsForFunctions"),
    idea("get_instructions_for_functions", ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readInstructionsForFunctions"),
    idea("list_memory", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readMemory"),
    idea("get_memory_for_indices", ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readMemoryByIndices"),
    idea("list_globals", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readGlobals"),
    idea("get_globals_for_indices", ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readGlobalsByIndices"),
    idea("list_types", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readTypes"),
    idea("list_value_types", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readValueTypes"),
    idea("list_reference_types", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readReferenceTypes"),
    idea("get_types_for_indices", ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readTypesByIndices"),
    idea("list_element_segments", ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readElementSegments"),
    idea("list_data", ToolInputPattern.ClosedRange("entriesRange"), "org.jetbrains.hexana.mcp.query.readDataSections"),
    idea("list_data_segments", ToolInputPattern.DataSegments, "org.jetbrains.hexana.mcp.query.readDataSegmentsByRange"),
    idea("audit_data_payload_policy", ToolInputPattern.DataPayloadPolicyAudit, "org.jetbrains.hexana.mcp.query.auditDataPayloadPolicy"),
    idea("query_artifacts", ToolInputPattern.ArtifactQuery, "org.jetbrains.hexana.mcp.query.readArtifactQuery"),
)

private val validatedHexanaIdeaToolBindings = HexanaToolCatalog.resolveBindings(
    HexanaIdeaToolBindings.map(IdeaToolBinding::id),
    HexanaIdeaToolBindings,
    "IDEA tool authority",
    IdeaToolBinding::id,
) { _, binding -> binding }

val HexanaIdeaTools: List<IdeaTool> by lazy {
    HexanaToolCatalog.resolveBindings(
        HexanaStandaloneDefaultToolIds,
        validatedHexanaIdeaToolBindings.filter { it.id in HexanaStandaloneDefaultToolIds.toSet() },
        "IDEA default tool authority",
        IdeaToolBinding::id,
    ) { contract, binding ->
        validateInputPattern(contract.name, contract.input, binding.inputPattern)
        IdeaTool(contract, binding)
    }
}

private val STANDALONE_TOOL_IDS = listOf(
    id("summarize_binary"), id("list_binary_functions"), id("search_binary_functions"), id("query_binary"),
    id("run_binary_script"), id("summarize_module"), id("list_enabled_features"), id("get_entrypoint_policy"),
    id("list_required_proposals"), id("diff_required_proposals"), id("check_runtime_compatibility"),
    id("check_component_compatibility"), id("resolve_component_dependencies"), id("list_component_surface"),
    id("plan_abi_binding_regeneration"), id("check_memory_contract"), id("audit_global_state_policy"),
    id("audit_local_stack_budget"), id("list_custom_sections"), id("list_imports"), id("list_exports"),
    id("search_exports"), id("list_exported_functions"), id("list_functions"), id("list_code_section_offsets"),
    id("functions_for_indices"), id("resolve_function_signature"), id("disassemble_function"),
    id("get_function_call_graph"), id("search_functions_by_name"), id("resolve_wasm_frames"),
    id("resolve_function_symbols"), id("resolve_source_locations"), id("get_locals_for_functions"),
    id("get_instructions_for_functions"), id("list_memory"), id("get_memory_for_indices"), id("list_globals"),
    id("get_globals_for_indices"), id("list_types"), id("list_value_types"), id("list_reference_types"),
    id("get_types_for_indices"), id("list_element_segments"), id("list_data"), id("list_data_segments"),
    id("audit_data_payload_policy"), id("query_artifacts"),
)

private val STANDALONE_TOOL_BINDINGS = listOf(
    standalone("summarize_binary", "summarizeBinary"), standalone("list_binary_functions", "listBinaryFunctions"),
    standalone("search_binary_functions", "searchBinaryFunctions"), standalone("query_binary", "queryBinary"),
    standalone("run_binary_script", "runBinaryScript"), standalone("summarize_module", "summarizeModule"),
    standalone("list_enabled_features", "listEnabledFeatures"), standalone("get_entrypoint_policy", "getEntrypointPolicy"),
    standalone("list_required_proposals", "listRequiredProposals"), standalone("diff_required_proposals", "diffRequiredProposals"),
    standalone("check_runtime_compatibility", "checkRuntimeCompatibility"), standalone("check_component_compatibility", "checkComponentCompatibility"),
    standalone("resolve_component_dependencies", "resolveComponentDependencies"), standalone("list_component_surface", "listComponentSurface"),
    standalone("plan_abi_binding_regeneration", "planAbiBindingRegeneration"), standalone("check_memory_contract", "checkMemoryContract"),
    standalone("audit_global_state_policy", "auditGlobalStatePolicy"), standalone("audit_local_stack_budget", "auditLocalStackBudget"),
    standalone("list_custom_sections", "listCustomSections"), standalone("list_imports", "listImports"),
    standalone("list_exports", "listExports"), standalone("search_exports", "searchExports"),
    standalone("list_exported_functions", "listExportedFunctions"), standalone("list_functions", "listFunctions"),
    standalone("list_code_section_offsets", "listCodeSectionOffsets"), standalone("functions_for_indices", "functionsForIndices"),
    standalone("resolve_function_signature", "resolveFunctionSignature"), standalone("disassemble_function", "disassembleFunction"),
    standalone("get_function_call_graph", "getFunctionCallGraph"), standalone("search_functions_by_name", "searchFunctionsByName"),
    standalone("resolve_wasm_frames", "resolveWasmFrames"), standalone("resolve_function_symbols", "resolveFunctionSymbols"),
    standalone("resolve_source_locations", "resolveSourceLocations"), standalone("get_locals_for_functions", "getLocalsForFunctions"),
    standalone("get_instructions_for_functions", "getInstructionsForFunctions"), standalone("list_memory", "listMemory"),
    standalone("get_memory_for_indices", "getMemoryForIndices"), standalone("list_globals", "listGlobals"),
    standalone("get_globals_for_indices", "getGlobalsForIndices"), standalone("list_types", "listTypes"),
    standalone("list_value_types", "listValueTypes"), standalone("list_reference_types", "listReferenceTypes"),
    standalone("get_types_for_indices", "getTypesForIndices"), standalone("list_element_segments", "listElementSegments"),
    standalone("list_data", "listData"), standalone("list_data_segments", "listDataSegments"),
    standalone("audit_data_payload_policy", "auditDataPayloadPolicy"), standalone("query_artifacts", "queryArtifacts"),
)

val HexanaStandaloneTools: List<StandaloneTool> = HexanaToolCatalog.resolveBindings(
    STANDALONE_TOOL_IDS,
    STANDALONE_TOOL_BINDINGS,
    "standalone tool authority",
    StandaloneToolBinding::id,
) { contract, binding -> StandaloneTool(contract, binding) }

private fun standaloneProfile(ids: List<ToolId>, owner: String): List<ToolId> {
    val selected = ids.toSet()
    return HexanaToolCatalog.resolveBindings(
        ids,
        STANDALONE_TOOL_BINDINGS.filter { it.id in selected },
        owner,
        StandaloneToolBinding::id,
    ) { contract, _ -> contract.id }
}

val HexanaStandaloneDefaultToolIds: List<ToolId> = standaloneProfile(listOf(
    id("summarize_binary"), id("summarize_module"), id("list_enabled_features"), id("get_entrypoint_policy"),
    id("list_required_proposals"), id("diff_required_proposals"), id("check_runtime_compatibility"),
    id("check_component_compatibility"), id("plan_abi_binding_regeneration"), id("check_memory_contract"),
    id("audit_global_state_policy"), id("audit_local_stack_budget"), id("list_imports"), id("list_exports"),
    id("search_exports"), id("list_exported_functions"), id("list_functions"), id("resolve_function_signature"),
    id("disassemble_function"), id("get_function_call_graph"), id("search_functions_by_name"),
    id("resolve_wasm_frames"), id("resolve_function_symbols"), id("resolve_source_locations"), id("list_memory"),
    id("list_globals"), id("list_element_segments"), id("list_data_segments"), id("audit_data_payload_policy"),
), "standalone default profile")

val HexanaStandaloneAdvancedToolIds: List<ToolId> = standaloneProfile(listOf(
    id("list_binary_functions"), id("search_binary_functions"), id("query_binary"),
    id("resolve_component_dependencies"), id("list_component_surface"), id("list_custom_sections"),
    id("list_code_section_offsets"), id("functions_for_indices"), id("get_locals_for_functions"),
    id("get_instructions_for_functions"), id("get_memory_for_indices"), id("get_globals_for_indices"),
    id("list_types"), id("list_value_types"), id("list_reference_types"), id("get_types_for_indices"),
    id("list_data"), id("query_artifacts"),
), "standalone advanced profile")

val HexanaStandaloneScriptingToolIds: List<ToolId> = standaloneProfile(
    listOf(id("run_binary_script")),
    "standalone scripting profile",
)

val HexanaStandaloneAuthority = StandaloneToolAuthority(
    registrations = HexanaStandaloneTools,
    defaultToolIds = HexanaStandaloneDefaultToolIds,
    advancedToolIds = HexanaStandaloneAdvancedToolIds,
    scriptingToolIds = HexanaStandaloneScriptingToolIds,
)

val HexanaMcpbTools: List<ToolSpec> = HexanaToolCatalog.resolve(
    HexanaStandaloneDefaultToolIds,
    "MCPB tools derived from standalone default profile",
)

private val CLI_TOOL_IDS = listOf(
    id("summarize_binary"), id("run_binary_script"), id("wasm_to_wat"), id("summarize_module"),
    id("list_enabled_features"), id("get_entrypoint_policy"), id("list_required_proposals"),
    id("diff_required_proposals"), id("check_runtime_compatibility"), id("check_component_compatibility"),
    id("plan_abi_binding_regeneration"), id("check_memory_contract"), id("audit_global_state_policy"),
    id("audit_local_stack_budget"), id("list_imports"), id("list_exports"), id("search_exports"),
    id("list_exported_functions"), id("list_functions"), id("resolve_function_signature"),
    id("disassemble_function"), id("get_function_call_graph"), id("search_functions_by_name"),
    id("resolve_wasm_frames"), id("resolve_function_symbols"), id("resolve_source_locations"), id("list_memory"),
    id("list_globals"), id("list_element_segments"), id("list_data_segments"), id("audit_data_payload_policy"),
)

private fun todoCli(id: String, command: String, group: ToolGroup) =
    cliToolBinding(id, command, group, ToolOutputPolicy.ToolDefined)

private val CLI_TOOL_BINDINGS = listOf(
    cliToolBinding(
        "summarize_binary", "summarize-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        executableRenderer = executableCliRenderer("renderSummarizeBinaryCliCommandOutput"),
        releaseProducts = setOf(CliReleaseProduct.HexanaMcp, CliReleaseProduct.HexanaCli),
        inputModel = binaryCliInputModel(),
    ),
    cliToolBinding(
        "run_binary_script", "run-script", ToolGroup.Scripting, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        defaultOutput = OutputConversionCapability.RawText,
        executableRenderer = executableCliRenderer("renderRunBinaryScriptCommandOutput"),
        releaseProducts = setOf(CliReleaseProduct.HexanaMcp, CliReleaseProduct.HexanaCliScript),
        inputModel = toolInputModel {
            binarySource("binary", target = SourceTargetSpec.RootInput) {
                cli {
                    positional("binary")
                    stdin("--stdin")
                    base64("--bytes-base64")
                    autoStdin()
                }
            }
            param(name = "scriptPath", cliOption = "--script")
            param(name = "script", cliOption = "--script-text")
        },
    ),
    cliToolBinding(
        "wasm_to_wat", "wasm-to-wat", ToolGroup.Converters, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        defaultOutput = OutputConversionCapability.RawText,
        executableRenderer = executableCliRenderer("renderWasmToWatCommandOutput"),
        releaseProducts = setOf(CliReleaseProduct.HexanaMcp, CliReleaseProduct.HexanaCli),
        inputModel = wasmCliInputModel(),
    ),
    cliToolBinding(
        "summarize_module", "summarize-module", ToolGroup.Module, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        defaultOutput = OutputConversionCapability.Pretty,
        executableRenderer = executableCliRenderer("renderSummarizeModuleCommandOutput"),
        releaseProducts = setOf(CliReleaseProduct.HexanaMcp, CliReleaseProduct.HexanaCli),
        inputModel = wasmCliInputModel(),
    ),
    todoCli("list_enabled_features", "list-enabled-features", ToolGroup.Module),
    todoCli("get_entrypoint_policy", "get-entrypoint-policy", ToolGroup.EntrypointPolicy),
    todoCli("list_required_proposals", "list-required-proposals", ToolGroup.Runtime),
    todoCli("diff_required_proposals", "diff-required-proposals", ToolGroup.Runtime),
    todoCli("check_runtime_compatibility", "check-runtime-compatibility", ToolGroup.Runtime),
    todoCli("check_component_compatibility", "check-component-compatibility", ToolGroup.Component),
    todoCli("plan_abi_binding_regeneration", "plan-abi-binding-regeneration", ToolGroup.ArtifactQuery),
    todoCli("check_memory_contract", "check-memory-contract", ToolGroup.Memory),
    todoCli("audit_global_state_policy", "audit-global-state-policy", ToolGroup.Global),
    todoCli("audit_local_stack_budget", "audit-local-stack-budget", ToolGroup.Function),
    todoCli("list_imports", "list-imports", ToolGroup.Import),
    todoCli("list_exports", "list-exports", ToolGroup.Export),
    todoCli("search_exports", "search-exports", ToolGroup.Export),
    todoCli("list_exported_functions", "list-exported-functions", ToolGroup.Export),
    todoCli("list_functions", "list-functions", ToolGroup.Function),
    todoCli("resolve_function_signature", "resolve-function-signature", ToolGroup.Function),
    todoCli("disassemble_function", "disassemble-function", ToolGroup.Function),
    todoCli("get_function_call_graph", "get-function-call-graph", ToolGroup.Function),
    todoCli("search_functions_by_name", "search-functions-by-name", ToolGroup.Function),
    todoCli("resolve_wasm_frames", "resolve-wasm-frames", ToolGroup.Function),
    todoCli("resolve_function_symbols", "resolve-function-symbols", ToolGroup.Function),
    todoCli("resolve_source_locations", "resolve-source-locations", ToolGroup.Function),
    todoCli("list_memory", "list-memory", ToolGroup.Memory),
    todoCli("list_globals", "list-globals", ToolGroup.Global),
    todoCli("list_element_segments", "list-element-segments", ToolGroup.Element),
    todoCli("list_data_segments", "list-data-segments", ToolGroup.Data),
    todoCli("audit_data_payload_policy", "audit-data-payload-policy", ToolGroup.Data),
)

val HexanaCliTools: List<CliTool> = HexanaToolCatalog.resolveBindings(
    CLI_TOOL_IDS,
    CLI_TOOL_BINDINGS,
    "CLI tool authority",
    CliToolBinding::id,
) { contract, binding ->
    validateCliBinding(contract, binding)
    CliTool(contract, binding)
}.also { tools ->
    val duplicateCommands = tools.groupBy { it.binding.commandName }.filterValues { it.size > 1 }
    require(duplicateCommands.isEmpty()) { "CLI tool authority contains duplicate commands: ${duplicateCommands.keys}" }
    validateCliReleaseProducts(tools)
}
