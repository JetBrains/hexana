package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes.F32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.F64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Flow
import org.jetbrains.hexana.wasm.opcodes.OpCodes.I32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.I64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Local

data class MemArg(val offset: Int, val align: Int)

private fun CommonByteBuffer.consumeInt(): Int = getULEB128().toInt()
private fun CommonByteBuffer.consumeFloat(): Float = getFloatLE()
private fun CommonByteBuffer.consumeDouble(): Double = getDoubleLE()
private fun CommonByteBuffer.consumeSignedInt(): Int = getSLEB128().toInt()
private fun CommonByteBuffer.consumeSignedLong(): Long = getSLEB128()
private fun CommonByteBuffer.consumeMemArg(): MemArg {
    return MemArg(
        getULEB128().toInt(), getULEB128().toInt()
    )
}

private fun CommonByteBuffer.consumeBlock(): List<OpCode<*>> {
    get() // consume blockType
    val blockOpCodes = parseOpCodes()
    return blockOpCodes
}

open class OpCode<T : Any>(val value: T) {
    // basically we are doing this for tests, and maybe we should just do tests differently
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as OpCode<*>
        return this.value == other.value
    }

    override fun hashCode(): Int {
        var result = this::class.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }
}

object OpCodes {
    object Flow {
        object Terminal : OpCode<Unit>(Unit) {
            const val code: UByte = 0x0Bu
            const val description: String = "terminal"
        }

        object Return : OpCode<Unit>(Unit) {
            const val code: UByte = 0x0Fu
            const val description: String = "return"
        }

        object ReturnCall : OpCode<Unit>(Unit) {
            const val code: UByte = 0x12u
            const val description: String = "return_call"
        }

        object Drop : OpCode<Unit>(Unit) {
            const val code: UByte = 0x1Au
            const val description: String = "drop"
        }

        object Select : OpCode<Unit>(Unit) {
            const val code: UByte = 0x1Bu
            const val description: String = "select"
        }

        object Unreachable : OpCode<Unit>(Unit) {
            const val code: UByte = 0x00u
            const val description: String = "unreachable"
        }

        object Nop : OpCode<Unit>(Unit) {
            const val code: UByte = 0x01u
            const val description: String = "nop"
        }

        class Call(value: Int) : OpCode<Int>(value) {
            companion object {
                const val code: UByte = 0x10u
                const val description: String = "call"
            }
        }

        class CallIndirect(value: Int) : OpCode<Int>(value) {
            companion object {
                const val code: UByte = 0x11u
                const val description: String = "call_indirect"
            }
        }

        class Br(value: Int) : OpCode<Int>(value) {
            companion object {
                const val code: UByte = 0x0Cu
                const val description: String = "br"
            }
        }

        class BrIf(value: Int) : OpCode<Int>(value) {
            companion object {
                const val code: UByte = 0x0Du
                const val description: String = "br_if"
            }
        }

        class Block(value: List<OpCode<*>>) : OpCode<List<OpCode<*>>>(value) {
            companion object {
                const val code: UByte = 0x02u
                const val description: String = "block"
            }
        }

        class Loop(value: List<OpCode<*>>) : OpCode<List<OpCode<*>>>(value) {
            companion object {
                const val code: UByte = 0x03u
                const val description: String = "loop"
            }
        }

        class If(value: List<OpCode<*>>) : OpCode<List<OpCode<*>>>(value) {
            companion object {
                const val code: UByte = 0x04u
                const val description: String = "if"
            }
        }

        class Else(value: List<OpCode<*>>) : OpCode<List<OpCode<*>>>(value) {
            companion object {
                const val code: UByte = 0x05u
                const val description: String = "else"
            }
        }
    }

    object Local {
        class Get(value: Int) : OpCode<Int>(value) {
            companion object {
                const val code: UByte = 0x20u
                const val description: String = "local.get"
            }
        }

