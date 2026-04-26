package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.opcodes.OpCodes.Memory
import org.jetbrains.hexana.wasm.opcodes.numeric.I32
import org.jetbrains.hexana.wasm.opcodes.numeric.I64
import org.jetbrains.hexana.wasm.opcodes.table.Element
import org.jetbrains.hexana.wasm.opcodes.table.Table

fun CommonByteBuffer.consumeMultibyteOpCode(offset: Int, base: UByte, opcode: UInt): OpCode {
    return when (base) {
        0xFBu.toUByte() -> {
            consumeGCOpCode(offset, opcode)
        }
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
        Memory.Init.code -> Memory.Init(getULEB128().toUInt(), getULEB128().toUInt(), offset)
        Memory.Data.Drop.code -> Memory.Data.Drop(getULEB128().toUInt(), offset)
        Memory.Copy.code -> Memory.Copy(getULEB128().toUInt(), getULEB128().toUInt(), offset)
        Memory.Fill.code -> Memory.Fill(getULEB128().toUInt(), offset)
        Table.Init.code -> Table.Init(getULEB128().toUInt(), getULEB128().toUInt(), offset)
        Table.Copy.code -> Table.Copy(getULEB128().toUInt(), getULEB128().toUInt(), offset)
        Table.Grow.code -> Table.Grow(getULEB128().toUInt(), offset)
        Table.Size.code -> Table.Size(getULEB128().toUInt(), offset)
        Table.Fill.code -> Table.Fill(getULEB128().toUInt(), offset)
        Element.Drop.code -> Element.Drop(getULEB128().toUInt(), offset)
        else -> throw UnsupportedOpsException(code, offset)
    }
}
