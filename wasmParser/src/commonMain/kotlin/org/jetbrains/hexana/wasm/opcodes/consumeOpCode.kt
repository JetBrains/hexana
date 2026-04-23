package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Flow
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Local
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Ref
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.i64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory.f64
import org.jetbrains.hexana.wasm.opcodes.numeric.F32
import org.jetbrains.hexana.wasm.opcodes.numeric.F64
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.blockType
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.heapType
import org.jetbrains.hexana.wasm.sections.StandardTypesParser.valueType
import org.jetbrains.hexana.wasm.sections.WasmValueType

fun CommonByteBuffer.consumeOpCode(bcode: UByte): OpCode {
    val offset = position() - 1
    return when (val code = bcode.toUInt()) {
        0x00u -> OpCodes.ParametricInstructions.Unreachable
        0x01u -> OpCodes.ParametricInstructions.Nop
        0x02u -> {
            Flow.Block(blockType(), consumeBlock(), offset)
        }
        0x03u -> {
            Flow.Loop(blockType(), consumeBlock(), offset)
        }
        0x04u -> {
            Flow.If(blockType(), consumeBlock(), offset)
        }
        0x05u -> {
            val opCodes = parseOpCodes()
            // If and Else sections share terminal OpCode - that said, we just (sort of) give it back to parent If declaration
            position(position() - 1)
            Flow.Else(opCodes, offset)
        }
        0x06u,
        0x07u -> ReservedOpCode(code, offset)
        0x08u -> Flow.Trow(consumeInt(), offset)
        0x09u -> ReservedOpCode(code, offset)
        0x0Au -> Flow.ThrowRef
        0x0Bu -> Flow.Terminal
        0x0Cu -> Flow.Br(consumeInt(), offset)
        0x0Du -> Flow.BrIf(consumeInt(), offset)
        0x0Eu -> {
            Flow.BrTable(buildList {
                // TODO: 🐛 without this@consumeOpCode this will fail in JS (and it shouldn't)
                consumeArray { add(getULEB128().toUInt()) }
            }, getULEB128().toUInt(), offset)
        }
        0x0Fu -> Flow.Return

        0x10u -> Flow.Call(consumeInt(), offset)
        0x11u -> {
            val value = getULEB128().toInt()
            Flow.CallIndirect(value, offset)
        }
        0x12u -> Flow.ReturnCall
        0x13u -> Flow.ReturnCallIndirect(consumeInt(), consumeInt(), offset)
        0x14u -> Flow.CallRef(consumeInt(), offset)
        0x15u -> Flow.ReturnCallRef(consumeInt(), offset)
        0x16u, 0x17u, 0x18u, 0x19u -> ReservedOpCode(code, offset)
        0x1Au -> OpCodes.ParametricInstructions.Drop
        0x1Bu -> OpCodes.ParametricInstructions.Select
        0x1Cu -> {
            val list = mutableListOf<WasmValueType<*>>()
            consumeArray {
                valueType()?.let { element -> list.add(element) }
            }
            OpCodes.ParametricInstructions.SelectWithType(list.toList(), offset)
        }
        0x1Du, 0x1Eu -> ReservedOpCode(code, offset)
        0x1Fu -> {
            val blockType = blockType()
            val catches = mutableListOf<Flow.CatchType>()
            consumeArray {
                when (val catchType = get()) {
                    0x0.toByte() -> catches.add(Flow.CatchType.Catch(getULEB128().toUInt(), getULEB128().toUInt()))
                    0x1.toByte() -> catches.add(Flow.CatchType.CatchRef(getULEB128().toUInt(), getULEB128().toUInt()))
                    0x2.toByte() -> catches.add(Flow.CatchType.CatchAll(getULEB128().toUInt()))
                    0x3.toByte() -> catches.add(Flow.CatchType.CatchAllRef(getULEB128().toUInt()))
                    else -> throw UnsupportedOpsException(code, position())
                }
            }
            val opcodes = parseOpCodes()
            Flow.TryTable(blockType, catches.toList(), opcodes, offset)
        }
        0x20u -> Local.Get(consumeInt(), offset)
        0x21u -> Local.Set(consumeInt(), offset)
        0x22u -> Local.Tee(consumeInt(), offset)
        0x23u -> OpCodes.Global.Get(consumeInt(), offset)
        0x24u -> OpCodes.Global.Set(consumeInt(), offset)
        0x25u -> OpCodes.Table.Get(consumeInt(), offset)
        0x26u -> OpCodes.Table.Set(consumeInt(), offset)
        0x27u -> ReservedOpCode(code, offset)
        0x28u -> i32.Load(consumeMemArg(), offset)
        0x29u -> i64.Load(consumeMemArg(), offset)
        0x2Au -> f32.Load(consumeMemArg(), offset)
        0x2Bu -> f64.Load(consumeMemArg(), offset)
        0x2Cu -> i32.Load8_S(consumeMemArg(), offset)
        0x2Du -> i32.Load8_U(consumeMemArg(), offset)
        0x2Eu -> i32.Load16_S(consumeMemArg(), offset)
        0x2Fu -> i32.Load16_U(consumeMemArg(), offset)

        0x30u -> i64.Load8_S(consumeMemArg(), offset)
        0x31u -> i64.Load8_U(consumeMemArg(), offset)
        0x32u -> i64.Load16_S(consumeMemArg(), offset)
        0x33u -> i64.Load16_U(consumeMemArg(), offset)
        0x34u -> i64.Load32_S(consumeMemArg(), offset)
        0x35u -> i64.Load32_U(consumeMemArg(), offset)
        0x36u -> i32.Store(consumeMemArg(), offset)
        0x37u -> i64.Store(consumeMemArg(), offset)
        0x38u -> f32.Store(consumeMemArg(), offset)
        0x39u -> f64.Store(consumeMemArg(), offset)

        0x3Au -> i32.Store8(consumeMemArg(), offset)
        0x3Bu -> i32.Store16(consumeMemArg(), offset)
        0x3Cu -> i64.Store8(consumeMemArg(), offset)
        0x3Du -> i64.Store16(consumeMemArg(), offset)
        0x3Eu -> i64.Store32(consumeMemArg(), offset)
        0x3Fu -> OpCodes.Memory.Size(consumeInt(), offset)

        0x40u -> OpCodes.Memory.Grow(consumeInt(), offset)
        0x41u -> I32.Const(consumeSignedInt(), offset)
        0x42u -> I64.Const(consumeSignedLong(), offset)
        0x43u -> F32.Const(consumeFloat(), offset)
        0x44u -> F64.Const(consumeDouble(), offset)
        0x45u -> I32.Eqz
        0x46u -> I32.Eq
        0x47u -> I32.Ne
        0x48u -> I32.LTS
        0x49u -> I32.LTU
        0x4Au -> I32.GTS
        0x4Bu -> I32.GTU
        0x4Cu -> I32.LES
        0x4Du -> I32.LEU
        0x4Eu -> I32.GES
        0x4Fu -> I32.GEU

        0x50u -> I64.Eqz
        0x51u -> I64.EQ
        0x52u -> I64.NE
        0x53u -> I64.LTS
        0x54u -> I64.LTU
        0x55u -> I64.GTS
        0x56u -> I64.GTU
        0x57u -> I64.LES
        0x58u -> I64.LEU
        0x59u -> I64.GES
        0x5Au -> I64.GEU
        0x5Bu -> F32.EQ
        0x5Cu -> F32.NE
        0x5Du -> F32.LT
        0x5Eu -> F32.GT
        0x5Fu -> F32.LE

        0x60u -> F32.GE
        0x61u -> F64.EQ
        0x62u -> F64.NE
        0x63u -> F64.LT
        0x64u -> F64.GT
        0x65u -> F64.LE
        0x66u -> F64.GE
        0x67u -> I32.Clz
        0x68u -> I32.Ctz
        0x69u -> I32.PopCnt
        0x6Au -> I32.Add
        0x6Bu -> I32.Sub
        0x6Cu -> I32.Mul
        0x6Du -> I32.DivS
        0x6Eu -> I32.DivU
        0x6Fu -> I32.RemS

        0x70u -> I32.RemU
        0x71u -> I32.And
        0x72u -> I32.Or
        0x73u -> I32.Xor
        0x74u -> I32.Shl
        0x75u -> I32.ShrS
        0x76u -> I32.ShrU
        0x77u -> I32.Rotl
        0x78u -> I32.Rotr
        0x79u -> I64.Clz
        0x7Au -> I64.Ctz
        0x7Bu -> I64.PopCnt
        0x7Cu -> I64.Add
        0x7Du -> I64.Sub
        0x7Eu -> I64.Mul
        0x7Fu -> I64.DivS

        0x80u -> I64.DivU
        0x81u -> I64.RemS
        0x82u -> I64.RemU
        0x83u -> I64.And
        0x84u -> I64.Or
        0x85u -> I64.Xor
        0x86u -> I64.Shl
        0x87u -> I64.ShrS
        0x88u -> I64.ShrU
        0x89u -> I64.RotL
        0x8Au -> I64.RotR
        0x8Bu -> F32.Abs
        0x8Cu -> F32.Neg
        0x8Du -> F32.Ceil
        0x8Eu -> F32.Floor
        0x8Fu -> F32.Trunc

        0x90u -> F32.Nearest
        0x91u -> F32.Sqrt
        0x92u -> F32.Add
        0x93u -> F32.Sub
        0x94u -> F32.Mul
        0x95u -> F32.Div
        0x96u -> F32.Min
        0x97u -> F32.Max
        0x98u -> F32.CopySign
        0x99u -> F64.Abs
        0x9Au -> F64.Neg
        0x9Bu -> F64.Ceil
        0x9Cu -> F64.Floor
        0x9Du -> F64.Trunc
        0x9Eu -> F64.Nearest
        0x9Fu -> F64.Sqrt

        0xA0u -> F64.Add
        0xA1u -> F64.Sub
        0xA2u -> F64.Mul
        0xA3u -> F64.Div
        0xA4u -> F64.Min
        0xA5u -> F64.Max
        0xA6u -> F64.Copysign
        0xA7u -> I32.WrapI64
        0xA8u -> I32.TruncF32S
        0xA9u -> I32.TruncF32U
        0xAAu -> I32.TruncF64S
        0xABu -> I32.TruncF64S
        0xACu -> I64.Extend_I32_S
        0xADu -> I64.Extend_I32_U
        0xAEu -> I64.TruncF32S
        0xAFu -> I64.TruncF32U

        0xB0u -> I64.TruncF64S
        0xB1u -> I64.TruncF64U
        0xB2u -> F32.ConvertI32S
        0xB3u -> F32.ConvertI32U
        0xB4u -> F32.ConvertI64S
        0xB5u -> F32.ConvertI64U
        0xB6u -> F32.DemoteF64
        0xB7u -> F64.ConvertI32S
        0xB8u -> F64.ConvertI32U
        0xB9u -> F64.ConvertI64S
        0xBAu -> F64.ConvertI64U
        0xBBu -> F64.PromoteF32
        0xBCu -> I32.ReinterpretF32
        0xBDu -> I64.ReinterpretF64
        0xBEu -> F32.ReinterpretI32
        0xBFu -> F64.ReinterpretI64

        0xC0u -> I32.Extend8S
        0xC1u -> I32.Extend16S
        0xC2u -> I64.Extend8S
        0xC3u -> I64.Extend16S
        0xC4u -> I64.Extend32S
        0xC5u, 0xC6u, 0xC7u, 0xC8u, 0xC9u, 0xCAu, 0xCBu, 0xCCu, 0xCDu, 0xCEu, 0xCFu -> ReservedOpCode(code, offset)
        0xD0u -> Ref.Null(heapType(), offset)
        0xD1u -> Ref.IsNull
        0xD2u -> Ref.Func(consumeInt(), offset)
        0xD3u -> Ref.Eq
        0xD4u -> Ref.AsNonNull
        0xD5u -> Flow.BrOnNull(consumeInt(), offset)
        0xD6u -> Flow.BrOnNonNull(consumeInt(), offset)
        in 0xD7u..0xFAu -> ReservedOpCode(code, offset)

        0xFBu, 0xFCu, 0xFDu, 0xFFu -> {
            consumeMultibyteOpCode(position() - 1,  code.toUByte(),getULEB128().toUInt())
        }
        else -> throw UnsupportedOpsException(code, position())
    }
}

private fun CommonByteBuffer.consumeBlock(): List<OpCode> {
    val blockOpCodes = parseOpCodes()
    return blockOpCodes
}

fun CommonByteBuffer.parseOpCodes(): List<OpCode> {
    val opCodes = mutableListOf<OpCode>()
    while (remaining() != 0) {
        val byte = get().toUByte()
        if (byte == Flow.Terminal.code) {
            break
        }

        val op = consumeOpCode(byte)
        opCodes.add(op)
    }
    return opCodes
}
