package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64

fun CommonByteBuffer.consumeMultibyteOpCode(offset: Int, base: UByte, opcode: UInt): OpCode {
    return when (base) {
        0xFCu.toUByte() -> {
            consumeFCBasedOpCode(offset, opcode)
        }
        0xFDu.toUByte() -> {
            consumeFDBasedOpCode(offset, opcode)
        }
        else -> throw UnsupportedOpsException(opcode, offset)
    }
}

fun CommonByteBuffer.consumeFCBasedOpCode(offset: Int, code: UInt): OpCode {
    return when(code) {
        I32.TruncSatF32S.code -> I32.TruncSatF32S
        I32.TruncSatF32U.code -> I32.TruncSatF32U
        I32.TruncSatF64U.code -> I32.TruncSatF64U
        I32.TruncSatF64S.code -> I32.TruncSatF64S
        I64.TruncSatF32S.code -> I64.TruncSatF32S
        I64.TruncSatF32U.code -> I64.TruncSatF32U
        I64.TruncSatF64S.code -> I64.TruncSatF64S
        I64.TruncSatF64U.code -> I64.TruncSatF64U
        Memory.Init.code -> Memory.Init(getULEB128().toUInt(), getULEB128().toUInt())
        Memory.Data.Drop.code -> Memory.Data.Drop(getULEB128().toUInt())
        Memory.Copy.code -> Memory.Copy(getULEB128().toUInt(), getULEB128().toUInt())
        Memory.Fill.code -> Memory.Fill(getULEB128().toUInt())
        else -> throw UnsupportedOpsException(code, offset)
    }
}
