package org.jetbrains.hexana.wasm.analysis.garbage

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphNode
import org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphResult
import org.jetbrains.hexana.wasm.analysis.graph.DominatorResult
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.sections.CodeEntryOffset
import org.jetbrains.hexana.wasm.sections.WasmStructTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmTypeRecord
import org.jetbrains.hexana.wasm.sections.parseCodeEntry

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

data class GarbageTypeEntry(
    val typeIndex: Int,
    val size: Int,
    val description: String,
)

data class GarbageDataSegmentEntry(
    val index: Int,
    val dataSize: Int,
    val recordSize: Int,
)

data class GarbageResult(
    val entries: List<GarbageEntry>,
    val totalWastedBytes: Long,
    val codeSectionTotalBytes: Long,
    val typeEntries: List<GarbageTypeEntry> = emptyList(),
    val typeWastedBytes: Long = 0L,
    val dataSegmentEntries: List<GarbageDataSegmentEntry> = emptyList(),
    val dataSegmentWastedBytes: Long = 0L,
)

class GarbageAnalyzer(
    private val callGraphResult: CallGraphResult,
    private val dominators: DominatorResult,
    private val codeOffsets: List<CodeEntryOffset>,
    private val importFuncCount: Int,
    private val buffer: CommonByteBuffer? = null,
    private val module: WasmModule? = null,
) {
    fun analyze(): GarbageResult {
        val funcEntries = analyzeFunctions()
        val typeEntries = analyzeTypes()
        val dataSegEntries = analyzeDataSegments()

        val totalWasted = funcEntries.sumOf { it.size.toLong() }
        val totalCode = codeOffsets.sumOf { it.size.toLong() }
        val typeWasted = typeEntries.sumOf { it.size.toLong() }
        val dataSegWasted = dataSegEntries.sumOf { it.dataSize.toLong() }

        return GarbageResult(
            funcEntries, totalWasted, totalCode,
            typeEntries, typeWasted,
            dataSegEntries, dataSegWasted,
        )
    }

    private fun analyzeFunctions(): List<GarbageEntry> {
        val unreachableSet = dominators.unreachable
        if (unreachableSet.isEmpty()) return emptyList()

        val graph = callGraphResult.graph
        val nodeMap = callGraphResult.nodeMap

        return unreachableSet.mapNotNull { nodeId ->
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
    }

    /**
     * Finds type definitions not referenced by any item in the module.
     *
     * This is independent of function reachability — a type is garbage only if
     * NO function signature, import, or instruction references it.
     *
     * Collects type references from:
     * - Function section (all func → type mappings)
     * - Import section (all function import type indices)
     * - call_indirect / return_call_indirect instructions (all code)
     * - Struct supertype lists
     * - Recursive type group entries
     */
    private fun analyzeTypes(): List<GarbageTypeEntry> {
        val buf = buffer ?: return emptyList()
        val mod = module ?: return emptyList()

        val typesSection = mod.typesSection ?: return emptyList()
        val types: List<WasmTypeRecord> = with(typesSection) { buf.parseTypes() }
        if (types.isEmpty()) return emptyList()

        val referencedTypes = HashSet<Int>(types.size * 2)

        // 1. Function section: each entry is a type index
        mod.functionSection?.let { funcSection ->
            val typeRefs = with(funcSection) { buf.parseFunctions() }
            referencedTypes.addAll(typeRefs)
        }

        // 2. Import section: function imports reference a type index
        mod.importSection?.let { importSection ->
            val imports = with(importSection) { buf.getImportRecords() }
            for (record in imports) {
                val type = record.type
                if (type is WasmTypeIndexExternalType) {
                    referencedTypes.add(type.index.toInt())
                }
            }
        }

        // 3. Scan all code for call_indirect / return_call_indirect type operands
        if (mod.codeSection != null) {
            for (entry in codeOffsets) {
                val record = buf.parseCodeEntry(entry.offset)
                collectTypeRefsFromOpcodes(record.opCodes, referencedTypes)
            }
        }

        // 4. Struct supertype references and recursive group expansion
        for (type in types) {
            when (type) {
                is WasmStructTypeRecord -> {
                    for (superType in type.superTypes) {
                        referencedTypes.add(superType.toInt())
                    }
                }
                is WasmRecursiveTypeRecord -> {
                    for (entry in type.entries) {
                        if (entry is WasmStructTypeRecord) {
                            for (superType in entry.superTypes) {
                                referencedTypes.add(superType.toInt())
                            }
                        }
                    }
                }
                else -> {}
            }
        }

        // Find unreferenced types
        return types.indices
            .filter { it !in referencedTypes }
            .map { idx ->
                val type = types[idx]
                GarbageTypeEntry(idx, type.size, describeType(types[idx]))
            }
            .sortedByDescending { it.size }
    }

    /**
     * Reports all data segments as potential false-positive garbage.
     *
     * Active data segments are copied into linear memory at instantiation time
     * by the WASM runtime — no instruction references them by index.
     * Static analysis cannot determine if a data segment's memory region is
     * actually read, so all segments are reported as "potential" garbage
     * (matching twiggy's behavior).
     */
    private fun analyzeDataSegments(): List<GarbageDataSegmentEntry> {
        val buf = buffer ?: return emptyList()
        val mod = module ?: return emptyList()
        val dataSection = mod.dataSection ?: return emptyList()

        val records = with(dataSection) { buf.scan() }
        return records.mapIndexed { index, record ->
            GarbageDataSegmentEntry(index, record.data.size, record.recordLength)
        }
    }

    private fun collectTypeRefsFromOpcodes(opcodes: List<OpCode>, refs: MutableSet<Int>) {
        for (op in opcodes) {
            when (op) {
                is OpCodes.Flow.CallIndirect -> refs.add(op.value)
                is OpCodes.Flow.ReturnCallIndirect -> refs.add(op.value0.toInt())
                is OpCodes.Flow.Block -> collectTypeRefsFromOpcodes(op.value, refs)
                else -> {}
            }
        }
    }

    private fun describeType(type: WasmTypeRecord): String {
        return when (type) {
            is org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord -> {
                val params = type.params.joinToString(", ") { it.toString() }
                val ret = type.retTypes.joinToString(", ") { it.toString() }
                "($params) -> ${if (ret.isEmpty()) "nil" else ret}"
            }
            is WasmStructTypeRecord -> "struct (${type.fields.size} fields)"
            is org.jetbrains.hexana.wasm.sections.WasmArrayTypeRecord -> "array"
            is WasmRecursiveTypeRecord -> "rec (${type.entries.size} entries)"
            else -> type.toString()
        }
    }
}
