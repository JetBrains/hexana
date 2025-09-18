package org.jetbrains.hexana.io

import java.nio.ByteBuffer
import java.nio.ByteOrder

actual class CommonByteBuffer(buffer: ByteBuffer) : BufferWrapper<ByteBuffer>(buffer) {
    actual fun getULEB128(): Long {
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

    actual fun getSLEB128(): Long {
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

    actual fun position(): Int = buffer.position()

    actual fun position(index: Int): CommonByteBuffer = CommonByteBuffer(buffer.position(index))

    actual fun remaining(): Int = buffer.remaining()

    actual fun hasRemaining(): Boolean = buffer.hasRemaining()

    actual fun get(): Byte = buffer.get()

    actual fun get(dst: ByteArray, offset: Int, size: Int) = CommonByteBuffer(buffer.get(dst, offset, size))

    actual fun getFloatLE(): Float {
        return buffer.order(ByteOrder.LITTLE_ENDIAN).getFloat()
    }
}


