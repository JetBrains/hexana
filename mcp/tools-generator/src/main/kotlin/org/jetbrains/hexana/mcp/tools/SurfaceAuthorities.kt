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
    val unknownFieldPolicy: UnknownFieldPolicy,
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
    val unknownFieldPolicy: UnknownFieldPolicy,
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
        val selectedIds = profiles.flatMapTo(mutableSetOf()) { it.second }
        val unselected = registeredIds - selectedIds
        require(unselected.isEmpty()) {
            "Standalone authority contains registered tool IDs without a profile selection: ${unselected.joinToString()}"
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

// These policies characterize the product adapter boundaries.  Generated helper code
// may read only known keys; this metadata intentionally does not change that behavior.
internal val HexanaIdeaUnknownFieldPolicy = UnknownFieldPolicy.Ignore
internal val HexanaStandaloneUnknownFieldPolicy = UnknownFieldPolicy.Ignore
internal val HexanaCliUnknownFieldPolicy = UnknownFieldPolicy.Reject

private fun idea(id: ToolId, pattern: ToolInputPattern, handlerFqn: String) =
    IdeaToolBinding(id, pattern, handlerFqn, HexanaIdeaUnknownFieldPolicy)

private fun standalone(id: ToolId, handler: String) =
    StandaloneToolBinding(id, StandaloneHandlerSpec(handler), HexanaStandaloneUnknownFieldPolicy)

internal val HexanaIdeaToolBindings = listOf(
    idea(SummarizeBinaryToolId, ToolInputPattern.BinaryOnly, "org.jetbrains.hexana.mcp.query.readBinarySummary"),
    idea(ListBinaryFunctionsToolId, ToolInputPattern.BinaryFunctionPage, "org.jetbrains.hexana.mcp.query.readBinaryFunctions"),
    idea(SearchBinaryFunctionsToolId, ToolInputPattern.BinaryFunctionSearch, "org.jetbrains.hexana.mcp.query.searchBinaryFunctions"),
    idea(QueryBinaryToolId, ToolInputPattern.BoundedBinaryQuery, "org.jetbrains.hexana.mcp.query.readBinaryQuery"),
    idea(SummarizeModuleToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readModuleSummary"),
    idea(ListEnabledFeaturesToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readEnabledFeatures"),
    idea(GetEntrypointPolicyToolId, ToolInputPattern.WasmPathLabel, "org.jetbrains.hexana.mcp.query.readEntrypointPolicy"),
    idea(ListRequiredProposalsToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readRequiredProposals"),
    idea(DiffRequiredProposalsToolId, ToolInputPattern.WasmWithBaseline, "org.jetbrains.hexana.mcp.query.diffRequiredProposals"),
    idea(CheckRuntimeCompatibilityToolId, ToolInputPattern.RuntimeCompatibility, "org.jetbrains.hexana.mcp.query.readRuntimeCompatibility"),
    idea(CheckComponentCompatibilityToolId, ToolInputPattern.ComponentCompatibility, "org.jetbrains.hexana.mcp.query.checkComponentCompatibility"),
    idea(ResolveComponentDependenciesToolId, ToolInputPattern.ComponentDependencies, "org.jetbrains.hexana.mcp.query.resolveComponentDependencies"),
    idea(ListComponentSurfaceToolId, ToolInputPattern.ComponentSurface, "org.jetbrains.hexana.mcp.query.readComponentSurface"),
    idea(PlanAbiBindingRegenerationToolId, ToolInputPattern.WasmWithBinding, "org.jetbrains.hexana.mcp.query.planAbiBindingRegeneration"),
    idea(CheckMemoryContractToolId, ToolInputPattern.PolicyWithBaselineAndLog, "org.jetbrains.hexana.mcp.query.checkMemoryContract"),
    idea(AuditGlobalStatePolicyToolId, ToolInputPattern.GlobalStatePolicyAudit, "org.jetbrains.hexana.mcp.query.auditGlobalStatePolicy"),
    idea(AuditLocalStackBudgetToolId, ToolInputPattern.LocalStackBudgetAudit, "org.jetbrains.hexana.mcp.query.auditLocalStackBudget"),
    idea(ListCustomSectionsToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readCustomSections"),
    idea(ListImportsToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readImportFunctions"),
    idea(ListExportsToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readExports"),
    idea(SearchExportsToolId, ToolInputPattern.ExportNameSearch, "org.jetbrains.hexana.mcp.query.readExportsByName"),
    idea(ListExportedFunctionsToolId, ToolInputPattern.ClosedRange("entriesRange"), "org.jetbrains.hexana.mcp.query.readExportedFunctions"),
    idea(ListFunctionsToolId, ToolInputPattern.FunctionPage, "org.jetbrains.hexana.mcp.query.readFunctions"),
    idea(ListCodeSectionOffsetsToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readCodeSectionOffsets"),
    idea(FunctionsForIndicesToolId, ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readFunctionsByIndices"),
    idea(ResolveFunctionSignatureToolId, ToolInputPattern.Funcidx, "org.jetbrains.hexana.mcp.query.readFunctionSignature"),
    idea(DisassembleFunctionToolId, ToolInputPattern.Funcidx, "org.jetbrains.hexana.mcp.query.readFunctionDisassembly"),
    idea(GetFunctionCallGraphToolId, ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readFunctionCallGraph"),
    idea(SearchFunctionsByNameToolId, ToolInputPattern.FunctionNameSearch, "org.jetbrains.hexana.mcp.query.readFunctionsByName"),
    idea(ResolveWasmFramesToolId, ToolInputPattern.WasmFrames, "org.jetbrains.hexana.mcp.query.resolveWasmFrames"),
    idea(ResolveFunctionSymbolsToolId, ToolInputPattern.SourceMappedIndices, "org.jetbrains.hexana.mcp.query.resolveFunctionSymbols"),
    idea(ResolveSourceLocationsToolId, ToolInputPattern.SourceLocations, "org.jetbrains.hexana.mcp.query.resolveSourceLocations"),
    idea(GetLocalsForFunctionsToolId, ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readLocalsForFunctions"),
    idea(GetInstructionsForFunctionsToolId, ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readInstructionsForFunctions"),
    idea(ListMemoryToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readMemory"),
    idea(GetMemoryForIndicesToolId, ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readMemoryByIndices"),
    idea(ListGlobalsToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readGlobals"),
    idea(GetGlobalsForIndicesToolId, ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readGlobalsByIndices"),
    idea(ListTypesToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readTypes"),
    idea(ListValueTypesToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readValueTypes"),
    idea(ListReferenceTypesToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readReferenceTypes"),
    idea(GetTypesForIndicesToolId, ToolInputPattern.Indices, "org.jetbrains.hexana.mcp.query.readTypesByIndices"),
    idea(ListElementSegmentsToolId, ToolInputPattern.WasmOnly, "org.jetbrains.hexana.mcp.query.readElementSegments"),
    idea(ListDataToolId, ToolInputPattern.ClosedRange("entriesRange"), "org.jetbrains.hexana.mcp.query.readDataSections"),
    idea(ListDataSegmentsToolId, ToolInputPattern.DataSegments, "org.jetbrains.hexana.mcp.query.readDataSegmentsByRange"),
    idea(AuditDataPayloadPolicyToolId, ToolInputPattern.DataPayloadPolicyAudit, "org.jetbrains.hexana.mcp.query.auditDataPayloadPolicy"),
    idea(QueryArtifactsToolId, ToolInputPattern.ArtifactQuery, "org.jetbrains.hexana.mcp.query.readArtifactQuery"),
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

private val STANDALONE_TOOL_BINDINGS = listOf(
    standalone(SummarizeBinaryToolId, "summarizeBinary"), standalone(ListBinaryFunctionsToolId, "listBinaryFunctions"),
    standalone(SearchBinaryFunctionsToolId, "searchBinaryFunctions"), standalone(QueryBinaryToolId, "queryBinary"),
    standalone(RunBinaryScriptToolId, "runBinaryScript"), standalone(SummarizeModuleToolId, "summarizeModule"),
    standalone(ListEnabledFeaturesToolId, "listEnabledFeatures"), standalone(GetEntrypointPolicyToolId, "getEntrypointPolicy"),
    standalone(ListRequiredProposalsToolId, "listRequiredProposals"), standalone(DiffRequiredProposalsToolId, "diffRequiredProposals"),
    standalone(CheckRuntimeCompatibilityToolId, "checkRuntimeCompatibility"), standalone(CheckComponentCompatibilityToolId, "checkComponentCompatibility"),
    standalone(ResolveComponentDependenciesToolId, "resolveComponentDependencies"), standalone(ListComponentSurfaceToolId, "listComponentSurface"),
    standalone(PlanAbiBindingRegenerationToolId, "planAbiBindingRegeneration"), standalone(CheckMemoryContractToolId, "checkMemoryContract"),
    standalone(AuditGlobalStatePolicyToolId, "auditGlobalStatePolicy"), standalone(AuditLocalStackBudgetToolId, "auditLocalStackBudget"),
    standalone(ListCustomSectionsToolId, "listCustomSections"), standalone(ListImportsToolId, "listImports"),
    standalone(ListExportsToolId, "listExports"), standalone(SearchExportsToolId, "searchExports"),
    standalone(ListExportedFunctionsToolId, "listExportedFunctions"), standalone(ListFunctionsToolId, "listFunctions"),
    standalone(ListCodeSectionOffsetsToolId, "listCodeSectionOffsets"), standalone(FunctionsForIndicesToolId, "functionsForIndices"),
    standalone(ResolveFunctionSignatureToolId, "resolveFunctionSignature"), standalone(DisassembleFunctionToolId, "disassembleFunction"),
    standalone(GetFunctionCallGraphToolId, "getFunctionCallGraph"), standalone(SearchFunctionsByNameToolId, "searchFunctionsByName"),
    standalone(ResolveWasmFramesToolId, "resolveWasmFrames"), standalone(ResolveFunctionSymbolsToolId, "resolveFunctionSymbols"),
    standalone(ResolveSourceLocationsToolId, "resolveSourceLocations"), standalone(GetLocalsForFunctionsToolId, "getLocalsForFunctions"),
    standalone(GetInstructionsForFunctionsToolId, "getInstructionsForFunctions"), standalone(ListMemoryToolId, "listMemory"),
    standalone(GetMemoryForIndicesToolId, "getMemoryForIndices"), standalone(ListGlobalsToolId, "listGlobals"),
    standalone(GetGlobalsForIndicesToolId, "getGlobalsForIndices"), standalone(ListTypesToolId, "listTypes"),
    standalone(ListValueTypesToolId, "listValueTypes"), standalone(ListReferenceTypesToolId, "listReferenceTypes"),
    standalone(GetTypesForIndicesToolId, "getTypesForIndices"), standalone(ListElementSegmentsToolId, "listElementSegments"),
    standalone(ListDataToolId, "listData"), standalone(ListDataSegmentsToolId, "listDataSegments"),
    standalone(AuditDataPayloadPolicyToolId, "auditDataPayloadPolicy"), standalone(QueryArtifactsToolId, "queryArtifacts"),
)

val HexanaStandaloneTools: List<StandaloneTool> = HexanaToolCatalog.resolveBindings(
    STANDALONE_TOOL_BINDINGS.map(StandaloneToolBinding::id),
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
    SummarizeBinaryToolId, SummarizeModuleToolId, ListEnabledFeaturesToolId, GetEntrypointPolicyToolId,
    ListRequiredProposalsToolId, DiffRequiredProposalsToolId, CheckRuntimeCompatibilityToolId,
    CheckComponentCompatibilityToolId, PlanAbiBindingRegenerationToolId, CheckMemoryContractToolId,
    AuditGlobalStatePolicyToolId, AuditLocalStackBudgetToolId, ListImportsToolId, ListExportsToolId,
    SearchExportsToolId, ListExportedFunctionsToolId, ListFunctionsToolId, ResolveFunctionSignatureToolId,
    DisassembleFunctionToolId, GetFunctionCallGraphToolId, SearchFunctionsByNameToolId,
    ResolveWasmFramesToolId, ResolveFunctionSymbolsToolId, ResolveSourceLocationsToolId, ListMemoryToolId,
    ListGlobalsToolId, ListElementSegmentsToolId, ListDataSegmentsToolId, AuditDataPayloadPolicyToolId,
), "standalone default profile")

val HexanaStandaloneAdvancedToolIds: List<ToolId> = standaloneProfile(listOf(
    ListBinaryFunctionsToolId, SearchBinaryFunctionsToolId, QueryBinaryToolId,
    ResolveComponentDependenciesToolId, ListComponentSurfaceToolId, ListCustomSectionsToolId,
    ListCodeSectionOffsetsToolId, FunctionsForIndicesToolId, GetLocalsForFunctionsToolId,
    GetInstructionsForFunctionsToolId, GetMemoryForIndicesToolId, GetGlobalsForIndicesToolId,
    ListTypesToolId, ListValueTypesToolId, ListReferenceTypesToolId, GetTypesForIndicesToolId,
    ListDataToolId, QueryArtifactsToolId,
), "standalone advanced profile")

val HexanaStandaloneScriptingToolIds: List<ToolId> = standaloneProfile(
    listOf(RunBinaryScriptToolId),
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

private fun todoCli(id: ToolId, command: String, group: ToolGroup) =
    cliToolBinding(id, command, group, ToolOutputPolicy.ToolDefined, unknownFieldPolicy = HexanaCliUnknownFieldPolicy)

private val CLI_TOOL_BINDINGS = listOf(
    cliToolBinding(
        SummarizeBinaryToolId, "summarize-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        releaseProducts = setOf(CliReleaseProduct.HexanaCli),
        inputModel = binaryCliInputModel(),
        unknownFieldPolicy = HexanaCliUnknownFieldPolicy,
    ),
    cliToolBinding(
        RunBinaryScriptToolId, "run-script", ToolGroup.Scripting, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        defaultOutput = OutputConversionCapability.RawText,
        releaseProducts = setOf(CliReleaseProduct.HexanaCliScript),
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
        unknownFieldPolicy = HexanaCliUnknownFieldPolicy,
    ),
    cliToolBinding(
        WasmToWatToolId, "wasm-to-wat", ToolGroup.Converters, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        defaultOutput = OutputConversionCapability.RawText,
        releaseProducts = setOf(CliReleaseProduct.HexanaCli),
        inputModel = wasmCliInputModel(),
        unknownFieldPolicy = HexanaCliUnknownFieldPolicy,
    ),
    cliToolBinding(
        SummarizeModuleToolId, "summarize-module", ToolGroup.Module, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        defaultOutput = OutputConversionCapability.Pretty,
        releaseProducts = setOf(CliReleaseProduct.HexanaCli),
        inputModel = wasmCliInputModel(),
        unknownFieldPolicy = HexanaCliUnknownFieldPolicy,
    ),
    todoCli(ListEnabledFeaturesToolId, "list-enabled-features", ToolGroup.Module),
    todoCli(GetEntrypointPolicyToolId, "get-entrypoint-policy", ToolGroup.EntrypointPolicy),
    todoCli(ListRequiredProposalsToolId, "list-required-proposals", ToolGroup.Runtime),
    todoCli(DiffRequiredProposalsToolId, "diff-required-proposals", ToolGroup.Runtime),
    todoCli(CheckRuntimeCompatibilityToolId, "check-runtime-compatibility", ToolGroup.Runtime),
    todoCli(CheckComponentCompatibilityToolId, "check-component-compatibility", ToolGroup.Component),
    todoCli(PlanAbiBindingRegenerationToolId, "plan-abi-binding-regeneration", ToolGroup.ArtifactQuery),
    todoCli(CheckMemoryContractToolId, "check-memory-contract", ToolGroup.Memory),
    todoCli(AuditGlobalStatePolicyToolId, "audit-global-state-policy", ToolGroup.Global),
    todoCli(AuditLocalStackBudgetToolId, "audit-local-stack-budget", ToolGroup.Function),
    todoCli(ListImportsToolId, "list-imports", ToolGroup.Import),
    todoCli(ListExportsToolId, "list-exports", ToolGroup.Export),
    todoCli(SearchExportsToolId, "search-exports", ToolGroup.Export),
    todoCli(ListExportedFunctionsToolId, "list-exported-functions", ToolGroup.Export),
    todoCli(ListFunctionsToolId, "list-functions", ToolGroup.Function),
    todoCli(ResolveFunctionSignatureToolId, "resolve-function-signature", ToolGroup.Function),
    todoCli(DisassembleFunctionToolId, "disassemble-function", ToolGroup.Function),
    todoCli(GetFunctionCallGraphToolId, "get-function-call-graph", ToolGroup.Function),
    todoCli(SearchFunctionsByNameToolId, "search-functions-by-name", ToolGroup.Function),
    todoCli(ResolveWasmFramesToolId, "resolve-wasm-frames", ToolGroup.Function),
    todoCli(ResolveFunctionSymbolsToolId, "resolve-function-symbols", ToolGroup.Function),
    todoCli(ResolveSourceLocationsToolId, "resolve-source-locations", ToolGroup.Function),
    todoCli(ListMemoryToolId, "list-memory", ToolGroup.Memory),
    todoCli(ListGlobalsToolId, "list-globals", ToolGroup.Global),
    todoCli(ListElementSegmentsToolId, "list-element-segments", ToolGroup.Element),
    todoCli(ListDataSegmentsToolId, "list-data-segments", ToolGroup.Data),
    todoCli(AuditDataPayloadPolicyToolId, "audit-data-payload-policy", ToolGroup.Data),
)

val HexanaCliTools: List<CliTool> = HexanaToolCatalog.resolveBindings(
    CLI_TOOL_BINDINGS.map(CliToolBinding::id),
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
