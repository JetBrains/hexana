package org.jetbrains.hexana

import com.intellij.openapi.util.io.ByteArraySequence
import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ColumnarBinaryFileTypeDetectorTest {

    private val detector = ColumnarBinaryFileTypeDetector()

    // detect() ignores the file argument (it reads only the byte prefix); a light
    // in-memory VirtualFile satisfies the non-null parameter.
    private val dummyFile = LightVirtualFile("test.bin")

    private fun detect(bytes: ByteArray) = detector.detect(
        dummyFile,
        ByteArraySequence(bytes),
        /* firstCharsIfText = */ null,
    )

    @Test
    fun `PAR1 prefix returns ParquetFileType`() {
        val bytes = byteArrayOf(0x50, 0x41, 0x52, 0x31, 0x00, 0x00, 0x00, 0x00)
        assertSame(ParquetFileType.INSTANCE, detect(bytes))
    }

    @Test
    fun `ARROW1 prefix returns ArrowFileType`() {
        val bytes = byteArrayOf(0x41, 0x52, 0x52, 0x4F, 0x57, 0x31, 0x00, 0x00)
        assertSame(ArrowFileType.INSTANCE, detect(bytes))
    }

    @Test
    fun `Arrow stream 0xFFFFFFFF prefix returns null — stream not auto-detected`() {
        // The 0xFFFFFFFF continuation marker is too weak for content detection; streams
        // open via the .arrow extension or buffer-level check in resolveProvider instead.
        val bytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x00)
        assertNull(detect(bytes))
    }

    @Test
    fun `random bytes return null`() {
        assertNull(detect(byteArrayOf(0x00, 0x01, 0x02, 0x03)))
    }

    @Test
    fun `short buffer under 4 bytes returns null`() {
        assertNull(detect(byteArrayOf(0x50, 0x41, 0x52)))
    }

    @Test
    fun `empty buffer returns null`() {
        assertNull(detect(byteArrayOf()))
    }
}