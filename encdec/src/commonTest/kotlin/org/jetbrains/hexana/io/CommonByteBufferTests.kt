package org.jetbrains.hexana.io

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue


private fun createByteBuffer(vararg bytes: Byte = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)) = bytes.toCommonByteBuffer()

class CommonByteBufferTests {

    @Test
    fun `reading byte affects position`() {
        val buffer = createByteBuffer()
        assertEquals(0, buffer.position())
        buffer.get()
        assertEquals(1, buffer.position())
        buffer.get()
        assertEquals(2, buffer.position())
    }

    @Test
    fun `setting position affects reading bytes`() {
        val buffer = createByteBuffer()
        buffer.position(2)
        val bytesSequential = listOf(buffer.get(), buffer.get(), buffer.get())
        assertContentEquals(listOf(3, 4, 5), bytesSequential)
    }

    @Test
    fun `get into array with offset and size updates destination and position`() {
        val buffer = createByteBuffer()
        val dst = ByteArray(5) { 0 }
        buffer.get(dst, 1, 3)
        assertContentEquals(listOf(0, 1, 2, 3, 0), dst.map { it.toInt() })
        assertEquals(3, buffer.position())
    }

    @Test
    fun `remaining and hasRemaining update as bytes are read`() {
        val buffer = createByteBuffer()
        assertEquals(9, buffer.remaining())
        assertTrue(buffer.hasRemaining())
        repeat(4) { buffer.get() }

        assertEquals(5, buffer.remaining())
        assertTrue(buffer.hasRemaining())

        repeat(5) { buffer.get() }
        assertEquals(0, buffer.remaining())
        assertTrue(!buffer.hasRemaining())
    }

    @Test
    fun `getULEB128 decoding 2-bytes`() {
        val buffer = createByteBuffer(0x80.toByte(), 0x01)
        val value = buffer.getULEB128()
        assertEquals(128L, value)
        assertEquals(2, buffer.position())
    }

    @Test
    fun `getULEB128 decoding 1-byte`() {
        val buffer = createByteBuffer(0x7F)
        val value = buffer.getULEB128()
        assertEquals(127L, value)
        assertEquals(1, buffer.position())
    }

    @Test
    fun `getULEB128 decoding 3-bytes`() {
        val buffer = createByteBuffer(0xE5.toByte(), 0x8E.toByte(), 0x26.toByte())
        val value = buffer.getULEB128()
        assertEquals(624485L, value)
        assertEquals(3, buffer.position())
    }

    // ========================================================================
    // ULEB128 — boundary values and round-trip
    //
    // Encodings hand-computed from the LEB128 spec:
    //   https://en.wikipedia.org/wiki/LEB128
    // ========================================================================

    @Test
    fun `getULEB128 zero`() {
        val buffer = createByteBuffer(0x00)
        assertEquals(0L, buffer.getULEB128())
        assertEquals(1, buffer.position())
    }

    @Test
    fun `getULEB128 max single-byte (63)`() {
        // 63 fits in 6 bits; high bit 0 → single-byte terminator
        val buffer = createByteBuffer(0x3F)
        assertEquals(63L, buffer.getULEB128())
        assertEquals(1, buffer.position())
    }

    @Test
    fun `getULEB128 boundary 128 (first 2-byte value)`() {
        // 128 = 0x80; needs continuation. byte1=0x80, byte2=0x01
        val buffer = createByteBuffer(0x80.toByte(), 0x01)
        assertEquals(128L, buffer.getULEB128())
        assertEquals(2, buffer.position())
    }

    @Test
    fun `getULEB128 max 2-byte (16383)`() {
        // 16383 = 0x3FFF; bits 0-6 = 0x7F, bits 7-13 = 0x7F
        val buffer = createByteBuffer(0xFF.toByte(), 0x7F)
        assertEquals(16383L, buffer.getULEB128())
        assertEquals(2, buffer.position())
    }

    @Test
    fun `getULEB128 boundary 16384 (first 3-byte value)`() {
        val buffer = createByteBuffer(0x80.toByte(), 0x80.toByte(), 0x01)
        assertEquals(16384L, buffer.getULEB128())
        assertEquals(3, buffer.position())
    }

    @Test
    fun `getULEB128 UInt MAX_VALUE (5 bytes)`() {
        // 0xFFFFFFFF = 4294967295. Five 7-bit chunks, the last is 0x0F.
        val buffer = createByteBuffer(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F,
        )
        assertEquals(4294967295L, buffer.getULEB128())
        assertEquals(5, buffer.position())
    }

