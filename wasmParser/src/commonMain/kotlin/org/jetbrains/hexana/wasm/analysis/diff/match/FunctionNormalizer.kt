package org.jetbrains.hexana.wasm.analysis.diff.match

import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import kotlin.reflect.KClass

private const val ANON_CALLEE = 0x7E_A0L

/** Cross-entity lookups for normalization, supplied by the provider layer. */
public interface NormalizerContext {
    /** A renumber-stable token for a call/ref.func target (e.g. its base name, or anon+arity). */
    public fun calleeToken(funcIdx: Int): Long

    /** Param/result arity of a type, for `call_indirect`/`call_ref` (renumber-stable). */
    public fun typeArity(typeIdx: Int): Int

    /** A renumber-stable token for a global (e.g. its value-type + mutability). */
    public fun globalToken(globalIdx: Int): Long

    public companion object {
        /** No cross-entity info: all calls/globals look alike (renumber-stable but coarse). */
        public val EMPTY: NormalizerContext = object : NormalizerContext {
            override fun calleeToken(funcIdx: Int): Long = ANON_CALLEE
            override fun typeArity(typeIdx: Int): Int = 0
            override fun globalToken(globalIdx: Int): Long = 0L
        }
    }
}

/** Output of [FunctionNormalizer.normalize]: the content hash plus the fingerprint. */
public data class NormalizedFunction(val hash: Long, val fingerprint: FunctionFingerprint)

/**
 * Computes a renumber-invariant content hash and structural fingerprint for one
 * function body. Two functions that differ only by index renumbering (of
 * functions, globals, types) and by local-slot reordering hash equal; functions
 * that differ in opcodes, constants, control structure, or call targets do not.
 *
 * Hold one instance per matching run (it memoizes per-opcode-class tags).
 */
public class FunctionNormalizer {

    private val tagMemo = HashMap<KClass<*>, Long>()

    public fun normalize(
        opCodes: List<OpCode>,
        paramCount: Int,
        resultCount: Int,
        signatureSeed: Long,
        ctx: NormalizerContext = NormalizerContext.EMPTY,
    ): NormalizedFunction {
        val hash = FnvHash().update(signatureSeed)
        val renumber = LocalRenumber(paramCount)
        val acc = Acc()
        walk(opCodes, hash, renumber, ctx, acc)
        val fp = FunctionFingerprint(
            typeArity = FunctionFingerprint.arity(paramCount, resultCount),
            instrCount = acc.instrCount,
            callCount = acc.callCount,
            maxNestDepth = acc.maxDepth,
        )
        return NormalizedFunction(hash.value(), fp)
    }

    private fun walk(ops: List<OpCode>, h: FnvHash, renumber: LocalRenumber, ctx: NormalizerContext, acc: Acc) {
        for (op in ops) {
            acc.instrCount++
            emitToken(op, h, renumber, ctx, acc)
            when (op) {
                is OpCodes.Flow.Block -> {            // Block/Loop/If/Else/LegacyEH.Try (all IS-A Block)
                    acc.enter()
                    walk(op.value, h, renumber, ctx, acc)
                    acc.exit()
                    h.update(END)
                }
                is OpCodes.Flow.TryTable -> {
                    acc.enter()
                    walk(op.code, h, renumber, ctx, acc)
                    acc.exit()
                    h.update(END)
                }
                else -> {}
            }
        }
    }

    private fun emitToken(op: OpCode, h: FnvHash, renumber: LocalRenumber, ctx: NormalizerContext, acc: Acc) {
        h.update(classTag(op))
        when (op) {
            is OpCodes.Flow.Call -> { h.update(CALL); h.update(ctx.calleeToken(op.value.toInt())); acc.callCount++ }
            is OpCodes.Flow.ReturnCall -> { h.update(CALL); h.update(ctx.calleeToken(op.value.toInt())); acc.callCount++ }
            is OpCodes.Ref.Func -> { h.update(REF_FUNC); h.update(ctx.calleeToken(op.value.toInt())) }
            is OpCodes.Flow.CallIndirect -> { h.update(CALL_IND); h.update(ctx.typeArity(op.value0).toLong()); acc.callCount++ }
            is OpCodes.Flow.ReturnCallIndirect -> {
                h.update(CALL_IND); h.update(ctx.typeArity(op.value0.toInt()).toLong()); acc.callCount++
            }
            is OpCodes.Flow.CallRef -> { h.update(CALL_IND); h.update(ctx.typeArity(op.value.toInt()).toLong()); acc.callCount++ }
            is OpCodes.Local.Get -> { h.update(LOCAL); h.update(renumber.map(op.value.toInt()).toLong()) }
            is OpCodes.Local.Set -> { h.update(LOCAL); h.update(renumber.map(op.value.toInt()).toLong()) }
            is OpCodes.Local.Tee -> { h.update(LOCAL); h.update(renumber.map(op.value.toInt()).toLong()) }
            is OpCodes.Global.Get -> { h.update(GLOBAL); h.update(ctx.globalToken(op.value.toInt())) }
            is OpCodes.Global.Set -> { h.update(GLOBAL); h.update(ctx.globalToken(op.value.toInt())) }
            is OpCodes.Flow.Br -> h.update(op.value.toLong())
            is OpCodes.Flow.BrIf -> h.update(op.value.toLong())
            is OpCodes.Flow.BrTable -> { op.value.forEach { h.update(it.toLong()) }; h.update(op.labelIdx.toLong()) }
            is I32.Const -> h.update(op.value.toLong())
            is I64.Const -> h.update(op.value)
            is F32.Const -> h.update(op.value.toRawBits().toLong())
            is F64.Const -> h.update(op.value.toRawBits())
            // Loads/stores carry a MemArg; emit align+offset. Must stay LAST (Const/Local/etc.
            // are also OpCodeWithValue and are handled by the specific branches above).
            is OpCodeWithValue<*> -> {
                val v = op.value
                if (v is MemArg) { h.update(v.align.toLong()); h.update(v.offset.toLong()) }
            }
            else -> {}
        }
    }

    private fun classTag(op: OpCode): Long {
        val k = op::class
        return tagMemo.getOrPut(k) { FnvHash().update(k.qualifiedName ?: k.simpleName ?: "?").value() }
    }

    /** Params keep their indices; declared locals are renumbered in first-use order. */
    private class LocalRenumber(private val paramCount: Int) {
        private val map = HashMap<Int, Int>()
        private var next = paramCount
        fun map(idx: Int): Int {
            if (idx < paramCount) return idx
            return map.getOrPut(idx) { next++ }
        }
    }

    private class Acc {
        var instrCount = 0
        var callCount = 0
        var maxDepth = 0
        private var depth = 0
        fun enter() { depth++; if (depth > maxDepth) maxDepth = depth }
        fun exit() { depth-- }
    }

    private companion object {
        // Reserved structural tokens (distinct from class-derived tags positionally).
        const val END = 0x7E_4E_44L
        const val CALL = 0x7E_C0L
        const val CALL_IND = 0x7E_C1L
        const val REF_FUNC = 0x7E_C2L
        const val LOCAL = 0x7E_10L
        const val GLOBAL = 0x7E_61L
    }
}
