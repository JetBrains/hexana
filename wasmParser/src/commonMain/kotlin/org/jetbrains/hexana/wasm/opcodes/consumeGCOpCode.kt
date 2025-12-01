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
        0x07u -> OpCodes.GC.ArrayNewDefault(consumeInt())
        0x09u -> OpCodes.GC.ArrayNewData(consumeInt(), consumeInt())
        0x11u -> OpCodes.GC.ArrayCopy(consumeInt(), consumeInt())
        0x0Bu -> OpCodes.GC.ArrayGet(consumeInt())
        0x0Cu -> OpCodes.GC.ArrayGetSigned(consumeInt())
        0x0Du -> OpCodes.GC.ArrayGetUnsigned(consumeInt())
        0x0Eu -> OpCodes.GC.ArraySet(consumeInt())
        0x0Fu -> OpCodes.GC.ArrayLength

        0x14u -> OpCodes.GC.RefTest(HeapType(consumeSignedInt()))
        0x16u -> OpCodes.GC.RefCast(HeapType(consumeSignedInt()))
        0x17u -> OpCodes.GC.RefCastNullable(HeapType(consumeSignedInt()))
        0x19u -> OpCodes.GC.BrOnCastFail(consumeSignedInt(), consumeInt(), consumeInt())
        0x1Au -> OpCodes.GC.AnyConvertExtern
        0x1Bu -> OpCodes.GC.ExternConvertAny

        else -> throw UnsupportedOpsException(opcode, position())
    }
}