    @Test
    fun `getULEB128 ULong MAX_VALUE (10 bytes)`() {
        // 0xFFFF_FFFF_FFFF_FFFF: nine 0xFF (with continuation) + final 0x01
        // for the 64th bit (bit 63). Lower 63 bits = 0x7FFF_FFFF_FFFF_FFFF
        // distributed across 9 7-bit chunks; bit 63 alone in the 10th byte.
        val buffer = createByteBuffer(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x01,
        )
        assertEquals(-1L, buffer.getULEB128()) // ULong.MAX_VALUE bit-pattern == -1L
        assertEquals(10, buffer.position())
    }

    @Test
    fun `getULEB128 sequential reads`() {
        // [0x05, 0x80 0x01, 0x7F] = 5, 128, 127
        val buffer = createByteBuffer(0x05, 0x80.toByte(), 0x01, 0x7F)
        assertEquals(5L, buffer.getULEB128())
        assertEquals(1, buffer.position())
        assertEquals(128L, buffer.getULEB128())
        assertEquals(3, buffer.position())
        assertEquals(127L, buffer.getULEB128())
        assertEquals(4, buffer.position())
    }

    // ========================================================================
    // SLEB128 — comprehensive coverage
    //
    // Critical: the negative-single-byte cases (-2, -18, -63, -64) are the
    // ones that exposed the sign-extension bug in getSLEB128 (the mask used
    // `shl shift` instead of `shl (shift + 7)`). Older code happened to
    // round-trip -1 only, because (-1 OR anything) == -1.
    // ========================================================================

    @Test
    fun `getSLEB128 zero`() {
        val buffer = createByteBuffer(0x00)
        assertEquals(0L, buffer.getSLEB128())
        assertEquals(1, buffer.position())
    }

    @Test
    fun `getSLEB128 small positive single-byte`() {
        // 1, 63 — single-byte positives, sign bit (bit 6) clear
        assertEquals(1L, createByteBuffer(0x01).getSLEB128())
        assertEquals(63L, createByteBuffer(0x3F).getSLEB128())
    }

    @Test
    fun `getSLEB128 negative single-byte minus 1`() {
        // 0x7F: bit 6 set → sign-extend. Pre-fix this was the only correct case.
        val buffer = createByteBuffer(0x7F)
        assertEquals(-1L, buffer.getSLEB128())
        assertEquals(1, buffer.position())
    }

    @Test
    fun `getSLEB128 negative single-byte minus 2 (regression for sign-extension bug)`() {
        // 0x7E: lower 7 bits = 126; with sign extension at shift+7 = 7 → -2.
        // Pre-fix code OR'd with `-1L shl 0` which collapses any value to -1.
        val buffer = createByteBuffer(0x7E)
        assertEquals(-2L, buffer.getSLEB128())
        assertEquals(1, buffer.position())
    }

    @Test
    fun `getSLEB128 negative single-byte minus 18 (anyref heap type byte 0x6E)`() {
        // 0x6E is the binary encoding of the `any` heap type in the GC
        // proposal. SLEB128(-18). This is the byte that surfaced the
        // sign-extension bug while testing br_on_cast_fail.
        val buffer = createByteBuffer(0x6E)
        assertEquals(-18L, buffer.getSLEB128())
        assertEquals(1, buffer.position())
    }

    @Test
    fun `getSLEB128 negative single-byte boundary minus 63 and minus 64`() {
        // -63 = 0x41 (bit 6 set, lower bits 0x01); -64 = 0x40 (bit 6 set, others 0)
        assertEquals(-63L, createByteBuffer(0x41).getSLEB128())
        assertEquals(-64L, createByteBuffer(0x40).getSLEB128())
    }

    @Test
    fun `getSLEB128 positive boundary 64 needs 2 bytes`() {
        // 64 single-byte would be 0x40 (sign bit set → would decode as -64).
        // Spec encoding adds a 0x00 terminator: 0xC0 0x00.
        val buffer = createByteBuffer(0xC0.toByte(), 0x00)
        assertEquals(64L, buffer.getSLEB128())
        assertEquals(2, buffer.position())
    }

    @Test
    fun `getSLEB128 negative boundary minus 65 needs 2 bytes`() {
        // -65: byte1 = 0xBF (continuation + 0x3F), byte2 = 0x7F (sign-extension byte)
        val buffer = createByteBuffer(0xBF.toByte(), 0x7F)
        assertEquals(-65L, buffer.getSLEB128())
        assertEquals(2, buffer.position())
    }

    @Test
    fun `getSLEB128 multi-byte positive 8191 and 8192`() {
        // 8191 = 0x1FFF: byte1 = 0xFF, byte2 = 0x3F
        assertEquals(8191L, createByteBuffer(0xFF.toByte(), 0x3F).getSLEB128())
        // 8192 = 0x2000: byte1 = 0x80, byte2 = 0xC0, byte3 = 0x00
        // (single byte2 = 0x40 would be sign-extended to -8192, so 3 bytes)
        assertEquals(8192L, createByteBuffer(0x80.toByte(), 0xC0.toByte(), 0x00).getSLEB128())
    }

