package org.jetbrains.hexana.wasm.analysis.callgraph

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.analysis.graph.DirectedGraph
import org.jetbrains.hexana.wasm.analysis.graph.DirectedGraphBuilder
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.ExprWasmElementSegment
import org.jetbrains.hexana.wasm.sections.FuncIdxWasmElementSegment
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.parseCodeEntry

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
        // Lightweight offset index — no opcode parsing
        val codeSection = module.codeSection
        val codeOffsets = codeSection?.let { section ->
            with(section) { buffer.scanOffsets() }
        } ?: emptyList()

        val importRecords = module.importSection?.let { section ->
            with(section) { buffer.getImportRecords() }
        } ?: emptyList()

        val importFuncCount = importRecords.count { it.type is WasmTypeIndexExternalType }
        val totalFuncCount = importFuncCount + codeOffsets.size

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
        for (i in codeOffsets.indices) {
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

        // ref.func occurrences in global / element / data init exprs are evaluated at
        // module instantiation, so the referenced function is reachable unconditionally.
        // For Kotlin/Wasm-GC binaries this is the dominant rooting mechanism (vtables
        // built from struct fields populated by ref.func in global init exprs).
        for (target in collectInitExprRefFuncs()) {
            graphBuilder.addEdge(virtualRoot.id, target)
        }

        // 5. Parse each function one at a time, extract call edges, let GC reclaim parsed data
        val indirectCallers = ArrayList<Int>()
        for (i in codeOffsets.indices) {
            val callerIdx = importFuncCount + i
            val record = buffer.parseCodeEntry(codeOffsets[i].offset)
            if (addEdgesForFunction(callerIdx, record.opCodes, graphBuilder)) {
                indirectCallers.add(callerIdx)
            }
        }

        // 6. Route indirect calls through a single hub node instead of a full
        // callers × element-table product of edges.
        if (indirectCallers.isNotEmpty() && elementFunctions.isNotEmpty()) {
            val hub = CallGraphNode.IndirectCallHub()
            graphBuilder.addNode(hub.id, hub.name)
            nodeMap[hub.id] = hub
            for (callerIdx in indirectCallers) {
                graphBuilder.addEdge(callerIdx, hub.id)
            }
            for (elemFuncIdx in elementFunctions) {
                graphBuilder.addEdge(hub.id, elemFuncIdx)
            }
        }

        val graph = graphBuilder.build(CallGraphNode.VIRTUAL_ROOT_ID)
        return CallGraphResult(graph, nodeMap)
    }

    /**
     * Walks the opcode tree of a single function, collects unique direct call targets
     * (call, return_call, ref.func), adds their edges once, and returns whether the
     * function contains any indirect call (the caller routes those through the
     * [CallGraphNode.IndirectCallHub]). Recurses into all block-like containers
     * (block, loop, if, else, legacy try, try_table). call_ref / return_call_ref add
     * no edges — their targets are reached via ref.func roots (matches twiggy
     * semantics).
     */
    private fun addEdgesForFunction(
        callerIdx: Int,
        opcodes: List<OpCode>,
        graphBuilder: DirectedGraphBuilder
    ): Boolean {
        val directTargets = HashSet<Int>(8)
        var hasIndirectCall = false

        fun walk(ops: List<OpCode>) {
            for (op in ops) {
                when (op) {
                    is OpCodes.Flow.Call -> directTargets.add(op.value.toInt())
                    is OpCodes.Flow.ReturnCall -> directTargets.add(op.value.toInt())
                    is OpCodes.Flow.CallIndirect -> hasIndirectCall = true
                    is OpCodes.Flow.ReturnCallIndirect -> hasIndirectCall = true
                    is OpCodes.Ref.Func -> directTargets.add(op.value.toInt())
                    is OpCodes.Flow.Block -> walk(op.value)
                    is OpCodes.Flow.TryTable -> walk(op.code)
                    else -> {}
                }
            }
        }
        walk(opcodes)

        for (target in directTargets) {
            graphBuilder.addEdge(callerIdx, target)
        }
        return hasIndirectCall
    }

    private fun collectElementFunctions(): Set<Int> {
        val elementSection = module.elementSection ?: return emptySet()
        val segments = with(elementSection) { buffer.scanElements() }
        val result = HashSet<Int>()
        for (segment in segments) {
            when (segment) {
                is FuncIdxWasmElementSegment -> {
                    for (idx in segment.funcs) result.add(idx.toInt())
                }
                is ExprWasmElementSegment -> {
                    for (expr in segment.exprs) collectRefFuncs(expr, result)
                }
            }
        }
        return result
    }

    /**
     * Collects ref.func targets from init expressions evaluated at module
     * instantiation: global init exprs and element-segment item exprs. These
     * functions are unconditionally reachable and become virtual-root edges.
     */
    private fun collectInitExprRefFuncs(): Set<Int> {
        val result = HashSet<Int>()

        module.globalSection?.let { section ->
            val records = with(section) { buffer.scan() }
            for (record in records) collectRefFuncs(record.expression, result)
        }

        // Element item exprs are already folded into elementFunctions, but pass-through
        // here keeps the rooting explicit for non-default segments (declare/passive),
        // whose funcs are also reachable in practice (ref.func in declare segments
        // exists exactly to root function references).
        module.elementSection?.let { section ->
            val segments = with(section) { buffer.scanElements() }
            for (segment in segments) {
                if (segment is ExprWasmElementSegment) {
                    for (expr in segment.exprs) collectRefFuncs(expr, result)
                }
            }
        }

        return result
    }

    private fun collectRefFuncs(ops: List<OpCode>, sink: MutableSet<Int>) {
        for (op in ops) {
            when (op) {
                is OpCodes.Ref.Func -> sink.add(op.value.toInt())
                is OpCodes.Flow.Block -> collectRefFuncs(op.value, sink)
                is OpCodes.Flow.TryTable -> collectRefFuncs(op.code, sink)
                else -> {}
            }
        }
    }
}