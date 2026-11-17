package org.jetbrains.hexana.mcp.tools

/** Canonical semantic contract catalog with validated, canonical-order ID resolution. */
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
        val selected = ids.toSet()
        return contracts.filter { it.id in selected }
    }

    fun <B, R> resolveBindings(
        ids: List<ToolId>,
        bindings: List<B>,
        owner: String,
        bindingId: (B) -> ToolId,
        combine: (ToolSpec, B) -> R,
    ): List<R> {
        val contracts = resolve(ids, owner)
        val duplicateBindings = bindings.groupBy(bindingId).filterValues { it.size > 1 }.keys
        require(duplicateBindings.isEmpty()) {
            "$owner contains duplicate bindings: ${duplicateBindings.joinToString()}"
        }
        val knownIds = this.contracts.mapTo(mutableSetOf()) { it.id }
        val unknownBindings = bindings.map(bindingId).filterNot(knownIds::contains)
        require(unknownBindings.isEmpty()) {
            "$owner contains bindings for unknown tool IDs: ${unknownBindings.joinToString()}"
        }
        val bindingsById = bindings.associateBy(bindingId)
        val missingBindings = contracts.map { it.id }.filterNot(bindingsById::containsKey)
        require(missingBindings.isEmpty()) {
            "$owner is missing bindings for tool IDs: ${missingBindings.joinToString()}"
        }
        val selectedIds = contracts.mapTo(mutableSetOf()) { it.id }
        val unselectedBindings = bindingsById.keys - selectedIds
        require(unselectedBindings.isEmpty()) {
            "$owner contains unselected bindings for tool IDs: ${unselectedBindings.joinToString()}"
        }
        return contracts.map { contract -> combine(contract, bindingsById.getValue(contract.id)) }
    }
}
