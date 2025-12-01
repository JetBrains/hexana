package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer

fun CommonByteBuffer.consumeMultibyteOpCode(opcode: Long): OpCode {
    return when (opcode) {
        0L -> OpCodes.I32.TruncSatF32S
        1L -> OpCodes.I32.TruncSatF32U
        2L -> OpCodes.I32.TruncSatF64U
        3L -> OpCodes.I32.TruncSatF64S
        4L -> OpCodes.I64.TruncSatF32S
        5L -> OpCodes.I64.TruncSatF32U
        6L -> OpCodes.I64.TruncSatF64S
        7L -> OpCodes.I64.TruncSatF64U
        else -> throw UnsupportedOpsException(opcode.toUByte(), position())
    }
}