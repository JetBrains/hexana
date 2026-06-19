package org.jetbrains.hexana.mcp.tools

private val HEXANA_TOOL_ORDER = listOf(
    "summarize_module",
    "list_enabled_features",
    "get_entrypoint_policy",
    "list_required_proposals",
    "diff_required_proposals",
    "check_runtime_compatibility",
    "resolve_component_dependencies",
    "list_component_surface",
    "check_component_compatibility",
    "plan_abi_binding_regeneration",
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
    "query_artifacts",
)

val HexanaTools: List<ToolSpec> = orderedTools(
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

private fun orderedTools(vararg groups: List<ToolSpec>): List<ToolSpec> {
    val tools = groups.flatMap { it }
    val byName = tools.groupBy { it.name }
    val duplicates = byName.filterValues { it.size > 1 }.keys
    require(duplicates.isEmpty()) { "Duplicate tool names: $duplicates" }

    val actualNames = byName.keys
    val expectedNames = HEXANA_TOOL_ORDER.toSet()
    val unknown = actualNames - expectedNames
    require(unknown.isEmpty()) { "Tool order is missing new tool names: $unknown" }

    val missing = expectedNames - actualNames
    require(missing.isEmpty()) { "Tool groups are missing tools from the canonical order: $missing" }

    return HEXANA_TOOL_ORDER.map { name -> byName.getValue(name).single() }
}
