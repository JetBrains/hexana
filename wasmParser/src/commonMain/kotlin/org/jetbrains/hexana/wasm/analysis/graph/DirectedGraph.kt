package org.jetbrains.hexana.wasm.analysis.graph

data class GraphNode(val id: Int, val label: String)

/**
 * Directed graph with adjacency lists stored in CSR (Compressed Sparse Row) format.
 *
 * Successors/predecessors are stored as two flat IntArrays + offset tables.
 * No boxing, no per-node ArrayList, cache-friendly sequential access.
 * Access pattern: successors of node `id` = `succData[succOffset[id] until succOffset[id+1]]`
 */
class DirectedGraph(
    val nodes: List<GraphNode>,
    idToIndex: IntArray,
    idOffset: Int,
    succOffset: IntArray,
    succData: IntArray,
    predOffset: IntArray,
    predData: IntArray,
    val root: Int
) {
    /** Map-like access for compatibility — returns empty list for unknown nodes. */
    val successors: AdjacencyAccessor = AdjacencyAccessor(idToIndex, idOffset, succOffset, succData)
    val predecessors: AdjacencyAccessor = AdjacencyAccessor(idToIndex, idOffset, predOffset, predData)
}

/**
 * Provides Map<Int, List<Int>>-like access over CSR arrays without boxing.
 */
class AdjacencyAccessor(
    private val idToIndex: IntArray,
    private val idOffset: Int,
    private val offset: IntArray,
    private val data: IntArray
) {
    operator fun get(nodeId: Int): List<Int>? {
        val idx = toIndex(nodeId) ?: return null
        val from = offset[idx]
        val to = offset[idx + 1]
        if (from == to) return null
        return CsrIntList(data, from, to)
    }

    private fun toIndex(nodeId: Int): Int? {
        val i = nodeId - idOffset
        if (i < 0 || i >= idToIndex.size) return null
        val idx = idToIndex[i]
        return if (idx >= 0) idx else null
    }
}

/**
 * Read-only List<Int> view over a range of an IntArray. Zero allocation on access.
 */
private class CsrIntList(
    private val data: IntArray,
    private val from: Int,
    private val to: Int
) : AbstractList<Int>() {
    override val size: Int get() = to - from
    override fun get(index: Int): Int = data[from + index]
}

class DirectedGraphBuilder(expectedNodes: Int = 16) {
    private val nodes = ArrayList<GraphNode>(expectedNodes)
    private val nodeIds = ArrayList<Int>(expectedNodes)
    private val nodeIdSet = HashSet<Int>(expectedNodes * 2)

    // Temporary edge storage: parallel int arrays (no boxing)
    private var edgeFrom = IntArray(expectedNodes * 4)
    private var edgeTo = IntArray(expectedNodes * 4)
    private var edgeCount = 0

    fun addNode(id: Int, label: String): DirectedGraphBuilder {
        if (nodeIdSet.add(id)) {
            nodeIds.add(id)
            nodes.add(GraphNode(id, label))
        }
        return this
    }

    fun addEdge(from: Int, to: Int): DirectedGraphBuilder {
        if (edgeCount == edgeFrom.size) {
            val newSize = edgeFrom.size * 2
            edgeFrom = edgeFrom.copyOf(newSize)
            edgeTo = edgeTo.copyOf(newSize)
        }
        edgeFrom[edgeCount] = from
        edgeTo[edgeCount] = to
        edgeCount++
        return this
    }

    fun build(root: Int): DirectedGraph {
        val n = nodeIds.size
        if (n == 0) {
            return DirectedGraph(
                emptyList(), IntArray(0), 0,
                IntArray(1), IntArray(0),
                IntArray(1), IntArray(0),
                root
            )
        }

        // Build id → index mapping using a direct-addressed array
        var minId = Int.MAX_VALUE
        var maxId = Int.MIN_VALUE
        for (i in 0 until n) {
            val id = nodeIds[i]
            if (id < minId) minId = id
            if (id > maxId) maxId = id
        }
        val idRange = maxId - minId + 1
        val idToIndex = IntArray(idRange) { -1 }
        for (i in 0 until n) {
            idToIndex[nodeIds[i] - minId] = i
        }

        // Build CSR for successors
        val (succOffset, succData) = buildCsr(n, edgeFrom, edgeTo, edgeCount, idToIndex, minId)
        // Build CSR for predecessors (swap from/to)
        val (predOffset, predData) = buildCsr(n, edgeTo, edgeFrom, edgeCount, idToIndex, minId)

        return DirectedGraph(
            nodes.toList(), idToIndex, minId,
            succOffset, succData,
            predOffset, predData,
            root
        )
    }

    private fun buildCsr(
        nodeCount: Int,
        fromArray: IntArray,
        toArray: IntArray,
        count: Int,
        idToIndex: IntArray,
        idOffset: Int
    ): Pair<IntArray, IntArray> {
        // Count edges per node
        val counts = IntArray(nodeCount)
        for (i in 0 until count) {
            val fromIdx = fromArray[i] - idOffset
            if (fromIdx in idToIndex.indices) {
                val idx = idToIndex[fromIdx]
                if (idx >= 0) counts[idx]++
            }
        }

        // Prefix sum → offsets
        val offset = IntArray(nodeCount + 1)
        for (i in 0 until nodeCount) {
            offset[i + 1] = offset[i] + counts[i]
        }
        val totalEdges = offset[nodeCount]

        // Fill data
        val data = IntArray(totalEdges)
        val pos = IntArray(nodeCount) // current write position per node
        for (i in 0 until count) {
            val fromIdx = fromArray[i] - idOffset
            if (fromIdx in idToIndex.indices) {
                val idx = idToIndex[fromIdx]
                if (idx >= 0) {
                    data[offset[idx] + pos[idx]] = toArray[i]
                    pos[idx]++
                }
            }
        }

        return offset to data
    }
}