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
    val inputPattern: ToolInputPattern?,
    val handlerFqn: String,
    val unknownFieldPolicy: UnknownFieldPolicy,
    val typedInvocation: IdeaTypedInvocationSpec? = null,
) {
    init {
        require((inputPattern == null) != (typedInvocation == null)) {
            "IDEA tool '${id.value}' must declare exactly one invocation binding."
        }
        require(HANDLER_FQN_REGEX.matches(handlerFqn)) {
            "IDEA tool '${id.value}' handler must be a fully-qualified function name, got `$handlerFqn`."
        }
    }
}

data class IdeaTypedInvocationSpec(
    val requestTypeName: String,
    val source: SourceSelection,
    val fields: List<FieldRef>,
    val halfOpenRanges: List<FieldRef> = emptyList(),
    val zeroWhenNull: List<FieldRef> = emptyList(),
) {
    init {
        require(Regex("^[A-Z][A-Za-z0-9_]*$").matches(requestTypeName)) {
            "IDEA typed request name must be a Kotlin type name, got `$requestTypeName`."
        }
        require(fields.distinctBy(FieldRef::name).size == fields.size) {
            "IDEA typed request `$requestTypeName` references duplicate fields."
        }
        require(halfOpenRanges.distinctBy(FieldRef::name).size == halfOpenRanges.size &&
            zeroWhenNull.distinctBy(FieldRef::name).size == zeroWhenNull.size) {
            "IDEA typed request `$requestTypeName` references duplicate range or null bindings."
        }
    }

    fun validateFor(input: ClassSpec) {
        require(source.kind == SourceSelectionKind.Path) {
            "IDEA typed request `$requestTypeName` currently requires a path source."
        }
        require(input.exactOneSourceGroups.any { group -> source in group.selections }) {
            "IDEA typed request `$requestTypeName` must select a source from `${input.name}`."
        }
        (listOf(source.field) + fields).forEach { reference ->
            require(input.fieldRef(reference.name) == reference) {
                "IDEA typed request `$requestTypeName` field `${reference.name}` does not match `${input.name}`."
            }
        }
        require(fields.none { field -> input.exactOneSourceGroups.any { group -> group.selections.any { it.field == field } } }) {
            "IDEA typed request `$requestTypeName` must keep source fields separate from ordinary fields."
        }
        halfOpenRanges.forEach { reference ->
            require(reference in fields) { "IDEA typed request `$requestTypeName` must bind range `${reference.name}` as an ordinary field." }
            validateHalfOpenRange(input, reference)
        }
        zeroWhenNull.forEach { reference ->
            require(reference in fields && reference.type == FieldType.Int32 && reference.nullable) {
                "IDEA typed request `$requestTypeName` can replace null with zero only for a nullable Int field."
            }
        }
    }
}

internal fun validateHalfOpenRange(input: ClassSpec, reference: FieldRef) {
    require(input.fieldRef(reference.name) == reference) { "Half-open range `${reference.name}` must belong to `${input.name}`." }
    val range = (reference.type as? FieldType.ClassRef)?.clazz
    require(range != null && range.fields.map(FieldSpec::name) == listOf("from", "to")) {
        "Half-open range `${reference.name}` must contain `from` and `to` fields."
    }
    val from = range.fields[0]
    val to = range.fields[1]
    require(!reference.nullable && reference.presence == FieldPresence.Required &&
        from.type == FieldType.Int32 && !from.nullable && from.presence == FieldPresence.Required && from.minimum == 0 &&
        to.type == FieldType.Int32 && to.nullable && to.presence == FieldPresence.Optional && to.minimum == 0) {
        "Half-open range `${reference.name}` requires a nonnegative `from` and optional nonnegative `to`."
    }
}

data class IdeaTool(
    override val contract: ToolSpec,
    val binding: IdeaToolBinding,
) : ContractBoundTool {
    val inputPattern: ToolInputPattern? get() = binding.inputPattern
    val typedInvocation: IdeaTypedInvocationSpec? get() = binding.typedInvocation
    val handlerFqn: String get() = binding.handlerFqn
}

data class StandaloneToolBinding(
    val id: ToolId,
    val invocation: StandaloneInvocationSpec,
    val unknownFieldPolicy: UnknownFieldPolicy,
)

data class StandaloneTool(
    override val contract: ToolSpec,
    val binding: StandaloneToolBinding,
) : ContractBoundTool {
    val standaloneInvocation: StandaloneInvocationSpec get() = binding.invocation
}

