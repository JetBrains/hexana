package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer

// TODO: consumeInt should return UInt
internal fun CommonByteBuffer.consumeInt(): UInt = getULEB128().toUInt()
internal fun CommonByteBuffer.consumeFloat(): Float = getFloatLE()
internal fun CommonByteBuffer.consumeDouble(): Double = getDoubleLE()
internal fun CommonByteBuffer.consumeSignedInt(): Int = getSLEB128().toInt()
internal fun CommonByteBuffer.consumeSignedLong(): Long = getSLEB128()
internal fun CommonByteBuffer.consumeMemArg(): MemArg {
    return MemArg(
        getULEB128().toInt(), getULEB128().toInt()
    )
}
