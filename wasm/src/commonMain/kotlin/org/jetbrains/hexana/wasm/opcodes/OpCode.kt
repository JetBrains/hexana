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

open class OpCodeSignedInt(initValue: Int? = null) : OpCode<Int>(initValue) {
    override fun CommonByteBuffer.consume(): Int = getSLEB128().toInt()
}

open class OpCodeSignedLong(initValue: Long? = null) : OpCode<Long>(initValue) {
    override fun CommonByteBuffer.consume(): Long = getSLEB128()
}

open class OpCodeUnit : OpCode<Unit>(Unit) {
    override fun CommonByteBuffer.consume() { }
    override fun CommonByteBuffer.consumeValue() { }
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

        object Store : OpCodeUnit() {
            const val code: UByte = 0x36u
            const val description: String = "i32.store"
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

        object Eqz: OpCodeUnit() {
            const val code: UByte = 0x45u
            const val description: String = "i32.eqz"
        }

        object Eq: OpCodeUnit() {
            const val code: UByte = 0x46u
            const val description: String = "i32.eq"
        }

        object Ne: OpCodeUnit() {
            const val code: UByte = 0x47u
            const val description: String = "i32.ne"
        }

        object LTS: OpCodeUnit() {
            const val code: UByte = 0x48u
            const val description: String = "i32.lt_s"
        }

        object GTU: OpCodeUnit() {
            const val code: UByte = 0x4Bu
            const val description: String = "i32.gt_u"
        }

        object GEU: OpCodeUnit() {
            const val code: UByte = 0x4Fu
            const val description: String = "i32.ge_u"
        }

        object RemU: OpCodeUnit() {
            const val code: UByte = 0x70u
            const val description: String = "i32.rem_u"
        }

        object WrapI64: OpCodeUnit()  {
            const val code: UByte = 0xA7u
            const val description: String = "i32.wrap_i64"
        }
    }

    object I64 {
        class Const(value: Long? = null) : OpCodeSignedLong(value) {
            companion object {
                const val code: UByte = 0x42u
                const val description: String = "i64.const"
            }
        }

        object Store : OpCodeUnit() {
            const val code: UByte = 0x37u
            const val description: String = "i64.store"
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

        object Ceil : OpCodeUnit() {
            const val code: UByte = 0x8du
            const val description: String = "f32.ceil"
        }

        object Floor : OpCodeUnit() {
            const val code: UByte = 0x8eu
            const val description: String = "f32.floor"
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

        object Sqrt : OpCodeUnit() {
            const val code: UByte = 0x91u
            const val description: String = "f32.sqrt"
        }
    }

    object F64 {
        object PromoteF32 : OpCodeUnit() {
            const val code: UByte = 0xBBu
            const val description: String = "f64.promote_f32"
        }
    }

    private val UnaryOpcodes = mapOf(
        Flow.Call.code to { Flow.Call() },
        Flow.Unreachable.code to { Flow.Unreachable },
        Flow.Nop.code to { Flow.Nop },
        Flow.Br.code to { Flow.Br() },
        Flow.BrIf.code to { Flow.BrIf() },
        Flow.Return.code to { Flow.Return },
        Flow.Drop.code to { Flow.Drop },
        Flow.Block.code to { Flow.Block() },
        Flow.Loop.code to { Flow.Loop() },
        Flow.If.code to { Flow.If() },
        Flow.Else.code to { Flow.Else() },
        Local.Get.code to { Local.Get() },
        Local.Set.code to { Local.Set() },
        Local.Tee.code to { Local.Tee() },
        I32.Load.code to { I32.Load() },
        I32.Store.code to { I32.Store },
        I32.Shl.code to { I32.Shl },
        I32.PopCnt.code to { I32.PopCnt },
        I32.And.code to { I32.And },
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
        I32.RemU.code to { I32.RemU },
        I32.Const.code to { I32.Const() },
        I32.WrapI64.code to { I32.WrapI64 },
        I64.Const.code to { I64.Const() },
        I64.Store.code to { I64.Store },
        I64.DivS.code to { I64.DivS },
        I64.Extent_I32_S.code to { I64.Extent_I32_S },
        F32.Const.code to { F32.Const() },
        F32.Nearest.code to { F32.Nearest },
        F32.Floor.code to { F32.Floor },
        F32.Ceil.code to { F32.Ceil },
        F32.Trunc.code to { F32.Trunc },
        F32.CopySign.code to { F32.CopySign },
        F32.Load.code to { F32.Load() },
        F32.Sqrt.code to { F32.Sqrt },
        F64.PromoteF32.code to { F64.PromoteF32 },
        Global.Set.code to { Global.Set() },
        Global.Get.code to { Global.Get() },
    )

    fun resolveOpCode(code: UByte): OpCode<*> {
        return UnaryOpcodes[code]?.invoke() ?: error("Unknown opcode $code (0x${code.toString(16).uppercase()})")
    }
}

fun CommonByteBuffer.consumeOpCode(code: UByte): OpCode<*> {
    val op = OpCodes.resolveOpCode(code)
    with (op) {
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