data class StandaloneToolAuthority(
    val registrations: List<StandaloneTool>,
    val productRegistry: McpProductRegistry,
    val runtimeBinding: McpRuntimeBinding?,
) {
    val runtimeProduct: McpProductSpec = productRegistry.resolveRuntime(runtimeBinding)

    init {
        val duplicateRegistrations = registrations.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateRegistrations.isEmpty()) {
            "Standalone authority contains duplicate registrations: ${duplicateRegistrations.joinToString()}"
        }
        val registeredIds = registrations.mapTo(mutableSetOf()) { it.id }

        runtimeProduct.profiles.forEach { profile ->
            val unregistered = profile.toolIds.filterNot(registeredIds::contains)
            require(unregistered.isEmpty()) {
                "Standalone product '${runtimeProduct.id.value}' profile '${profile.id.value}' references unregistered tool IDs: ${unregistered.joinToString()}"
            }
        }

        if (runtimeProduct.membershipPolicy == McpProfileMembershipPolicy.DisjointAndComplete) {
            runtimeProduct.profiles.forEachIndexed { index, profile ->
                runtimeProduct.profiles.drop(index + 1).forEach { otherProfile ->
                    val overlap = profile.toolIds.intersect(otherProfile.toolIds.toSet())
                    require(overlap.isEmpty()) {
                        "Standalone product '${runtimeProduct.id.value}' profiles '${profile.id.value}' and '${otherProfile.id.value}' must be disjoint; overlapping tool IDs: ${overlap.joinToString()}"
                    }
                }
            }
            val selectedIds = runtimeProduct.profiles.flatMapTo(mutableSetOf()) { it.toolIds }
            val unselected = registeredIds - selectedIds
            require(unselected.isEmpty()) {
                "Standalone product '${runtimeProduct.id.value}' contains registered tool IDs without a profile selection: ${unselected.joinToString()}"
            }
        }
    }

    fun profile(id: ProfileId): McpProfileSpec = runtimeProduct.profile(id)
        ?: error("Resolved runtime product '${runtimeProduct.id.value}' is missing profile '${id.value}'.")
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

private fun ideaTyped(id: ToolId, invocation: IdeaTypedInvocationSpec, handlerFqn: String) =
    IdeaToolBinding(id, null, handlerFqn, HexanaIdeaUnknownFieldPolicy, invocation)

private fun standalone(id: ToolId, handler: String) =
    StandaloneToolBinding(id, StandaloneInvocationSpec.JsonArguments(handler), HexanaStandaloneUnknownFieldPolicy)

private fun standalone(id: ToolId, invocation: StandaloneInvocationSpec) =
    StandaloneToolBinding(id, invocation, HexanaStandaloneUnknownFieldPolicy)

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
    ideaTyped(
        ListFunctionsToolId,
        IdeaTypedInvocationSpec(
            "ListFunctionsIdeaRequest",
            pathSource(ListFunctionsInput.fieldRef("path")),
            listOf(ListFunctionsInput.fieldRef("offset"), ListFunctionsInput.fieldRef("limit")),
        ),
        "org.jetbrains.hexana.mcp.query.readFunctions",
    ),
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
    ideaTyped(
        ListDataSegmentsToolId,
        IdeaTypedInvocationSpec(
            "ListDataSegmentsIdeaRequest",
            pathSource(ListDataSegmentsInput.fieldRef("path")),
            listOf("segmentRange", "includeExports", "includeGlobals", "minByteLength").map(ListDataSegmentsInput::fieldRef),
            halfOpenRanges = listOf(ListDataSegmentsInput.fieldRef("segmentRange")),
            zeroWhenNull = listOf(ListDataSegmentsInput.fieldRef("minByteLength")),
        ),
        "org.jetbrains.hexana.mcp.query.readDataSegmentsByRange",
    ),
    idea(AuditDataPayloadPolicyToolId, ToolInputPattern.DataPayloadPolicyAudit, "org.jetbrains.hexana.mcp.query.auditDataPayloadPolicy"),
    idea(QueryArtifactsToolId, ToolInputPattern.ArtifactQuery, "org.jetbrains.hexana.mcp.query.readArtifactQuery"),
)

private val validatedHexanaIdeaToolBindings = HexanaToolCatalog.resolveBindings(
    HexanaIdeaToolBindings.map(IdeaToolBinding::id),
    HexanaIdeaToolBindings,
    "IDEA tool authority",
    IdeaToolBinding::id,
) { _, binding -> binding }

