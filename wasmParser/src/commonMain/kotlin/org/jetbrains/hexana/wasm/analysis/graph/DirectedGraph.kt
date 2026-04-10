package org.jetbrains.hexana.wasm.analysis.graph

data class GraphNode(val id: Int, val label: String)

class DirectedGraph(
    val nodes: List<GraphNode>,
    val successors: Map<Int, List<Int>>,
    val predecessors: Map<Int, List<Int>>,
    val root: Int
)

class DirectedGraphBuilder(expectedNodes: Int = 16) {
    private val nodes = ArrayList<GraphNode>(expectedNodes)
    private val nodeIds = HashSet<Int>(expectedNodes * 2)
    private val successors = HashMap<Int, ArrayList<Int>>(expectedNodes)
    private val predecessors = HashMap<Int, ArrayList<Int>>(expectedNodes)

    fun addNode(id: Int, label: String): DirectedGraphBuilder {
        if (nodeIds.add(id)) {
            nodes.add(GraphNode(id, label))
        }
        return this
    }

    fun addEdge(from: Int, to: Int): DirectedGraphBuilder {
        successors.getOrPut(from) { ArrayList(4) }.add(to)
        predecessors.getOrPut(to) { ArrayList(4) }.add(from)
        return this
    }

    fun build(root: Int): DirectedGraph {
        return DirectedGraph(
            nodes.toList(),
            successors,
            predecessors,
            root
        )
    }
}