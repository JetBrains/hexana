package org.jetbrains.hexana.io

import org.khronos.webgl.Int8Array
import org.khronos.webgl.set
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * On JS a [ByteArray] is an `Int8Array` and may be a view into a larger buffer: a small file the
 * VS Code extension host posts arrives as a view into Node's 8 KiB allocation pool, at an offset.
 */
class ByteArrayViewBufferTest {
    @Test
    fun aViewIntoALargerBufferWrapsOnlyItsOwnBytes() {
        val pool = Int8Array(16)
        for (i in 0 until 16) pool[i] = (0x50 + i).toByte()
        // "\u0000asm" at offset 4 of the pool, as a pooled Buffer would sit.
        val file = Int8Array(pool.buffer, 4, 4)
        file[0] = 0; file[1] = 0x61; file[2] = 0x73; file[3] = 0x6D

        val buffer = file.unsafeCast<ByteArray>().toCommonByteBuffer()

        assertEquals(4, buffer.size)
        assertEquals(listOf<Byte>(0, 0x61, 0x73, 0x6D), (0 until 4).map { buffer[it] })
        assertEquals(0.toByte(), buffer.get(), "the cursor starts at the view's first byte")
    }

    @Test
    fun aViewOfAWholeBufferIsWrappedAsIs() {
        val bytes = byteArrayOf(1, 2, 3)
        val buffer = bytes.toCommonByteBuffer()
        assertEquals(3, buffer.size)
        assertEquals(3.toByte(), buffer[2])
    }
}
