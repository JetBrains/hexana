package org.jetbrains.hexana.wasm.analysis.graph

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.allocateCommonByteBuffer

data class GraphNode(val id: Int, val label: String)

/**
 * Directed graph with adjacency lists stored in CSR (Compressed Sparse Row) format,
 * backed by a single [CommonByteBuffer].
 *
 * Buffer layout (all ints little-endian):
 * ```
 * [idToIndex: idRange ints] [succOffset: n+1 ints] [succData: E1 ints]
 *                           [predOffset: n+1 ints] [predData: E2 ints]
 * ```
 * On JVM the buffer can be swapped to a memory-mapped file for large graphs.
 */
class DirectedGraph(
    val nodes: List<GraphNode>,
    val root: Int,
    internal val buffer: CommonByteBuffer,
    internal val idOffset: Int,
    internal val idRange: Int,
    @Suppress("unused") internal val nodeCount: Int,
    private val succOffsetPos: Int,
    private val succDataPos: Int,
    private val predOffsetPos: Int,
    private val predDataPos: Int
) {
    private val idToIndexPos = 0

    val successors: AdjacencyAccessor get() =
        AdjacencyAccessor(buffer, idToIndexPos, idRange, idOffset, succOffsetPos, succDataPos)

    val predecessors: AdjacencyAccessor get() =
        AdjacencyAccessor(buffer, idToIndexPos, idRange, idOffset, predOffsetPos, predDataPos)
}

/**
 * Provides `Map<Int, List<Int>>`-like read access over CSR data in a [CommonByteBuffer].
 */
class AdjacencyAccessor(
    private val buffer: CommonByteBuffer,
    private val idToIndexPos: Int,
    private val idRange: Int,
    private val idOffset: Int,
    private val offsetPos: Int,
    private val dataPos: Int
) {
    operator fun get(nodeId: Int): List<Int>? {
        val i = nodeId - idOffset
        if (i < 0 || i >= idRange) return null
        val idx = buffer.readInt(idToIndexPos + i * 4)
        if (idx < 0) return null
        val from = buffer.readInt(offsetPos + idx * 4)
        val to = buffer.readInt(offsetPos + (idx + 1) * 4)
        if (from == to) return null
        return BufferIntList(buffer, dataPos + from * 4, to - from)
    }
}

/** Read-only List<Int> view over ints in a [CommonByteBuffer]. */
private class BufferIntList(
    private val buffer: CommonByteBuffer,
    private val byteOffset: Int,
    override val size: Int
) : AbstractList<Int>() {
    override fun get(index: Int): Int = buffer.readInt(byteOffset + index * 4)
}

/** Read a little-endian int at absolute byte position (stateless — does not move cursor). */
private fun CommonByteBuffer.readInt(pos: Int): Int {
    val saved = position()
    position(pos)
    val v = getIntLE()
    position(saved)
    return v
}

class DirectedGraphBuilder(expectedNodes: Int = 16) {
    private val nodes = ArrayList<GraphNode>(expectedNodes)
    private val nodeIds = ArrayList<Int>(expectedNodes)
    private val nodeIdSet = HashSet<Int>(expectedNodes * 2)

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
            val buf = allocateCommonByteBuffer(4)
            buf.putIntLE(0)
            return DirectedGraph(emptyList(), root, buf, 0, 0, 0, 0, 0, 0, 0)
        }

        var minId = Int.MAX_VALUE
        var maxId = Int.MIN_VALUE
        for (i in 0 until n) {
            val id = nodeIds[i]
            if (id < minId) minId = id
            if (id > maxId) maxId = id
        }
        val idRange = maxId - minId + 1

        // Build id→index mapping
        val idToIndex = IntArray(idRange) { -1 }
        for (i in 0 until n) {
            idToIndex[nodeIds[i] - minId] = i
        }

        // Build CSR for successors and predecessors
        val succOffset = IntArray(n + 1)
        val succData = buildCsr(n, edgeFrom, edgeTo, edgeCount, idToIndex, minId, succOffset)
        val predOffset = IntArray(n + 1)
        val predData = buildCsr(n, edgeTo, edgeFrom, edgeCount, idToIndex, minId, predOffset)

        // Pack into a single CommonByteBuffer
        val totalInts = idRange + (n + 1) + succData.size + (n + 1) + predData.size
        val buf = allocateCommonByteBuffer(totalInts * 4)

        // idToIndex at position 0
        for (v in idToIndex) buf.putIntLE(v)

        // succOffset
        val succOffsetPos = buf.position()
        for (v in succOffset) buf.putIntLE(v)

        // succData
        val succDataPos = buf.position()
        for (v in succData) buf.putIntLE(v)

        // predOffset
        val predOffsetPos = buf.position()
        for (v in predOffset) buf.putIntLE(v)

        // predData
        val predDataPos = buf.position()
        for (v in predData) buf.putIntLE(v)

        return DirectedGraph(
            nodes.toList(), root, buf,
            minId, idRange, n,
            succOffsetPos, succDataPos,
            predOffsetPos, predDataPos
        )
    }

    private fun buildCsr(
        nodeCount: Int,
        fromArray: IntArray,
        toArray: IntArray,
        count: Int,
        idToIndex: IntArray,
        idOffset: Int,
        outOffset: IntArray
    ): IntArray {
        val counts = IntArray(nodeCount)
        for (i in 0 until count) {
            val fromIdx = fromArray[i] - idOffset
            if (fromIdx in idToIndex.indices) {
                val idx = idToIndex[fromIdx]
                if (idx >= 0) counts[idx]++
            }
        }

        for (i in 0 until nodeCount) {
            outOffset[i + 1] = outOffset[i] + counts[i]
        }
        val totalEdges = outOffset[nodeCount]

        val data = IntArray(totalEdges)
        val pos = IntArray(nodeCount)
        for (i in 0 until count) {
            val fromIdx = fromArray[i] - idOffset
            if (fromIdx in idToIndex.indices) {
                val idx = idToIndex[fromIdx]
                if (idx >= 0) {
                    data[outOffset[idx] + pos[idx]] = toArray[i]
                    pos[idx]++
                }
            }
        }
        return data
    }
}