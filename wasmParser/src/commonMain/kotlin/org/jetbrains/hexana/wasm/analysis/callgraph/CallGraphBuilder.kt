package org.jetbrains.hexana.wasm.analysis.callgraph

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.analysis.graph.DirectedGraph
import org.jetbrains.hexana.wasm.analysis.graph.DirectedGraphBuilder
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.FuncIdxWasmElementSegment
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

/**
 * Result of building a call graph: the [DirectedGraph] and a mapping from node ID to [CallGraphNode].
 */
data class CallGraphResult(val graph: DirectedGraph, val nodeMap: Map<Int, CallGraphNode>)

/**
 * Builds an inter-procedural call graph for a core WASM module.
 *
 * The graph contains:
 * - A virtual root node connected to exported functions, the start function, and element segment functions.
 * - An [CallGraphNode.ImportedFunction] node for each function import.
 * - A [CallGraphNode.DefinedFunction] node for each code entry (defined function).
 * - Edges from each defined function to its call targets (extracted by scanning opcodes).
 */
class CallGraphBuilder(
    private val buffer: CommonByteBuffer,
    private val module: WasmModule
) {

    private lateinit var elementFunctions: Set<Int>

    fun build(): CallGraphResult {
        val graphBuilder = DirectedGraphBuilder()
        val nodeMap = mutableMapOf<Int, CallGraphNode>()

        // 1. Virtual root
        val virtualRoot = CallGraphNode.VirtualRoot()
        graphBuilder.addNode(virtualRoot.id, virtualRoot.name)
        nodeMap[virtualRoot.id] = virtualRoot

        // 2. Import function nodes (funcidx 0 to importCount-1)
        val importCount = addImportedFunctions(graphBuilder, nodeMap)

        // 3. Defined function nodes (funcidx importCount to N)
        val codeRecords = addDefinedFunctions(graphBuilder, nodeMap, importCount)

        // 4. Connect virtual root to exported functions, start function, element segment functions
        elementFunctions = collectElementFunctions()
        connectRootToExports(graphBuilder, virtualRoot.id)
        connectRootToStart(graphBuilder, virtualRoot.id)
        for (funcIdx in elementFunctions) {
            graphBuilder.addEdge(virtualRoot.id, funcIdx)
        }

        // 5. For each defined function, scan opcodes for Call instructions and add edges
        codeRecords.forEachIndexed { index, record ->
            val callerFuncIdx = importCount + index
            collectCallEdges(record.opCodes, callerFuncIdx, graphBuilder)
        }

        val graph = graphBuilder.build(CallGraphNode.VIRTUAL_ROOT_ID)
        return CallGraphResult(graph, nodeMap)
    }

    private fun addImportedFunctions(
        graphBuilder: DirectedGraphBuilder,
        nodeMap: MutableMap<Int, CallGraphNode>
    ): Int {
        val importSection = module.importSection ?: return 0
        val records = with(importSection) { buffer.getImportRecords() }
        var funcIdx = 0
        for (record in records) {
            if (record.type is WasmTypeIndexExternalType) {
                val name = if (record.name.isBlank()) "import_$funcIdx" else record.name
                val node = CallGraphNode.ImportedFunction(funcIdx, name, record.prefix)
                graphBuilder.addNode(node.id, node.name)
                nodeMap[node.id] = node
                funcIdx++
            }
        }
        return funcIdx
    }

    private fun addDefinedFunctions(
        graphBuilder: DirectedGraphBuilder,
        nodeMap: MutableMap<Int, CallGraphNode>,
        importCount: Int
    ): List<org.jetbrains.hexana.wasm.sections.WasmCodeRecord> {
        val codeSection = module.codeSection ?: return emptyList()
        val records = with(codeSection) { buffer.scan() }
        records.forEachIndexed { index, _ ->
            val funcIdx = importCount + index
            val name = "func_$funcIdx"
            val node = CallGraphNode.DefinedFunction(funcIdx, name, funcIdx)
            graphBuilder.addNode(node.id, node.name)
            nodeMap[node.id] = node
        }
        return records
    }

    private fun connectRootToExports(graphBuilder: DirectedGraphBuilder, rootId: Int) {
        val exportSection = module.exportSection ?: return
        val exports = with(exportSection) { buffer.scan() }
        for (export in exports) {
            if (export is WasmFunctionExportRecordEntry) {
                graphBuilder.addEdge(rootId, export.index.toInt())
            }
        }
    }

    private fun connectRootToStart(graphBuilder: DirectedGraphBuilder, rootId: Int) {
        val startSection = module.startSection ?: return
        buffer.position(startSection.contentOffset)
        val startFuncIdx = buffer.getULEB128().toInt()
        graphBuilder.addEdge(rootId, startFuncIdx)
    }

    private fun collectElementFunctions(): Set<Int> {
        val elementSection = module.elementSection ?: return emptySet()
        val segments = with(elementSection) { buffer.scanElements() }
        val result = mutableSetOf<Int>()
        for (segment in segments) {
            if (segment is FuncIdxWasmElementSegment) {
                for (funcIdx in segment.funcs) {
                    result.add(funcIdx.toInt())
                }
            }
        }
        return result
    }

    private fun collectCallEdges(opcodes: List<OpCode>, callerFuncIdx: Int, graphBuilder: DirectedGraphBuilder) {
        for (opcode in opcodes) {
            when (opcode) {
                is OpCodes.Flow.Call -> {
                    graphBuilder.addEdge(callerFuncIdx, opcode.value.toInt())
                }
                is OpCodes.Flow.CallIndirect -> {
                    // Conservative: add edges to all element segment functions with matching type.
                    // Since precise type matching requires resolving the type section, we connect
                    // to all element segment functions (already connected to virtual root).
                    for (funcIdx in elementFunctions) {
                        graphBuilder.addEdge(callerFuncIdx, funcIdx)
                    }
                }
                is OpCodes.Flow.Block -> {
                    // Block, Loop, If, Else all extend Block and have nested opcodes in value
                    collectCallEdges(opcode.value, callerFuncIdx, graphBuilder)
                }
                else -> { /* not a call or block-structured opcode */ }
            }
        }
    }
}