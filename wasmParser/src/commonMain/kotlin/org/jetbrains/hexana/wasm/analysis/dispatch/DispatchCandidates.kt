package org.jetbrains.hexana.wasm.analysis.dispatch

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.sections.ExprWasmElementSegment
import org.jetbrains.hexana.wasm.sections.FuncIdxWasmElementSegment
import org.jetbrains.hexana.wasm.sections.WasmAnyRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmArrayRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmEqRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmExnRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmExternRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmHeapType
import org.jetbrains.hexana.wasm.sections.WasmI31RefHeapType
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type
import org.jetbrains.hexana.wasm.sections.WasmIndexHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoExnHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoExternRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoFuncHeapType
import org.jetbrains.hexana.wasm.sections.WasmNoneHeapType
import org.jetbrains.hexana.wasm.sections.WasmNonNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmNullableReferenceType
import org.jetbrains.hexana.wasm.sections.WasmRecursiveTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmRefType
import org.jetbrains.hexana.wasm.sections.WasmSectionFunctionTypeRecord
import org.jetbrains.hexana.wasm.sections.WasmStructRefHeapType
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmValueType
import org.jetbrains.hexana.wasm.sections.WasmVecType

/**
 * The canonical key for `call_indirect` dispatch: a function signature by
 * structure, not by type index. The runtime type check is structural, so two
 * type-section entries with identical params/results are interchangeable
 * dispatch targets; keying by typeidx would silently split their candidate
 * sets. Equality relies on [WasmValueType] semantics (singleton objects for
 * num/vec types, structural equals on [WasmRefType]).
 */
data class DispatchSignature(
    val params: List<WasmValueType<*>>,
    val results: List<WasmValueType<*>>,
) {
    /** WAT-flavored rendering for popup headers, e.g. `(i32, i64) -> i32`. */
    fun text(): String {
        val p = params.joinToString(", ") { valueTypeText(it) }
        val r = when (results.size) {
            0 -> "()"
            1 -> valueTypeText(results[0])
            else -> results.joinToString(", ", prefix = "(", postfix = ")") { valueTypeText(it) }
        }
        return "($p) -> $r"
    }
}

/**
 * Statically known `call_indirect` dispatch candidates, grouped by canonical
 * signature. Candidates are the functions referenced from element segments
 * that populate (or can populate) tables: active segments and passive
 * segments (reachable via `table.init`). `declare` segments are excluded —
 * they exist only to forward-declare `ref.func` and never enter a table.
 *
 * This is a static under-approximation: entries written at runtime via
 * `table.set` are invisible here. Callers presenting this data should say
 * "statically known candidates", not "possible targets".
 */
class DispatchCandidates internal constructor(
    private val functionSignatures: Map<Int, DispatchSignature>,
    private val bySignature: Map<DispatchSignature, IntArray>,
    private val slotImage: Map<Int, Int>,
) {
    /**
     * The function statically installed at table-0 slot [slot] by an active
     * element segment with a constant offset, or null when the slot is not
     * statically initialized (runtime `table.set`/`table.init`, non-constant
     * segment offsets such as an imported `__table_base`, or out of range).
     * Powers exact-target resolution for constant-index `call_indirect`.
     */
    fun functionAtSlot(slot: Int): Int? = slotImage[slot]

    /**
     * The canonical signature of type-section entry [typeIdx], or null when
     * the entry is not a function type (or out of range).
     */
    fun signatureOfType(typeIdx: Int): DispatchSignature? = functionSignatures[typeIdx]

    /**
     * Sorted funcidx list of statically known candidates for a
     * `call_indirect` carrying [typeIdx]. Empty when the signature has no
     * table entries (such a call site can only trap) or the type is unknown.
     */
    fun candidatesForType(typeIdx: Int): IntArray {
        val sig = functionSignatures[typeIdx] ?: return EMPTY
        return bySignature[sig] ?: EMPTY
    }

    /** Same lookup keyed directly by canonical signature. */
    fun candidatesFor(signature: DispatchSignature): IntArray = bySignature[signature] ?: EMPTY

    /**
     * Every signature with at least one statically known candidate, mapped to
     * its sorted candidate funcidx array. Powers whole-surface consumers (the
     * dispatch-surface diff); per-site consumers use [candidatesForType].
     */
    fun allCandidates(): Map<DispatchSignature, IntArray> = bySignature

    private companion object {
        val EMPTY = IntArray(0)
    }
}

/**
 * Builds [DispatchCandidates] for a core module. Mirrors
 * [CallGraphBuilder][org.jetbrains.hexana.wasm.analysis.callgraph.CallGraphBuilder]'s
 * data access: one pass over the type, import, function, and element
 * sections; no code-body decoding. O(types + functions + table entries).
 */