        class Set(value: Int) : OpCode<Int>(value) {
            companion object {
                const val code: UByte = 0x21u
                const val description: String = "local.set"
            }
        }

        class Tee(value: Int) : OpCode<Int>(value) {
            companion object {
                const val code: UByte = 0x22u
                const val description: String = "local.tee"
            }
        }
    }

    object Global {
        class Get(value: Int) : OpCode<Int>(value) {
            companion object {
                const val code: UByte = 0x23u
                const val description: String = "global.get"
            }
        }

        class Set(value: Int) : OpCode<Int>(value) {
            companion object {
                const val code: UByte = 0x24u
                const val description: String = "global.set"
            }
        }
    }

    object I32 {
        class Load(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x28u
                const val description: String = "i32.load"
            }
        }

        class Load8_S(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x2Cu
                const val description: String = "i32.load8_s"
            }
        }

        class Load8_U(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x2Du
                const val description: String = "i32.load8_u"
            }
        }

        class Load16_U(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x2Fu
                const val description: String = "i32.load16_u"
            }
        }

        class Store8(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x3Au
                const val description: String = "i32.store8"
            }
        }

        class Store16(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x3Bu
                const val description: String = "i32.store16"
            }
        }

        class Store(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x36u
                const val description: String = "i32.store"
            }
        }

        object Shl : OpCode<Unit>(Unit) {
            const val code: UByte = 0x74u
            const val description: String = "i32.shl"
        }

        object PopCnt : OpCode<Unit>(Unit) {
            const val code: UByte = 0x69u
            const val description: String = "i32.popcnt"
        }

        object And : OpCode<Unit>(Unit) {
            const val code: UByte = 0x71u
            const val description: String = "i32.and"
        }

        object Or : OpCode<Unit>(Unit) {
            const val code: UByte = 0x72u
            const val description: String = "i32.or"
        }

        object Xor : OpCode<Unit>(Unit) {
            const val code: UByte = 0x73u
            const val description: String = "i32.xor"
        }

        object Add : OpCode<Unit>(Unit) {
            const val code: UByte = 0x6Au
            const val description: String = "i32.add"
        }

        object Sub : OpCode<Unit>(Unit) {
            const val code: UByte = 0x6Bu
            const val description: String = "i32.sub"
        }

        object Mul : OpCode<Unit>(Unit) {
            const val code: UByte = 0x6Cu
            const val description: String = "i32.mul"
        }

        object Rotl : OpCode<Unit>(Unit) {
            const val code: UByte = 0x77u
            const val description: String = "i32.rotl"
        }

        object Rotr : OpCode<Unit>(Unit) {
            const val code: UByte = 0x78u
            const val description: String = "i32.rotr"
        }

        object Clz : OpCode<Unit>(Unit) {
            const val code: UByte = 0x67u
            const val description: String = "i32.clz"
        }

        object Ctz : OpCode<Unit>(Unit) {
            const val code: UByte = 0x68u
            const val description: String = "i32.ctz"
        }

        class Const(value: Int) : OpCode<Int>(value) {
            companion object {
                const val code: UByte = 0x41u
                const val description: String = "i32.const"
            }
        }

        object Eqz : OpCode<Unit>(Unit) {
            const val code: UByte = 0x45u
            const val description: String = "i32.eqz"
        }

        object Eq : OpCode<Unit>(Unit) {
            const val code: UByte = 0x46u
            const val description: String = "i32.eq"
        }

        object Ne : OpCode<Unit>(Unit) {
            const val code: UByte = 0x47u
            const val description: String = "i32.ne"
        }

        object LTS : OpCode<Unit>(Unit) {
            const val code: UByte = 0x48u
            const val description: String = "i32.lt_s"
        }

        object LTU : OpCode<Unit>(Unit) {
            const val code: UByte = 0x49u
            const val description: String = "i32.lt_u"
        }

        object LES : OpCode<Unit>(Unit) {
            const val code: UByte = 0x4Cu
            const val description: String = "i32.le_s"
        }

        object LEU : OpCode<Unit>(Unit) {
            const val code: UByte = 0x4Du
            const val description: String = "i32.le_u"
        }

        object GTS : OpCode<Unit>(Unit) {
            const val code: UByte = 0x4Au
            const val description: String = "i32.gt_s"
        }

        object GES : OpCode<Unit>(Unit) {
            const val code: UByte = 0x4Eu
            const val description: String = "i32.ge_s"
        }

        object GTU : OpCode<Unit>(Unit) {
            const val code: UByte = 0x4Bu
            const val description: String = "i32.gt_u"
        }

        object GEU : OpCode<Unit>(Unit) {
            const val code: UByte = 0x4Fu
            const val description: String = "i32.ge_u"
        }

        object RemU : OpCode<Unit>(Unit) {
            const val code: UByte = 0x70u
            const val description: String = "i32.rem_u"
        }

        object WrapI64 : OpCode<Unit>(Unit) {
            const val code: UByte = 0xA7u
            const val description: String = "i32.wrap_i64"
        }

        object Extend16S : OpCode<Unit>(Unit) {
            const val code: UByte = 0xC1u
            const val description: String = "i32.extend16_s"
        }

        object ReinterpretF32 : OpCode<Unit>(Unit) {
            const val code: UByte = 0xBCu
            const val description: String = "i32.reinterpret_f32"
        }

        object TruncF32S : OpCode<Unit>(Unit) {
            const val code: UByte = 0xA8u
            const val description: String = "i32.trunc_f32_s"
        }

        object TruncF32U : OpCode<Unit>(Unit) {
            const val code: UByte = 0xA9u
            const val description: String = "i32.trunc_f32_u"
        }

        object DivU : OpCode<Unit>(Unit) {
            const val code: UByte = 0x6Eu
            const val description: String = "i32.div_u"
        }

        object DivS : OpCode<Unit>(Unit) {
            const val code: UByte = 0x6Du
            const val description: String = "i32.div_s"
        }

        object ShrU : OpCode<Unit>(Unit) {
            const val code: UByte = 0x76u
            const val description: String = "i32.shr_u"
        }

        object ShrS : OpCode<Unit>(Unit) {
            const val code: UByte = 0x75u
            const val description: String = "i32.shr_s"
        }
    }

    object I64 {
        // supposed to be signed long ???
        class Const(value: Long) : OpCode<Long>(value) {
            companion object {
                const val code: UByte = 0x42u
                const val description: String = "i64.const"
            }
        }

        class Load(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x29u
                const val description: String = "i64.load"
            }
        }

        class Store(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x37u
                const val description: String = "i64.store"
            }
        }

        object DivS : OpCode<Unit>(Unit) {
            const val code: UByte = 0x7Fu
            const val description: String = "i64.div_s"
        }

        object Extent_I32_S : OpCode<Unit>(Unit) {
            const val code: UByte = 0xACu
            const val description: String = "i64.extend_i32_s"
        }
    }

    object F32 {
        class Const(initValue: Float) : OpCode<Float>(initValue) {
            companion object {
                const val code: UByte = 0x43u
                const val description: String = "f32.const"
            }
        }

        object Nearest : OpCode<Unit>(Unit) {
            const val code: UByte = 0x90u
            const val description: String = "f32.nearest"
        }

        object NE : OpCode<Unit>(Unit) {
            const val code: UByte = 0x5Cu
            const val description: String = "f32.ne"
        }

        object LE : OpCode<Unit>(Unit) {
            const val code: UByte = 0x5Fu
            const val description: String = "f32.le"
        }

        object Ceil : OpCode<Unit>(Unit) {
            const val code: UByte = 0x8du
            const val description: String = "f32.ceil"
        }

        object Floor : OpCode<Unit>(Unit) {
            const val code: UByte = 0x8eu
            const val description: String = "f32.floor"
        }

        object Sub : OpCode<Unit>(Unit) {
            const val code: UByte = 0x93u
            const val description: String = "f32.sub"
        }

        object Mul : OpCode<Unit>(Unit) {
            const val code: UByte = 0x94u
            const val description: String = "f32.mul"
        }

        object Trunc : OpCode<Unit>(Unit) {
            const val code: UByte = 0x8fu
            const val description: String = "f32.trunc"
        }

        object CopySign : OpCode<Unit>(Unit) {
            const val code: UByte = 0x98u
            const val description: String = "f32.copysign"
        }

        class Load(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x2Au
                const val description: String = "f32.load"
            }
        }

        class Store(value: MemArg) : OpCode<MemArg>(value) {
            companion object {
                const val code: UByte = 0x38u
                const val description: String = "f32.store"
            }
        }

        object Sqrt : OpCode<Unit>(Unit) {
            const val code: UByte = 0x91u
            const val description: String = "f32.sqrt"
        }

        object Add : OpCode<Unit>(Unit) {
            const val code: UByte = 0x92u
            const val description: String = "f32.add"
        }

        object Div : OpCode<Unit>(Unit) {
            const val code: UByte = 0x95u
            const val description: String = "f32.div"
        }

        object DemoteF64 : OpCode<Unit>(Unit) {
            const val code: UByte = 0xB6u
            const val description: String = "f32.demote_f64"
        }

        object Neg : OpCode<Unit>(Unit) {
            const val code: UByte = 0x8Cu
            const val description: String = "f32.neg"
        }

        object ConvertI32S : OpCode<Unit>(Unit) {
            val code: UByte = 0xB2u
            const val description: String = "f32.convert_i32_s"
        }

        object ConvertI32U : OpCode<Unit>(Unit) {
            val code: UByte = 0xB3u
            const val description: String = "f32.convert_i32_u"
        }

        object GE : OpCode<Unit>(Unit) {
            val code: UByte = 0x60u
            const val description: String = "f32.ge"
        }

        object LT : OpCode<Unit>(Unit) {
            val code: UByte = 0x5Du
            const val description: String = "f32.lt"
        }

        object GT : OpCode<Unit>(Unit) {
            val code: UByte = 0x5Eu
            const val description: String = "f32.gt"
        }

        object Abs : OpCode<Unit>(Unit) {
            val code: UByte = 0x8Bu
            const val description: String = "f32.abs"
        }
    }

    object F64 {
        object PromoteF32 : OpCode<Unit>(Unit) {
            const val code: UByte = 0xBBu
            const val description: String = "f64.promote_f32"
        }

        object Add : OpCode<Unit>(Unit) {
            const val code: UByte = 0xA0u
            const val description: String = "f64.add"
        }

        object Floor : OpCode<Unit>(Unit) {
            const val code: UByte = 0x9Cu
            const val description: String = "f64.floor"
        }

        class Const(initValue: Double) : OpCode<Double>(initValue) {
            companion object {
                const val code: UByte = 0x44u
                const val description: String = "f64.const"
            }
        }
    }
}

