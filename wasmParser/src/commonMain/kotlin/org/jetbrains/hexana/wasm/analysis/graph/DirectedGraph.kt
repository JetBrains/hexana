package org.jetbrains.hexana.wasm.analysis.graph

data class GraphNode(val id: Int, val label: String)

class DirectedGraph(
    val nodes: List<GraphNode>,
    val successors: Map<Int, List<Int>>,
    val predecessors: Map<Int, List<Int>>,
    val root: Int
)

class DirectedGraphBuilder(expectedNodes: Int = 16, expectedEdges: Int = 64) {
    private val nodes = ArrayList<GraphNode>(expectedNodes)
    private val nodeIds = HashSet<Int>(expectedNodes * 2)

    // Store edges as packed longs: high 32 bits = from, low 32 bits = to
    private var edgeArray = LongArray(expectedEdges)
    private var edgeCount = 0

    // Dedup set for edges (packed long)
    private val edgeSet = HashSet<Long>(expectedEdges * 2)

    fun addNode(id: Int, label: String): DirectedGraphBuilder {
        if (nodeIds.add(id)) {
            nodes.add(GraphNode(id, label))
        }
        return this
    }

    fun addEdge(from: Int, to: Int): DirectedGraphBuilder {
        val packed = (from.toLong() shl 32) or (to.toLong() and 0xFFFFFFFFL)
        if (edgeSet.add(packed)) {
            if (edgeCount == edgeArray.size) {
                edgeArray = edgeArray.copyOf(edgeArray.size * 2)
            }
            edgeArray[edgeCount++] = packed
        }
        return this
    }

    fun build(root: Int): DirectedGraph {
        val successors = HashMap<Int, MutableList<Int>>(nodeIds.size * 2)
        val predecessors = HashMap<Int, MutableList<Int>>(nodeIds.size * 2)

        for (i in 0 until edgeCount) {
            val packed = edgeArray[i]
            val from = (packed shr 32).toInt()
            val to = (packed and 0xFFFFFFFFL).toInt()
            successors.getOrPut(from) { ArrayList(4) }.add(to)
            predecessors.getOrPut(to) { ArrayList(4) }.add(from)
        }

        return DirectedGraph(nodes.toList(), successors, predecessors, root)
    }
}