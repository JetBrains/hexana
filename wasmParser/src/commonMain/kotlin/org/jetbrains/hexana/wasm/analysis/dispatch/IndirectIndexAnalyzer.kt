package org.jetbrains.hexana.wasm.analysis.dispatch

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32

/**
 * What is statically evident about the table-index operand of a
 * `call_indirect` / `return_call_indirect` site. Shape only, never
 * interpretation: a [Load] does not claim "vtable" — that reading belongs
 * to the (heuristic, separately labeled) mining phase that consumes
 * [Load.byteOffset].
 */
sealed class IndirectIndexProvenance {
    /** The index is the constant table slot [slot] (the N1 exact case). */
    data class Const(val slot: Int) : IndirectIndexProvenance()

    /**
     * The index is loaded from linear memory at static offset [byteOffset]
     * off [base] — the classic Rust `dyn` / C++ virtual dispatch shape.
     * [base] is a one-level display description of the address producer
     * ("local 1", "global 0", "i32.load(+4)", or "<expr>").
     */
    data class Load(val byteOffset: Int, val base: String) : IndirectIndexProvenance()

    /** The index is a local/parameter value — it flows in from callers. */
    data class Local(val localIdx: Int) : IndirectIndexProvenance()

    data object Unknown : IndirectIndexProvenance()
}

/**
 * Resolves the provenance of the index operand at [siteOffset]. Same sound
 * walk discipline as the N1 constant check: the index is the value on top
 * of the operand stack at the call site, and only the IMMEDIATELY preceding
 * instruction within the same block level is inspected (wasm branches only
 * target block boundaries, so nothing can land between that instruction and
 * the call). For a load, the instruction before it is described one level
 * deep, display-only. Everything else answers [IndirectIndexProvenance.Unknown]
 * and callers fall back to the signature-matched candidate set.
 */
fun resolveIndirectIndexProvenance(opcodes: List<OpCode>, siteOffset: Int): IndirectIndexProvenance {
    var result: IndirectIndexProvenance = IndirectIndexProvenance.Unknown
    var found = false

    fun describeBase(op: OpCode?): String = when (op) {
        is OpCodes.Local.Get -> "local ${op.value}"
        is OpCodes.Global.Get -> "global ${op.value}"
        is OpCodes.Memory.i32.Load -> "i32.load(+${op.value.offset})"
        else -> "<expr>"
    }

    fun walk(ops: List<OpCode>) {
        var previous: OpCode? = null
        var beforePrevious: OpCode? = null
        for (op in ops) {
            if (found) return
            val isSite = (op is OpCodes.Flow.CallIndirect || op is OpCodes.Flow.ReturnCallIndirect) &&
                (op as OpCodeWithOffset).offset == siteOffset
            if (isSite) {
                result = when (val p = previous) {
                    is I32.Const -> IndirectIndexProvenance.Const(p.value)
                    is OpCodes.Memory.i32.Load ->
                        IndirectIndexProvenance.Load(p.value.offset, describeBase(beforePrevious))
                    is OpCodes.Local.Get -> IndirectIndexProvenance.Local(p.value.toInt())
                    else -> IndirectIndexProvenance.Unknown
                }
                found = true
                return
            }
            when (op) {
                is OpCodes.Flow.Block -> walk(op.value)
                is OpCodes.Flow.TryTable -> walk(op.code)
                else -> {}
            }
            beforePrevious = previous
            previous = op
        }
    }
    walk(opcodes)
    return result
}

/** The N1 constant case of [resolveIndirectIndexProvenance], or null. */
fun resolveConstantIndirectIndex(opcodes: List<OpCode>, siteOffset: Int): Int? =
    (resolveIndirectIndexProvenance(opcodes, siteOffset) as? IndirectIndexProvenance.Const)?.slot
