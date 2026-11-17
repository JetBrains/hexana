package org.jetbrains.hexana.codec

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Snappy block-format test vectors.
 *
 * All vectors are hand-constructed from the spec at:
 * https://github.com/google/snappy/blob/main/format_description.txt
 *
 * Vector construction notes:
 *
 *   ALL-LITERAL "Hello":
 *     preamble:  0x05 (varint 5)
 *     literal:   tag = (4<<2)|0 = 0x10, data = 48 65 6C 6C 6F
 *
 *   COPY-2 "abcabc":
 *     preamble:  0x06 (varint 6)
 *     literal:   tag = (2<<2)|0 = 0x08, data = 61 62 63
 *     copy-2:    tag = ((3-1)<<2)|0b10 = 0x0A, offset 3 LE16 = 03 00
 *
 *   RLE "aaaaaa" (overlapping copy, length > offset):
 *     preamble:  0x06
 *     literal:   tag = (0<<2)|0 = 0x00, data = 61
 *     copy-1:    tag = (1<<2)|(0<<5)|0b01 = 0x05, offset-low = 0x01
 *                length = (tag>>2)&0x7 + 4 = 1+4 = 5, offset = 1
 *
 *   TWO-BYTE VARINT preamble (length 200):
 *     preamble:  0xC8 0x01 (200 = 0xC8 | 0x80 -> varint cont., then 0x01)
 *     literal:   tag 0xF0 = (60<<2)|0 (extra=60 -> 1-byte length extension), ext=0xC7 (199+1=200),
 *                data = 200 bytes of value 0x42
 */
class SnappyBlockTest {

    @Test
    fun `all-literal block decodes Hello`() {
        val block = byteArrayOf(
            0x05,                               // preamble: uncompressed length = 5
            0x10.toByte(),                      // literal tag: extra=4, length=5
            0x48, 0x65, 0x6C, 0x6C, 0x6F,      // "Hello"
        )
        val expected = "Hello".encodeToByteArray()
        assertContentEquals(expected, snappyUncompress(block, 5))
    }

    @Test
    fun `copy-2 back-reference abcabc`() {
        val block = byteArrayOf(
            0x06,                               // preamble: length 6
            0x08,                               // literal tag: extra=2, length=3
            0x61, 0x62, 0x63,                   // "abc"
            0x0A,                               // copy-2 tag: length-1=2 -> length=3, next 2 bytes = offset
            0x03, 0x00,                         // offset=3 LE16
        )
        val expected = "abcabc".encodeToByteArray()
        assertContentEquals(expected, snappyUncompress(block, 6))
    }

    @Test
    fun `copy-1 overlapping run aaaaaa`() {
        val block = byteArrayOf(
            0x06,   // preamble: length 6
            0x00,   // literal tag: extra=0, length=1
            0x61,   // 'a'
            0x05,   // copy-1 tag: length=1+4=5, offset_high3=0
            0x01,   // offset_low = 1 -> offset=1
        )
        val expected = "aaaaaa".encodeToByteArray()
        assertContentEquals(expected, snappyUncompress(block, 6))
    }

    @Test
    fun `two-byte varint preamble 200 bytes`() {
        // Preamble: 200 as unsigned LEB128.
        //   200 = 0xC8; bits[6:0] = 0x48, with continuation bit -> 0xC8; remaining = 200>>7 = 1 -> 0x01.
        // Literal with 1-byte length extension: extra=60 -> tag = (60 << 2) | 0 = 0xF0.
        //   Next byte = length-1 = 199 = 0xC7. Then 200 literal bytes.
        val literalData = ByteArray(200) { 0x42 }
        val block = ByteArray(2 + 1 + 1 + 200)
        block[0] = 0xC8.toByte()    // varint byte 0: bits[6:0]=0x48, continuation
        block[1] = 0x01             // varint byte 1: bits[13:7]=0x01
        block[2] = 0xF0.toByte()    // literal tag: extra=60 (1-byte length ext), type=0b00
        block[3] = 0xC7.toByte()    // length-1 = 199, so length = 200
        literalData.copyInto(block, 4)

        assertContentEquals(literalData, snappyUncompress(block, 200))
    }

    @Test
    fun `length mismatch throws CodecException`() {
        val block = byteArrayOf(
            0x05,
            0x10.toByte(),
            0x48, 0x65, 0x6C, 0x6C, 0x6F,
        )
        assertFailsWith<CodecException> {
            snappyUncompress(block, 4) // wrong expected size
        }
    }

    @Test
    fun `decompress UNCOMPRESSED passthrough`() {
        val data = byteArrayOf(1, 2, 3, 4, 5)
        assertContentEquals(data, decompress(Codec.UNCOMPRESSED, data, data.size))
    }

    @Test
    fun `decompress SNAPPY delegates to snappyUncompress`() {
        val block = byteArrayOf(
            0x05,
            0x10.toByte(),
            0x48, 0x65, 0x6C, 0x6C, 0x6F,
        )
        val expected = "Hello".encodeToByteArray()
        assertContentEquals(expected, decompress(Codec.SNAPPY, block, 5))
    }
}
