package org.jetbrains.hexana.wasm.analysis.callgraph

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.analysis.graph.DirectedGraph
import org.jetbrains.hexana.wasm.analysis.graph.DirectedGraphBuilder
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.FuncIdxWasmElementSegment
import org.jetbrains.hexana.wasm.sections.WasmCodeRecord
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

    fun build(): CallGraphResult {
        // Parse code records once — used for both node creation and edge extraction
        val codeRecords: List<WasmCodeRecord> = module.codeSection?.let { section ->
            with(section) { buffer.scan() }
        } ?: emptyList()

        val importRecords = module.importSection?.let { section ->
            with(section) { buffer.getImportRecords() }
        } ?: emptyList()

        val importFuncCount = importRecords.count { it.type is WasmTypeIndexExternalType }
        val totalFuncCount = importFuncCount + codeRecords.size

        val graphBuilder = DirectedGraphBuilder(expectedNodes = totalFuncCount + 1)
        val nodeMap = HashMap<Int, CallGraphNode>(totalFuncCount * 2)

        // 1. Virtual root
        val virtualRoot = CallGraphNode.VirtualRoot()
        graphBuilder.addNode(virtualRoot.id, virtualRoot.name)
        nodeMap[virtualRoot.id] = virtualRoot

        // 2. Import function nodes (funcidx 0 to importCount-1)
        var funcIdx = 0
        for (record in importRecords) {
            if (record.type is WasmTypeIndexExternalType) {
                val name = if (record.name.isBlank()) "import_$funcIdx" else record.name
                val node = CallGraphNode.ImportedFunction(funcIdx, name, record.prefix)
                graphBuilder.addNode(node.id, node.name)
                nodeMap[node.id] = node
                funcIdx++
            }
        }

        // 3. Defined function nodes (funcidx importCount to N)
        for (i in codeRecords.indices) {
            val idx = importFuncCount + i
            val name = "func_$idx"
            val node = CallGraphNode.DefinedFunction(idx, name, idx)
            graphBuilder.addNode(node.id, node.name)
            nodeMap[node.id] = node
        }

        // 4. Connect virtual root to exported functions, start function, element segment functions
        val elementFunctions = collectElementFunctions()

        module.exportSection?.let { exportSection ->
            val exports = with(exportSection) { buffer.scan() }
            for (export in exports) {
                if (export is WasmFunctionExportRecordEntry) {
                    graphBuilder.addEdge(virtualRoot.id, export.index.toInt())
                }
            }
        }

        module.startSection?.let { startSection ->
            buffer.position(startSection.contentOffset)
            graphBuilder.addEdge(virtualRoot.id, buffer.getULEB128().toInt())
        }

        for (elemFuncIdx in elementFunctions) {
            graphBuilder.addEdge(virtualRoot.id, elemFuncIdx)
        }

        // 5. For each defined function, collect call info then add edges once
        for (i in codeRecords.indices) {
            val callerIdx = importFuncCount + i
            addEdgesForFunction(callerIdx, codeRecords[i].opCodes, elementFunctions, graphBuilder)
        }

        val graph = graphBuilder.build(CallGraphNode.VIRTUAL_ROOT_ID)
        return CallGraphResult(graph, nodeMap)
    }

    /**
     * Walks the opcode tree of a single function, collects unique direct call targets
     * and whether any indirect call exists, then adds edges once.
     * This avoids adding duplicate edges for repeated call_indirect instructions.
     */
    private fun addEdgesForFunction(
        callerIdx: Int,
        opcodes: List<OpCode>,
        elementFunctions: Set<Int>,
        graphBuilder: DirectedGraphBuilder
    ) {
        val directTargets = HashSet<Int>(8)
        var hasIndirectCall = false

        fun walk(ops: List<OpCode>) {
            for (op in ops) {
                when (op) {
                    is OpCodes.Flow.Call -> directTargets.add(op.value.toInt())
                    is OpCodes.Flow.CallIndirect -> hasIndirectCall = true
                    is OpCodes.Flow.Block -> walk(op.value)
                    else -> {}
                }
            }
        }
        walk(opcodes)

        for (target in directTargets) {
            graphBuilder.addEdge(callerIdx, target)
        }
        if (hasIndirectCall) {
            for (elemFuncIdx in elementFunctions) {
                graphBuilder.addEdge(callerIdx, elemFuncIdx)
            }
        }
    }

    private fun collectElementFunctions(): Set<Int> {
        val elementSection = module.elementSection ?: return emptySet()
        val segments = with(elementSection) { buffer.scanElements() }
        val result = HashSet<Int>()
        for (segment in segments) {
            if (segment is FuncIdxWasmElementSegment) {
                for (idx in segment.funcs) {
                    result.add(idx.toInt())
                }
            }
        }
        return result
    }
}