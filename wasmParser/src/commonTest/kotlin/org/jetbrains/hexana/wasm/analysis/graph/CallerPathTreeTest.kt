package org.jetbrains.hexana.wasm.analysis.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallerPathTreeTest {

    private fun buildTree(
        rootId: Int,
        nodes: List<Pair<Int, String>>,
        edges: List<Pair<Int, Int>>,
    ): CallerPathTree {
        val builder = DirectedGraphBuilder(nodes.size)
        for ((id, label) in nodes) builder.addNode(id, label)
        for ((from, to) in edges) builder.addEdge(from, to)
        val graph = builder.build(root = rootId)
        return CallerPathTree(graph)
    }

    @Test
    fun `callers returns direct predecessors excluding virtual root`() {
        // root(0) -> A(1) -> B(2) -> C(3)
        val tree = buildTree(0, listOf(0 to "root", 1 to "A", 2 to "B", 3 to "C"),
            listOf(0 to 1, 1 to 2, 2 to 3))
        assertEquals(listOf(2), tree.callers(3))
        assertEquals(listOf(1), tree.callers(2))
        assertEquals(emptyList(), tree.callers(1)) // root excluded
    }

    @Test
    fun `diamond has multiple callers`() {
        // root(0) -> A(1) -> C(3), root(0) -> B(2) -> C(3)
        val tree = buildTree(0, listOf(0 to "root", 1 to "A", 2 to "B", 3 to "C"),
            listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3))
        val callers = tree.callers(3)
        assertEquals(setOf(1, 2), callers.toSet())
    }

    @Test
    fun `isRoot identifies direct successors of graph root`() {
        // root(0) -> A(1) -> B(2)
        val tree = buildTree(0, listOf(0 to "root", 1 to "A", 2 to "B"),
            listOf(0 to 1, 1 to 2))
        assertTrue(tree.isRoot(1))
        assertFalse(tree.isRoot(2))
        assertFalse(tree.isRoot(0))
    }

    @Test
    fun `callers of isolated node returns empty`() {
        // root(0) -> A(1), B(2) disconnected
        val tree = buildTree(0, listOf(0 to "root", 1 to "A", 2 to "B"),
            listOf(0 to 1))
        assertEquals(emptyList(), tree.callers(2))
    }

    @Test
    fun `callers with cycle does not stack overflow`() {
        // root(0) -> A(1) -> B(2) -> A(1)
        val tree = buildTree(0, listOf(0 to "root", 1 to "A", 2 to "B"),
            listOf(0 to 1, 1 to 2, 2 to 1))
        assertEquals(listOf(2), tree.callers(1))
        assertEquals(listOf(1), tree.callers(2))
    }

    @Test
    fun `callerCount returns predecessor count excluding root`() {
        // root(0) -> A(1) -> C(3), root(0) -> B(2) -> C(3)
        val tree = buildTree(0, listOf(0 to "root", 1 to "A", 2 to "B", 3 to "C"),
            listOf(0 to 1, 0 to 2, 1 to 3, 2 to 3))
        assertEquals(2, tree.callerCount(3))
        assertEquals(0, tree.callerCount(1)) // root excluded
        assertEquals(0, tree.callerCount(2)) // root excluded
    }
}