package org.jetbrains.hexana.wasm.analysis.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DominatorTreeTest {

    @Test
    fun `build simple directed graph`() {
        val graph = DirectedGraphBuilder()
            .addNode(0, "A")
            .addNode(1, "B")
            .addNode(2, "C")
            .addEdge(0, 1)
            .addEdge(0, 2)
            .build(root = 0)

        assertEquals(3, graph.nodes.size)
        assertEquals(listOf(1, 2), graph.successors[0])
        assertEquals(listOf(0), graph.predecessors[1])
        assertEquals(listOf(0), graph.predecessors[2])
        assertTrue(graph.successors[1].isNullOrEmpty())
    }
}