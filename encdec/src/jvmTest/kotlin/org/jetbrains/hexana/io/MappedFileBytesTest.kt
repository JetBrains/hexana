package org.jetbrains.hexana.io

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A sparse 3 GiB file with markers around the chunk boundaries, so the test maps far more than a
 * single `ByteBuffer` can address without writing gigabytes.
 */
class MappedFileBytesTest {
    private val dir: Path = Files.createTempDirectory("hexana-mapped")
    private val size = 3L * 1024 * 1024 * 1024
    private val step = MappedFileBytes.CHUNK_STEP

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun sparse(vararg marks: Pair<Long, ByteArray>): MappedFileBytes {
        val file = dir.resolve("big.bin")
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            raf.setLength(size)
            for ((offset, bytes) in marks) {
                raf.seek(offset)
                raf.write(bytes)
            }
        }
        return MappedFileBytes.open(file)
    }

    @Test
    fun readsAcrossChunks() {
        val marker = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val bytes = sparse(step - 4 to marker, 2 * step + 12345 to marker, size - 8 to marker)

        assertEquals(size, bytes.size)
        assertEquals(1, bytes[step - 4].toInt())
        assertEquals(5, bytes[step].toInt())
        assertContentEquals(marker, bytes.readBytes(step - 4, 8), "a read straddling chunk 0 and 1")
        assertContentEquals(marker, bytes.readBytes(2 * step + 12345, 8))
        assertEquals(8, bytes[size - 1].toInt())
        assertNull(bytes.asCommonByteBufferOrNull())
        assertEquals(0x0807060504030201L, bytes.u64LeOrNull(size - 8))
        assertFailsWith<IndexOutOfBoundsException> { bytes[size] }
    }

    @Test
    fun windowsInsideAndBeyondTheOverlap() {
        val marker = byteArrayOf(9, 8, 7, 6)
        val bytes = sparse(step - 2 to marker, 2 * step + MappedFileBytes.CHUNK_OVERLAP to marker)

        // Straddles a chunk start but fits the previous chunk's overlap: a view, no extra mapping.
        val small = bytes.window(step - 2, 4)
        assertContentEquals(marker, ByteArray(4) { small[it] })

        // Longer than the overlap from where it starts: gets a mapping of its own.
        val start = step + 10
        val length = (step + MappedFileBytes.CHUNK_OVERLAP).toInt()
        val big = bytes.window(start, length)
        assertEquals(length, big.size)
        val markerAt = (2 * step + MappedFileBytes.CHUNK_OVERLAP - start).toInt()
        assertEquals(9, big[markerAt].toInt())
    }

    @Test
    fun smallFilesFitOneBuffer() {
        val file = dir.resolve("small.bin")
        Files.write(file, byteArrayOf(1, 2, 3))
        val bytes = MappedFileBytes.open(file)
        assertEquals(3, bytes.asCommonByteBufferOrNull()!!.size)
        assertEquals(3, bytes[2].toInt())
    }
}
