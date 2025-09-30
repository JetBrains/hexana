package org.jetbrains.hexana.io

interface CommonByteBuffer {
    fun get(): Byte
    fun get(dst: ByteArray, offset: Int, size: Int)
    fun position(): Int
    fun position(index: Int): CommonByteBuffer
    fun remaining(): Int
    fun hasRemaining(): Boolean
    fun getFloatLE(): Float
    fun getDoubleLE(): Double

    fun getULEB128(): Long {
        var result = 0L
        var shift = 0

        while (true) {
            val byte = get().toUInt()
            result = result or ((byte and 0x7Fu) shl shift).toLong()
            if (byte and 0x80u == 0u) {
                break
            }
            shift +=7
        }

        return result
    }

    fun getSLEB128(): Long {
        var result = 0L
        var shift = 0

        while (true) {
            val byte = get().toUInt()
            result = result or ((byte and 0x7Fu) shl shift).toLong()
            if (byte and 0x80u == 0u) {
                if (shift < 32 && (byte and 0x40u) != 0u) {
                    result = result or ((-1L) shl shift)
                    break
                }
                break
            }
            shift +=7
        }

        return result
    }
}

