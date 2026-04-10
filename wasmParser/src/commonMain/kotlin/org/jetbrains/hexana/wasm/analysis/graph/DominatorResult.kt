package org.jetbrains.hexana.wasm.analysis.graph

class DominatorResult(
    val idom: Map<Int, Int?>,
    val children: Map<Int, List<Int>>,
    val dominanceFrontier: Map<Int, Set<Int>>,
    val unreachable: Set<Int>
) {
    fun dominates(a: Int, b: Int): Boolean {
        if (a == b) return true
        var current: Int? = b
        while (current != null) {
            val parent = idom[current] ?: return false
            if (parent == a) return true
            current = parent
        }
        return false
    }

    fun subtree(nodeId: Int): List<Int> {
        val result = mutableListOf<Int>()
        fun collect(id: Int) {
            result.add(id)
            children[id]?.forEach { collect(it) }
        }
        collect(nodeId)
        return result
    }
}
