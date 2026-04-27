package org.jetbrains.hexana.mcp

import org.jetbrains.hexana.wasm.opcodes.*
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f32
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.opcodes.table.Element
import org.jetbrains.hexana.wasm.opcodes.table.Table
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
        is OpCodes.Flow.ReturnCall -> "return_call $value"
        is OpCodes.ParametricInstructions.Drop -> "drop"
        is OpCodes.ParametricInstructions.Select -> "select"
        is OpCodes.ParametricInstructions.SelectWithType -> "select"
        is OpCodes.ParametricInstructions.Unreachable -> "unreachable"
        is OpCodes.ParametricInstructions.Nop -> "nop"
        is OpCodes.Flow.Call -> "call ${value}"
        is OpCodes.Flow.CallIndirect -> "call_indirect $value0 $value1"
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
        is I32.Const -> "i32.const $value"
        is I32.Shl -> "i32.shl"
        is I32.PopCnt -> "i32.popcnt"
        is I32.And -> "i32.and"
        is I32.Or -> "i32.or"
        is I32.Xor -> "i32.xor"
        is I32.Add -> "i32.add"
        is I32.Sub -> "i32.sub"
        is I32.Mul -> "i32.mul"
        is I32.Rotl -> "i32.rotl"
        is I32.Rotr -> "i32.rotr"
        is I32.Clz -> "i32.clz"
        is I32.Ctz -> "i32.ctz"
        is I32.Eqz -> "i32.eqz"
        is I32.Eq -> "i32.eq"
        is I32.Ne -> "i32.ne"
        is I32.LTS -> "i32.lt_s"
        is I32.LTU -> "i32.lt_u"
        is I32.LES -> "i32.le_s"
        is I32.LEU -> "i32.le_u"
        is I32.GTS -> "i32.gt_s"
        is I32.GTU -> "i32.gt_u"
        is I32.GES -> "i32.ge_s"
        is I32.GEU -> "i32.ge_u"
        is I32.RemS -> "i32.rem_s"
        is I32.RemU -> "i32.rem_u"
        is I32.WrapI64 -> "i32.wrap_i64"
        is I32.Extend16S -> "i32.extend16_s"
        is I32.ReinterpretF32 -> "i32.reinterpret_f32"
        is I32.TruncF32S -> "i32.trunc_f32_s"
        is I32.TruncF32U -> "i32.trunc_f32_u"
        is I32.TruncF64S -> "i32.trunc_f64_s"
        is I32.TruncF64U -> "i32.trunc_f64_u"
        is I32.DivU -> "i32.div_u"
        is I32.DivS -> "i32.div_s"
        is I32.ShrU -> "i32.shr_u"
        is I32.ShrS -> "i32.shr_s"
        /* i64 */
        is i64.Load -> "i64.load ${value.watString()}"
        is i64.Load8_S -> "i64.load8_s ${value.watString()}"
        is i64.Load8_U -> "i64.load8_u ${value.watString()}"
        is i64.Load16_U -> "i64.load16_u ${value.watString()}"
        is i64.Store -> "i64.store ${value.watString()}"
        is i64.Store8 -> "i64.store8 ${value.watString()}"
        is i64.Store16 -> "i64.store16 ${value.watString()}"
        is I64.Const -> "i64.const $value"
        is I64.Shl -> "i64.shl"
        is I64.PopCnt -> "i64.popcnt"
        is I64.And -> "i64.and"
        is I64.Or -> "i64.or"
        is I64.Xor -> "i64.xor"
        is I64.Add -> "i64.add"
        is I64.Sub -> "i64.sub"
        is I64.Mul -> "i64.mul"
        is I64.RotL -> "i64.rotl"
        is I64.RotR -> "i64.rotr"
        is I64.Extend_I32_S -> "i64.extend_i32_s"
        is I64.Extend_I32_U -> "i64.extend_i32_u"
        is I64.Clz -> "i64.clz"
        is I64.Ctz -> "i64.ctz"
        is I64.Eqz -> "i64.eqz"
        is I64.LTS -> "i64.lt_s"
        is I64.LTU -> "i64.lt_u"
        is I64.LES -> "i64.le_s"
        is I64.LEU -> "i64.le_u"
        is I64.GTS -> "i64.gt_s"
        is I64.GTU -> "i64.gt_u"
        is I64.GES -> "i64.ge_s"
        is I64.GEU -> "i64.ge_u"
        is I64.RemS -> "i64.rem_s"
        is I64.RemU -> "i64.rem_u"
        is I64.TruncF32S -> "i64.trunc_f32_s"
        is I64.TruncF32U -> "i64.trunc_f32_u"
        is I64.TruncF64S -> "i64.trunc_f64_s"
        is I64.TruncF64U -> "i64.trunc_f64_u"
        is I64.ReinterpretF64 -> "i64.reinterpret_f64"
        is I64.DivU -> "i64.div_u"
        is I64.DivS -> "i64.div_s"
        is I64.ShrU -> "i64.shr_u"
        is I64.ShrS -> "i64.shr_s"
        /* f32 */
        is F32.Const -> "f32.const $value"
        is F32.Nearest -> "f32.nearest"
        is F32.NE -> "f32.ne"
        is F32.LE -> "f32.le"
        is F32.Ceil -> "f32.ceil"
        is F32.Floor -> "f32.floor"
        is F32.Sub -> "f32.sub"
        is F32.Mul -> "f32.mul"
        is F32.Trunc -> "f32.trunc"
        is F32.CopySign -> "f32.copysign"
        is f32.Load -> "f32.load ${value.watString()}"
        is f32.Store -> "f32.store ${value.watString()}"
        is F32.Sqrt -> "f32.sqrt"
        is F32.Add -> "f32.add"
        is F32.Div -> "f32.div"
        is F32.Min -> "f32.min"
        is F32.Max -> "f32.max"
        is F32.DemoteF64 -> "f32.demote_f64"
        is F32.ReinterpretI32 -> "f32.reinterpret_i32"
        is F32.Neg -> "f32.neg"
        is F32.ConvertI32S -> "f32.convert_i32_s"
        is F32.ConvertI32U -> "f32.convert_i32_u"
        is F32.ConvertI64S -> "f32.convert_i64_s"
        is F32.ConvertI64U -> "f32.convert_i64_u"
        is F32.EQ -> "f32.eq"
        is F32.GE -> "f32.ge"
        is F32.GT -> "f32.gt"
        is F32.LT -> "f32.lt"
        is F32.Abs -> "f32.abs"
        /* f64 */
        is F64.Const -> "f64.const $value"
        is F64.Nearest -> "f64.nearest"
        is F64.NE -> "f64.ne"
        is F64.LE -> "f64.le"
        is F64.Ceil -> "f64.ceil"
        is F64.Floor -> "f64.floor"
        is F64.Sub -> "f64.sub"
        is F64.Mul -> "f64.mul"
        is F64.Trunc -> "f64.trunc"
        is F64.Copysign -> "f64.copysign"
        is F64.Sqrt -> "f64.sqrt"
        is F64.Add -> "f64.add"
        is F64.Div -> "f64.div"
        is F64.Min -> "f64.min"
        is F64.Max -> "f64.max"
        is F64.PromoteF32 -> "f64.promote_f32"
        is F64.ReinterpretI64 -> "f64.reinterpret_i64"
        is F64.Neg -> "f64.neg"
        is F64.ConvertI32S -> "f64.convert_i32_s"
        is F64.ConvertI32U -> "f64.convert_i32_u"
        is F64.ConvertI64S -> "f64.convert_i64_s"
        is F64.ConvertI64U -> "f64.convert_i64_u"
        is F64.EQ -> "f64.eq"
        is F64.GE -> "f64.ge"
        is F64.GT -> "f64.gt"
        is F64.LT -> "f64.lt"
        is F64.Abs -> "f64.abs"
        is Element.Drop -> "elem.drop $value"
        is Table.Init -> "table.init $value1 $value0"
        is OpCodes.Memory.Init -> "memory.init $dataIdx"
        is OpCodes.Memory.Data.Drop -> "data.drop $dataIdx"
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
