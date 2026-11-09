package org.jetbrains.hexana.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparseLargeBytesTest {
    private val page = SparseLargeBytes.PAGE_SIZE
    private val size = 100 * page
    private val headSize = 4 * page.toInt()

    /** The byte every position of the simulated file holds. */
    private fun byteAt(offset: Long): Byte = (offset * 31 + 7).toByte()

    private fun content(start: Long, length: Int) = ByteArray(length) { byteAt(start + it) }

    private fun sparse() = SparseLargeBytes.of(content(0, headSize).toCommonByteBuffer(), size)

    private fun SparseLargeBytes.fetched(range: LongRange) =
        withChunks(listOf(SparseLargeBytes.Chunk(range.first, content(range.first, (range.last - range.first + 1).toInt()))))

    @Test
    fun headReadsNeedNothing() {
        val bytes = sparse()
        assertEquals(byteAt(10), bytes[10])
        assertContentEquals(content(100, 50), bytes.readBytes(100, 50))
        assertContentEquals(content(page, 16), bytes.window(page, 16).toByteArray())
        assertEquals(emptyList(), bytes.misses)
        assertNull(bytes.asCommonByteBufferOrNull())
    }

    @Test
    fun missesReadAsZerosAndArePageAligned() {
        val bytes = sparse()
        var calls = 0
        bytes.onMiss = { calls++ }
        val far = 50 * page + 100
        assertEquals(0, bytes[far].toInt())
        assertContentEquals(ByteArray(8), bytes.readBytes(70 * page + 3, 8))
        assertEquals(listOf(50 * page until 51 * page, 70 * page until 71 * page), bytes.misses)
        assertEquals(2, calls)
        assertFalse(bytes.isFullyLoaded(far, far + 1))
        assertTrue(bytes.isFullyLoaded(0, headSize.toLong()))
        // Out of the file is still an error, not a miss.
        assertFailsWith<IndexOutOfBoundsException> { bytes[size] }
    }

    @Test
    fun aByteByByteWalkOverAGapIsOneMiss() {
        val bytes = sparse()
        for (offset in 60 * page until 60 * page + 1000) bytes[offset]
        assertEquals(listOf(60 * page until 61 * page), bytes.misses)
    }

    @Test
    fun fetchedChunksServeReadsAndWindowsAcrossTheHead() {
        val bytes = sparse().fetched(headSize.toLong() until 10 * page)
        assertTrue(bytes.isFullyLoaded(0, 10 * page))
        // Spans the head and the chunk.
        val across = headSize - 10L
        assertContentEquals(content(across, 20), bytes.readBytes(across, 20))
        assertContentEquals(content(across, 20), bytes.window(across, 20).toByteArray())
        // Inside the chunk.
        assertContentEquals(content(6 * page, 32), bytes.window(6 * page, 32).toByteArray())
        assertEquals(emptyList(), bytes.misses)
        assertEquals(10 * page, bytes.loadedBytes)
    }

    @Test
    fun aMissRightAfterLoadedBytesGrowsByTheirSize() {
        val bytes = sparse().fetched(20 * page until 22 * page)
        bytes[22 * page + 5]
        // Follows a 2-page chunk: asks for 2 pages, not 1.
        assertEquals(listOf(22 * page until 24 * page), bytes.misses)
    }

    @Test
    fun adjacentAndOverlappingChunksMerge() {
        val bytes = sparse()
            .fetched(30 * page until 31 * page)
            .fetched(31 * page until 32 * page)
            .fetched(30 * page + 10 until 33 * page)
        assertTrue(bytes.isFullyLoaded(30 * page, 33 * page))
        assertContentEquals(content(31 * page - 4, 8), bytes.readBytes(31 * page - 4, 8))
        assertEquals(headSize + 3 * page, bytes.loadedBytes)
    }

    @Test
    fun aHugeWindowOverMissingBytesThrowsInsteadOfZeroFilling() {
        val big = 3L * SparseLargeBytes.MAX_ZERO_FILLED_WINDOW
        val bytes = SparseLargeBytes.of(content(0, headSize).toCommonByteBuffer(), big)
        val length = SparseLargeBytes.MAX_ZERO_FILLED_WINDOW + 1
        assertFailsWith<BytesNotLoadedException> { bytes.window(headSize.toLong(), length) }
        assertEquals(headSize.toLong(), bytes.misses.single().first)
    }

    @Test
    fun otherLargeBytesAreAlwaysLoaded() {
        assertTrue(ByteArray(4).toCommonByteBuffer().asLargeBytes().isFullyLoaded(0, 4))
    }
}
