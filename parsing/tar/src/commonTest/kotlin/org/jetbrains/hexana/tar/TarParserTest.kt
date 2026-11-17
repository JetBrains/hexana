package org.jetbrains.hexana.tar

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TarParserTest {
    @Test
    fun `parses a synthesized two-entry tar archive`() {
        val builder = TarBuilder()
        builder.addEntry("hello.txt", "Hello, world!".encodeToByteArray())
        builder.addEntry("data.bin", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        val bytes = builder.finish()

        val entries = TarParser.parse(bytes.toCommonByteBuffer())

        assertEquals(2, entries.size)

        val hello = entries[0]
        assertEquals("hello.txt", hello.name)
        assertEquals("Hello, world!".length.toLong(), hello.size)
        assertTrue(hello.isRegularFile)
        assertFalse(hello.isDirectory)
        // Data starts after the 512-byte header block.
        assertEquals(512L, hello.dataOffset)
        // Verify data is accessible.
        assertEquals('H'.code.toByte(), bytes[hello.dataOffset.toInt()])

        val data = entries[1]
        assertEquals("data.bin", data.name)
        assertEquals(4L, data.size)
        assertTrue(data.isRegularFile)
    }

    @Test
    fun `parses an empty tar archive`() {
        val bytes = TarBuilder().finish()
        val entries = TarParser.parse(bytes.toCommonByteBuffer())
        assertEquals(emptyList(), entries)
    }

    @Test
    fun `detects tar via isTar`() {
        val builder = TarBuilder()
        builder.addEntry("test.txt", "test".encodeToByteArray())
        val bytes = builder.finish()
        assertTrue(TarParser.isTar(bytes.toCommonByteBuffer()))
    }

    @Test
    fun `rejects non-tar data`() {
        val notATar = ByteArray(1024) { 0x42 }
        assertFalse(TarParser.isTar(notATar.toCommonByteBuffer()))
    }

    @Test
    fun `parses directory entries`() {
        val builder = TarBuilder()
        builder.addDirectory("mydir/")
        builder.addEntry("mydir/file.txt", "content".encodeToByteArray())
        val bytes = builder.finish()

        val entries = TarParser.parse(bytes.toCommonByteBuffer())
        assertEquals(2, entries.size)
        assertTrue(entries[0].isDirectory)
        assertEquals("mydir/", entries[0].name)
        assertFalse(entries[1].isDirectory)
        assertEquals("mydir/file.txt", entries[1].name)
    }

    @Test
    fun `parses GNU long name entries`() {
        val longName = "a".repeat(200) + ".txt"
        val builder = TarBuilder()
        builder.addEntry(longName, "data".encodeToByteArray())
        val bytes = builder.finish()

        val entries = TarParser.parse(bytes.toCommonByteBuffer())
        assertEquals(1, entries.size)
        assertEquals(longName, entries[0].name)
    }

    @Test
    fun `too small buffer returns empty`() {
        val tiny = ByteArray(100)
        val entries = TarParser.parse(tiny.toCommonByteBuffer())
        assertEquals(emptyList(), entries)
    }
}

/**
 * Minimal builder for ustar TAR archives — enough to exercise the parser
 * without platform-specific tar libraries (works on Kotlin/JS and Kotlin/Wasm).
 */
private class TarBuilder {
    private val out = ArrayList<Byte>()

    fun addEntry(name: String, data: ByteArray, typeFlag: Char = '0') {
        val actualName: String
        val needsLongName = name.length > 100

        if (needsLongName) {
            // Emit a GNU long name header.
            val longNameBytes = name.encodeToByteArray()
            writeHeader(
                name = "././@LongLink",
                size = longNameBytes.size.toLong(),
                typeFlag = 'L',
            )
            writeData(longNameBytes)
            actualName = name.take(100)
        } else {
            actualName = name
        }

        writeHeader(name = actualName, size = data.size.toLong(), typeFlag = typeFlag)
        writeData(data)
    }

    fun addDirectory(name: String) {
        writeHeader(name = name, size = 0, typeFlag = '5')
    }

    fun finish(): ByteArray {
        // Two zero blocks mark end of archive.
        repeat(BLOCK_SIZE * 2) { out.add(0) }
        return ByteArray(out.size) { out[it] }
    }

    private fun writeHeader(name: String, size: Long, typeFlag: Char) {
        val header = ByteArray(BLOCK_SIZE)

        // Name (0-99).
        val nameBytes = name.encodeToByteArray()
        nameBytes.copyInto(header, 0, 0, nameBytes.size.coerceAtMost(100))

        // Mode (100-107): "0000644\0"
        writeOctal(header, 100, 8, 0b110_100_100) // 0644

        // UID (108-115): "0000000\0"
        writeOctal(header, 108, 8, 0)

        // GID (116-123): "0000000\0"
        writeOctal(header, 116, 8, 0)

        // Size (124-135).
        writeOctal(header, 124, 12, size)

        // Mtime (136-147).
        writeOctal(header, 136, 12, 0)

        // Typeflag (156).
        header[156] = typeFlag.code.toByte()

        // Magic (257-262): "ustar\0"
        val magic = "ustar\u0000"
        for (i in magic.indices) header[257 + i] = magic[i].code.toByte()

        // Version (263-264): "00"
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()

        // Compute checksum.
        // First fill checksum field with spaces.
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var checksum = 0L
        for (b in header) checksum += b.toLong() and 0xFFL
        writeOctal(header, 148, 7, checksum)
        header[155] = ' '.code.toByte()

        for (b in header) out.add(b)
    }

    private fun writeData(data: ByteArray) {
        for (b in data) out.add(b)
        // Pad to 512-byte boundary.
        val remainder = data.size % BLOCK_SIZE
        if (remainder != 0) {
            repeat(BLOCK_SIZE - remainder) { out.add(0) }
        }
    }

    private fun writeOctal(header: ByteArray, offset: Int, length: Int, value: Long) {
        val octal = value.toString(8)
        val padded = octal.padStart(length - 1, '0')
        for (i in padded.indices) {
            header[offset + i] = padded[i].code.toByte()
        }
        header[offset + length - 1] = 0 // null terminator
    }

    companion object {
        private const val BLOCK_SIZE = 512
    }
}
