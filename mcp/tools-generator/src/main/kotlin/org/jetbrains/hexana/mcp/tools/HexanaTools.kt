package org.jetbrains.hexana.mcp.tools

private val HEXANA_TOOL_ORDER = listOf(
    "summarize_module",
    "list_custom_sections",
    "list_imports",
    "list_exports",
    "search_exports",
    "list_globals",
    "list_types",
    "list_memory",
    "list_element_segments",
    "list_functions",
    "list_code_section_offsets",
    "functions_for_indices",
    "resolve_function_signature",
    "disassemble_function",
    "search_functions_by_name",
    "get_globals_for_indices",
    "get_memory_for_indices",
    "get_types_for_indices",
    "get_locals_for_functions",
    "get_instructions_for_functions",
    "list_exported_functions",
    "list_data",
    "list_data_segments",
)

val HexanaTools: List<ToolSpec> = orderedTools(
    ModuleTools,
    ImportTools,
    ExportTools,
    GlobalTools,
    TypeTools,
    MemoryTools,
    ElementTools,
    FunctionTools,
    DataTools,
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
