package org.jetbrains.hexana.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommonByteBufferTests {

    @Test
    fun `reading byte affects position`() {
        val buffer = createByteBuffer()
        assertEquals(0, buffer.position())
        buffer.get()
        assertEquals(1, buffer.position())
        buffer.get()
        assertEquals(2, buffer.position())
    }

    @Test
    fun `setting position affects reading bytes`() {
        val buffer = createByteBuffer()
        buffer.position(2)
        val bytesSequential = listOf(buffer.get(), buffer.get(), buffer.get())
        assertContentEquals(listOf(3, 4, 5), bytesSequential)
    }

    @Test
    fun `get into array with offset and size updates destination and position`() {
        val buffer = createByteBuffer()
        val dst = ByteArray(5) { 0 }
        buffer.get(dst, 1, 3)
        assertContentEquals(listOf(0, 1, 2, 3, 0), dst.map { it.toInt() })
        assertEquals(3, buffer.position())
    }

    @Test
    fun `remaining and hasRemaining update as bytes are read`() {
        val buffer = createByteBuffer()
        assertEquals(9, buffer.remaining())
        assertTrue(buffer.hasRemaining())
        repeat(4) { buffer.get() }

        assertEquals(5, buffer.remaining())
        assertTrue(buffer.hasRemaining())

        repeat(5) { buffer.get() }
        assertEquals(0, buffer.remaining())
        assertTrue(!buffer.hasRemaining())
    }

    @Test
    fun `getULEB128 decoding 2-bytes`() {
        val buffer = createByteBuffer(0x80.toByte(), 0x01)
        val value = buffer.getULEB128()
        assertEquals(128L, value)
        assertEquals(2, buffer.position())
    }

    @Test
    fun `getULEB128 decoding 1-byte`() {
        val buffer = createByteBuffer(0x7F)
        val value = buffer.getULEB128()
        assertEquals(127L, value)
        assertEquals(1, buffer.position())
    }

    @Test
    fun `getULEB128 decoding 3-bytes`() {
        val buffer = createByteBuffer(0xE5.toByte(), 0x8E.toByte(), 0x26.toByte())
        val value = buffer.getULEB128()
        assertEquals(624485L, value)
        assertEquals(3, buffer.position())
    }

    @Test
    fun `getFloatLE reads little endian float and advances position`() {
        val buffer = createByteBuffer(0x00, 0x00, 0x80.toByte(), 0x3F)
        val value = buffer.getFloatLE()
        assertEquals(1.0f, value)
        assertEquals(4, buffer.position())
    }
}