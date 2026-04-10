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

    @Test
    fun `linear chain dominators`() {
        // A -> B -> C -> D
        val graph = DirectedGraphBuilder()
            .addNode(0, "A").addNode(1, "B").addNode(2, "C").addNode(3, "D")
            .addEdge(0, 1).addEdge(1, 2).addEdge(2, 3)
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        assertEquals(null, result.idom[0])
        assertEquals(0, result.idom[1])
        assertEquals(1, result.idom[2])
        assertEquals(2, result.idom[3])
    }

    @Test
    fun `diamond dominators`() {
        //     A
        //    / \
        //   B   C
        //    \ /
        //     D
        val graph = DirectedGraphBuilder()
            .addNode(0, "A").addNode(1, "B").addNode(2, "C").addNode(3, "D")
            .addEdge(0, 1).addEdge(0, 2).addEdge(1, 3).addEdge(2, 3)
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        assertEquals(null, result.idom[0])
        assertEquals(0, result.idom[1])
        assertEquals(0, result.idom[2])
        assertEquals(0, result.idom[3])  // D's idom is A (not B or C)
    }

    @Test
    fun `loop back-edge dominators`() {
        // A -> B -> C -> B (back edge)
        //           |
        //           v
        //           D
        val graph = DirectedGraphBuilder()
            .addNode(0, "A").addNode(1, "B").addNode(2, "C").addNode(3, "D")
            .addEdge(0, 1).addEdge(1, 2).addEdge(2, 1).addEdge(2, 3)
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        assertEquals(null, result.idom[0])
        assertEquals(0, result.idom[1])
        assertEquals(1, result.idom[2])
        assertEquals(2, result.idom[3])
    }

    @Test
    fun `single node graph`() {
        val graph = DirectedGraphBuilder()
            .addNode(0, "A")
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        assertEquals(null, result.idom[0])
        assertTrue(result.children[0].isNullOrEmpty())
    }

    @Test
    fun `unreachable nodes excluded`() {
        // A -> B, C is disconnected
        val graph = DirectedGraphBuilder()
            .addNode(0, "A").addNode(1, "B").addNode(2, "C")
            .addEdge(0, 1)
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        assertEquals(null, result.idom[0])
        assertEquals(0, result.idom[1])
        assertTrue(2 !in result.idom)
        assertEquals(setOf(2), result.unreachable)
    }

    @Test
    fun `lengauer-tarjan paper example`() {
        // The classic example from Lengauer & Tarjan 1979, Fig. 1
        // R=0, A=1, B=2, C=3, D=4, E=5, F=6, G=7, H=8, I=9, J=10, K=11, L=12
        val graph = DirectedGraphBuilder()
            .addNode(0, "R").addNode(1, "A").addNode(2, "B").addNode(3, "C")
            .addNode(4, "D").addNode(5, "E").addNode(6, "F").addNode(7, "G")
            .addNode(8, "H").addNode(9, "I").addNode(10, "J").addNode(11, "K")
            .addNode(12, "L")
            .addEdge(0, 1).addEdge(0, 2).addEdge(0, 3)
            .addEdge(1, 4)
            .addEdge(2, 1).addEdge(2, 4).addEdge(2, 5)
            .addEdge(3, 6).addEdge(3, 7)
            .addEdge(4, 12)
            .addEdge(5, 8)
            .addEdge(6, 9)
            .addEdge(7, 9).addEdge(7, 10)
            .addEdge(8, 5).addEdge(8, 11)
            .addEdge(9, 11)
            .addEdge(10, 9)
            .addEdge(11, 0).addEdge(11, 9)
            .addEdge(12, 8)
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        assertEquals(null, result.idom[0])   // R is root
        assertEquals(0, result.idom[1])      // A: idom = R
        assertEquals(0, result.idom[2])      // B: idom = R
        assertEquals(0, result.idom[3])      // C: idom = R
        assertEquals(0, result.idom[4])      // D: idom = R
        assertEquals(0, result.idom[5])      // E: idom = R
        assertEquals(3, result.idom[6])      // F: idom = C
        assertEquals(3, result.idom[7])      // G: idom = C
        assertEquals(0, result.idom[8])      // H: idom = R
        assertEquals(0, result.idom[9])      // I: idom = R
        assertEquals(7, result.idom[10])     // J: idom = G
        assertEquals(0, result.idom[11])     // K: idom = R
        assertEquals(4, result.idom[12])     // L: idom = D
    }

    @Test
    fun `dominates query`() {
        // A -> B -> C -> D
        val graph = DirectedGraphBuilder()
            .addNode(0, "A").addNode(1, "B").addNode(2, "C").addNode(3, "D")
            .addEdge(0, 1).addEdge(1, 2).addEdge(2, 3)
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        assertTrue(result.dominates(0, 3))
        assertTrue(result.dominates(1, 3))
        assertTrue(result.dominates(0, 0))
        assertTrue(!result.dominates(3, 0))
        assertTrue(!result.dominates(2, 1))
    }

    @Test
    fun `dominator tree children`() {
        // A -> B -> C, A -> D
        val graph = DirectedGraphBuilder()
            .addNode(0, "A").addNode(1, "B").addNode(2, "C").addNode(3, "D")
            .addEdge(0, 1).addEdge(1, 2).addEdge(0, 3)
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        assertEquals(setOf(1, 3), result.children[0]?.toSet())
        assertEquals(listOf(2), result.children[1])
        assertTrue(result.children[2].isNullOrEmpty())
    }

    @Test
    fun `dominance frontier for diamond`() {
        //     A
        //    / \
        //   B   C
        //    \ /
        //     D
        val graph = DirectedGraphBuilder()
            .addNode(0, "A").addNode(1, "B").addNode(2, "C").addNode(3, "D")
            .addEdge(0, 1).addEdge(0, 2).addEdge(1, 3).addEdge(2, 3)
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        // D is the join point — B and C both have D in their dominance frontier
        assertEquals(setOf(3), result.dominanceFrontier[1])
        assertEquals(setOf(3), result.dominanceFrontier[2])
        // A and D have empty frontiers
        assertTrue(result.dominanceFrontier[0].isNullOrEmpty())
        assertTrue(result.dominanceFrontier[3].isNullOrEmpty())
    }

    @Test
    fun `dominance frontier for loop`() {
        // A -> B -> C -> B (back edge), C -> D
        val graph = DirectedGraphBuilder()
            .addNode(0, "A").addNode(1, "B").addNode(2, "C").addNode(3, "D")
            .addEdge(0, 1).addEdge(1, 2).addEdge(2, 1).addEdge(2, 3)
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        // B is the loop header — C has B in its frontier (back edge target)
        assertEquals(setOf(1), result.dominanceFrontier[2])
        // B has itself in its frontier (it's a loop header with a back edge)
        assertEquals(setOf(1), result.dominanceFrontier[1])
    }

    @Test
    fun `subtree returns full subtree`() {
        // A -> B -> C, A -> D
        val graph = DirectedGraphBuilder()
            .addNode(0, "A").addNode(1, "B").addNode(2, "C").addNode(3, "D")
            .addEdge(0, 1).addEdge(1, 2).addEdge(0, 3)
            .build(root = 0)

        val result = DominatorTree.compute(graph)

        // Subtree of A (root) is everything
        assertEquals(setOf(0, 1, 2, 3), result.subtree(0).toSet())
        // Subtree of B includes B and C
        assertEquals(setOf(1, 2), result.subtree(1).toSet())
        // Subtree of C is just C (leaf)
        assertEquals(listOf(2), result.subtree(2))
        // Subtree of D is just D (leaf)
        assertEquals(listOf(3), result.subtree(3))
    }
}