package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.wasm.opcodes.MemArg
import org.jetbrains.hexana.wasm.opcodes.OpCode
import org.jetbrains.hexana.wasm.opcodes.OpCodeWithValue
import org.jetbrains.hexana.wasm.opcodes.OpCodes
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64

/**
 * Renders the WAT mnemonic + immediate operands for a single [OpCode].
 *
 * Coverage: top ~120 most common opcodes (parametric, control flow,
 * variable, memory, full numeric for i32/i64/f32/f64). Long-tail opcodes
 * (SIMD, GC, Atomic, EH, table, reference) currently fall through to a
 * `(; <ClassName> ;)` placeholder — added incrementally as coverage demands.
 *
 * Output is the *right-hand* portion of a rendered line — the offset gutter
 * and hex-bytes column are added by the renderer.
 */
/**
 * Resolves a symbolic name for a function index, for symbolic operand rendering.
 * Returns null to fall back to the numeric index.
 */
internal fun interface WatSymbolNames {
    fun function(funcIdx: Int): String?
}

private fun callOperand(funcIdx: UInt, symbols: WatSymbolNames?): String =
    symbols?.function(funcIdx.toInt())?.let { "\$$it" } ?: funcIdx.toString()

@Suppress("CyclomaticComplexMethod", "LongMethod")
internal fun watFor(op: OpCode, symbols: WatSymbolNames? = null): String {
    return when (op) {
        // -- Parametric -----------------------------------------------------
        is OpCodes.ParametricInstructions.Unreachable -> "unreachable"
        is OpCodes.ParametricInstructions.Nop -> "nop"
        is OpCodes.ParametricInstructions.Drop -> "drop"
        is OpCodes.ParametricInstructions.Select -> "select"

        // -- Control flow ---------------------------------------------------
        is OpCodes.Flow.Terminal -> "end"
        is OpCodes.Flow.Return -> "return"
        is OpCodes.Flow.Else -> "else"
        is OpCodes.Flow.Loop -> "loop"
        is OpCodes.Flow.If -> "if"
        is OpCodes.Flow.Block -> "block"
        is OpCodes.Flow.Call -> "call ${callOperand(op.value, symbols)}"
        is OpCodes.Flow.ReturnCall -> "return_call ${callOperand(op.value, symbols)}"
        is OpCodes.Flow.CallRef -> "call_ref ${op.value}"
        is OpCodes.Flow.ReturnCallRef -> "return_call_ref ${op.value}"
        is OpCodes.Flow.CallIndirect -> "call_indirect (type ${op.value0}) ${op.value1}"
        is OpCodes.Flow.ReturnCallIndirect -> "return_call_indirect (type ${op.value0}) ${op.value1}"
        is OpCodes.Flow.Br -> "br ${op.value}"
        is OpCodes.Flow.BrIf -> "br_if ${op.value}"
        is OpCodes.Flow.BrTable -> "br_table ${op.value.joinToString(" ")} ${op.labelIdx}"
        is OpCodes.Flow.BrOnNull -> "br_on_null ${op.value}"
        is OpCodes.Flow.BrOnNonNull -> "br_on_non_null ${op.value}"

        // -- Exception handling (try_table proposal) ------------------------
        is OpCodes.Flow.Trow -> "throw ${op.value}"
        is OpCodes.Flow.ThrowRef -> "throw_ref"
        is OpCodes.Flow.TryTable -> "try_table" + op.catches.joinToString("") { " ${catchClauseText(it)}" }

        // -- Local / Global -------------------------------------------------
        is OpCodes.Local.Get -> "local.get ${op.value}"
        is OpCodes.Local.Set -> "local.set ${op.value}"
        is OpCodes.Local.Tee -> "local.tee ${op.value}"
        is OpCodes.Global.Get -> "global.get ${op.value}"
        is OpCodes.Global.Set -> "global.set ${op.value}"

        // -- Numeric: i32 ---------------------------------------------------
        is I32.Const -> "i32.const ${op.value}"

        // -- Numeric: i64 ---------------------------------------------------
        is I64.Const -> "i64.const ${op.value}"

        // -- Numeric: f32 / f64 (constants format with a decimal point) -----
        is F32.Const -> "f32.const ${formatFloat(op.value)}"
        is F64.Const -> "f64.const ${formatDouble(op.value)}"

        // -- Memory ---------------------------------------------------------
        is OpCodes.Memory.i32.Load -> "i32.load ${memArg(op.value, naturalAlign = 2)}"
        is OpCodes.Memory.i32.Load8_S -> "i32.load8_s ${memArg(op.value, naturalAlign = 0)}"
        is OpCodes.Memory.i32.Load8_U -> "i32.load8_u ${memArg(op.value, naturalAlign = 0)}"
        is OpCodes.Memory.i32.Load16_S -> "i32.load16_s ${memArg(op.value, naturalAlign = 1)}"
        is OpCodes.Memory.i32.Load16_U -> "i32.load16_u ${memArg(op.value, naturalAlign = 1)}"
        is OpCodes.Memory.i32.Store -> "i32.store ${memArg(op.value, naturalAlign = 2)}"
        is OpCodes.Memory.i32.Store8 -> "i32.store8 ${memArg(op.value, naturalAlign = 0)}"
        is OpCodes.Memory.i32.Store16 -> "i32.store16 ${memArg(op.value, naturalAlign = 1)}"

        is OpCodes.Memory.i64.Load -> "i64.load ${memArg(op.value, naturalAlign = 3)}"
        is OpCodes.Memory.i64.Load8_S -> "i64.load8_s ${memArg(op.value, naturalAlign = 0)}"
        is OpCodes.Memory.i64.Load8_U -> "i64.load8_u ${memArg(op.value, naturalAlign = 0)}"
        is OpCodes.Memory.i64.Load16_S -> "i64.load16_s ${memArg(op.value, naturalAlign = 1)}"
        is OpCodes.Memory.i64.Load16_U -> "i64.load16_u ${memArg(op.value, naturalAlign = 1)}"
        is OpCodes.Memory.i64.Load32_S -> "i64.load32_s ${memArg(op.value, naturalAlign = 2)}"
        is OpCodes.Memory.i64.Load32_U -> "i64.load32_u ${memArg(op.value, naturalAlign = 2)}"
        is OpCodes.Memory.i64.Store -> "i64.store ${memArg(op.value, naturalAlign = 3)}"
        is OpCodes.Memory.i64.Store8 -> "i64.store8 ${memArg(op.value, naturalAlign = 0)}"
        is OpCodes.Memory.i64.Store16 -> "i64.store16 ${memArg(op.value, naturalAlign = 1)}"
        is OpCodes.Memory.i64.Store32 -> "i64.store32 ${memArg(op.value, naturalAlign = 2)}"

        is OpCodes.Memory.f32.Load -> "f32.load ${memArg(op.value, naturalAlign = 2)}"
        is OpCodes.Memory.f32.Store -> "f32.store ${memArg(op.value, naturalAlign = 2)}"
        is OpCodes.Memory.f64.Load -> "f64.load ${memArg(op.value, naturalAlign = 3)}"
        is OpCodes.Memory.f64.Store -> "f64.store ${memArg(op.value, naturalAlign = 3)}"

        is OpCodes.Memory.Size -> "memory.size"
        is OpCodes.Memory.Grow -> "memory.grow"

        // -- Numeric: i32/i64/f32/f64 regular families ----------------------
        else -> NUMERIC_MNEMONICS[op] ?: placeholder(op)
    }
}

