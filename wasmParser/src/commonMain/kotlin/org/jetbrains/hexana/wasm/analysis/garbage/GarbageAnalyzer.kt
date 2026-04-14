package org.jetbrains.hexana.wasm.analysis.garbage

import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphNode
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphResult
import org.jetbrains.hexana.wasm.analysis.graph.DominatorResult
import org.jetbrains.hexana.wasm.sections.CodeEntryOffset

enum class GarbageReason {
    NO_CALLERS,
    CALLERS_UNREACHABLE,
}

data class GarbageEntry(
    val nodeId: Int,
    val funcIndex: Int,
    val name: String,
    val size: Int,
    val module: String?,
    val reason: GarbageReason,
)

data class GarbageResult(
    val entries: List<GarbageEntry>,
    val totalWastedBytes: Long,
    val codeSectionTotalBytes: Long,
)

class GarbageAnalyzer(
    private val callGraphResult: CallGraphResult,
    private val dominators: DominatorResult,
    private val codeOffsets: List<CodeEntryOffset>,
    private val importFuncCount: Int,
) {
    fun analyze(): GarbageResult {
        val unreachableSet = dominators.unreachable
        if (unreachableSet.isEmpty()) {
            val totalCode = codeOffsets.sumOf { it.size.toLong() }
            return GarbageResult(emptyList(), 0L, totalCode)
        }

        val graph = callGraphResult.graph
        val nodeMap = callGraphResult.nodeMap

        val entries = unreachableSet.mapNotNull { nodeId ->
            val node = nodeMap[nodeId] ?: return@mapNotNull null

            val funcIndex: Int
            val name: String
            val size: Int
            val module: String?

            when (node) {
                is CallGraphNode.DefinedFunction -> {
                    funcIndex = node.funcIndex
                    name = node.name
                    val codeIdx = funcIndex - importFuncCount
                    size = codeOffsets.getOrNull(codeIdx)?.size ?: 0
                    module = null
                }
                is CallGraphNode.ImportedFunction -> {
                    funcIndex = node.id
                    name = node.name
                    size = 0
                    module = node.module
                }
                is CallGraphNode.VirtualRoot -> return@mapNotNull null
                is CallGraphNode.ComponentFunction -> return@mapNotNull null
            }

            val preds = graph.predecessors[nodeId]
            val reason = if (preds.isNullOrEmpty()) {
                GarbageReason.NO_CALLERS
            } else {
                val allCallersUnreachable = preds.all { it in unreachableSet }
                if (allCallersUnreachable) GarbageReason.CALLERS_UNREACHABLE
                else GarbageReason.NO_CALLERS
            }

            GarbageEntry(nodeId, funcIndex, name, size, module, reason)
        }.sortedByDescending { it.size }

        val totalWasted = entries.sumOf { it.size.toLong() }
        val totalCode = codeOffsets.sumOf { it.size.toLong() }

        return GarbageResult(entries, totalWasted, totalCode)
    }
}