class DispatchCandidatesBuilder(
    private val buffer: CommonByteBuffer,
    private val module: WasmModule,
) {

    fun build(): DispatchCandidates {
        val typeRecords = module.typesSection?.let { section ->
            with(section) { buffer.parseTypes() }
        } ?: emptyList()

        // typeidx -> signature, expanding rec groups: each subtype of a
        // recursive group occupies its own consecutive type index (a rec
        // group is ONE list entry but MANY type indices — positional
        // indexing into the raw list is wrong for GC modules).
        val functionSignatures = HashMap<Int, DispatchSignature>(typeRecords.size * 2)
        var typeIdx = 0
        for (record in typeRecords) {
            when (record) {
                is WasmRecursiveTypeRecord -> {
                    for (entry in record.entries) {
                        if (entry is WasmSectionFunctionTypeRecord) {
                            functionSignatures[typeIdx] = DispatchSignature(entry.params, entry.retTypes)
                        }
                        typeIdx++
                    }
                }
                is WasmSectionFunctionTypeRecord -> {
                    functionSignatures[typeIdx] = DispatchSignature(record.params, record.retTypes)
                    typeIdx++
                }
                else -> typeIdx++
            }
        }

        // funcidx -> typeidx: imported functions first, then defined ones.
        val funcTypeIndices = ArrayList<Int>()
        module.importSection?.let { section ->
            val records = with(section) { buffer.getImportRecords() }
            for (record in records) {
                val type = record.type
                if (type is WasmTypeIndexExternalType) funcTypeIndices.add(type.index.toInt())
            }
        }
        module.functionSection?.let { section ->
            funcTypeIndices.addAll(with(section) { buffer.parseFunctions() })
        }

        val tableFuncs = collectTableFuncs()
        val grouped = HashMap<DispatchSignature, MutableList<Int>>()
        for (funcIdx in tableFuncs) {
            val funcTypeIdx = funcTypeIndices.getOrNull(funcIdx) ?: continue
            val sig = functionSignatures[funcTypeIdx] ?: continue
            grouped.getOrPut(sig) { ArrayList() }.add(funcIdx)
        }

        val bySignature = HashMap<DispatchSignature, IntArray>(grouped.size * 2)
        for ((sig, funcs) in grouped) {
            bySignature[sig] = funcs.toIntArray().also { it.sort() }
        }
        return DispatchCandidates(functionSignatures, bySignature, collectSlotImage())
    }

    /**
     * Table-0 slot -> funcidx for active element segments whose offset is a
     * single `i32.const` (segments offset by an imported global such as
     * `__table_base` are skipped — the base is a link-time unknown).
     */
    private fun collectSlotImage(): Map<Int, Int> {
        val elementSection = module.elementSection ?: return emptyMap()
        val segments = with(elementSection) { buffer.scanElements() }
        val image = HashMap<Int, Int>()

        fun constOffset(offsetExpr: List<OpCode>): Int? {
            if (offsetExpr.any { it is OpCodes.Global.Get }) return null
            return offsetExpr.filterIsInstance<I32.Const>().singleOrNull()?.value
        }

        for (segment in segments) {
            when (segment) {
                is FuncIdxWasmElementSegment.ActiveDefault -> {
                    val base = constOffset(segment.offsetExpr) ?: continue
                    segment.funcs.forEachIndexed { i, func -> image[base + i] = func.toInt() }
                }
                is FuncIdxWasmElementSegment.ActiveExplicit -> {
                    if (segment.tableIndex != 0u) continue
                    val base = constOffset(segment.offsetExpr) ?: continue
                    segment.funcs.forEachIndexed { i, func -> image[base + i] = func.toInt() }
                }
                is ExprWasmElementSegment.ActiveDefault -> {
                    val base = constOffset(segment.offsetExpr) ?: continue
                    segment.exprs.forEachIndexed { i, expr ->
                        expr.filterIsInstance<OpCodes.Ref.Func>().singleOrNull()?.let { image[base + i] = it.value.toInt() }
                    }
                }
                is ExprWasmElementSegment.ActiveExplicit -> {
                    if (segment.tableIndex != 0u) continue
                    val base = constOffset(segment.offsetExpr) ?: continue
                    segment.exprs.forEachIndexed { i, expr ->
                        expr.filterIsInstance<OpCodes.Ref.Func>().singleOrNull()?.let { image[base + i] = it.value.toInt() }
                    }
                }
                else -> {}
            }
        }
        return image
    }

    /**
     * Functions referenced from table-populating element segments: active
     * (in the table at instantiation) and passive (can enter via
     * `table.init`). `declare` segments never populate a table and are
     * excluded — including them would invent candidates.
     */
    private fun collectTableFuncs(): Set<Int> {
        val elementSection = module.elementSection ?: return emptySet()
        val segments = with(elementSection) { buffer.scanElements() }
        val result = HashSet<Int>()
        for (segment in segments) {
            when (segment) {
                is FuncIdxWasmElementSegment.Declare, is ExprWasmElementSegment.Declare -> {}
                is FuncIdxWasmElementSegment -> for (idx in segment.funcs) result.add(idx.toInt())
                is ExprWasmElementSegment -> for (expr in segment.exprs) collectRefFuncs(expr, result)
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

private fun valueTypeText(type: WasmValueType<*>): String = when (type) {
    is WasmI32Type -> "i32"
    is WasmI64Type -> "i64"
    is WasmF32Type -> "f32"
    is WasmF64Type -> "f64"
    is WasmVecType -> "v128"
    is WasmRefType -> when (val ref = type.code) {
        is WasmNullableReferenceType -> "(ref null ${heapText(ref.heapType)})"
        is WasmNonNullableReferenceType -> "(ref ${heapText(ref.heapType)})"
        else -> "${heapText(ref.heapType)}ref"
    }
    else -> type.toString()
}

private fun heapText(heap: WasmHeapType): String = when (heap) {
    is WasmFuncHeapType -> "func"
    is WasmExternRefHeapType -> "extern"
    is WasmAnyRefHeapType -> "any"
    is WasmEqRefHeapType -> "eq"
    is WasmI31RefHeapType -> "i31"
    is WasmStructRefHeapType -> "struct"
    is WasmArrayRefHeapType -> "array"
    is WasmExnRefHeapType -> "exn"
    is WasmNoneHeapType -> "none"
    is WasmNoExternRefHeapType -> "noextern"
    is WasmNoFuncHeapType -> "nofunc"
    is WasmNoExnHeapType -> "noexn"
    is WasmIndexHeapType -> "\$type${heap.type}"
    else -> "?"
}
