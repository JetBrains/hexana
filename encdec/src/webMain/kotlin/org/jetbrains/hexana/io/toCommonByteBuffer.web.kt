package org.jetbrains.hexana.io

import org.khronos.webgl.toInt8Array
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get

actual fun ByteArray.toCommonByteBuffer(): CommonByteBuffer = WebByteBuffer(toInt8Array().buffer)

actual fun allocateCommonByteBuffer(size: Int): CommonByteBuffer = WebByteBuffer(ArrayBuffer(size))

fun ArrayBuffer.toCommonByteBuffer(): CommonByteBuffer = WebByteBuffer(this)

private class WebByteBuffer(private val buffer: ArrayBuffer) : CommonByteBuffer {
    private var cursorPosition = 0
    private val dataView = DataView(buffer)

    // https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/ArrayBuffer
    override fun get(): Byte {
        val res = Int8Array(buffer)[position()]
        cursorPosition++
        return res
    }

    override fun get(index: Int): Byte = dataView.getInt8(index)

    override fun get(dst: ByteArray, offset: Int, size: Int) {
        val slice = Int8Array(buffer.slice(cursorPosition, cursorPosition + size))
        for (i in offset until offset + size) {
            dst.set(i, slice.get(i - offset))
        }
        position(position() + size)
    }

    override fun put(value: Byte) {
        dataView.setInt8(cursorPosition, value)
        cursorPosition++
    }

    override fun put(src: ByteArray, offset: Int, size: Int) {
        for (i in 0 until size) {
            dataView.setInt8(cursorPosition + i, src[offset + i])
        }
        cursorPosition += size
    }

    override fun putIntLE(value: Int) {
        dataView.setInt32(cursorPosition, value, true)
        cursorPosition += 4
    }

    override fun getIntLE(): Int {
        val res = dataView.getInt32(cursorPosition, true)
        cursorPosition += 4
        return res
    }

    override fun position(): Int {
        return cursorPosition
    }

    override fun position(index: Int): WebByteBuffer {
        cursorPosition = index
        return this
    }

    override fun remaining(): Int {
        return buffer.byteLength - cursorPosition
    }

    override fun hasRemaining(): Boolean {
        return cursorPosition < buffer.byteLength
    }

    override fun getFloatLE(): Float {
        val res = dataView.getFloat32(cursorPosition, true)
        position(cursorPosition + 4)
        return res
    }

    override fun getDoubleLE(): Double {
        val res = dataView.getFloat64(cursorPosition, true)
        position(cursorPosition + 8)
        return res
    }

    override fun limit(): Int {
        return buffer.byteLength
    }

    override fun slice(offset: Int, length: Int): CommonByteBuffer {
        // ArrayBuffer.slice(begin, end) takes an exclusive end index — convert
        // (offset, length) → (offset, offset + length).
        return WebByteBuffer(buffer.slice(offset, offset + length))
    }

    override fun duplicate(): CommonByteBuffer = WebByteBuffer(buffer).also { it.cursorPosition = cursorPosition }
}