    @Test
    fun `getSLEB128 multi-byte negative minus 8191 and minus 8192`() {
        // -8191: byte1 = 0x81 (continuation + 0x01), byte2 = 0x40 (sign + 0)
        assertEquals(-8191L, createByteBuffer(0x81.toByte(), 0x40).getSLEB128())
        // -8192: byte1 = 0x80, byte2 = 0x40
        assertEquals(-8192L, createByteBuffer(0x80.toByte(), 0x40).getSLEB128())
    }

    @Test
    fun `getSLEB128 Int MAX_VALUE (5 bytes)`() {
        // 0x7FFFFFFF: bytes 1-4 each 0xFF, byte 5 = 0x07 (positive sign in bit 6 = 0)
        val buffer = createByteBuffer(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x07,
        )
        assertEquals(Int.MAX_VALUE.toLong(), buffer.getSLEB128())
        assertEquals(5, buffer.position())
    }

    @Test
    fun `getSLEB128 Int MIN_VALUE (5 bytes)`() {
        // -2^31: bytes 1-4 each 0x80 (continuation + 0), byte 5 = 0x78 (sign bit set)
        val buffer = createByteBuffer(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x78,
        )
        assertEquals(Int.MIN_VALUE.toLong(), buffer.getSLEB128())
        assertEquals(5, buffer.position())
    }

    @Test
    fun `getSLEB128 Long MAX_VALUE (10 bytes)`() {
        // 0x7FFF_FFFF_FFFF_FFFF: nine 0xFF + final 0x00 (positive)
        val buffer = createByteBuffer(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00,
        )
        assertEquals(Long.MAX_VALUE, buffer.getSLEB128())
        assertEquals(10, buffer.position())
    }

    @Test
    fun `getSLEB128 Long MIN_VALUE (10 bytes)`() {
        // -2^63: nine 0x80 (continuation + 0) + final 0x7F (sign bit set, all data 1)
        val buffer = createByteBuffer(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x7F,
        )
        assertEquals(Long.MIN_VALUE, buffer.getSLEB128())
        assertEquals(10, buffer.position())
    }

    @Test
    fun `getSLEB128 sequential reads (alternating signs)`() {
        // [0x7F, 0x01, 0x6E, 0x3F] = -1, 1, -18, 63
        val buffer = createByteBuffer(0x7F, 0x01, 0x6E, 0x3F)
        assertEquals(-1L, buffer.getSLEB128())
        assertEquals(1, buffer.position())
        assertEquals(1L, buffer.getSLEB128())
        assertEquals(2, buffer.position())
        assertEquals(-18L, buffer.getSLEB128())
        assertEquals(3, buffer.position())
        assertEquals(63L, buffer.getSLEB128())
        assertEquals(4, buffer.position())
    }

    @Test
    fun `getSLEB128 followed by getULEB128 in mixed stream`() {
        // SLEB(-18) | ULEB(128) | SLEB(63) | ULEB(0)
        // = 0x6E | 0x80 0x01 | 0x3F | 0x00
        val buffer = createByteBuffer(0x6E, 0x80.toByte(), 0x01, 0x3F, 0x00)
        assertEquals(-18L, buffer.getSLEB128())
        assertEquals(128L, buffer.getULEB128())
        assertEquals(63L, buffer.getSLEB128())
        assertEquals(0L, buffer.getULEB128())
        assertEquals(5, buffer.position())
    }

    @Test
    fun `getSLEB128 non-canonical multi-byte encoding still decodes`() {
        // Some encoders emit non-minimal (over-padded) representations.
        // e.g. value 0 encoded as [0x80, 0x00] (continuation, then 0).
        // The spec allows this; decoders must handle it.
        assertEquals(0L, createByteBuffer(0x80.toByte(), 0x00).getSLEB128())
        // value 1 padded to 2 bytes: [0x81, 0x00]
        assertEquals(1L, createByteBuffer(0x81.toByte(), 0x00).getSLEB128())
        // value -1 padded to 2 bytes: [0xFF, 0x7F]
        assertEquals(-1L, createByteBuffer(0xFF.toByte(), 0x7F).getSLEB128())
    }

    @Test
    fun `getFloatLE reads little endian float and advances position`() {
        val buffer = createByteBuffer(0x00, 0x00, 0x80.toByte(), 0x3F)
        val value = buffer.getFloatLE()
        assertEquals(1.0f, value)
        assertEquals(4, buffer.position())
    }

    @Test
    fun `getDoubleLE reads little endian double and advances position`() {
        val buffer = createByteBuffer(
            0x1F,
            0x85.toByte(),
            0xEB.toByte(),
            0x51,
            0xB8.toByte(),
            0x1E,
            0x09,
            0x40
        )
        val value = buffer.getDoubleLE()
        assertEquals(3.14, value)
        assertEquals(8, buffer.position())
    }
}