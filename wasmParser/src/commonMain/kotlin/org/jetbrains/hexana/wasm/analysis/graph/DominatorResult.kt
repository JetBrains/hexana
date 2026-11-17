package org.jetbrains.hexana.wasm.analysis.graph

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Sentinel for "this node has no immediate dominator" (the root itself and
 * unreachable nodes). Must never collide with a real node id: the call
 * graph's virtual root is id -1 and indirect-call hubs use ids below it, so
 * -1 as the sentinel silently dropped every direct child of the root from
 * the children CSR (the whole dominator tree rendered empty).
 */
internal const val NO_IDOM: Int = Int.MIN_VALUE

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
 * An `idom` slot holds the immediate dominator's node id (which can be the
 * root's -1), or [NO_IDOM] for the root itself and for unreachable nodes. On
 * JVM the buffer can be swapped to a memory-mapped file for large graphs,
 * same as [DirectedGraph].
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
        if (i < 0 || i >= idRange) return NO_IDOM
        val idx = buffer.readInt(i * 4)
        if (idx < 0) return NO_IDOM
        return buffer.readInt(idomPos + idx * 4)
    }

    operator fun get(nodeId: Int): Int? = slot(nodeId).takeIf { it != NO_IDOM }

    operator fun contains(nodeId: Int): Boolean = nodeId == rootId || slot(nodeId) != NO_IDOM
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