fun CommonByteBuffer.consumeOpCode(code: UByte): OpCode<*> {
    val codeInt = code.toUInt()
    return when (codeInt) {
        0x00u -> Flow.Unreachable
        0x01u -> Flow.Nop
        0x02u -> Flow.Block(consumeBlock())
        0x03u -> Flow.Loop(consumeBlock())
        0x04u -> Flow.If(consumeBlock())
        0x05u -> {
            val opCodes = parseOpCodes()
            // If and Else sections share terminal OpCode - that said, we just (sort of) give it back to parent If declaration
            position(position() - 1)
            Flow.Else(opCodes)
        }
        0x06u -> null // try
        0x07u -> null // catch
        0x08u -> null // throw
        0x09u -> null // rethrow
        0x0Au -> null // throw_ref
        0x0Bu -> Flow.Terminal
        0x0Cu -> Flow.Br(consumeInt())
        0x0Du -> Flow.BrIf(consumeInt())
        0x0Eu -> null // br_table
        0x0Fu -> Flow.Return

        0x10u -> Flow.Call(consumeInt())
        0x11u -> {
            val value = getULEB128().toInt()
            get()
            Flow.CallIndirect(value)
        }
        0x12u -> Flow.ReturnCall
        0x13u -> null // return_call_indirect
        0x14u -> null // call_ref
        0x15u -> null // return_call_ref
        0x16u -> null // reserved
        0x17u -> null // reserved
        0x18u -> null // delegate
        0x19u -> null // catch_all
        0x1Au -> Flow.Drop
        0x1Bu -> Flow.Select
        0x1Cu -> null // select reference
        0x1Du -> null // reserved
        0x1Eu -> null // reserved
        0x1Fu -> null // try_table

        0x20u -> Local.Get(consumeInt())
        0x21u -> Local.Set(consumeInt())
        0x22u -> Local.Tee(consumeInt())
        0x23u -> OpCodes.Global.Get(consumeInt())
        0x24u -> OpCodes.Global.Set(consumeInt())
        0x25u -> null // table.get
        0x26u -> null // table.set
        0x27u -> null // reserved
        0x28u -> I32.Load(consumeMemArg())
        0x29u -> I64.Load(consumeMemArg())
        0x2Au -> F32.Load(consumeMemArg())
        0x2Bu -> null // f64.load
        0x2Cu -> I32.Load8_S(consumeMemArg())
        0x2Du -> I32.Load8_U(consumeMemArg())
        0x2Eu -> null // i32.load16_s
        0x2Fu -> I32.Load16_U(consumeMemArg())

        0x30u -> null // i64.load8_s
        0x31u -> null // i64.load8_u
        0x32u -> null // i64.load16_s
        0x33u -> null // i64.load16_u
        0x34u -> null // i64.load32_s
        0x35u -> null // i64.load32_u
        0x36u -> I32.Store(consumeMemArg())
        0x37u -> I64.Store(consumeMemArg())
        0x38u -> F32.Store(consumeMemArg())
        0x39u -> null // f64.store
        0x3Au -> I32.Store8(consumeMemArg())
        0x3Bu -> I32.Store16(consumeMemArg())
        0x3Cu -> null // i64.store8
        0x3Du -> null // i64.store16
        0x3Eu -> null // i64.store32
        0x3Fu -> null // memory.size

        0x40u -> null // memory.grow
        0x41u -> I32.Const(consumeSignedInt())
        0x42u -> I64.Const(consumeSignedLong())
        0x43u -> F32.Const(consumeFloat())
        0x44u -> F64.Const(consumeDouble())
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

        0x50u -> null // i64.eqz
        0x51u -> null // i64.eq
        0x52u -> null // i64.ne
        0x53u -> null // i64.lt_s
        0x54u -> null // i64.lt_u
        0x55u -> null // i64.gt_s
        0x56u -> null // i64.gt_u
        0x57u -> null // i64.le_s
        0x58u -> null // i64.le_u
        0x59u -> null // i64.ge_s
        0x5Au -> null // i64.ge_u
        0x5Bu -> null // f32.eq
        0x5Cu -> F32.NE
        0x5Du -> F32.LT
        0x5Eu -> F32.GT
        0x5Fu -> F32.LE

        0x60u -> F32.GE
        0x61u -> null // f64.eq
        0x62u -> null // f64.ne
        0x63u -> null // f64.lt
        0x64u -> null // f64.gt
        0x65u -> null // f64.le
        0x66u -> null // f64.ge
        0x67u -> I32.Clz
        0x68u -> I32.Ctz
        0x69u -> I32.PopCnt
        0x6Au -> I32.Add
        0x6Bu -> I32.Sub
        0x6Cu -> I32.Mul
        0x6Du -> I32.DivS
        0x6Eu -> I32.DivU
        0x6Fu -> null // i32.rem_s

        0x70u -> I32.RemU
        0x71u -> I32.And
        0x72u -> I32.Or
        0x73u -> I32.Xor
        0x74u -> I32.Shl
        0x75u -> I32.ShrS
        0x76u -> I32.ShrU
        0x77u -> I32.Rotl
        0x78u -> I32.Rotr
        0x79u -> null // i64.clz
        0x7Au -> null // i64.ctz
        0x7Bu -> null // i64.popcnt
        0x7Cu -> null // i64.add
        0x7Du -> null // i64.sub
        0x7Eu -> null // i64.mul
        0x7Fu -> I64.DivS

        0x80u -> null // i64.div_u
        0x81u -> null // i64.rem_s
        0x82u -> null // i64.rem_u
        0x83u -> null // i64.and
        0x84u -> null // i64.or
        0x85u -> null // i64.xor
        0x86u -> null // i64.shl
        0x87u -> null // i64.shr_s
        0x88u -> null // i64.shr_u
        0x89u -> null // i64.rotl
        0x8Au -> null // i64.rotr
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
        0x96u -> null // f32.min
        0x97u -> null // f32.max
        0x98u -> F32.CopySign
        0x99u -> null // f64.abs
        0x9Au -> null // f64.neg
        0x9Bu -> null // f64.ceil
        0x9Cu -> F64.Floor
        0x9Du -> null // f64.trunc
        0x9Eu -> null // f64.nearest
        0x9Fu -> null // f64.sqrt

        0xA0u -> F64.Add
        0xA1u -> null // f64.sub
        0xA2u -> null // f64.mul
        0xA3u -> null // f64.div
        0xA4u -> null // f64.min
        0xA5u -> null // f64.max
        0xA6u -> null // f64.copysign
        0xA7u -> I32.WrapI64
        0xA8u -> I32.TruncF32S
        0xA9u -> I32.TruncF32U
        0xAAu -> null // i32.trunc_f64_s
        0xABu -> null // i32.trunc_f64_u
        0xACu -> I64.Extent_I32_S
        0xADu -> null // i64.extend_i32_u
        0xAEu -> null // i64.trunc_f32_s
        0xAFu -> null // i64.trunc_f32_u

        0xB0u -> null // i64.trunc_f64_s
        0xB1u -> null // i64.trunc_f64_u
        0xB2u -> F32.ConvertI32S
        0xB3u -> F32.ConvertI32U
        0xB4u -> null // f32.convert_i64_s
        0xB5u -> null // f32.convert_i64_u
        0xB6u -> F32.DemoteF64
        0xB7u -> null // f64.convert_i32_s
        0xB8u -> null // f64.convert_i32_u
        0xB9u -> null // f64.convert_i64_s
        0xBAu -> null // f64.convert_i64_u
        0xBBu -> F64.PromoteF32
        0xBCu -> I32.ReinterpretF32
        0xBDu -> null // i64.reinterpret_f64
        0xBEu -> null // f32.reinterpret_i32
        0xBFu -> null // f64.reinterpret_i64

        0xC0u -> null // i32.extend8_s
        0xC1u -> I32.Extend16S
        0xC2u -> null // i64.extend8_s
        0xC3u -> null // i64.extend16_s
        0xC4u -> null // i64.extend32_s

        else -> null
    } ?: error("Unknown opcode $code (0x${code.toString(16).uppercase()})")
}

fun CommonByteBuffer.parseOpCodes(): List<OpCode<*>> {
    val opCodes = mutableListOf<OpCode<*>>()
    while (true) {
        val opCode = get().toUByte()
        if (opCode == OpCodes.Flow.Terminal.code) {
            break
        }

        val op = consumeOpCode(opCode)
        opCodes.add(op)
    }
    return opCodes
}
