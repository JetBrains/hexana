package org.jetbrains.hexana.dex

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DexFileParserTest {

    @Test
    fun isDexReturnsFalseForTooSmallBuffer() {
        val buf = ByteArray(4).toCommonByteBuffer()
        assertFalse(DexFileParser.isDex(buf))
    }

    @Test
    fun isDexReturnsFalseForNonDexMagic() {
        val buf = ByteArray(8) { 0 }.toCommonByteBuffer()
        assertFalse(DexFileParser.isDex(buf))
    }

    @Test
    fun isDexReturnsTrueForDexMagic() {
        val magic = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x39, 0x00)
        val buf = magic.toCommonByteBuffer()
        assertTrue(DexFileParser.isDex(buf))
    }

    @Test
    fun parseMinimalDex() {
        // Build a minimal DEX file with 0 strings, types, protos, fields,
        // methods, and classes. Header is 0x70 bytes.
        val header = ByteArray(0x70)
        // magic: dex\n039\0
        val magic = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x39, 0x00)
        magic.copyInto(header, 0)
        // checksum at offset 8 (4 bytes LE) — arbitrary
        writeU4(header, 8, 0x12345678L)
        // signature at offset 12 (20 bytes) — zeroes
        // file_size at offset 32
        writeU4(header, 32, 0x70L)
        // header_size at offset 36
        writeU4(header, 36, 0x70L)
        // endian_tag at offset 40
        writeU4(header, 40, 0x12345678L)
        // link_size, link_off, map_off at 44, 48, 52 — zeroes
        // string_ids_size at 56 = 0
        // string_ids_off at 60 = 0
        // type_ids_size at 64 = 0
        // type_ids_off at 68 = 0
        // proto_ids_size at 72 = 0
        // proto_ids_off at 76 = 0
        // field_ids_size at 80 = 0
        // field_ids_off at 84 = 0
        // method_ids_size at 88 = 0
        // method_ids_off at 92 = 0
        // class_defs_size at 96 = 0
        // class_defs_off at 100 = 0
        // data_size at 104 = 0
        // data_off at 108 = 0

        val buf = header.toCommonByteBuffer()
        val dex = DexFileParser.parse(buf)

        assertEquals("039", dex.header.version)
        assertEquals(0x70L, dex.header.fileSize)
        assertEquals(0x70L, dex.header.headerSize)
        assertEquals(0, dex.stringIds.size)
        assertEquals(0, dex.typeIds.size)
        assertEquals(0, dex.protoIds.size)
        assertEquals(0, dex.fieldIds.size)
        assertEquals(0, dex.methodIds.size)
        assertEquals(0, dex.classDefs.size)
    }

    @Test
    fun parseWithOneString() {
        // Header (0x70) + string_id (4 bytes at 0x70) + string_data at 0x74
        val stringData = byteArrayOf(
            0x05, // ULEB128 utf16_size = 5
            0x48, 0x65, 0x6C, 0x6C, 0x6F, // "Hello"
            0x00, // null terminator
        )
        val totalSize = 0x70 + 4 + stringData.size
        val data = ByteArray(totalSize)

        // magic
        byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x39, 0x00).copyInto(data, 0)
        writeU4(data, 8, 0xAABBCCDDL) // checksum
        writeU4(data, 32, totalSize.toLong()) // file_size
        writeU4(data, 36, 0x70L) // header_size
        writeU4(data, 40, 0x12345678L) // endian_tag
        writeU4(data, 56, 1L) // string_ids_size = 1
        writeU4(data, 60, 0x70L) // string_ids_off = 0x70

        // string_id_item at 0x70: offset to string_data = 0x74
        writeU4(data, 0x70, 0x74L)

        // string_data at 0x74
        stringData.copyInto(data, 0x74)

        val dex = DexFileParser.parse(data.toCommonByteBuffer())
        assertEquals(1, dex.stringIds.size)
        assertEquals("Hello", dex.string(0))
    }

    @Test
    fun renderClassAccessFlags() {
        assertEquals("public final", renderDexClassAccessFlags(0x0011))
        assertEquals("public interface abstract", renderDexClassAccessFlags(0x0601))
        assertEquals("", renderDexClassAccessFlags(0))
    }

    private fun writeU4(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
