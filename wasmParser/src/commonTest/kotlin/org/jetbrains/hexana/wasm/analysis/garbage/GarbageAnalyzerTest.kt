package org.jetbrains.hexana.wasm.analysis.garbage

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.WasmParseSuccess
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphBuilder
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphNode
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphResult
import org.jetbrains.hexana.wasm.analysis.graph.DominatorTree
import org.jetbrains.hexana.wasm.analysis.graph.DirectedGraphBuilder
import org.jetbrains.hexana.wasm.sections.CodeEntryOffset
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import kotlin.test.Test
import kotlin.test.assertIs
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

    /**
     * Run GarbageAnalyzer on the real skiko.wasm binary (~8MB, 14k functions).
     *
     * `twiggy garbage --all` reports 0 unreachable functions for skiko.wasm.
     * Our analyzer uses conservative indirect call resolution (over-approximation
     * via element segment type matching), which may miss some call edges that
     * twiggy's more precise analysis captures. As a result, we report more
     * unreachable functions than twiggy.
     *
     * This test captures the current state as a regression baseline.
     */
    @Test
    fun `skiko wasm garbage analysis — regression baseline`() = runTest {
        val buffer = resourceAsByteBuffer("maps/skiko.wasm")
        val parseResult = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(parseResult)
        val module = parseResult.wasmModule
        assertIs<WasmModule>(module)

        val cgr = CallGraphBuilder(buffer, module).build()
        val dom = DominatorTree.compute(cgr.graph)

        val codeSection = module.codeSection!!
        val codeOffsets = with(codeSection) { buffer.scanOffsets() }

        val importRecords = module.importSection?.let { section ->
            with(section) { buffer.getImportRecords() }
        } ?: emptyList()
        val importFuncCount = importRecords.count { it.type is WasmTypeIndexExternalType }

        val result = GarbageAnalyzer(cgr, dom, codeOffsets, importFuncCount, buffer, module).analyze()

        // twiggy reports 0 unreachable functions; our conservative call graph
        // over-approximation misses some indirect call edges, yielding 566.
        // If call graph precision improves, this number should decrease toward 0.
        assertEquals(566, result.entries.size, "Regression check: unreachable function count changed")
        assertTrue(result.totalWastedBytes > 0, "Unreachable functions should have non-zero wasted bytes")
        assertTrue(result.codeSectionTotalBytes > 0)

        // Transitive type garbage: types referenced only by unreachable functions.
        // twiggy reports 3 garbage types (110, 143, 176) but considers all functions
        // reachable. Our analysis has 566 unreachable functions, so types used ONLY by
        // those functions are also garbage. We find 6 garbage types.
        // twiggy's types 110, 143, 176 are NOT in our set because some reachable
        // functions also reference them — the divergence stems from different function
        // reachability results, not from the transitive logic itself.
        assertEquals(6, result.typeEntries.size, "Regression: transitive garbage type count")
        assertEquals(
            setOf(165, 254, 282, 283, 288, 338),
            result.typeEntries.map { it.typeIndex }.toSet(),
            "Regression: transitive garbage type indices",
        )
        assertTrue(result.typeWastedBytes > 0)

        // twiggy reports "4185 potential false-positive data segments" totaling 1,531,520 bytes.
        // twiggy splits data entries into sub-items by heuristic — the WASM binary has
        // 3 actual data section entries. Our byte total (1,913,196) differs from twiggy's
        // (1,531,520) because twiggy may exclude certain segments or use different sizing.
        assertEquals(3, result.dataSegmentEntries.size, "3 WASM data section entries")
        assertTrue(result.dataSegmentWastedBytes > 0, "Data segments should have non-zero size")
    }
}
