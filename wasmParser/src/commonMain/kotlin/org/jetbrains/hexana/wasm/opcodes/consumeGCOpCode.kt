package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer

fun CommonByteBuffer.consumeGCOpCode(opcode: UInt): OpCode {
    return when (opcode) {
        0xFB00u -> OpCodes.GC.StructNew(consumeInt())
        0xFB01u -> OpCodes.GC.StructNewDefault(consumeInt())
        0xFB02u -> OpCodes.GC.StructGet(consumeInt(), consumeInt())
        0xFB03u -> OpCodes.GC.StructGetSignExtended(consumeInt(), consumeInt())
        0xFB04u -> OpCodes.GC.StructGetZeroExtended(consumeInt(), consumeInt())
        0xFB05u -> OpCodes.GC.StructSet(consumeInt(), consumeInt())
        0xFB06u -> OpCodes.GC.ArrayNew(consumeInt())
        0xFB07u -> OpCodes.GC.ArrayNewDefault(consumeInt())
        0xFB08u -> OpCodes.GC.ArrayNewFixed(consumeInt(), consumeInt())
        0xFB09u -> OpCodes.GC.ArrayNewData(consumeInt(), consumeInt())
        0xFB10u -> OpCodes.GC.ArrayFill(consumeInt())
        0xFB11u -> OpCodes.GC.ArrayCopy(consumeInt(), consumeInt())
        0xFB12u -> OpCodes.GC.ArrayInitData(consumeInt(), consumeInt())
        0xFB13u -> OpCodes.GC.ArrayInitElem(consumeInt(), consumeInt())
        0xFB0Bu -> OpCodes.GC.ArrayGet(consumeInt())
        0xFB0Cu -> OpCodes.GC.ArrayGetSigned(consumeInt())
        0xFB0Du -> OpCodes.GC.ArrayGetUnsigned(consumeInt())
        0xFB0Eu -> OpCodes.GC.ArraySet(consumeInt())
        0xFB0Fu -> OpCodes.GC.ArrayLength
        0xFB14u -> OpCodes.GC.RefTest(HeapType(consumeSignedInt()))
        0xFB15u -> OpCodes.GC.RefTestNullable(HeapType(consumeSignedInt()))
        0xFB16u -> OpCodes.GC.RefCast(HeapType(consumeSignedInt()))
        0xFB17u -> OpCodes.GC.RefCastNullable(HeapType(consumeSignedInt()))
        0xFB18u -> OpCodes.GC.BrOnCast(consumeInt(), HeapType(consumeSignedInt()), HeapType(consumeSignedInt()))
        0xFB19u -> OpCodes.GC.BrOnCastFail(consumeSignedInt(), consumeInt(), consumeInt())
        0xFB1Au -> OpCodes.GC.AnyConvertExtern
        0xFB1Bu -> OpCodes.GC.ExternConvertAny
        0xFB1Cu -> OpCodes.GC.RefI31
        0xFB1Du -> OpCodes.GC.I31GetS
        0xFB1Eu -> OpCodes.GC.I31GetU
        else -> throw UnsupportedOpsException(opcode, position())
    }
}

