package org.jetbrains.hexana.wasm.analysis.graph

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Dominator tree stored in a single [CommonByteBuffer], mirroring the CSR layout
 * of [DirectedGraph] so results for large call graphs stay off the object heap.
 *
 * Buffer layout (all ints little-endian):
 * ```
 * [idToIndex: idRange ints] [idom: n ints]
 * [childOffset: n+1 ints] [childData: E ints]
 * [unreachable: u ints, sorted ascending]
 * ```
 * An `idom` slot holds the immediate dominator's node id, or -1 for the root and
 * for unreachable nodes. On JVM the buffer can be swapped to a memory-mapped
 * file for large graphs, same as [DirectedGraph].
 */
class DominatorResult internal constructor(
    buffer: CommonByteBuffer,
    idOffset: Int,
    idRange: Int,
    idomPos: Int,
    childOffsetPos: Int,
    childDataPos: Int,
    unreachablePos: Int,
    unreachableCount: Int,
    rootId: Int
) {
    /** `Map<Int, Int?>`-like view: `idom[nodeId]` is the immediate dominator, null for the root. */
    val idom: IdomAccessor = IdomAccessor(buffer, idOffset, idRange, idomPos, rootId)

    /** `Map<Int, List<Int>>`-like view of dominator-tree children, keyed by node id. */
    val children: AdjacencyAccessor =
        AdjacencyAccessor(buffer, 0, idRange, idOffset, childOffsetPos, childDataPos)

    /** Node ids not reachable from the root, sorted ascending. */
    val unreachable: Set<Int> = BufferIntSet(buffer, unreachablePos, unreachableCount)

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

/**
 * `Map<Int, Int?>`-like read access to idom slots: `get` returns null for the root
 * and for nodes outside the dominator tree; `contains` is true for nodes in the tree
 * (the root included), matching the old `Map<Int, Int?>` semantics.
 */
class IdomAccessor internal constructor(
    private val buffer: CommonByteBuffer,
    private val idOffset: Int,
    private val idRange: Int,
    private val idomPos: Int,
    private val rootId: Int
) {
    private fun slot(nodeId: Int): Int {
        val i = nodeId - idOffset
        if (i < 0 || i >= idRange) return -1
        val idx = buffer.readInt(i * 4)
        if (idx < 0) return -1
        return buffer.readInt(idomPos + idx * 4)
    }

    operator fun get(nodeId: Int): Int? = slot(nodeId).takeIf { it != -1 }

    operator fun contains(nodeId: Int): Boolean = nodeId == rootId || slot(nodeId) != -1
}

/** Read-only sorted Set<Int> view over ints in a [CommonByteBuffer]. */
private class BufferIntSet(
    private val buffer: CommonByteBuffer,
    private val byteOffset: Int,
    override val size: Int
) : AbstractSet<Int>() {
    override fun contains(element: Int): Boolean {
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = buffer.readInt(byteOffset + mid * 4)
            when {
                v < element -> lo = mid + 1
                v > element -> hi = mid - 1
                else -> return true
            }
        }
        return false
    }

    override fun iterator(): Iterator<Int> = object : Iterator<Int> {
        private var i = 0
        override fun hasNext() = i < size
        override fun next(): Int {
            if (i >= size) throw NoSuchElementException()
            return buffer.readInt(byteOffset + i++ * 4)
        }
    }
}
