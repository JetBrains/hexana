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

    override fun get(index: Int): Byte = buffer.get(index)

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

    override fun slice(offset: Int, length: Int): CommonByteBuffer {
        return buffer.slice(offset, length).toCommonByteBuffer()
    }

    override fun duplicate(): CommonByteBuffer = JvmByteBuffer(buffer.duplicate())

    override fun toByteArray(): ByteArray {
        // Bulk copy through a duplicate so we don't disturb our own cursor.
        val dup = buffer.duplicate()
        dup.position(0)
        val out = ByteArray(dup.limit())
        dup.get(out, 0, out.size)
        return out
    }
}

fun CommonByteBuffer.toByteBuffer(): ByteBuffer = (this as JvmByteBuffer).buffer
