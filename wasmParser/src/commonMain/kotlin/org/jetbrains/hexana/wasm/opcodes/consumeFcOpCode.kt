package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer

fun CommonByteBuffer.consumeMultibyteOpCode(opcode: Long): OpCode {
    return when (opcode) {
        0xFC00L -> OpCodes.I32.TruncSatF32S
        0xFC01L -> OpCodes.I32.TruncSatF32U
        0xFC02L -> OpCodes.I32.TruncSatF64U
        0xFC03L -> OpCodes.I32.TruncSatF64S
        0xFC04L -> OpCodes.I64.TruncSatF32S
        0xFC05L -> OpCodes.I64.TruncSatF32U
        0xFC06L -> OpCodes.I64.TruncSatF64S
        0xFC07L -> OpCodes.I64.TruncSatF64U
        else -> throw UnsupportedOpsException(opcode.toUInt(), position())
    }
}