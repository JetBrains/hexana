package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

private fun source(vararg b: Int) =
    binaryDocumentOf(b.map { it.toByte() }.toByteArray().toCommonByteBuffer()).byteSource()

class LebDecodeTest {

    @Test
    fun uleb_single_byte() {
        val r = decodeULEB128(source(0x05), 0L)
        assertEquals(5L, r.value)
        assertEquals(1, r.byteCount)
        assertContentEquals(byteArrayOf(0x05), r.rawBytes)
    }

    @Test
    fun uleb_two_bytes() {
        // 0x80 0x02 -> 256
        val r = decodeULEB128(source(0x80, 0x02), 0L)
        assertEquals(256L, r.value)
        assertEquals(2, r.byteCount)
    }

    @Test
    fun uleb_three_bytes_canonical_test_vector() {
        // 624485 = 0xE5 0x8E 0x26 (canonical wasm test vector)
        val r = decodeULEB128(source(0xE5, 0x8E, 0x26), 0L)
        assertEquals(624485L, r.value)
        assertEquals(3, r.byteCount)
        assertContentEquals(byteArrayOf(0xE5.toByte(), 0x8E.toByte(), 0x26), r.rawBytes)
    }

    @Test
    fun uleb_zero() {
        val r = decodeULEB128(source(0x00), 0L)
        assertEquals(0L, r.value)
        assertEquals(1, r.byteCount)
    }

    @Test
    fun sleb_negative_one() {
        // -1 SLEB128 = 0x7F (single byte, sign bit set)
        val r = decodeSLEB128(source(0x7F), 0L)
        assertEquals(-1L, r.value)
        assertEquals(1, r.byteCount)
    }

    @Test
    fun sleb_negative_value_two_bytes() {
        // -123456 = 0xC0 0xBB 0x78 (3 bytes) — verifying sign extension
        val r = decodeSLEB128(source(0xC0, 0xBB, 0x78), 0L)
        assertEquals(-123456L, r.value)
        assertEquals(3, r.byteCount)
    }

    @Test
    fun sleb_positive_zero() {
        val r = decodeSLEB128(source(0x00), 0L)
        assertEquals(0L, r.value)
        assertEquals(1, r.byteCount)
    }

    @Test
    fun sleb_positive_value_two_bytes() {
        // +624485 SLEB128 = 0xE5 0x8E 0xA6 0x00 (with trailing 0 byte to clear sign)
        // Or: 0xE5 0x8E 0x26 if bit 6 of last byte is clear (which it is, 0x26 = 0010 0110)
        val r = decodeSLEB128(source(0xE5, 0x8E, 0x26), 0L)
        assertEquals(624485L, r.value)
        assertEquals(3, r.byteCount)
    }

    @Test
    fun toHexBytes_uppercase_pairs() {
        assertEquals("E5 8E 26", byteArrayOf(0xE5.toByte(), 0x8E.toByte(), 0x26).toHexBytes())
        assertEquals("00", byteArrayOf(0x00).toHexBytes())
        assertEquals("", ByteArray(0).toHexBytes())
    }

    @Test
    fun decode_at_nonzero_offset() {
        // Place the encoded bytes after a prefix; decode should respect `at`.
        val r = decodeULEB128(source(0xFF, 0xFF, 0xE5, 0x8E, 0x26), at = 2L)
        assertEquals(624485L, r.value)
        assertEquals(3, r.byteCount)
    }
}
