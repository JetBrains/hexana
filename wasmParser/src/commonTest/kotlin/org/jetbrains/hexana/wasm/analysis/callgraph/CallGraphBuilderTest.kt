package org.jetbrains.hexana.wasm.analysis.callgraph

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.WasmParseSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CallGraphBuilderTest {

    @Test
    fun `call wasm produces call graph with edges`() = runTest {
        val buffer = resourceAsByteBuffer("snippets/call.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val builder = CallGraphBuilder(buffer, module)
        val (graph, nodeMap) = builder.build()

        assertTrue(graph.root == CallGraphNode.VIRTUAL_ROOT_ID)
        assertTrue(graph.nodes.isNotEmpty())
    }

    @Test
    fun `exported functions are roots`() = runTest {
        val buffer = resourceAsByteBuffer("snippets/call.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val builder = CallGraphBuilder(buffer, module)
        val (graph, _) = builder.build()

        val rootSuccessors = graph.successors[CallGraphNode.VIRTUAL_ROOT_ID]
        assertNotNull(rootSuccessors)
        assertTrue(rootSuccessors.isNotEmpty(), "Virtual root should connect to exported functions")
    }

    @Test
    fun `large module call graph has many nodes`() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val builder = CallGraphBuilder(buffer, module)
        val (graph, nodeMap) = builder.build()

        assertTrue(graph.nodes.size > 5, "UTF8 module should have multiple function nodes")
        val totalEdges = graph.nodes.sumOf { (graph.successors[it.id]?.size ?: 0) }
        assertTrue(totalEdges > 0, "Should have call edges")
    }

    @Test
    fun `indirect calls route through per-signature hub nodes`() = runTest {
        val buffer = resourceAsByteBuffer("skiko.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val builder = CallGraphBuilder(buffer, module)
        val (graph, nodeMap) = builder.build()

        val hubs = nodeMap.values.filterIsInstance<CallGraphNode.IndirectCallHub>()
        assertTrue(hubs.size > 1, "Emscripten module should dispatch through multiple signatures, got ${hubs.size}")
        val populated = hubs.filter { !graph.successors[it.id].isNullOrEmpty() }
        assertTrue(populated.isNotEmpty(), "At least one hub should fan out to element-table functions")
        for (hub in populated) {
            val callers = graph.predecessors[hub.id]
            assertNotNull(callers, "Hub ${hub.name} should have indirect callers")
            assertTrue(callers.isNotEmpty())
        }
        // Linearity guard: total hub edges stay O(sites + table entries), far
        // below the callers × table product that motivated the hub design.
        val hubEdges = hubs.sumOf {
            (graph.successors[it.id]?.size ?: 0) + (graph.predecessors[it.id]?.size ?: 0)
        }
        val functions = nodeMap.size
        assertTrue(hubEdges < functions * 20, "hub edges ($hubEdges) should stay linear in module size ($functions nodes)")
    }

    @Test
    fun `per-signature hubs connect callers to matching candidates only`() = runTest {
        // Fixture (dispatch-candidates.wat): type 0/1 are both (i32) -> i32
        // with table candidates {0, 1, 2, 5}; func 6 ($caller) call_indirects
        // through type 1; func 7 ($caller2) uses (f64) -> f64, which has NO
        // table entries — its hub must exist with zero candidates.
        val buffer = resourceAsByteBuffer("snippets/dispatch-candidates.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val (graph, nodeMap) = CallGraphBuilder(buffer, module).build()
        val hubs = nodeMap.values.filterIsInstance<CallGraphNode.IndirectCallHub>()
        assertEquals(2, hubs.size, "one hub per distinct signature used by call_indirect")

        val i32Hub = hubs.single { it.signatureText == "(i32) -> i32" }
        assertEquals(setOf(0, 1, 2, 5), graph.successors[i32Hub.id]?.toSet(), "candidates merged across type 0 and 1")
        assertEquals(setOf(6), graph.predecessors[i32Hub.id]?.toSet(), "\$caller routes through the i32 hub")

        val f64Hub = hubs.single { it.signatureText == "(f64) -> f64" }
        assertTrue(graph.successors[f64Hub.id].isNullOrEmpty(), "trap-only signature keeps an empty hub")
        assertEquals(setOf(7), graph.predecessors[f64Hub.id]?.toSet(), "\$caller2 routes through the f64 hub")
    }

    @Test
    fun `dominators computable on call graph`() = runTest {
        val buffer = resourceAsByteBuffer("c/utf8-c.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val builder = CallGraphBuilder(buffer, module)
        val (graph, _) = builder.build()
        val dominators = org.jetbrains.hexana.wasm.analysis.graph.DominatorTree.compute(graph)

        assertNotNull(dominators.idom)
    }
}