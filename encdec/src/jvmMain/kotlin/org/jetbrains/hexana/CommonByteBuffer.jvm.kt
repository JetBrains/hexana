package org.jetbrains.hexana

import java.nio.ByteBuffer

actual class CommonByteBuffer(buffer: ByteBuffer) : BufferWrapper<ByteBuffer>(buffer) {
    actual fun getULEB128(): Long {
        var result = 0L
        var shift = 0

        while (true) {
            val byte = get().toUInt()
            result = result or ((byte and 0x7f.toUInt()) shl shift).toLong()
            if (byte and 0x80.toUInt() == 0.toUInt()) {
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
}


