package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory

fun CommonByteBuffer.consumeMultibyteOpCode(opcode: Long): OpCode {
    throw UnsupportedOpsException(opcode.toUInt(), position())
}

fun CommonByteBuffer.consumeFCBasedOpCode(code: UByte): OpCode {
    return when(code) {
        0x00.toUByte() -> OpCodes.NumericInstructions.i32.TruncSatF32S
        0x01.toUByte() -> OpCodes.NumericInstructions.i32.TruncSatF32U
        0x02.toUByte() -> OpCodes.NumericInstructions.i32.TruncSatF64U
        0x03.toUByte() -> OpCodes.NumericInstructions.i32.TruncSatF64S
        0x04.toUByte() -> OpCodes.NumericInstructions.i64.TruncSatF32S
        0x05.toUByte() -> OpCodes.NumericInstructions.i64.TruncSatF32U
        0x06.toUByte() -> OpCodes.NumericInstructions.i64.TruncSatF64S
        0x07.toUByte() -> OpCodes.NumericInstructions.i64.TruncSatF64U
        Memory.Init.code -> Memory.Init(getULEB128().toUInt(), getULEB128().toUInt())
        Memory.Data.Drop.code -> Memory.Data.Drop(getULEB128().toUInt())
        Memory.Copy.code -> Memory.Copy(getULEB128().toUInt(), getULEB128().toUInt())
        Memory.Fill.code -> Memory.Fill(getULEB128().toUInt())
        else -> throw UnsupportedOpsException(code.toUInt(), position())
    }
}