internal fun resolveIdeaTools(
    productRegistry: McpProductRegistry = HexanaMcpProductRegistry,
    binding: McpProductProfileBinding = HexanaIdeaMcpProfileBinding,
): List<IdeaTool> {
    val defaultProfile = productRegistry.resolve(binding)
    return HexanaToolCatalog.resolveBindings(
        defaultProfile.toolIds,
        validatedHexanaIdeaToolBindings.filter { it.id in defaultProfile.toolIds.toSet() },
        "IDEA default tool authority",
        IdeaToolBinding::id,
    ) { contract, binding ->
        binding.inputPattern?.let { validateInputPattern(contract.name, contract.input, it) }
        binding.typedInvocation?.validateFor(contract.input)
        IdeaTool(contract, binding)
    }
}

val HexanaIdeaTools: List<IdeaTool> by lazy(::resolveIdeaTools)

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
    standalone(ListExportedFunctionsToolId, "listExportedFunctions"), standalone(
        ListFunctionsToolId,
        StandaloneInvocationSpec.TypedRequest("listFunctions", "ListFunctionsRequest", "listFunctionsInputFailure"),
    ),
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
    standalone(ListDataToolId, "listData"), standalone(
        ListDataSegmentsToolId,
        StandaloneInvocationSpec.TypedRequest(
            "listDataSegments", "ListDataSegmentsRequest", "listDataSegmentsInputFailure",
            halfOpenRanges = listOf(ListDataSegmentsInput.fieldRef("segmentRange")),
        ),
    ),
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

private val HexanaMcpDefaultProfile = McpProfileSpec(ProfileId("default"), standaloneProfile(listOf(
    SummarizeBinaryToolId, SummarizeModuleToolId, ListEnabledFeaturesToolId, GetEntrypointPolicyToolId,
    ListRequiredProposalsToolId, DiffRequiredProposalsToolId, CheckRuntimeCompatibilityToolId,
    CheckComponentCompatibilityToolId, PlanAbiBindingRegenerationToolId, CheckMemoryContractToolId,
    AuditGlobalStatePolicyToolId, AuditLocalStackBudgetToolId, ListImportsToolId, ListExportsToolId,
    SearchExportsToolId, ListExportedFunctionsToolId, ListFunctionsToolId, ResolveFunctionSignatureToolId,
    DisassembleFunctionToolId, GetFunctionCallGraphToolId, SearchFunctionsByNameToolId,
    ResolveWasmFramesToolId, ResolveFunctionSymbolsToolId, ResolveSourceLocationsToolId, ListMemoryToolId,
    ListGlobalsToolId, ListElementSegmentsToolId, ListDataSegmentsToolId, AuditDataPayloadPolicyToolId,
), "standalone product hexana-mcp default profile"))

private val HexanaMcpAdvancedProfile = McpProfileSpec(ProfileId("advanced"), standaloneProfile(listOf(
    ListBinaryFunctionsToolId, SearchBinaryFunctionsToolId, QueryBinaryToolId,
    ResolveComponentDependenciesToolId, ListComponentSurfaceToolId, ListCustomSectionsToolId,
    ListCodeSectionOffsetsToolId, FunctionsForIndicesToolId, GetLocalsForFunctionsToolId,
    GetInstructionsForFunctionsToolId, GetMemoryForIndicesToolId, GetGlobalsForIndicesToolId,
    ListTypesToolId, ListValueTypesToolId, ListReferenceTypesToolId, GetTypesForIndicesToolId,
    ListDataToolId, QueryArtifactsToolId,
), "standalone product hexana-mcp advanced profile"))

private val HexanaMcpScriptingProfile = McpProfileSpec(ProfileId("scripting"), standaloneProfile(
    listOf(RunBinaryScriptToolId),
    "standalone product hexana-mcp scripting profile",
))

val HexanaMcpProductSpec = McpProductSpec(
    id = ProductId("hexana-mcp"),
    profiles = listOf(HexanaMcpDefaultProfile, HexanaMcpAdvancedProfile, HexanaMcpScriptingProfile),
    defaultProfileId = ProfileId("default"),
    published = true,
    membershipPolicy = McpProfileMembershipPolicy.DisjointAndComplete,
)

internal val FixtureMcpProductSpec = McpProductSpec(
    id = ProductId("fixture-mcp"),
    profiles = listOf(McpProfileSpec(ProfileId("default"), listOf(QueryArtifactsToolId))),
    defaultProfileId = ProfileId("default"),
    published = false,
)

val HexanaMcpProductRegistry = McpProductRegistry(listOf(HexanaMcpProductSpec, FixtureMcpProductSpec))

