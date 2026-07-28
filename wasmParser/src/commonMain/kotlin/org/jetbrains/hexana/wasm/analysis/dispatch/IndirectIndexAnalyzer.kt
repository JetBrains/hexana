package org.jetbrains.hexana.wasm.analysis.dispatch

import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithOffset
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.I32

/**
 * Resolves the table-index operand of the `call_indirect` /
 * `return_call_indirect` at [siteOffset] to a compile-time constant table
 * slot, or null when the operand is not statically evident.
 *
 * Deliberately minimal and sound (N1 of the narrowing ladder in the
 * indirect-call visibility spec): the index is the value on top of the
 * operand stack at the call site, and the only shape recognized is the
 * canonical `i32.const K` pushed by the IMMEDIATELY preceding instruction
 * within the same block level. Anything else — a value loaded from memory
 * (vtable dispatch), a parameter, a nested block's result — answers null and
 * the caller falls back to the signature-matched candidate set. Branch
 * targets cannot land between the const and the call: wasm branches only
 * target block boundaries, and an intervening block boundary means the const
 * is not the previous sibling.
 */
fun resolveConstantIndirectIndex(opcodes: List<OpCode>, siteOffset: Int): Int? {
    var result: Int? = null
    var found = false

    fun walk(ops: List<OpCode>) {
        var previous: OpCode? = null
        for (op in ops) {
            if (found) return
            val isSite = (op is OpCodes.Flow.CallIndirect || op is OpCodes.Flow.ReturnCallIndirect) &&
                (op as OpCodeWithOffset).offset == siteOffset
            if (isSite) {
                result = (previous as? I32.Const)?.value
                found = true
                return
            }
            when (op) {
                is OpCodes.Flow.Block -> walk(op.value)
                is OpCodes.Flow.TryTable -> walk(op.code)
                else -> {}
            }
            previous = op
        }
    }
    walk(opcodes)
    return result
}
