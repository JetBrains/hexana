package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.CommonByteBuffer

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
        other as OpCode<*>
        val thisInit = ::value.isInitialized
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

open class OpCodeUnit : OpCode<Unit>(Unit) {
    override fun CommonByteBuffer.consume() { }
    override fun CommonByteBuffer.consumeValue() { }
}

object OpCodes {
    object Flow {
        object Terminal : OpCodeUnit() {
            const val code: Byte = 0x0b
            const val description: String = "terminal"
        }

        object Return : OpCodeUnit() {
            const val code: Byte = 0x0f
            const val description: String = "return"
        }

        object ReturnCall : OpCodeUnit() {
            const val code: Byte = 0x12
            const val description: String = "return_call"
        }

        object Unreachable : OpCodeUnit() {
            const val code: Byte = 0x00
            const val description: String = "unreachable"
        }

        object Nop : OpCodeUnit() {
            const val code: Byte = 0x01
            const val description: String = "nop"
        }

        class Call(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: Byte = 0x10
                const val description: String = "call"
            }
        }
    }

    object Local {
        class Get(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: Byte = 0x20
                const val description: String = "local.get"
            }
        }

        class Set(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: Byte = 0x21
                const val description: String = "local.set"
            }
        }

        class Tee(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: Byte = 0x22
                const val description: String = "local.tee"
            }
        }
    }

    object Global {
        class Get(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: Byte = 0x23
                const val description: String = "global.get"
            }
        }

        class Set(value: Int? = null) : OpCodeInt(value) {
            companion object {
                const val code: Byte = 0x24
                const val description: String = "global.set"
            }
        }
    }

    object I32 {
        object Shl : OpCodeUnit() {
            const val code: Byte = 0x74
            const val description: String = "i32.shl"
        }

        object Popcnt : OpCodeUnit() {
            const val code: Byte = 0x69
            const val description: String = "i32.popcnt"
        }

        object And : OpCodeUnit() {
            const val code: Byte = 0x71
            const val description: String = "i32.and"
        }

        object Or : OpCodeUnit() {
            const val code: Byte = 0x72
            const val description: String = "i32.or"
        }

        object Xor : OpCodeUnit() {
            const val code: Byte = 0x73
            const val description: String = "i32.xor"
        }

        object Rotl : OpCodeUnit() {
            const val code: Byte = 0x77
            const val description: String = "i32.rotl"
        }

        object Rotr : OpCodeUnit() {
            const val code: Byte = 0x78
            const val description: String = "i32.rotr"
        }

        object Clz : OpCodeUnit() {
            const val code: Byte = 0x67
            const val description: String = "i32.clz"
        }

        object Ctz : OpCodeUnit() {
            const val code: Byte = 0x68
            const val description: String = "i32.ctz"
        }
    }
}

private val UnaryOpcodes = mapOf(
    OpCodes.Flow.Call.code to { OpCodes.Flow.Call() },
    OpCodes.Flow.Unreachable.code to { OpCodes.Flow.Unreachable },
    OpCodes.Flow.Nop.code to { OpCodes.Flow.Nop },
    OpCodes.Local.Get.code to { OpCodes.Local.Get() },
    OpCodes.Local.Set.code to { OpCodes.Local.Set() },
    OpCodes.Local.Tee.code to { OpCodes.Local.Tee() },
    OpCodes.I32.Shl.code to { OpCodes.I32.Shl },
    OpCodes.I32.Popcnt.code to { OpCodes.I32.Popcnt },
    OpCodes.I32.And.code to { OpCodes.I32.And },
    OpCodes.I32.Or.code to { OpCodes.I32.Or },
    OpCodes.I32.Xor.code to { OpCodes.I32.Xor },
    OpCodes.I32.Rotl.code to { OpCodes.I32.Rotl },
    OpCodes.I32.Rotr.code to { OpCodes.I32.Rotr },
    OpCodes.I32.Clz.code to { OpCodes.I32.Clz },
    OpCodes.I32.Ctz.code to { OpCodes.I32.Ctz },
)

fun resolveOpCode(code: Byte): OpCode<*> {
    return UnaryOpcodes[code]?.invoke() ?: error("Unknown opcode $code")
}

fun CommonByteBuffer.consumeOpCode(code: Byte): OpCode<*> {
    val op = resolveOpCode(code)
    with (op) {
        consumeValue()
    }
    return op
}