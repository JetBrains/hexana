package org.jetbrains.hexana.parsing.common

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NativeBinaryFormatDetectorTest {
    @Test
    fun `detects ELF64 little endian executable`() {
        val bytes = elf64Bytes()
        bytes.putU16Le(16, 2)
        bytes.putU16Le(18, 0x3E)

        val format = assertIs<NativeBinaryFormat.Elf>(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
        assertEquals(NativeBinaryWordSize.Bits64, format.wordSize)
        assertEquals(NativeBinaryEndian.Little, format.endian)
        assertEquals(2, format.fileType)
        assertEquals(0x3E, format.machine)
        assertEquals("ELF64", format.displayName)
    }

    @Test
    fun `detects ELF32 big endian relocatable object`() {
        val bytes = ByteArray(64)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 1
        bytes[5] = 2
        bytes.putU16Be(16, 1)
        bytes.putU16Be(18, 0x28)

        val format = assertIs<NativeBinaryFormat.Elf>(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
        assertEquals(NativeBinaryWordSize.Bits32, format.wordSize)
        assertEquals(NativeBinaryEndian.Big, format.endian)
        assertEquals(1, format.fileType)
        assertEquals(0x28, format.machine)
        assertEquals("ELF32", format.displayName)
    }

    @Test
    fun `detects PE32 plus executable`() {
        val bytes = ByteArray(256)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes.putU32Le(0x3C, 0x80u)
        bytes[0x80] = 'P'.code.toByte()
        bytes[0x81] = 'E'.code.toByte()
        bytes.putU16Le(0x80 + 4, 0x8664)
        bytes.putU16Le(0x80 + 24, 0x020B)

        val format = assertIs<NativeBinaryFormat.Pe>(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
        assertEquals(NativeBinaryWordSize.Bits64, format.wordSize)
        assertEquals(0x8664, format.machine)
        assertEquals("PE32+", format.displayName)
    }

    @Test
    fun `rejects MZ file without PE signature`() {
        val bytes = ByteArray(128)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        bytes.putU32Le(0x3C, 0x40u)

        assertNull(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
    }

    @Test
    fun `detects little endian Mach-O 64`() {
        val bytes = ByteArray(32)
        bytes.putU32Be(0, 0xCFFAEDFEu)
        bytes.putU32Le(4, 0x0100000Cu)

        val format = assertIs<NativeBinaryFormat.MachO>(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
        assertEquals(NativeBinaryWordSize.Bits64, format.wordSize)
        assertEquals(NativeBinaryEndian.Little, format.endian)
        assertEquals(0x0100000C, format.cpuType)
        assertEquals("Mach-O 64", format.displayName)
    }

    @Test
    fun `detects big endian fat Mach-O 64`() {
        val bytes = ByteArray(16)
        bytes.putU32Be(0, 0xCAFEBABFu)

        val format = assertIs<NativeBinaryFormat.FatMachO>(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
        assertEquals(NativeBinaryWordSize.Bits64, format.wordSize)
        assertEquals(NativeBinaryEndian.Big, format.endian)
        assertEquals("Mach-O Universal 64", format.displayName)
    }

    @Test
    fun `detects Unix archive`() {
        val bytes = byteArrayOf(
            '!'.code.toByte(),
            '<'.code.toByte(),
            'a'.code.toByte(),
            'r'.code.toByte(),
            'c'.code.toByte(),
            'h'.code.toByte(),
            '>'.code.toByte(),
            '\n'.code.toByte(),
        )

        val format = assertIs<NativeBinaryFormat.Archive>(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
        assertEquals("Archive", format.displayName)
    }

    @Test
    fun `detects COFF object file`() {
        val bytes = ByteArray(64)
        bytes.putU16Le(0, 0x8664)
        bytes.putU16Le(2, 3)
        bytes.putU32Le(8, 0u)
        bytes.putU32Le(12, 0u)
        bytes.putU16Le(16, 0)

        val format = assertIs<NativeBinaryFormat.Coff>(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
        assertEquals(0x8664, format.machine)
        assertEquals(3, format.sectionCount)
        assertEquals("COFF", format.displayName)
    }

    @Test
    fun `rejects implausible COFF object file`() {
        val bytes = ByteArray(64)
        bytes.putU16Le(0, 0x8664)
        bytes.putU16Le(2, 0)
        bytes.putU32Le(8, 0u)
        bytes.putU32Le(12, 0u)
        bytes.putU16Le(16, 0)

        assertNull(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
    }

    @Test
    fun `rejects COFF object file with truncated symbol table`() {
        val bytes = ByteArray(64)
        bytes.putU16Le(0, 0x8664)
        bytes.putU16Le(2, 1)
        bytes.putU32Le(8, 48u)
        bytes.putU32Le(12, 2u)
        bytes.putU16Le(16, 0)

        assertNull(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
    }

    @Test
    fun `does not classify Wasm as native binary`() {
        val bytes = byteArrayOf(
            0x00, 0x61, 0x73, 0x6D,
            0x01, 0x00, 0x00, 0x00,
        )

        assertNull(NativeBinaryFormatDetector.detect(bytes.toCommonByteBuffer()))
    }

    @Test
    fun `returns null for truncated input`() {
        assertNull(NativeBinaryFormatDetector.detect(byteArrayOf(0x7F, 0x45).toCommonByteBuffer()))
        assertNull(NativeBinaryFormatDetector.detect(byteArrayOf(0x4D, 0x5A).toCommonByteBuffer()))
        assertNull(NativeBinaryFormatDetector.detect(byteArrayOf(0xFE.toByte(), 0xED.toByte()).toCommonByteBuffer()))
    }

    @Test
    fun `does not move input buffer cursor`() {
        val buffer = elf64Bytes().toCommonByteBuffer()
        buffer.position(7)

        NativeBinaryFormatDetector.detect(buffer)

        assertEquals(7, buffer.position())
    }

}

private fun elf64Bytes(): ByteArray {
    val bytes = ByteArray(64)
    bytes[0] = 0x7F
    bytes[1] = 'E'.code.toByte()
    bytes[2] = 'L'.code.toByte()
    bytes[3] = 'F'.code.toByte()
    bytes[4] = 2
    bytes[5] = 1
    return bytes
}

private fun ByteArray.putU16Le(offset: Int, value: Int) {
    this[offset] = (value and 0xFF).toByte()
    this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

private fun ByteArray.putU16Be(offset: Int, value: Int) {
    this[offset] = ((value ushr 8) and 0xFF).toByte()
    this[offset + 1] = (value and 0xFF).toByte()
}

private fun ByteArray.putU32Le(offset: Int, value: UInt) {
    this[offset] = (value and 0xFFu).toByte()
    this[offset + 1] = ((value shr 8) and 0xFFu).toByte()
    this[offset + 2] = ((value shr 16) and 0xFFu).toByte()
    this[offset + 3] = ((value shr 24) and 0xFFu).toByte()
}

private fun ByteArray.putU32Be(offset: Int, value: UInt) {
    this[offset] = ((value shr 24) and 0xFFu).toByte()
    this[offset + 1] = ((value shr 16) and 0xFFu).toByte()
    this[offset + 2] = ((value shr 8) and 0xFFu).toByte()
    this[offset + 3] = (value and 0xFFu).toByte()
}
