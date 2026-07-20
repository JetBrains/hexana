package org.jetbrains.hexana.mcp.tools

/** Canonical semantic contract catalog with validated, order-preserving ID resolution. */
class ToolCatalog(contracts: List<ToolSpec>) {
    val contracts: List<ToolSpec> = contracts.toList()
    private val byId: Map<ToolId, ToolSpec>

    init {
        val duplicateIds = this.contracts.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) { "Duplicate tool contract IDs: ${duplicateIds.joinToString()}" }
        byId = this.contracts.associateBy { it.id }
    }

    fun resolve(ids: List<ToolId>, owner: String): List<ToolSpec> {
        val duplicates = ids.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "$owner contains duplicate tool IDs: ${duplicates.joinToString()}" }
        val unknown = ids.filterNot(byId::containsKey)
        require(unknown.isEmpty()) { "$owner references unknown tool IDs: ${unknown.joinToString()}" }
        return ids.map(byId::getValue)
    }
}
