package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes.F32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.F64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Flow
import org.jetbrains.hexana.wasm.opcodes.OpCodes.I32
import org.jetbrains.hexana.wasm.opcodes.OpCodes.I64
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Local
import org.jetbrains.hexana.wasm.sections.WasmHeapType
import org.jetbrains.hexana.wasm.sections.WasmValueType
import org.jetbrains.hexana.wasm.sections.consumeArray
import org.jetbrains.hexana.wasm.sections.heapType
import org.jetbrains.hexana.wasm.sections.valueType

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

class ReservedOpCode(val code: UInt) : OpCodeWithValue<Unit>(Unit)
class UnsupportedOpsException(val code: UInt, val position: Int):Exception("Unsupported opcode: ${code.toHexString()} @0x${position.toHexString()}")
