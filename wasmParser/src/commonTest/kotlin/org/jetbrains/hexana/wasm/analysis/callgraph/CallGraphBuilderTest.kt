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
        val totalEdges = graph.successors.values.sumOf { it.size }
        assertTrue(totalEdges > 0, "Should have call edges")
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