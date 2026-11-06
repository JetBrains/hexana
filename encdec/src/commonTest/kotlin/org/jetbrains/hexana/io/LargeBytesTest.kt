package org.jetbrains.hexana.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class LargeBytesTest {
    private val bytes = ByteArray(32) { it.toByte() }.toCommonByteBuffer().asLargeBytes()

    @Test
    fun readsAndWindows() {
        assertEquals(32L, bytes.size)
        assertEquals(7, bytes[7].toInt())
        assertContentEquals(byteArrayOf(10, 11, 12), bytes.readBytes(10, 3))
        val window = bytes.window(16, 8)
        assertEquals(8, window.size)
        assertEquals(16, window[0].toInt())
        assertEquals(23, window[7].toInt())
        assertEquals(32, bytes.asCommonByteBufferOrNull()!!.size)
    }

    @Test
    fun littleEndianReads() {
        val le = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08).toCommonByteBuffer().asLargeBytes()
        assertEquals(0x0201, le.u16LeOrNull(0))
        assertEquals(0x04030201L, le.u32LeOrNull(0))
        assertEquals(0x0807060504030201L, le.u64LeOrNull(0))
        assertNull(le.u32LeOrNull(5))
        assertNull(le.u16LeOrNull(-1))
    }

    @Test
    fun rangesAreChecked() {
        assertTrue(bytes.containsRange(0, 32))
        assertFalse(bytes.containsRange(1, 32))
        assertFalse(bytes.containsRange(Long.MAX_VALUE, 1))
        assertFailsWith<IndexOutOfBoundsException> { bytes[32] }
        assertFailsWith<IndexOutOfBoundsException> { bytes.window(30, 4) }
        assertFailsWith<IndexOutOfBoundsException> { bytes.read(0, ByteArray(2), 0, 3) }
    }
}