/**
 * Identity-based lookup table for all numeric opcode singletons.
 * Keyed by object identity (reliable on all Kotlin targets including JS).
 * `qualifiedName` is not used because it is unavailable on Kotlin/JS.
 *
 * I32: 41 entries, I64: 43 entries, F32: 26 entries, F64: 26 entries = 136 total.
 */
private val NUMERIC_MNEMONICS: Map<OpCode, String> = buildMap {
    // -- i32 ----------------------------------------------------------------
    put(I32.Add, "i32.add")
    put(I32.Sub, "i32.sub")
    put(I32.Mul, "i32.mul")
    put(I32.DivS, "i32.div_s")
    put(I32.DivU, "i32.div_u")
    put(I32.RemS, "i32.rem_s")
    put(I32.RemU, "i32.rem_u")
    put(I32.And, "i32.and")
    put(I32.Or, "i32.or")
    put(I32.Xor, "i32.xor")
    put(I32.Shl, "i32.shl")
    put(I32.ShrS, "i32.shr_s")
    put(I32.ShrU, "i32.shr_u")
    put(I32.Rotl, "i32.rotl")
    put(I32.Rotr, "i32.rotr")
    put(I32.Clz, "i32.clz")
    put(I32.Ctz, "i32.ctz")
    put(I32.PopCnt, "i32.popcnt")
    put(I32.Eqz, "i32.eqz")
    put(I32.Eq, "i32.eq")
    put(I32.Ne, "i32.ne")
    put(I32.LTS, "i32.lt_s")
    put(I32.LTU, "i32.lt_u")
    put(I32.LES, "i32.le_s")
    put(I32.LEU, "i32.le_u")
    put(I32.GTS, "i32.gt_s")
    put(I32.GTU, "i32.gt_u")
    put(I32.GES, "i32.ge_s")
    put(I32.GEU, "i32.ge_u")
    put(I32.WrapI64, "i32.wrap_i64")
    put(I32.Extend8S, "i32.extend8_s")
    put(I32.Extend16S, "i32.extend16_s")
    put(I32.TruncF32S, "i32.trunc_f32_s")
    put(I32.TruncF32U, "i32.trunc_f32_u")
    put(I32.TruncF64S, "i32.trunc_f64_s")
    put(I32.TruncF64U, "i32.trunc_f64_u")
    put(I32.TruncSatF32S, "i32.trunc_sat_f32_s")
    put(I32.TruncSatF32U, "i32.trunc_sat_f32_u")
    put(I32.TruncSatF64S, "i32.trunc_sat_f64_s")
    put(I32.TruncSatF64U, "i32.trunc_sat_f64_u")
    put(I32.ReinterpretF32, "i32.reinterpret_f32")

    // -- i64 ----------------------------------------------------------------
    put(I64.Add, "i64.add")
    put(I64.Sub, "i64.sub")
    put(I64.Mul, "i64.mul")
    put(I64.DivS, "i64.div_s")
    put(I64.DivU, "i64.div_u")
    put(I64.RemS, "i64.rem_s")
    put(I64.RemU, "i64.rem_u")
    put(I64.And, "i64.and")
    put(I64.Or, "i64.or")
    put(I64.Xor, "i64.xor")
    put(I64.Shl, "i64.shl")
    put(I64.ShrS, "i64.shr_s")
    put(I64.ShrU, "i64.shr_u")
    put(I64.RotL, "i64.rotl")
    put(I64.RotR, "i64.rotr")
    put(I64.Clz, "i64.clz")
    put(I64.Ctz, "i64.ctz")
    put(I64.PopCnt, "i64.popcnt")
    put(I64.Eqz, "i64.eqz")
    put(I64.EQ, "i64.eq")
    put(I64.NE, "i64.ne")
    put(I64.LTS, "i64.lt_s")
    put(I64.LTU, "i64.lt_u")
    put(I64.LES, "i64.le_s")
    put(I64.LEU, "i64.le_u")
    put(I64.GTS, "i64.gt_s")
    put(I64.GTU, "i64.gt_u")
    put(I64.GES, "i64.ge_s")
    put(I64.GEU, "i64.ge_u")
    put(I64.Extend_I32_S, "i64.extend_i32_s")
    put(I64.Extend_I32_U, "i64.extend_i32_u")
    put(I64.TruncF32S, "i64.trunc_f32_s")
    put(I64.TruncF32U, "i64.trunc_f32_u")
    put(I64.TruncF64S, "i64.trunc_f64_s")
    put(I64.TruncF64U, "i64.trunc_f64_u")
    put(I64.Extend8S, "i64.extend8_s")
    put(I64.Extend16S, "i64.extend16_s")
    put(I64.Extend32S, "i64.extend32_s")
    put(I64.TruncSatF32S, "i64.trunc_sat_f32_s")
    put(I64.TruncSatF32U, "i64.trunc_sat_f32_u")
    put(I64.TruncSatF64S, "i64.trunc_sat_f64_s")
    put(I64.TruncSatF64U, "i64.trunc_sat_f64_u")
    put(I64.ReinterpretF64, "i64.reinterpret_f64")

    // -- f32 ----------------------------------------------------------------
    put(F32.Add, "f32.add")
    put(F32.Sub, "f32.sub")
    put(F32.Mul, "f32.mul")
    put(F32.Div, "f32.div")
    put(F32.Abs, "f32.abs")
    put(F32.Neg, "f32.neg")
    put(F32.Sqrt, "f32.sqrt")
    put(F32.Ceil, "f32.ceil")
    put(F32.Floor, "f32.floor")
    put(F32.Trunc, "f32.trunc")
    put(F32.Nearest, "f32.nearest")
    put(F32.Min, "f32.min")
    put(F32.Max, "f32.max")
    put(F32.CopySign, "f32.copysign")
    put(F32.EQ, "f32.eq")
    put(F32.NE, "f32.ne")
    put(F32.LT, "f32.lt")
    put(F32.LE, "f32.le")
    put(F32.GT, "f32.gt")
    put(F32.GE, "f32.ge")
    put(F32.DemoteF64, "f32.demote_f64")
    put(F32.ReinterpretI32, "f32.reinterpret_i32")
    put(F32.ConvertI32S, "f32.convert_i32_s")
    put(F32.ConvertI32U, "f32.convert_i32_u")
    put(F32.ConvertI64S, "f32.convert_i64_s")
    put(F32.ConvertI64U, "f32.convert_i64_u")

    // -- f64 ----------------------------------------------------------------
    put(F64.Add, "f64.add")
    put(F64.Sub, "f64.sub")
    put(F64.Mul, "f64.mul")
    put(F64.Div, "f64.div")
    put(F64.Abs, "f64.abs")
    put(F64.Neg, "f64.neg")
    put(F64.Sqrt, "f64.sqrt")
    put(F64.Ceil, "f64.ceil")
    put(F64.Floor, "f64.floor")
    put(F64.Trunc, "f64.trunc")
    put(F64.Nearest, "f64.nearest")
    put(F64.Min, "f64.min")
    put(F64.Max, "f64.max")
    put(F64.Copysign, "f64.copysign")
    put(F64.EQ, "f64.eq")
    put(F64.NE, "f64.ne")
    put(F64.LT, "f64.lt")
    put(F64.LE, "f64.le")
    put(F64.GT, "f64.gt")
    put(F64.GE, "f64.ge")
    put(F64.PromoteF32, "f64.promote_f32")
    put(F64.ReinterpretI64, "f64.reinterpret_i64")
    put(F64.ConvertI32S, "f64.convert_i32_s")
    put(F64.ConvertI32U, "f64.convert_i32_u")
    put(F64.ConvertI64S, "f64.convert_i64_s")
    put(F64.ConvertI64U, "f64.convert_i64_u")
}

