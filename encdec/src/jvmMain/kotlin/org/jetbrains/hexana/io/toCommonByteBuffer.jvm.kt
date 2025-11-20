package org.jetbrains.hexana.io

import java.nio.ByteBuffer
import java.nio.ByteOrder

actual fun ByteArray.toCommonByteBuffer(): CommonByteBuffer = JvmByteBuffer(ByteBuffer.wrap(this))

fun ByteBuffer.toCommonByteBuffer(): CommonByteBuffer = JvmByteBuffer(this)

private class JvmByteBuffer(val buffer: ByteBuffer) : CommonByteBuffer {
    override fun position(): Int = buffer.position()

    override fun position(index: Int): CommonByteBuffer = JvmByteBuffer(buffer.position(index))

    override fun remaining(): Int = buffer.remaining()

    override fun hasRemaining(): Boolean = buffer.hasRemaining()

    override fun get(): Byte = buffer.get()

    override fun get(dst: ByteArray, offset: Int, size: Int) {
        buffer.get(dst, offset, size)
    }

    override fun getFloatLE(): Float {
        return buffer.order(ByteOrder.LITTLE_ENDIAN).getFloat()
    }

    override fun getDoubleLE(): Double {
        return buffer.order(ByteOrder.LITTLE_ENDIAN).getDouble()
    }
}
