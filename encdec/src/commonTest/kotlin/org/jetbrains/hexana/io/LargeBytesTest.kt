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

class LargeBytesSearchTest {
    private fun bytesOf(text: String) = text.encodeToByteArray().toCommonByteBuffer().asLargeBytes()

    @Test
    fun findsForwardAndBackward() {
        val hay = bytesOf("abcabcab")
        val needle = "ab".encodeToByteArray()
        assertEquals(0L, hay.indexOf(needle))
        assertEquals(3L, hay.indexOf(needle, from = 1))
        assertEquals(6L, hay.indexOf(needle, from = 7, reverse = true))
        assertEquals(3L, hay.indexOf(needle, from = 5, reverse = true))
        assertEquals(-1L, hay.indexOf(needle, from = 7))
        assertEquals(listOf(0L, 3L, 6L), hay.findAll(needle, limit = 10))
        assertEquals(listOf(0L, 3L), hay.findAll(needle, limit = 2))
    }

    @Test
    fun overlappingMatchesCountSeparately() {
        assertEquals(listOf(0L, 1L), bytesOf("aaa").findAll("aa".encodeToByteArray(), limit = 10))
    }

    @Test
    fun findsMatchesSpanningSearchChunks() {
        val size = (1 shl 20) * 3 + 7
        val data = ByteArray(size)
        val at = (1 shl 20) - 2 // straddles the first 1 MiB chunk boundary
        "NEEDLE".encodeToByteArray().copyInto(data, at)
        val hay = data.toCommonByteBuffer().asLargeBytes()
        assertEquals(at.toLong(), hay.indexOf("NEEDLE".encodeToByteArray()))
        assertEquals(at.toLong(), hay.indexOf("NEEDLE".encodeToByteArray(), from = size - 1L, reverse = true))
    }

    @Test
    fun findAllFindsMatchesSpanningSearchChunks() {
        val data = ByteArray((1 shl 20) * 2 + 3)
        val at = (1 shl 20) - 2
        "NEEDLE".encodeToByteArray().copyInto(data, at)
        "NEEDLE".encodeToByteArray().copyInto(data, data.size - 6)
        val hay = data.toCommonByteBuffer().asLargeBytes()
        assertEquals(listOf(at.toLong(), data.size - 6L), hay.findAll("NEEDLE".encodeToByteArray(), limit = 10))
    }

    /** Counts the bytes [read] copies out of [bytes]. */
    private class CountingBytes(private val bytes: LargeBytes) : LargeBytes by bytes {
        var bytesRead = 0L
        override fun read(offset: Long, dst: ByteArray, dstOffset: Int, length: Int) {
            bytesRead += length
            bytes.read(offset, dst, dstOffset, length)
        }
    }

    @Test
    fun denseMatchesAreFoundInOnePass() {
        // Every offset of a zero-filled region matches a zero byte.
        val zeros = CountingBytes(ByteArray(4 shl 20).toCommonByteBuffer().asLargeBytes())
        val found = zeros.findAll(byteArrayOf(0), limit = 100_000)
        assertEquals(100_000, found.size)
        assertEquals(99_999L, found.last())
        // The first chunk holds them all; per-match reads would copy 100 000 MiB.
        assertTrue(zeros.bytesRead <= (1L shl 20), "read ${zeros.bytesRead} bytes")
    }

    @Test
    fun findAllOfDegenerateNeedles() {
        assertEquals(emptyList(), bytesOf("ab").findAll(ByteArray(0), limit = 10))
        assertEquals(emptyList(), bytesOf("ab").findAll("abc".encodeToByteArray(), limit = 10))
        assertEquals(emptyList(), bytesOf("ab").findAll("a".encodeToByteArray(), limit = 0))
    }

    @Test
    fun degenerateNeedles() {
        assertEquals(-1L, bytesOf("ab").indexOf(ByteArray(0)))
        assertEquals(-1L, bytesOf("ab").indexOf("abc".encodeToByteArray()))
    }
}