val HexanaIdeaMcpProfileBinding = McpProductProfileBinding(ProductId("hexana-mcp"), ProfileId("default"))
val HexanaMcpbProfileBinding = McpProductProfileBinding(ProductId("hexana-mcp"), ProfileId("default"))
val HexanaStandaloneRuntimeBinding = McpRuntimeBinding(HexanaMcpProductSpec.id)

val HexanaStandaloneAuthority = StandaloneToolAuthority(
    registrations = HexanaStandaloneTools,
    productRegistry = HexanaMcpProductRegistry,
    runtimeBinding = HexanaStandaloneRuntimeBinding,
)

fun mcpbTools(
    binding: McpProductProfileBinding = HexanaMcpbProfileBinding,
    productRegistry: McpProductRegistry = HexanaMcpProductRegistry,
): List<ToolSpec> {
    val profile = productRegistry.resolve(binding)
    return HexanaToolCatalog.resolve(profile.toolIds, "MCPB tools derived from ${binding.productId.value}.${binding.profileId.value}")
}

val HexanaMcpbTools: List<ToolSpec> = mcpbTools()

private fun todoCli(id: ToolId, command: String, group: ToolGroup) =
    cliToolBinding(id, command, group, ToolOutputPolicy.ToolDefined, unknownFieldPolicy = HexanaCliUnknownFieldPolicy)

val HexanaCliProductSpec = CliProductSpec(
    id = ProductId("hexana-cli"),
    executableName = "hexana-cli",
    description = "Hexana command-line tools for binary and WebAssembly inspection.",
    nativeArtifactKind = "standard-cli-native",
)

val HexanaCliScriptProductSpec = CliProductSpec(
    id = ProductId("hexana-cli-script"),
    executableName = "hexana-cli-script",
    description = "Hexana command-line scripting runtime.",
    nativeArtifactKind = "script-cli-native",
)

val HexanaCliProductRegistry = CliProductRegistry(
    listOf(HexanaCliProductSpec, HexanaCliScriptProductSpec),
)

private val CLI_TOOL_BINDINGS = listOf(
    cliToolBinding(
        SummarizeBinaryToolId, "summarize-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        products = setOf(HexanaCliProductSpec.id),
        inputModel = binaryCliInputModel(),
        unknownFieldPolicy = HexanaCliUnknownFieldPolicy,
    ),
    cliToolBinding(
        RunBinaryScriptToolId, "run-script", ToolGroup.Scripting, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        defaultOutput = OutputConversionCapability.RawText,
        products = setOf(HexanaCliScriptProductSpec.id),
        inputModel = toolInputModel {
            binarySource("binary", target = SourceTargetSpec.RootInput) {
                cli {
                    positional("binary")
                    stdin("--stdin")
                    base64("--bytes-base64")
                    base64Alias("--binary-base64")
                    autoStdin()
                }
            }
            param(name = "scriptPath", cliOption = "--script")
            param(name = "script", cliOption = "--script-text")
        },
        unknownFieldPolicy = HexanaCliUnknownFieldPolicy,
    ),
    cliToolBinding(
        RunScriptSuiteToolId, "test", ToolGroup.Scripting, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        defaultOutput = OutputConversionCapability.RawText,
        products = setOf(HexanaCliScriptProductSpec.id),
        inputModel = toolInputModel {
            param(name = "suiteFiles")
        },
        unknownFieldPolicy = HexanaCliUnknownFieldPolicy,
    ),
    cliToolBinding(
        WasmToWatToolId, "wasm-to-wat", ToolGroup.Converters, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        defaultOutput = OutputConversionCapability.RawText,
        products = setOf(HexanaCliProductSpec.id),
        inputModel = wasmCliInputModel(),
        unknownFieldPolicy = HexanaCliUnknownFieldPolicy,
    ),
    cliToolBinding(
        SummarizeModuleToolId, "summarize-module", ToolGroup.Module, ToolOutputPolicy.ToolDefined,
        executionStatus = CliExecutionStatus.Executable,
        defaultOutput = OutputConversionCapability.Pretty,
        products = setOf(HexanaCliProductSpec.id),
        inputModel = wasmCliInputModel(),
        generatedInputRequest = "SummarizeModuleCliInput",
        examples = listOf(
            CliUsageExample("Summarize a local WebAssembly module.", CliExampleSource.Path("wasm", "module.wasm")),
            CliUsageExample("Summarize a module supplied on standard input.", CliExampleSource.Stdin("wasm")),
        ),
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
    validateCliProducts(tools, HexanaCliProductRegistry)
}
