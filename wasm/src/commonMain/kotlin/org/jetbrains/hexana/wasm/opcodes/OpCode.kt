package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer

data class MemArg(val offset: Int, val align: Int)

sealed class OpCode<T : Any>(initialValue: T? = null) {
    lateinit var value: T
        private set

    init {
        if (initialValue != null) {
            value = initialValue
        }
    }

    abstract fun CommonByteBuffer.consume(): T

    open fun CommonByteBuffer.consumeValue() {
        if (::value.isInitialized) {
            throw IllegalStateException("Value has already been consumed")
        }
        value = consume()
    }

    override fun toString(): String {
        val className = this::class.simpleName ?: this::class.toString()
        val valueStr = if (::value.isInitialized) ":$value" else ""
        return "$className$valueStr"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        val thisInit = ::value.isInitialized

        other as OpCode<*>
        val otherInit = other::value.isInitialized

        return when {
            thisInit && otherInit -> this.value == other.value
            !thisInit && !otherInit -> true
            else -> false
        }
    }

    override fun hashCode(): Int {
        var result = this::class.hashCode()
        if (::value.isInitialized) {
            result = 31 * result + value.hashCode()
        }
        return result
    }
}

open class OpCodeInt(initValue: Int? = null) : OpCode<Int>(initValue) {
    override fun CommonByteBuffer.consume(): Int = getULEB128().toInt()
}

open class OpCodeFloat(initValue: Float? = null) : OpCode<Float>(initValue) {
    override fun CommonByteBuffer.consume(): Float = getFloatLE()
}

open class OpCodeDouble(initValue: Double? = null) : OpCode<Double>(initValue) {
    override fun CommonByteBuffer.consume(): Double = getDoubleLE()
}

open class OpCodeSignedInt(initValue: Int? = null) : OpCode<Int>(initValue) {
    override fun CommonByteBuffer.consume(): Int = getSLEB128().toInt()
}

open class OpCodeSignedLong(initValue: Long? = null) : OpCode<Long>(initValue) {
    override fun CommonByteBuffer.consume(): Long = getSLEB128()
}

open class OpCodeUnit : OpCode<Unit>(Unit) {
    override fun CommonByteBuffer.consume() {}
    override fun CommonByteBuffer.consumeValue() {}
}

open class OpCodeBlock(initValue: List<OpCode<*>>? = null) : OpCode<List<OpCode<*>>>(initValue) {
    override fun CommonByteBuffer.consume(): List<OpCode<*>> {
        val blockType = get()
        val blockOpCodes = parseOpCodes()
        return blockOpCodes
    }
}

open class OpMemBlock(initValue: MemArg? = null) : OpCode<MemArg>(initValue) {
    override fun CommonByteBuffer.consume(): MemArg {
        return MemArg(
            getULEB128().toInt(), getULEB128().toInt()
        )
    }
}

object OpCodes {
    object Flow {
        object Terminal : OpCodeUnit() {
            const val code: UByte = 0x0Bu
            const val description: String = "terminal"
        }

        object Return : OpCodeUnit() {
            const val code: UByte = 0x0fu
            const val description: String = "return"
        }

        object ReturnCall : OpCodeUnit() {
            const val code: UByte = 0x12u
            const val description: String = "return_call"
        }

        object Drop : OpCodeUnit() {
            const val code: UByte = 0x1au
            const val description: String = "drop"
        }

        object Select : OpCodeUnit() {
            const val code: UByte = 0x1Bu
            const val description: String = "select"
        }

        object Unreachable : OpCodeUnit() {
            const val code: UByte = 0x00u
            const val description: String = "unreachable"
        }

        object Nop : OpCodeUnit() {
            const val code: UByte = 0x01u
            const val description: String = "nop"
        }

        class Call(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: UByte = 0x10u
                const val description: String = "call"
            }
        }

        class CallIndirect(value: Int? = null) : OpCode<Int>(value) {
            override fun CommonByteBuffer.consume(): Int {
                val x = getULEB128().toInt()
                get()
                return x
            }

            companion object {
                const val code: UByte = 0x11u
                const val description: String = "call_indirect"
            }
        }

