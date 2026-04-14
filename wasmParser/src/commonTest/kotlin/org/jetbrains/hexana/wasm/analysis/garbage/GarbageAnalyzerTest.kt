package org.jetbrains.hexana.wasm.analysis.garbage

import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphNode
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphResult
import org.jetbrains.hexana.wasm.analysis.graph.DominatorTree
import org.jetbrains.hexana.wasm.analysis.graph.DirectedGraphBuilder
import org.jetbrains.hexana.wasm.sections.CodeEntryOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GarbageAnalyzerTest {

    /**
     * Helper: builds a call graph and runs garbage analysis.
     *
     * @param nodes list of (nodeId, CallGraphNode) — first node is the virtual root
     * @param edges list of (from, to)
     * @param codeOffsets lightweight code offset entries for defined functions
     * @param importFuncCount number of import functions in the function index space
     */
    private fun analyze(
        nodes: List<Pair<Int, CallGraphNode>>,
        edges: List<Pair<Int, Int>>,
        codeOffsets: List<CodeEntryOffset> = emptyList(),
        importFuncCount: Int = 0,
    ): GarbageResult {
        val builder = DirectedGraphBuilder(nodes.size)
        for ((id, _) in nodes) builder.addNode(id, nodes.first { it.first == id }.second.name)
        for ((from, to) in edges) builder.addEdge(from, to)
        val graph = builder.build(root = CallGraphNode.VIRTUAL_ROOT_ID)

        val nodeMap = nodes.associate { (id, node) -> id to node }
        val cgr = CallGraphResult(graph, nodeMap)
        val dom = DominatorTree.compute(graph)

        return GarbageAnalyzer(cgr, dom, codeOffsets, importFuncCount).analyze()
    }

    @Test
    fun `all functions reachable — empty result`() {
        // root -> A -> B
        val nodes = listOf(
            -1 to CallGraphNode.VirtualRoot(),
            0 to CallGraphNode.DefinedFunction(0, "A", funcIndex = 0),
            1 to CallGraphNode.DefinedFunction(1, "B", funcIndex = 1),
        )
        val edges = listOf(-1 to 0, 0 to 1)
        val offsets = listOf(CodeEntryOffset(100, 50), CodeEntryOffset(200, 30))

        val result = analyze(nodes, edges, offsets, importFuncCount = 0)

        assertTrue(result.entries.isEmpty())
        assertEquals(0L, result.totalWastedBytes)
        assertEquals(80L, result.codeSectionTotalBytes)
    }

    @Test
    fun `single unreachable function with no callers`() {
        // root -> A, B is disconnected
        val nodes = listOf(
            -1 to CallGraphNode.VirtualRoot(),
            0 to CallGraphNode.DefinedFunction(0, "A", funcIndex = 0),
            1 to CallGraphNode.DefinedFunction(1, "B", funcIndex = 1),
        )
        val edges = listOf(-1 to 0)
        val offsets = listOf(CodeEntryOffset(100, 50), CodeEntryOffset(200, 30))

        val result = analyze(nodes, edges, offsets, importFuncCount = 0)

        assertEquals(1, result.entries.size)
        val entry = result.entries[0]
        assertEquals("B", entry.name)
        assertEquals(1, entry.funcIndex)
        assertEquals(30, entry.size)
        assertEquals(GarbageReason.NO_CALLERS, entry.reason)
        assertEquals(30L, result.totalWastedBytes)
    }

    @Test
    fun `transitive unreachable — callers also unreachable`() {
        // root -> A, B -> C (B and C both unreachable)
        val nodes = listOf(
            -1 to CallGraphNode.VirtualRoot(),
            0 to CallGraphNode.DefinedFunction(0, "A", funcIndex = 0),
            1 to CallGraphNode.DefinedFunction(1, "B", funcIndex = 1),
            2 to CallGraphNode.DefinedFunction(2, "C", funcIndex = 2),
        )
        val edges = listOf(-1 to 0, 1 to 2)
        val offsets = listOf(
            CodeEntryOffset(100, 50),
            CodeEntryOffset(200, 40),
            CodeEntryOffset(300, 20),
        )

        val result = analyze(nodes, edges, offsets, importFuncCount = 0)

        assertEquals(2, result.entries.size)
        // Sorted by size descending: B(40) then C(20)
        assertEquals("B", result.entries[0].name)
        assertEquals(GarbageReason.NO_CALLERS, result.entries[0].reason)
        assertEquals("C", result.entries[1].name)
        assertEquals(GarbageReason.CALLERS_UNREACHABLE, result.entries[1].reason)
        assertEquals(60L, result.totalWastedBytes)
    }

    @Test
    fun `unreachable import function`() {
        // root -> A (defined), B (import, unreachable)
        val nodes = listOf(
            -1 to CallGraphNode.VirtualRoot(),
            0 to CallGraphNode.ImportedFunction(0, "importFn", module = "env"),
            1 to CallGraphNode.DefinedFunction(1, "A", funcIndex = 1),
        )
        val edges = listOf(-1 to 1)
        val offsets = listOf(CodeEntryOffset(100, 50))

        val result = analyze(nodes, edges, offsets, importFuncCount = 1)

        assertEquals(1, result.entries.size)
        val entry = result.entries[0]
        assertEquals("importFn", entry.name)
        assertEquals("env", entry.module)
        assertEquals(0, entry.size)
        assertEquals(GarbageReason.NO_CALLERS, entry.reason)
        assertEquals(0L, result.totalWastedBytes)
    }

    @Test
    fun `mixed reachable and unreachable`() {
        // root -> A -> B, C is disconnected, D -> C (D also disconnected)
        val nodes = listOf(
            -1 to CallGraphNode.VirtualRoot(),
            0 to CallGraphNode.DefinedFunction(0, "A", funcIndex = 0),
            1 to CallGraphNode.DefinedFunction(1, "B", funcIndex = 1),
            2 to CallGraphNode.DefinedFunction(2, "C", funcIndex = 2),
            3 to CallGraphNode.DefinedFunction(3, "D", funcIndex = 3),
        )
        val edges = listOf(-1 to 0, 0 to 1, 3 to 2)
        val offsets = listOf(
            CodeEntryOffset(100, 100),
            CodeEntryOffset(200, 80),
            CodeEntryOffset(300, 60),
            CodeEntryOffset(400, 40),
        )

        val result = analyze(nodes, edges, offsets, importFuncCount = 0)

        assertEquals(2, result.entries.size)
        val names = result.entries.map { it.name }.toSet()
        assertEquals(setOf("C", "D"), names)
        // C has D as caller, D is also unreachable → CALLERS_UNREACHABLE
        val entryC = result.entries.first { it.name == "C" }
        assertEquals(GarbageReason.CALLERS_UNREACHABLE, entryC.reason)
        // D has no callers
        val entryD = result.entries.first { it.name == "D" }
        assertEquals(GarbageReason.NO_CALLERS, entryD.reason)
        assertEquals(100L, result.totalWastedBytes)
    }

    @Test
    fun `sort order is by size descending`() {
        // root only, three disconnected functions of different sizes
        val nodes = listOf(
            -1 to CallGraphNode.VirtualRoot(),
            0 to CallGraphNode.DefinedFunction(0, "Small", funcIndex = 0),
            1 to CallGraphNode.DefinedFunction(1, "Big", funcIndex = 1),
            2 to CallGraphNode.DefinedFunction(2, "Medium", funcIndex = 2),
        )
        val edges = emptyList<Pair<Int, Int>>()
        val offsets = listOf(
            CodeEntryOffset(100, 10),
            CodeEntryOffset(200, 100),
            CodeEntryOffset(300, 50),
        )

        val result = analyze(nodes, edges, offsets, importFuncCount = 0)

        assertEquals(3, result.entries.size)
        assertEquals("Big", result.entries[0].name)
        assertEquals("Medium", result.entries[1].name)
        assertEquals("Small", result.entries[2].name)
    }
}