private fun memArg(arg: MemArg, naturalAlign: Int): String {
    val parts = mutableListOf<String>()
    if (arg.offset != 0) parts += "offset=${arg.offset}"
    // align is exponent — emit `align=2^N` only when it differs from natural alignment
    if (arg.align != naturalAlign) parts += "align=${1 shl arg.align}"
    return parts.joinToString(" ")
}

private fun formatFloat(value: Float): String = when {
    value.isNaN() -> "nan"
    value == Float.POSITIVE_INFINITY -> "inf"
    value == Float.NEGATIVE_INFINITY -> "-inf"
    else -> {
        val s = value.toString()
        if ('.' in s || 'e' in s || 'E' in s) s else "$s.0"
    }
}

private fun formatDouble(value: Double): String = when {
    value.isNaN() -> "nan"
    value == Double.POSITIVE_INFINITY -> "inf"
    value == Double.NEGATIVE_INFINITY -> "-inf"
    else -> {
        val s = value.toString()
        if ('.' in s || 'e' in s || 'E' in s) s else "$s.0"
    }
}

/** WAT text for one `try_table` catch clause, matching wasm-tools spelling. */
private fun catchClauseText(catch: OpCodes.Flow.CatchType): String = when (catch) {
    is OpCodes.Flow.CatchType.Catch -> "(catch ${catch.tagIdx} ${catch.labelIdx})"
    is OpCodes.Flow.CatchType.CatchRef -> "(catch_ref ${catch.tagIdx} ${catch.labelIdx})"
    is OpCodes.Flow.CatchType.CatchAll -> "(catch_all ${catch.labelIdx})"
    is OpCodes.Flow.CatchType.CatchAllRef -> "(catch_all_ref ${catch.labelIdx})"
}

/** Last-resort placeholder for opcodes not yet covered by [watFor]. */
private fun placeholder(op: OpCode): String {
    val name = op::class.simpleName ?: "OpCode"
    val value = (op as? OpCodeWithValue<*>)?.value
    return if (value != null && value !is Unit) "(; $name $value ;)" else "(; $name ;)"
}
