package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.wasm.opcodes.*
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f64
import org.jetbrains.hexana.wasm.sections.UnparsedOpCode
import org.jetbrains.hexana.wasm.sections.WasmHeapType
import org.jetbrains.hexana.wasm.sections.WasmValueType
import org.jetbrains.hexana.wasm.opcodes.HeapType as OpCodeHeapType

fun List<OpCode>.wat(): String {
    return this.joinToString("\n") { it.watString() }
}

internal fun OpCode.watString(): CharSequence {
    return when (this) {
        is OpCodes.Flow.Terminal -> "terminal"
        is OpCodes.Flow.Return -> "return"
        is OpCodes.Flow.ReturnCall -> "return_call"
        is OpCodes.ParametricInstructions.Drop -> "drop"
        is OpCodes.ParametricInstructions.Select -> "select"
        is OpCodes.ParametricInstructions.SelectWithType -> "select"
        is OpCodes.ParametricInstructions.Unreachable -> "unreachable"
        is OpCodes.ParametricInstructions.Nop -> "nop"
        is OpCodes.Flow.Call -> "call ${value}"
        is OpCodes.Flow.CallIndirect -> "call_indirect ${value}"
        is OpCodes.Flow.Br -> "br $value"
        is OpCodes.Flow.BrIf -> "br_if $value"
        is OpCodes.Flow.BrTable -> "br_table (${value.joinToString(", ")})"
        is OpCodes.Flow.Loop -> "loop{\n${value.wat()}\n}"
        is OpCodes.Flow.If -> "if{\n${value.wat()}\n}"
        is OpCodes.Flow.Else -> "else{\n${value.wat()}\n}"
        is OpCodes.Local.Get -> "local.get $value"
        is OpCodes.Local.Set -> "local.set $value"
        is OpCodes.Local.Tee -> "local.tee $value"
        is OpCodes.Global.Get -> "global.get $value"
        is OpCodes.Global.Set -> "global.set $value"
        is OpCodes.Memory.Size -> "memory.size${value.takeIf { it != 0u }?.let { " $it" } ?: ""}"
        is OpCodes.Memory.Grow -> "memory.grow${value.takeIf { it != 0u }?.let { " $it" } ?: ""}"
        /* i32 */
        is i32.Load -> "i32.load ${value.watString()}"
        is i32.Load8_S -> "i32.load8_s ${value.watString()}"
        is i32.Load8_U -> "i32.load8_u ${value.watString()}"
        is i32.Load16_U -> "i32.load16_u ${value.watString()}"
        is i32.Store -> "i32.store ${value.watString()}"
        is i32.Store8 -> "i32.store8 ${value.watString()}"
        is i32.Store16 -> "i32.store16 ${value.watString()}"
        is OpCodes.NumericInstructions.i32.Const -> "i32.const $value"
        is OpCodes.NumericInstructions.i32.Shl -> "i32.shl"
        is OpCodes.NumericInstructions.i32.PopCnt -> "i32.popcnt"
        is OpCodes.NumericInstructions.i32.And -> "i32.and"
        is OpCodes.NumericInstructions.i32.Or -> "i32.or"
        is OpCodes.NumericInstructions.i32.Xor -> "i32.xor"
        is OpCodes.NumericInstructions.i32.Add -> "i32.add"
        is OpCodes.NumericInstructions.i32.Sub -> "i32.sub"
        is OpCodes.NumericInstructions.i32.Mul -> "i32.mul"
        is OpCodes.NumericInstructions.i32.Rotl -> "i32.rotl"
        is OpCodes.NumericInstructions.i32.Rotr -> "i32.rotr"
        is OpCodes.NumericInstructions.i32.Clz -> "i32.clz"
        is OpCodes.NumericInstructions.i32.Ctz -> "i32.ctz"
        is OpCodes.NumericInstructions.i32.Eqz -> "i32.eqz"
        is OpCodes.NumericInstructions.i32.Eq -> "i32.eq"
        is OpCodes.NumericInstructions.i32.Ne -> "i32.ne"
        is OpCodes.NumericInstructions.i32.LTS -> "i32.lt_s"
        is OpCodes.NumericInstructions.i32.LTU -> "i32.lt_u"
        is OpCodes.NumericInstructions.i32.LES -> "i32.le_s"
        is OpCodes.NumericInstructions.i32.LEU -> "i32.le_u"
        is OpCodes.NumericInstructions.i32.GTS -> "i32.gt_s"
        is OpCodes.NumericInstructions.i32.GTU -> "i32.gt_u"
        is OpCodes.NumericInstructions.i32.GES -> "i32.ge_s"
        is OpCodes.NumericInstructions.i32.GEU -> "i32.ge_u"
        is OpCodes.NumericInstructions.i32.RemS -> "i32.rem_s"
        is OpCodes.NumericInstructions.i32.RemU -> "i32.rem_u"
        is OpCodes.NumericInstructions.i32.WrapI64 -> "i32.wrap_i64"
        is OpCodes.NumericInstructions.i32.Extend16S -> "i32.extend16_s"
        is OpCodes.NumericInstructions.i32.ReinterpretF32 -> "i32.reinterpret_f32"
        is OpCodes.NumericInstructions.i32.TruncF32S -> "i32.trunc_f32_s"
        is OpCodes.NumericInstructions.i32.TruncF32U -> "i32.trunc_f32_u"
        is OpCodes.NumericInstructions.i32.TruncF64S -> "i32.trunc_f64_s"
        is OpCodes.NumericInstructions.i32.TruncF64U -> "i32.trunc_f64_u"
        is OpCodes.NumericInstructions.i32.DivU -> "i32.div_u"
        is OpCodes.NumericInstructions.i32.DivS -> "i32.div_s"
        is OpCodes.NumericInstructions.i32.ShrU -> "i32.shr_u"
        is OpCodes.NumericInstructions.i32.ShrS -> "i32.shr_s"
        /* i64 */
        is i64.Load -> "i64.load ${value.watString()}"
        is i64.Load8_S -> "i64.load8_s ${value.watString()}"
        is i64.Load8_U -> "i64.load8_u ${value.watString()}"
        is i64.Load16_U -> "i64.load16_u ${value.watString()}"
        is i64.Store -> "i64.store ${value.watString()}"
        is i64.Store8 -> "i64.store8 ${value.watString()}"
        is i64.Store16 -> "i64.store16 ${value.watString()}"
        is OpCodes.NumericInstructions.i64.Const -> "i64.const $value"
        is OpCodes.NumericInstructions.i64.Shl -> "i64.shl"
        is OpCodes.NumericInstructions.i64.PopCnt -> "i64.popcnt"
        is OpCodes.NumericInstructions.i64.And -> "i64.and"
        is OpCodes.NumericInstructions.i64.Or -> "i64.or"
        is OpCodes.NumericInstructions.i64.Xor -> "i64.xor"
        is OpCodes.NumericInstructions.i64.Add -> "i64.add"
        is OpCodes.NumericInstructions.i64.Sub -> "i64.sub"
        is OpCodes.NumericInstructions.i64.Mul -> "i64.mul"
        is OpCodes.NumericInstructions.i64.RotL -> "i64.rotl"
        is OpCodes.NumericInstructions.i64.RotR -> "i64.rotr"
        is OpCodes.NumericInstructions.i64.Extend_I32_S -> "i64.extend_i32_s"
        is OpCodes.NumericInstructions.i64.Extend_I32_U -> "i64.extend_i32_u"
        is OpCodes.NumericInstructions.i64.Clz -> "i64.clz"
        is OpCodes.NumericInstructions.i64.Ctz -> "i64.ctz"
        is OpCodes.NumericInstructions.i64.Eqz -> "i64.eqz"
        is OpCodes.NumericInstructions.i64.LTS -> "i64.lt_s"
        is OpCodes.NumericInstructions.i64.LTU -> "i64.lt_u"
        is OpCodes.NumericInstructions.i64.LES -> "i64.le_s"
        is OpCodes.NumericInstructions.i64.LEU -> "i64.le_u"
        is OpCodes.NumericInstructions.i64.GTS -> "i64.gt_s"
        is OpCodes.NumericInstructions.i64.GTU -> "i64.gt_u"
        is OpCodes.NumericInstructions.i64.GES -> "i64.ge_s"
        is OpCodes.NumericInstructions.i64.GEU -> "i64.ge_u"
        is OpCodes.NumericInstructions.i64.RemS -> "i64.rem_s"
        is OpCodes.NumericInstructions.i64.RemU -> "i64.rem_u"
        is OpCodes.NumericInstructions.i64.TruncF32S -> "i64.trunc_f32_s"
        is OpCodes.NumericInstructions.i64.TruncF32U -> "i64.trunc_f32_u"
        is OpCodes.NumericInstructions.i64.TruncF64S -> "i64.trunc_f64_s"
        is OpCodes.NumericInstructions.i64.TruncF64U -> "i64.trunc_f64_u"
        is OpCodes.NumericInstructions.i64.ReinterpretF64 -> "i64.reinterpret_f64"
        is OpCodes.NumericInstructions.i64.DivU -> "i64.div_u"
        is OpCodes.NumericInstructions.i64.DivS -> "i64.div_s"
        is OpCodes.NumericInstructions.i64.ShrU -> "i64.shr_u"
        is OpCodes.NumericInstructions.i64.ShrS -> "i64.shr_s"
        /* f32 */
        is OpCodes.NumericInstructions.F32.Const -> "f32.const $value"
        is OpCodes.NumericInstructions.F32.Nearest -> "f32.nearest"
        is OpCodes.NumericInstructions.F32.NE -> "f32.ne"
        is OpCodes.NumericInstructions.F32.LE -> "f32.le"
        is OpCodes.NumericInstructions.F32.Ceil -> "f32.ceil"
        is OpCodes.NumericInstructions.F32.Floor -> "f32.floor"
        is OpCodes.NumericInstructions.F32.Sub -> "f32.sub"
        is OpCodes.NumericInstructions.F32.Mul -> "f32.mul"
        is OpCodes.NumericInstructions.F32.Trunc -> "f32.trunc"
        is OpCodes.NumericInstructions.F32.CopySign -> "f32.copysign"
        is f32.Load -> "f32.load ${value.watString()}"
        is f32.Store -> "f32.store ${value.watString()}"
        is OpCodes.NumericInstructions.F32.Sqrt -> "f32.sqrt"
        is OpCodes.NumericInstructions.F32.Add -> "f32.add"
        is OpCodes.NumericInstructions.F32.Div -> "f32.div"
        is OpCodes.NumericInstructions.F32.Min -> "f32.min"
        is OpCodes.NumericInstructions.F32.Max -> "f32.max"
        is OpCodes.NumericInstructions.F32.DemoteF64 -> "f32.demote_f64"
        is OpCodes.NumericInstructions.F32.ReinterpretI32 -> "f32.reinterpret_i32"
        is OpCodes.NumericInstructions.F32.Neg -> "f32.neg"
        is OpCodes.NumericInstructions.F32.ConvertI32S -> "f32.convert_i32_s"
        is OpCodes.NumericInstructions.F32.ConvertI32U -> "f32.convert_i32_u"
        is OpCodes.NumericInstructions.F32.ConvertI64S -> "f32.convert_i64_s"
        is OpCodes.NumericInstructions.F32.ConvertI64U -> "f32.convert_i64_u"
        is OpCodes.NumericInstructions.F32.EQ -> "f32.eq"
        is OpCodes.NumericInstructions.F32.GE -> "f32.ge"
        is OpCodes.NumericInstructions.F32.GT -> "f32.gt"
        is OpCodes.NumericInstructions.F32.LT -> "f32.lt"
        is OpCodes.NumericInstructions.F32.Abs -> "f32.abs"
        /* f64 */
        is OpCodes.NumericInstructions.F64.Const -> "f64.const $value"
        is OpCodes.NumericInstructions.F64.Nearest -> "f64.nearest"
        is OpCodes.NumericInstructions.F64.NE -> "f64.ne"
        is OpCodes.NumericInstructions.F64.LE -> "f64.le"
        is OpCodes.NumericInstructions.F64.Ceil -> "f64.ceil"
        is OpCodes.NumericInstructions.F64.Floor -> "f64.floor"
        is OpCodes.NumericInstructions.F64.Sub -> "f64.sub"
        is OpCodes.NumericInstructions.F64.Mul -> "f64.mul"
        is OpCodes.NumericInstructions.F64.Trunc -> "f64.trunc"
        is OpCodes.NumericInstructions.F64.Copysign -> "f64.copysign"
        is OpCodes.NumericInstructions.F64.Sqrt -> "f64.sqrt"
        is OpCodes.NumericInstructions.F64.Add -> "f64.add"
        is OpCodes.NumericInstructions.F64.Div -> "f64.div"
        is OpCodes.NumericInstructions.F64.Min -> "f64.min"
        is OpCodes.NumericInstructions.F64.Max -> "f64.max"
        is OpCodes.NumericInstructions.F64.PromoteF32 -> "f64.promote_f32"
        is OpCodes.NumericInstructions.F64.ReinterpretI64 -> "f64.reinterpret_i64"
        is OpCodes.NumericInstructions.F64.Neg -> "f64.neg"
        is OpCodes.NumericInstructions.F64.ConvertI32S -> "f64.convert_i32_s"
        is OpCodes.NumericInstructions.F64.ConvertI32U -> "f64.convert_i32_u"
        is OpCodes.NumericInstructions.F64.ConvertI64S -> "f64.convert_i64_s"
        is OpCodes.NumericInstructions.F64.ConvertI64U -> "f64.convert_i64_u"
        is OpCodes.NumericInstructions.F64.EQ -> "f64.eq"
        is OpCodes.NumericInstructions.F64.GE -> "f64.ge"
        is OpCodes.NumericInstructions.F64.GT -> "f64.gt"
        is OpCodes.NumericInstructions.F64.LT -> "f64.lt"
        is OpCodes.NumericInstructions.F64.Abs -> "f64.abs"
        is UnparsedOpCode -> "unparsed block: 0x${value.offset.toHexString()} .. 0x${(value.offset + value.size).toHexString()}"
        // https://webassembly.github.io/spec/core/text/instructions.html
        // I changed begin/end to {} because of AI
        is OpCodes.Flow.Block -> "block${type?.let { " (result ${it.toSignature()})" } ?: ""}{\n${value.wat()}\n}"
        // TODO add more opcodes. Protect somehow from 100+ lines switch?
        else -> structuredWatString()
    }
}

