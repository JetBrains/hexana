package org.jetbrains.hexana.wasm.analysis.graph

data class GraphNode(val id: Int, val label: String)

class DirectedGraph(
    val nodes: List<GraphNode>,
    val successors: Map<Int, List<Int>>,
    val predecessors: Map<Int, List<Int>>,
    val root: Int
)

class DirectedGraphBuilder {
    private val nodes = mutableListOf<GraphNode>()
    private val nodeIds = mutableSetOf<Int>()
    private val edges = mutableListOf<Pair<Int, Int>>()

    fun addNode(id: Int, label: String): DirectedGraphBuilder {
        if (nodeIds.add(id)) {
            nodes.add(GraphNode(id, label))
        }
        return this
    }

    fun addEdge(from: Int, to: Int): DirectedGraphBuilder {
        edges.add(from to to)
        return this
    }

    fun build(root: Int): DirectedGraph {
        val successors = mutableMapOf<Int, MutableList<Int>>()
        val predecessors = mutableMapOf<Int, MutableList<Int>>()

        for ((from, to) in edges) {
            successors.getOrPut(from) { mutableListOf() }.add(to)
            predecessors.getOrPut(to) { mutableListOf() }.add(from)
        }

        return DirectedGraph(nodes.toList(), successors, predecessors, root)
    }
}