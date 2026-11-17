package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer

fun CommonByteBuffer.consumeGCOpCode(offset: Int, opcode: UInt): OpCode {
    return when (opcode) {
        0x00u -> OpCodes.GC.StructNew(consumeInt(), offset)
        0x01u -> OpCodes.GC.StructNewDefault(consumeInt(), offset)
        0x02u -> OpCodes.GC.StructGet(consumeInt(), consumeInt(), offset)
        0x03u -> OpCodes.GC.StructGetSignExtended(consumeInt(), consumeInt(), offset)
        0x04u -> OpCodes.GC.StructGetZeroExtended(consumeInt(), consumeInt(), offset)
        0x05u -> OpCodes.GC.StructSet(consumeInt(), consumeInt(), offset)
        0x06u -> OpCodes.GC.ArrayNew(consumeInt(), offset)
        0x07u -> OpCodes.GC.ArrayNewDefault(consumeInt(), offset)
        0x08u -> OpCodes.GC.ArrayNewFixed(consumeInt(), consumeInt(), offset)
        0x09u -> OpCodes.GC.ArrayNewData(consumeInt(), consumeInt(), offset)
        0x0Au -> OpCodes.GC.ArrayNewElement(consumeInt(), consumeInt(), offset)
        0x10u -> OpCodes.GC.ArrayFill(consumeInt(), offset)
        0x11u -> OpCodes.GC.ArrayCopy(consumeInt(), consumeInt(), offset)
        0x12u -> OpCodes.GC.ArrayInitData(consumeInt(), consumeInt(), offset)
        0x13u -> OpCodes.GC.ArrayInitElem(consumeInt(), consumeInt(), offset)
        0x0Bu -> OpCodes.GC.ArrayGet(consumeInt(), offset)
        0x0Cu -> OpCodes.GC.ArrayGetSigned(consumeInt(), offset)
        0x0Du -> OpCodes.GC.ArrayGetUnsigned(consumeInt(), offset)
        0x0Eu -> OpCodes.GC.ArraySet(consumeInt(), offset)
        0x0Fu -> OpCodes.GC.ArrayLength
        0x14u -> OpCodes.GC.RefTest(HeapType(consumeSignedInt()), offset)
        0x15u -> OpCodes.GC.RefTestNullable(HeapType(consumeSignedInt()), offset)
        0x16u -> OpCodes.GC.RefCast(HeapType(consumeSignedInt()), offset)
        0x17u -> OpCodes.GC.RefCastNullable(HeapType(consumeSignedInt()), offset)
        0x18u -> OpCodes.GC.BrOnCast(get(),consumeInt(), HeapType(consumeSignedInt()), HeapType(consumeSignedInt()), offset)
        0x19u -> OpCodes.GC.BrOnCastFail(get(), consumeInt(), HeapType(consumeSignedInt()), HeapType(consumeSignedInt()), offset)
        0x1Au -> OpCodes.GC.AnyConvertExtern
        0x1Bu -> OpCodes.GC.ExternConvertAny
        0x1Cu -> OpCodes.GC.RefI31
        0x1Du -> OpCodes.GC.I31GetS
        0x1Eu -> OpCodes.GC.I31GetU
        else -> throw UnsupportedOpsException(opcode, offset)
    }
}

