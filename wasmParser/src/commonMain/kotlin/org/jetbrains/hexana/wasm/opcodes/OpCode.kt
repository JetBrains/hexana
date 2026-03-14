package org.jetbrains.hexana.wasm.opcodes


data class MemArg(val offset: Int, val align: Int)

interface OpCode

open class OpCodeWithValue<T : Any>(val value: T) : OpCode {
    // basically we are doing this for tests, and maybe we should just do tests differently
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as OpCodeWithValue<*>
        return this.value == other.value
    }

    override fun hashCode(): Int {
        var result = this::class.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }
}

open class OpCodeWithTwoValue<A : Any, B : Any>(val value0: A, val value1: B) : OpCode {
    // basically we are doing this for tests, and maybe we should just do tests differently
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as OpCodeWithTwoValue<*, *>
        return this.value0 == other.value0 && this.value1 == this.value1
    }

    override fun hashCode(): Int {
        var result = this::class.hashCode()
        result = 31 * result + value0.hashCode() + value1.hashCode()
        return result
    }
}

class ReservedOpCode(val code: UInt) : OpCodeWithValue<Unit>(Unit)
class UnsupportedOpsException(val code: UInt, val position: Int):Exception("Unsupported opcode: ${code.toHexString()} @0x${position.toHexString()}")
