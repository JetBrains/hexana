package org.jetbrains.hexana.io

interface BufferWrapperInterface

abstract class BufferWrapper<T>(internal val buffer: T): BufferWrapperInterface {
    abstract fun get(): Byte
    abstract fun get(dst: ByteArray, offset: Int, size: Int)
    abstract fun position(): Int
    abstract fun position(index: Int): CommonByteBuffer
    abstract fun remaining(): Int
    abstract fun hasRemaining(): Boolean
    abstract fun getFloatLE(): Float

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

expect class CommonByteBuffer : BufferWrapperInterface