private fun MemArg.watString() = "offset=$offset align=$align"

private fun OpCode.structuredWatString(): String =
    when (this) {
        is OpCodeGCStruct -> "${mnemonic()} $typeIndex $fieldIndex"
        is OpCodeGCSrcDst -> "${mnemonic()} $typeIndexDst $typeIndexSrc"
        is OpCodeGCBreak -> "${mnemonic()} $label $srcType $dstType"
        is OpCodeWithTwoValue<*, *> -> "${mnemonic()} ${value0.renderOperand()} ${value1.renderOperand()}"
        is OpCodeWithValue<*> -> {
            val operand = value
            when {
                operand is List<*> && operand.all { it is OpCode } -> "${mnemonic()}{\n${
                    operand.filterIsInstance<OpCode>().wat()
                }\n}"

                operand == Unit -> mnemonic()
                else -> "${mnemonic()} ${operand.renderOperand()}"
            }
        }

        else -> mnemonic()
    }

// TODO deal with it somehow
// Self-made fallback (best effort)
// OpCodes$GC$RefCastNullable -> gc.ref_cast_nullable
// OpCodes$Flow$BrIf -> flow.br_if
private fun OpCode.mnemonic(): String {
    val nestedName = javaClass.name.substringAfter("OpCodes$").substringAfterLast('.')
    return nestedName
        .split('$')
        .joinToString(".") { segment ->
            when {
                segment.isEmpty() -> segment
                segment.all { it.isUpperCase() || it.isDigit() } -> segment.lowercase()
                else -> segment
                    .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                    .lowercase()
            }
        }
}

