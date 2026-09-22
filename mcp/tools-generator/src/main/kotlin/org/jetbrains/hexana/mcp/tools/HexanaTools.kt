package org.jetbrains.hexana.mcp.tools

private val HEXANA_TOOL_ORDER = listOf(
    SummarizeBinaryToolId, ListBinaryFunctionsToolId, SearchBinaryFunctionsToolId, QueryBinaryToolId,
    RunBinaryScriptToolId, RunScriptSuiteToolId, WasmToWatToolId, SummarizeModuleToolId, ListEnabledFeaturesToolId,
    GetEntrypointPolicyToolId, ListRequiredProposalsToolId, DiffRequiredProposalsToolId,
    CheckRuntimeCompatibilityToolId, CheckComponentCompatibilityToolId, ResolveComponentDependenciesToolId,
    ListComponentSurfaceToolId, PlanAbiBindingRegenerationToolId, CheckMemoryContractToolId,
    AuditGlobalStatePolicyToolId, AuditLocalStackBudgetToolId, ListCustomSectionsToolId, ListImportsToolId,
    ListExportsToolId, SearchExportsToolId, ListExportedFunctionsToolId, ListFunctionsToolId,
    ListCodeSectionOffsetsToolId, FunctionsForIndicesToolId, ResolveFunctionSignatureToolId,
    DisassembleFunctionToolId, GetFunctionCallGraphToolId, SearchFunctionsByNameToolId, ResolveWasmFramesToolId,
    ResolveFunctionSymbolsToolId, ResolveSourceLocationsToolId, GetLocalsForFunctionsToolId,
    GetInstructionsForFunctionsToolId, ListMemoryToolId, GetMemoryForIndicesToolId, ListGlobalsToolId,
    GetGlobalsForIndicesToolId, ListTypesToolId, ListValueTypesToolId, ListReferenceTypesToolId,
    GetTypesForIndicesToolId, ListElementSegmentsToolId, ListDataToolId, ListDataSegmentsToolId,
    AuditDataPayloadPolicyToolId, QueryArtifactsToolId,
)

val HexanaToolSchemaCompatibility = ToolCompatibilitySpec(
    schemaVersion = 3,
    policy = "Tool schemas are append-compatible within a version; breaking changes to names, required inputs, defaults, bounds, output keys, or descriptions require a schema version bump.",
)

val HexanaTools: List<ToolSpec> = orderedTools(
    BinaryTools,
    ScriptingTools,
    ConverterTools,
    ModuleTools,
    EntrypointPolicyTools,
    RuntimeTools,
    ComponentTools,
    ImportTools,
    ExportTools,
    FunctionTools,
    MemoryTools,
    GlobalTools,
    TypeTools,
    ElementTools,
    DataTools,
    ArtifactQueryTools,
)

val HexanaToolCatalog: ToolCatalog = ToolCatalog(HexanaTools)

private fun orderedTools(vararg groups: List<ToolSpec>): List<ToolSpec> {
    val tools = groups.flatMap { it }
    validateToolCollection(tools)
    val byId = tools.groupBy { it.id }

    val actualIds = byId.keys
    val expectedIds = HEXANA_TOOL_ORDER.toSet()
    val unknown = actualIds - expectedIds
    require(unknown.isEmpty()) { "Tool order is missing new tool IDs: $unknown" }

    val missing = expectedIds - actualIds
    require(missing.isEmpty()) { "Tool groups are missing tools from the canonical order: $missing" }

    return HEXANA_TOOL_ORDER.map { id -> byId.getValue(id).single() }
}
