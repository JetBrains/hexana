package org.jetbrains.hexana.mcp.tools

private val HEXANA_TOOL_ORDER = listOf(
    "query_artifacts",
)

val HexanaTools: List<ToolSpec> = orderedTools(
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