private fun Any?.renderOperand(): String =
    when (this) {
        null -> "null"
        is MemArg -> watString()
        is OpCodeHeapType -> toHeapTypeName()
        is WasmHeapType -> toHeapTypeName()
        is WasmValueType<*> -> toSignature()
        is List<*> -> joinToString(", ", prefix = "[", postfix = "]") { it.renderOperand() }
        is UByte, is UShort, is UInt, is ULong -> toString()
        is String, is Number, is Boolean, is Char -> toString()
        else -> error("Unhandled MCP operand type: ${this::class.qualifiedName}")
    }

// TODO deal with it somehow
// Why the mapping has both -23 and 0x69:
//
//  - opcode GC instructions parse heap types through consumeSignedInt(), not through the section-level WasmHeapType model
//  - see /home/vladislavkuznetsov/Vl/Work/Hexana/wasmParser/src/commonMain/kotlin/org/jetbrains/hexana/wasm/opcodes/consumeGCOpCode.kt:21
//  - so the same heap type may appear as:
//      - signed SLEB value like -23
//      - or positive hex form like 0x69
private fun OpCodeHeapType.toHeapTypeName(): String =
    when (type) {
        -23, 0x69 -> "exn"
        -22, 0x6A -> "array"
        -21, 0x6B -> "struct"
        -20, 0x6C -> "i31"
        -19, 0x6D -> "eq"
        -18, 0x6E -> "any"
        -17, 0x6F -> "extern"
        -16, 0x70 -> "func"
        -15, 0x71 -> "none"
        -14, 0x72 -> "noextern"
        -13, 0x73 -> "nofunc"
        -12, 0x74 -> "noexn"
        else -> "type[$type]"
    }
