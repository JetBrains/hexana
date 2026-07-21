package org.jetbrains.hexana.wasm.analysis.callgraph

import kotlinx.coroutines.test.runTest
import org.jetbrains.hexana.tests.resourceAsByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.WasmParser
import org.jetbrains.hexana.wasm.WasmParseSuccess
import kotlin.test.Test
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
    fun `indirect calls route through a single hub node`() = runTest {
        val buffer = resourceAsByteBuffer("skiko.wasm")
        val result = WasmParser().parse(buffer)
        assertIs<WasmParseSuccess>(result)
        val module = result.wasmModule
        assertIs<WasmModule>(module)

        val builder = CallGraphBuilder(buffer, module)
        val (graph, nodeMap) = builder.build()

        val hub = nodeMap[CallGraphNode.INDIRECT_CALL_HUB_ID]
        assertIs<CallGraphNode.IndirectCallHub>(hub, "Emscripten module with call_indirect should have a hub node")
        val hubTargets = graph.successors[CallGraphNode.INDIRECT_CALL_HUB_ID]
        assertNotNull(hubTargets, "Hub should fan out to element-table functions")
        assertTrue(hubTargets.isNotEmpty())
        val hubCallers = graph.predecessors[CallGraphNode.INDIRECT_CALL_HUB_ID]
        assertNotNull(hubCallers, "Indirect callers should route through the hub")
        assertTrue(hubCallers.isNotEmpty())
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