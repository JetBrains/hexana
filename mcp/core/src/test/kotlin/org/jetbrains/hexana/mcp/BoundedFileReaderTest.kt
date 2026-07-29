package org.jetbrains.hexana.mcp

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedFileReaderTest {
    private val unusedPath = Path.of("unused")

    @Test
    fun readsFileAtExactBoundary() {
        val bytes = byteArrayOf(1, 2, 3)

        val result = readFileBytesBounded(unusedPath, bytes.size) { ByteArrayInputStream(bytes) }

        assertContentEquals(bytes, result)
    }

    @Test
    fun returnsOnlyOneSentinelByteForOversizedFile() {
        val source = CountingInputStream(ByteArray(32) { it.toByte() })

        val result = readFileBytesBounded(unusedPath, 3) { source }

        assertContentEquals(byteArrayOf(0, 1, 2, 3), result)
        assertEquals(4, source.consumedBytes)
    }

    @Test
    fun handlesShortAndZeroLengthReadsDeterministically() {
        val bytes = byteArrayOf(4, 5, 6)
        val source = ShortReadInputStream(bytes)

        val result = readFileBytesBounded(unusedPath, bytes.size) { source }

        assertContentEquals(bytes, result)
        assertEquals(bytes.size, source.consumedBytes)
    }

    @Test
    fun rejectsLimitThatCannotRepresentSentinelWithoutOpeningFile() {
        var opened = false

        assertFailsWith<IllegalArgumentException> {
            readFileBytesBounded(unusedPath, Int.MAX_VALUE) {
                opened = true
                ByteArrayInputStream(byteArrayOf())
            }
        }

        assertEquals(false, opened)
    }

    private class CountingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var consumedBytes: Int = 0
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return super.read(buffer, offset, length).also { if (it > 0) consumedBytes += it }
        }

        override fun read(): Int = super.read().also { if (it >= 0) consumedBytes += 1 }
    }

    private class ShortReadInputStream(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var returnZero = true
        var consumedBytes: Int = 0
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (returnZero) {
                returnZero = false
                return 0
            }
            returnZero = true
            return delegate.read(buffer, offset, minOf(length, 1)).also { if (it > 0) consumedBytes += it }
        }

        override fun read(): Int = delegate.read().also { if (it >= 0) consumedBytes += 1 }
    }
}
