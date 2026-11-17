package org.jetbrains.hexana.wasm.opcodes

import org.jetbrains.hexana.io.CommonByteBuffer

// TODO: consumeInt should return UInt
internal fun CommonByteBuffer.consumeInt(): UInt = getULEB128().toUInt()
internal fun CommonByteBuffer.consumeFloat(): Float = getFloatLE()
internal fun CommonByteBuffer.consumeDouble(): Double = getDoubleLE()
internal fun CommonByteBuffer.consumeSignedInt(): Int = getSLEB128().toInt()
internal fun CommonByteBuffer.consumeSignedLong(): Long = getSLEB128()
internal fun CommonByteBuffer.consumeMemArg(): MemArg {
    val rawAlign = getULEB128().toInt()
    // Multi-memory proposal: bit 6 (0x40) of the align field signals an explicit memory index.
    // Consume the memory index to keep the byte stream aligned; clear the flag from effective align.
    if (rawAlign and 0x40 != 0) {
        getULEB128() // memory index — consumed for stream alignment, not modeled in MemArg
    }
    return MemArg(rawAlign and 0x40.inv(), getULEB128().toInt())
}
