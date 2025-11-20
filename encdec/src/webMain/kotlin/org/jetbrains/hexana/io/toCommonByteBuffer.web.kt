package org.jetbrains.hexana.io

import org.khronos.webgl.toInt8Array
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get

actual fun ByteArray.toCommonByteBuffer(): CommonByteBuffer = WebByteBuffer(toInt8Array().buffer)

fun ArrayBuffer.toCommonByteBuffer(): CommonByteBuffer = WebByteBuffer(this)

private class WebByteBuffer(private val buffer: ArrayBuffer) : CommonByteBuffer {
    private var cursorPosition = 0

    // https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/ArrayBuffer
    override fun get(): Byte {
        val res = Int8Array(buffer)[position()]
        cursorPosition++
        return res
    }

    override fun get(dst: ByteArray, offset: Int, size: Int) {
        val slice = Int8Array(buffer.slice(cursorPosition, cursorPosition + size))
        for (i in offset until offset + size) {
            dst.set(i, slice.get(i - offset))
        }
        position(position() + size)
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
        val res = DataView(buffer).getFloat32(cursorPosition, true)
        position(cursorPosition + 4)
        return res;
    }

    override fun getDoubleLE(): Double {
        val res = DataView(buffer).getFloat64(cursorPosition, true)
        position(cursorPosition + 8)
        return res
    }
}