        class Br(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: UByte = 0x0Cu
                const val description: String = "br"
            }
        }

        class BrIf(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: UByte = 0x0Du
                const val description: String = "br_if"
            }
        }

        class Block(value: List<OpCode<*>>? = null) : OpCodeBlock(value) {
            companion object {
                const val code: UByte = 0x02u
                const val description: String = "block"
            }
        }

        class Loop(value: List<OpCode<*>>? = null) : OpCodeBlock(value) {
            companion object {
                const val code: UByte = 0x03u
                const val description: String = "loop"
            }
        }

        class If(value: List<OpCode<*>>? = null) : OpCodeBlock(value) {
            companion object {
                const val code: UByte = 0x04u
                const val description: String = "if"
            }
        }

        class Else(value: List<OpCode<*>>? = null) : OpCode<List<OpCode<*>>>(value) {
            companion object {
                const val code: UByte = 0x05u
                const val description: String = "else"
            }

            override fun CommonByteBuffer.consume(): List<OpCode<*>> {
                val opCodes = parseOpCodes()
                // If and Else sections share terminal OpCode - that said, we just (sort of) give it back to parent If declaration
                position(position() - 1)
                return opCodes
            }
        }
    }

    object Local {

        class Get(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: UByte = 0x20u
                const val description: String = "local.get"
            }
        }

        class Set(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: UByte = 0x21u
                const val description: String = "local.set"
            }
        }

        class Tee(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: UByte = 0x22u
                const val description: String = "local.tee"
            }
        }
    }

    object Global {
        class Get(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: UByte = 0x23u
                const val description: String = "global.get"
            }
        }

        class Set(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: UByte = 0x24u
                const val description: String = "global.set"
            }
        }
    }

    object I32 {
        class Load(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x28u
                const val description: String = "i32.load"
            }
        }

        class Load8_S(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x2Cu
                const val description: String = "i32.load8_s"
            }
        }

        class Load8_U(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x2Du
                const val description: String = "i32.load8_u"
            }
        }

        class Load16_U(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x2Fu
                const val description: String = "i32.load16_u"
            }
        }

        class Store8(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x3Au
                const val description: String = "i32.store8"
            }
        }

        class Store16(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x3Bu
                const val description: String = "i32.store16"
            }
        }

        class Store(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x36u
                const val description: String = "i32.store"
            }
        }

        object Shl : OpCodeUnit() {
            const val code: UByte = 0x74u
            const val description: String = "i32.shl"
        }

        object PopCnt : OpCodeUnit() {
            const val code: UByte = 0x69u
            const val description: String = "i32.popcnt"
        }

        object And : OpCodeUnit() {
            const val code: UByte = 0x71u
            const val description: String = "i32.and"
        }

        object Or : OpCodeUnit() {
            const val code: UByte = 0x72u
            const val description: String = "i32.or"
        }

        object Xor : OpCodeUnit() {
            const val code: UByte = 0x73u
            const val description: String = "i32.xor"
        }

        object Add : OpCodeUnit() {
            const val code: UByte = 0x6Au
            const val description: String = "i32.add"
        }

        object Sub : OpCodeUnit() {
            const val code: UByte = 0x6Bu
            const val description: String = "i32.sub"
        }

        object Mul : OpCodeUnit() {
            const val code: UByte = 0x6Cu
            const val description: String = "i32.mul"
        }

        object Rotl : OpCodeUnit() {
            const val code: UByte = 0x77u
            const val description: String = "i32.rotl"
        }

        object Rotr : OpCodeUnit() {
            const val code: UByte = 0x78u
            const val description: String = "i32.rotr"
        }

        object Clz : OpCodeUnit() {
            const val code: UByte = 0x67u
            const val description: String = "i32.clz"
        }

        object Ctz : OpCodeUnit() {
            const val code: UByte = 0x68u
            const val description: String = "i32.ctz"
        }

        class Const(value: Int? = null) : OpCodeSignedInt(value) {
            companion object {
                const val code: UByte = 0x41u
                const val description: String = "i32.const"
            }
        }

        object Eqz : OpCodeUnit() {
            const val code: UByte = 0x45u
            const val description: String = "i32.eqz"
        }

        object Eq : OpCodeUnit() {
            const val code: UByte = 0x46u
            const val description: String = "i32.eq"
        }

        object Ne : OpCodeUnit() {
            const val code: UByte = 0x47u
            const val description: String = "i32.ne"
        }

        object LTS : OpCodeUnit() {
            const val code: UByte = 0x48u
            const val description: String = "i32.lt_s"
        }

        object LTU : OpCodeUnit() {
            const val code: UByte = 0x49u
            const val description: String = "i32.lt_u"
        }

        object LES : OpCodeUnit() {
            const val code: UByte = 0x4Cu
            const val description: String = "i32.le_s"
        }

        object LEU : OpCodeUnit() {
            const val code: UByte = 0x4Du
            const val description: String = "i32.le_u"
        }

        object GTS : OpCodeUnit() {
            const val code: UByte = 0x4Au
            const val description: String = "i32.gt_s"
        }

        object GES : OpCodeUnit() {
            const val code: UByte = 0x4Eu
            const val description: String = "i32.ge_s"
        }

        object GTU : OpCodeUnit() {
            const val code: UByte = 0x4Bu
            const val description: String = "i32.gt_u"
        }

        object GEU : OpCodeUnit() {
            const val code: UByte = 0x4Fu
            const val description: String = "i32.ge_u"
        }

        object RemU : OpCodeUnit() {
            const val code: UByte = 0x70u
            const val description: String = "i32.rem_u"
        }

        object WrapI64 : OpCodeUnit() {
            const val code: UByte = 0xA7u
            const val description: String = "i32.wrap_i64"
        }

        object Extend16S : OpCodeUnit() {
            const val code: UByte = 0xC1u
            const val description: String = "i32.extend16_s"
        }

        object ReinterpretF32 : OpCodeUnit() {
            const val code: UByte = 0xBCu
            const val description: String = "i32.reinterpret_f32"
        }

        object TruncF32S : OpCodeUnit() {
            const val code: UByte = 0xA8u
            const val description: String = "i32.trunc_f32_s"
        }

        object TruncF32U : OpCodeUnit() {
            const val code: UByte = 0xA9u
            const val description: String = "i32.trunc_f32_u"
        }

        object DivU : OpCodeUnit() {
            const val code: UByte = 0x6Eu
            const val description: String = "i32.div_u"
        }

        object DivS : OpCodeUnit() {
            const val code: UByte = 0x6Du
            const val description: String = "i32.div_s"
        }

        object ShrU : OpCodeUnit() {
            const val code: UByte = 0x76u
            const val description: String = "i32.shr_u"
        }

        object ShrS : OpCodeUnit() {
            const val code: UByte = 0x75u
            const val description: String = "i32.shr_s"
        }
    }

    object I64 {
        class Const(value: Long? = null) : OpCodeSignedLong(value) {
            companion object {
                const val code: UByte = 0x42u
                const val description: String = "i64.const"
            }
        }

        class Load(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x29u
                const val description: String = "i64.load"
            }
        }

        class Store(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x37u
                const val description: String = "i64.store"
            }
        }

        object DivS : OpCodeUnit() {
            const val code: UByte = 0x7Fu
            const val description: String = "i64.div_s"
        }

        object Extent_I32_S : OpCodeUnit() {
            const val code: UByte = 0xACu
            const val description: String = "i64.extend_i32_s"
        }
    }

    object F32 {
        class Const(initValue: Float? = null) : OpCodeFloat(initValue) {
            companion object {
                const val code: UByte = 0x43u
                const val description: String = "f32.const"
            }
        }

        object Nearest : OpCodeUnit() {
            const val code: UByte = 0x90u
            const val description: String = "f32.nearest"
        }

        object NE : OpCodeUnit() {
            const val code: UByte = 0x5Cu
            const val description: String = "f32.ne"
        }

        object LE : OpCodeUnit() {
            const val code: UByte = 0x5Fu
            const val description: String = "f32.le"
        }

        object Ceil : OpCodeUnit() {
            const val code: UByte = 0x8du
            const val description: String = "f32.ceil"
        }

        object Floor : OpCodeUnit() {
            const val code: UByte = 0x8eu
            const val description: String = "f32.floor"
        }

        object Sub : OpCodeUnit() {
            const val code: UByte = 0x93u
            const val description: String = "f32.sub"
        }

        object Mul : OpCodeUnit() {
            const val code: UByte = 0x94u
            const val description: String = "f32.mul"
        }

        object Trunc : OpCodeUnit() {
            const val code: UByte = 0x8fu
            const val description: String = "f32.trunc"
        }

        object CopySign : OpCodeUnit() {
            const val code: UByte = 0x98u
            const val description: String = "f32.copysign"
        }

        class Load(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x2Au
                const val description: String = "f32.load"
            }
        }

        class Store(value: MemArg? = null) : OpMemBlock(value) {
            companion object {
                const val code: UByte = 0x38u
                const val description: String = "f32.store"
            }
        }

        object Sqrt : OpCodeUnit() {
            const val code: UByte = 0x91u
            const val description: String = "f32.sqrt"
        }

        object Add : OpCodeUnit() {
            const val code: UByte = 0x92u
            const val description: String = "f32.add"
        }

        object Div : OpCodeUnit() {
            const val code: UByte = 0x95u
            const val description: String = "f32.div"
        }

        object DemoteF64 : OpCodeUnit() {
            const val code: UByte = 0xB6u
            const val description: String = "f32.demote_f64"
        }

        object Neg : OpCodeUnit() {
            const val code: UByte = 0x8Cu
            const val description: String = "f32.neg"
        }

        object ConvertI32S : OpCodeUnit() {
            val code: UByte = 0xB2u
            const val description: String = "f32.convert_i32_s"
        }

        object ConvertI32U : OpCodeUnit() {
            val code: UByte = 0xB3u
            const val description: String = "f32.convert_i32_u"
        }

        object GE : OpCodeUnit() {
            val code: UByte = 0x60u
            const val description: String = "f32.ge"
        }

        object LT : OpCodeUnit() {
            val code: UByte = 0x5Du
            const val description: String = "f32.lt"
        }

        object GT : OpCodeUnit() {
            val code: UByte = 0x5Eu
            const val description: String = "f32.gt"
        }

        object Abs : OpCodeUnit() {
            val code: UByte = 0x8Bu
            const val description: String = "f32.abs"
        }
    }

    object F64 {
        object PromoteF32 : OpCodeUnit() {
            const val code: UByte = 0xBBu
            const val description: String = "f64.promote_f32"
        }

        object Add : OpCodeUnit() {
            const val code: UByte = 0xA0u
            const val description: String = "f64.add"
        }

        object Floor : OpCodeUnit() {
            const val code: UByte = 0x9Cu
            const val description: String = "f64.floor"
        }

        class Const(initValue: Double? = null) : OpCodeDouble(initValue) {
            companion object {
                const val code: UByte = 0x44u
                const val description: String = "f64.const"
            }
        }
    }

    private val UnaryOpcodes = mapOf(
        Flow.Call.code to { Flow.Call() },
        Flow.CallIndirect.code to { Flow.CallIndirect() },
        Flow.Unreachable.code to { Flow.Unreachable },
        Flow.Nop.code to { Flow.Nop },
        Flow.Br.code to { Flow.Br() },
        Flow.BrIf.code to { Flow.BrIf() },
        Flow.Return.code to { Flow.Return },
        Flow.Drop.code to { Flow.Drop },
        Flow.Select.code to { Flow.Select },
        Flow.Block.code to { Flow.Block() },
        Flow.Loop.code to { Flow.Loop() },
        Flow.If.code to { Flow.If() },
        Flow.Else.code to { Flow.Else() },
        Local.Get.code to { Local.Get() },
        Local.Set.code to { Local.Set() },
        Local.Tee.code to { Local.Tee() },
        I32.Load.code to { I32.Load() },
        I32.Load8_S.code to { I32.Load8_S() },
        I32.Load8_U.code to { I32.Load8_U() },
        I32.Load16_U.code to { I32.Load16_U() },
        I32.Store8.code to { I32.Store8() },
        I32.Store16.code to { I32.Store16() },
        I32.Store.code to { I32.Store() },
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
        I32.Const.code to { I32.Const() },
        I32.WrapI64.code to { I32.WrapI64 },
        I32.Extend16S.code to { I32.Extend16S },
        I32.ReinterpretF32.code to { I32.ReinterpretF32 },
        I32.TruncF32S.code to { I32.TruncF32S },
        I32.TruncF32U.code to { I32.TruncF32U },
        I64.Const.code to { I64.Const() },
        I64.Load.code to { I64.Load() },
        I64.Store.code to { I64.Store() },
        I64.DivS.code to { I64.DivS },
        I64.Extent_I32_S.code to { I64.Extent_I32_S },
        F32.Const.code to { F32.Const() },
        F32.NE.code to { F32.NE },
        F32.LE.code to { F32.LE },
        F32.Nearest.code to { F32.Nearest },
        F32.Floor.code to { F32.Floor },
        F32.Mul.code to { F32.Mul },
        F32.Sub.code to { F32.Sub },
        F32.Ceil.code to { F32.Ceil },
        F32.Trunc.code to { F32.Trunc },
        F32.CopySign.code to { F32.CopySign },
        F32.Load.code to { F32.Load() },
        F32.Store.code to { F32.Store() },
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
        F64.Const.code to { F64.Const() },
        Global.Set.code to { Global.Set() },
        Global.Get.code to { Global.Get() },
    )

    fun resolveOpCode(code: UByte): OpCode<*> {
        return UnaryOpcodes[code]?.invoke() ?: error("Unknown opcode $code (0x${code.toString(16).uppercase()})")
    }
}

fun CommonByteBuffer.consumeOpCode(code: UByte): OpCode<*> {
    val op = OpCodes.resolveOpCode(code)
    with(op) {
        consumeValue()
    }
    return op
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
