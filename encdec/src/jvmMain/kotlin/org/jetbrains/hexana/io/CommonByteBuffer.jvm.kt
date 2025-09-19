package org.jetbrains.hexana.io

import java.nio.ByteBuffer
import java.nio.ByteOrder

actual class CommonByteBuffer(buffer: ByteBuffer) : BufferWrapper<ByteBuffer>(buffer) {
    override fun position(): Int = buffer.position()

    override fun position(index: Int): CommonByteBuffer = CommonByteBuffer(buffer.position(index))

    override fun remaining(): Int = buffer.remaining()

    override fun hasRemaining(): Boolean = buffer.hasRemaining()

    override fun get(): Byte = buffer.get()

    override fun get(dst: ByteArray, offset: Int, size: Int) {
        buffer.get(dst, offset, size)
    }

    override fun getFloatLE(): Float {
        return buffer.order(ByteOrder.LITTLE_ENDIAN).getFloat()
    }
}


