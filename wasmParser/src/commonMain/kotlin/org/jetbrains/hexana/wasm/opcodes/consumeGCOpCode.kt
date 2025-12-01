package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer

fun CommonByteBuffer.consumeGCOpCode(opcode: UByte): OpCode {
    val code = opcode.toUInt()
    return when (code) {
        0x00u -> OpCodes.GC.StructNew(consumeInt())
        0x02u -> OpCodes.GC.StructGet(consumeInt(), consumeInt())
        0x03u -> OpCodes.GC.StructGetSignExtended(consumeInt(), consumeInt())
        0x04u -> OpCodes.GC.StructGetZeroExtended(consumeInt(), consumeInt())
        0x05u -> OpCodes.GC.StructSet(consumeInt(), consumeInt())
        0x0Bu -> OpCodes.GC.ArrayGet(consumeInt())

        0x17u -> OpCodes.GC.RefCastNullable(consumeSignedInt())


        else -> throw Exception("GC Opcodes under construction!!! Current opcode: ${opcode.toString(16)}")
    }
}