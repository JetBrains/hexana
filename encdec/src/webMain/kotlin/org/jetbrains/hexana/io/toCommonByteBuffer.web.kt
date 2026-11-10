package org.jetbrains.hexana.io

import org.khronos.webgl.toInt8Array
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.DataView
import org.khronos.webgl.Int8Array
import org.khronos.webgl.get

/**
 * On JS a [ByteArray] is an `Int8Array`, which can be a view into a larger buffer — bytes posted
 * by the VS Code extension host arrive as a view into Node's shared allocation pool. Only the view's
 * own bytes are wrapped: its buffer as is when the view covers all of it (no copy), else a copy of
 * just the view. Wrapping `.buffer` regardless read the pool from its start.
 */
actual fun ByteArray.toCommonByteBuffer(): CommonByteBuffer {
    val view = toInt8Array()
    val buffer = view.buffer
    val own = if (view.byteOffset == 0 && view.length == buffer.byteLength) {
        buffer
    } else {
        buffer.slice(view.byteOffset, view.byteOffset + view.length)
    }
    return WebByteBuffer(own)
}

actual fun allocateCommonByteBuffer(size: Int): CommonByteBuffer = WebByteBuffer(ArrayBuffer(size))

fun ArrayBuffer.toCommonByteBuffer(): CommonByteBuffer = WebByteBuffer(this)

private class WebByteBuffer(private val buffer: ArrayBuffer) : CommonByteBuffer {
    private var cursorPosition = 0
    private val dataView = DataView(buffer)

    // One view for the buffer's lifetime: the cursor read is the parsers' hot path, and building
    // a view per byte made every byte cost an allocation. The buffer never resizes.
    private val bytes = Int8Array(buffer)
    private val byteLength = buffer.byteLength

    // https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/ArrayBuffer
    //
    // A JS TypedArray out-of-bounds (or negative) index yields `undefined`, which
    // coerces to 0 — it never throws. Check the bound ourselves so a read past the
    // end fails fast, matching the JVM implementation's BufferUnderflowException.
    override fun get(): Byte {
        if (cursorPosition < 0 || cursorPosition >= byteLength) {
            throw IndexOutOfBoundsException(
                "Read past end of buffer: position=$cursorPosition, byteLength=$byteLength"
            )
        }
        return bytes[cursorPosition++]
    }

    override fun get(index: Int): Byte = dataView.getInt8(index)

    override fun get(dst: ByteArray, offset: Int, size: Int) {
        // `ArrayBuffer.slice` clamps its end index instead of throwing, so a read
        // past the end would otherwise silently zero-fill `dst`. Compare via
        // `size > buffer.byteLength - cursorPosition` (not `cursorPosition + size`)
        // to avoid Int overflow when `size` is huge.
        if (size < 0 || cursorPosition < 0 || size > buffer.byteLength - cursorPosition) {
            throw IndexOutOfBoundsException(
                "Read past end of buffer: position=$cursorPosition, size=$size, byteLength=${buffer.byteLength}"
            )
        }
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