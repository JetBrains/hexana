package org.jetbrains.hexana.wasm.analysis.graph

/**
 * Provides lazy caller enumeration for building "paths from roots" trees.
 * Each query is a single O(1) CSR predecessors lookup.
 */
class CallerPathTree(private val graph: DirectedGraph) {
    private val rootSuccessors: Set<Int> = graph.successors[graph.root]?.toSet() ?: emptySet()

    /** Direct callers of [nodeId], excluding the graph root. */
    fun callers(nodeId: Int): List<Int> {
        val preds = graph.predecessors[nodeId] ?: return emptyList()
        return preds.filter { it != graph.root }
    }

    /** Number of direct callers of [nodeId], excluding the graph root. */
    fun callerCount(nodeId: Int): Int {
        val preds = graph.predecessors[nodeId] ?: return 0
        var count = preds.size
        if (preds.contains(graph.root)) count--
        return count
    }

    /** True if [nodeId] is a direct successor of the graph root (export, start, elem). */
    fun isRoot(nodeId: Int): Boolean = nodeId in rootSuccessors
}