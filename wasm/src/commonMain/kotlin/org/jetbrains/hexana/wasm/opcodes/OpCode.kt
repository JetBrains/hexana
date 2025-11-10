package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer

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
            const val code: UByte = 0x0fu
            const val description: String = "return"
        }

        object ReturnCall : OpCode<Unit>(Unit) {
            const val code: UByte = 0x12u
            const val description: String = "return_call"
        }

        object Drop : OpCode<Unit>(Unit) {
            const val code: UByte = 0x1au
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

    private val UnaryOpcodes = mapOf<UByte, CommonByteBuffer.() -> OpCode<*>>(
        Flow.Call.code to { Flow.Call(consumeInt()) },
        Flow.CallIndirect.code to {
            val value = getULEB128().toInt()
            get()
            Flow.CallIndirect(value)
         },
        Flow.Unreachable.code to { Flow.Unreachable },
        Flow.Nop.code to { Flow.Nop },
        Flow.Br.code to { Flow.Br(consumeInt()) },
        Flow.BrIf.code to { Flow.BrIf(consumeInt()) },
        Flow.Return.code to { Flow.Return },
        Flow.Drop.code to { Flow.Drop },
        Flow.Select.code to { Flow.Select },
        Flow.Block.code to { Flow.Block(consumeBlock()) },
        Flow.Loop.code to { Flow.Loop(consumeBlock()) },
        Flow.If.code to { Flow.If(consumeBlock()) },
        Flow.Else.code to {
            val opCodes = parseOpCodes()
            // If and Else sections share terminal OpCode - that said, we just (sort of) give it back to parent If declaration
            position(position() - 1)
            Flow.Else(opCodes)
        },
        Local.Get.code to { Local.Get(consumeInt()) },
        Local.Set.code to { Local.Set(consumeInt()) },
        Local.Tee.code to { Local.Tee(consumeInt()) },
        I32.Load.code to { I32.Load(consumeMemArg()) },
        I32.Load8_S.code to { I32.Load8_S(consumeMemArg()) },
        I32.Load8_U.code to { I32.Load8_U(consumeMemArg()) },
        I32.Load16_U.code to { I32.Load16_U(consumeMemArg()) },
        I32.Store8.code to { I32.Store8(consumeMemArg()) },
        I32.Store16.code to { I32.Store16(consumeMemArg()) },
        I32.Store.code to { I32.Store(consumeMemArg()) },
        I32.Shl.code to { I32.Shl },
        I32.PopCnt.code to { I32.PopCnt },
        I32.And.code to { I32.And },
        I32.DivU.code to { I32.DivU },
        I32.DivS.code to { I32.DivS },
        I32.ShrU.code to { I32.ShrU },
        I32.ShrS.code to { I32.ShrS },
        I32.Or.code to { I32.Or },
        I32.Xor.code to { I32.Xor },
        I32.Add.code to { I32.Add },
        I32.Sub.code to { I32.Sub },
        I32.Mul.code to { I32.Mul },
        I32.Rotl.code to { I32.Rotl },
        I32.Rotr.code to { I32.Rotr },
        I32.Clz.code to { I32.Clz },
        I32.Ctz.code to { I32.Ctz },
        I32.Eqz.code to { I32.Eqz },
        I32.Eq.code to { I32.Eq },
        I32.Ne.code to { I32.Ne },
        I32.GTU.code to { I32.GTU },
        I32.GEU.code to { I32.GEU },
        I32.LTS.code to { I32.LTS },
        I32.LTU.code to { I32.LTU },
        I32.LES.code to { I32.LES },
        I32.LEU.code to { I32.LEU },
        I32.GTS.code to { I32.GTS },
        I32.GES.code to { I32.GES },
        I32.RemU.code to { I32.RemU },
        I32.Const.code to { I32.Const(consumeSignedInt()) },
        I32.WrapI64.code to { I32.WrapI64 },
        I32.Extend16S.code to { I32.Extend16S },
        I32.ReinterpretF32.code to { I32.ReinterpretF32 },
        I32.TruncF32S.code to { I32.TruncF32S },
        I32.TruncF32U.code to { I32.TruncF32U },
        I64.Const.code to { I64.Const(consumeSignedLong()) },
        I64.Load.code to { I64.Load(consumeMemArg()) },
        I64.Store.code to { I64.Store(consumeMemArg()) },
        I64.DivS.code to { I64.DivS },
        I64.Extent_I32_S.code to { I64.Extent_I32_S },
        F32.Const.code to { F32.Const(consumeFloat()) },
        F32.NE.code to { F32.NE },
        F32.LE.code to { F32.LE },
        F32.Nearest.code to { F32.Nearest },
        F32.Floor.code to { F32.Floor },
        F32.Mul.code to { F32.Mul },
        F32.Sub.code to { F32.Sub },
        F32.Ceil.code to { F32.Ceil },
        F32.Trunc.code to { F32.Trunc },
        F32.CopySign.code to { F32.CopySign },
        F32.Load.code to { F32.Load(consumeMemArg()) },
        F32.Store.code to { F32.Store(consumeMemArg()) },
        F32.Sqrt.code to { F32.Sqrt },
        F32.Div.code to { F32.Div },
        F32.Add.code to { F32.Add },
        F32.Neg.code to { F32.Neg },
        F32.DemoteF64.code to { F32.DemoteF64 },
        F32.ConvertI32S.code to { F32.ConvertI32S },
        F32.ConvertI32U.code to { F32.ConvertI32U },
        F32.GE.code to { F32.GE },
        F32.LT.code to { F32.LT },
        F32.GT.code to { F32.GT },
        F32.Abs.code to { F32.Abs },
        F64.PromoteF32.code to { F64.PromoteF32 },
        F64.Add.code to { F64.Add },
        F64.Floor.code to { F64.Floor },
        F64.Const.code to { F64.Const(consumeDouble()) },
        Global.Set.code to { Global.Set(consumeInt()) },
        Global.Get.code to { Global.Get(consumeInt()) },
    )

    fun resolveOpCode(code: UByte): CommonByteBuffer.() -> OpCode<*> {
        return UnaryOpcodes[code] ?: error("Unknown opcode $code (0x${code.toString(16).uppercase()})")
    }
}

fun CommonByteBuffer.consumeOpCode(code: UByte): OpCode<*> {
    val op = OpCodes.resolveOpCode(code)
    return op.invoke(this)
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
