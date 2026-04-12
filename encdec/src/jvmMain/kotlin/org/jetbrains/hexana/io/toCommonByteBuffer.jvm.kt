package org.jetbrains.hexana.io

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files

actual fun ByteArray.toCommonByteBuffer(): CommonByteBuffer = JvmByteBuffer(ByteBuffer.wrap(this))

actual fun allocateCommonByteBuffer(size: Int): CommonByteBuffer {
    val tempFile = Files.createTempFile("hexana-graph-", ".buf").toFile()
    tempFile.deleteOnExit()
    val raf = RandomAccessFile(tempFile, "rw")
    raf.setLength(size.toLong())
    val mapped = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, size.toLong())
    raf.close()
    mapped.order(ByteOrder.LITTLE_ENDIAN)
    return JvmByteBuffer(mapped)
}

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

    override fun put(value: Byte) {
        buffer.put(value)
    }

    override fun put(src: ByteArray, offset: Int, size: Int) {
        buffer.put(src, offset, size)
    }

    override fun putIntLE(value: Int) {
        buffer.order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    override fun getIntLE(): Int {
        return buffer.order(ByteOrder.LITTLE_ENDIAN).getInt()
    }

    override fun getFloatLE(): Float {
        return buffer.order(ByteOrder.LITTLE_ENDIAN).getFloat()
    }

    override fun getDoubleLE(): Double {
        return buffer.order(ByteOrder.LITTLE_ENDIAN).getDouble()
    }

    override fun limit(): Int {
        return buffer.limit()
    }

    override fun slice(range: IntRange): CommonByteBuffer {
        return buffer.slice(range.first, range.last).toCommonByteBuffer()
    }
}

fun CommonByteBuffer.toByteBuffer(): ByteBuffer = (this as JvmByteBuffer).buffer
