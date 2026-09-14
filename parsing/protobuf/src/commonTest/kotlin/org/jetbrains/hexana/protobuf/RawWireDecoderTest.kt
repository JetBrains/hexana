package org.jetbrains.hexana.protobuf

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the schema-less [RawWireDecoder]. Reuses [ProtoBuilder] from
 * FileDescriptorSetParserTest for fixture construction.
 */
class RawWireDecoderTest {

    // ---- Flat message ----------------------------------------------------

    @Test
    fun flatMessageAllWireTypes() {
        // field1 varint 42; field2 string "hello"; field3 i32 (bytes 78 56 34 12 LE); field4 varint 1.
        val bytes = buildProtoMessage {
            varintField(1, 42L)
            lenField(2, "hello".encodeToByteArray())
            i32Field(3, byteArrayOf(0x78, 0x56, 0x34, 0x12))
            varintField(4, 1L)
        }
        val rows = RawWireDecoder.decode(bytes.toCommonByteBuffer())

        assertEquals(4, rows.size)
        assertEquals(listOf("1", "2", "3", "4"), rows.map { it.path })
        assertEquals(listOf("varint", "string", "i32", "varint"), rows.map { it.typeGuess })
        assertEquals("42", rows[0].value)
        assertTrue(rows[1].value.contains("hello"), "expected hello, got ${rows[1].value}")
        assertTrue(rows[2].value.startsWith("0x12345678"), "expected 0x12345678, got ${rows[2].value}")
        assertEquals("1", rows[3].value)

        // Offsets: field1 tag at 0. field1 = tag(1 byte) + varint 42(1 byte) = 2 bytes,
        // so field2 tag sits at offset 2.
        assertEquals(0, rows[0].offset)
        assertEquals(2, rows[1].offset)
        assertEquals(0, rows[0].depth)
    }

    // ---- Nested message --------------------------------------------------

    @Test
    fun nestedMessageInterleavedFlat() {
        val inner = buildProtoMessage {
            varintField(1, 100L)
            lenField(2, "nested".encodeToByteArray())
        }
        val outer = buildProtoMessage {
            varintField(1, 7L)
            lenField(3, inner)
        }
        val rows = RawWireDecoder.decode(outer.toCommonByteBuffer())

        // Expected flat order: "1" varint; "2" message; "2.1" varint; "2.2" string.
        // (positional path, so the LEN field is positional 2 even though field number is 3)
        assertEquals(4, rows.size)

        val r0 = rows[0]
        assertEquals("1", r0.path)
        assertEquals("varint", r0.typeGuess)
        assertEquals("7", r0.value)
        assertEquals(0, r0.depth)

        val msg = rows[1]
        assertEquals("2", msg.path)
        assertEquals("message", msg.typeGuess)
        assertEquals("{ 2 fields }", msg.value)
        assertEquals(0, msg.depth)

        val c1 = rows[2]
        assertEquals("2.1", c1.path)
        assertEquals("varint", c1.typeGuess)
        assertEquals("100", c1.value)
        assertEquals(1, c1.depth)

        val c2 = rows[3]
        assertEquals("2.2", c2.path)
        assertEquals("string", c2.typeGuess)
        assertTrue(c2.value.contains("nested"))
        assertEquals(1, c2.depth)

        // Child offsets must fall strictly inside the parent message's span.
        val msgStart = msg.offset
        val msgEnd = msg.offset + msg.length
        assertTrue(c1.offset in (msgStart + 1) until msgEnd, "child1 offset ${c1.offset} not inside [$msgStart,$msgEnd)")
        assertTrue(c2.offset in (msgStart + 1) until msgEnd, "child2 offset ${c2.offset} not inside [$msgStart,$msgEnd)")
    }

    // ---- Ambiguous LEN: non-message, non-UTF-8 -> bytes ------------------

    @Test
    fun ambiguousBinaryPayloadDecodesAsBytes() {
        val bytes = buildProtoMessage {
            lenField(1, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
        }
        val rows = RawWireDecoder.decode(bytes.toCommonByteBuffer())

        assertEquals(1, rows.size)
        assertEquals("bytes", rows[0].typeGuess)
        assertTrue(rows[0].value.contains("DE AD BE EF"), "expected DE AD BE EF, got ${rows[0].value}")
    }

    // ---- Short printable string -----------------------------------------

    @Test
    fun shortPrintableStringGatedByWireType() {
        // "ok" = 0x6F 0x6B. As a tag, 0x6F => fn=13, wt=7 (invalid) => message probe fails => STRING.
        val bytes = buildProtoMessage {
            lenField(1, "ok".encodeToByteArray())
        }
        val rows = RawWireDecoder.decode(bytes.toCommonByteBuffer())

        assertEquals(1, rows.size)
        assertEquals("string", rows[0].typeGuess)
        assertTrue(rows[0].value.contains("ok"), "expected ok, got ${rows[0].value}")
    }

    // ---- Hostile input never throws -------------------------------------

    @Test
    fun hostileInputDoesNotThrow() {
        val hostile = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        // Must not throw; returns a (possibly error-terminated) list.
        val rows = RawWireDecoder.decode(hostile.toCommonByteBuffer())
        assertTrue(rows.isNotEmpty(), "expected at least one row for hostile input")
        // 0xFF 0xFF 0xFF is an unterminated varint tag => truncated => error sentinel.
        val last = rows.last()
        assertTrue(
            last.typeGuess == "error" || last.typeGuess == "bytes",
            "expected error or bytes terminal row, got ${last.typeGuess}",
        )
    }

    // ---- Empty LEN field -> bytes (len==0 branch) -----------------------

    @Test
    fun emptyLenFieldDecodesAsBytes() {
        val bytes = buildProtoMessage {
            lenField(1, byteArrayOf())
        }
        val rows = RawWireDecoder.decode(bytes.toCommonByteBuffer())

        assertEquals(1, rows.size)
        assertEquals("bytes", rows[0].typeGuess)
        assertTrue(
            rows[0].value == "(empty)" || rows[0].value.isEmpty(),
            "expected empty indicator, got '${rows[0].value}'",
        )
    }

    // ---- Top-level field ranges ------------------------------------------

    @Test
    fun topLevelRangesMatchTopLevelDecodedRows() {
        val inner = buildProtoMessage {
            varintField(1, 100L)
            lenField(2, "nested".encodeToByteArray())
        }
        val bytes = buildProtoMessage {
            varintField(1, 42L)
            lenField(2, "hello".encodeToByteArray())
            i32Field(3, byteArrayOf(0x78, 0x56, 0x34, 0x12))
            lenField(4, inner)
        }
        val ranges = RawWireDecoder.topLevelFieldRanges(bytes.toCommonByteBuffer())
        val topRows = RawWireDecoder.decode(bytes.toCommonByteBuffer()).filter { it.depth == 0 }

        assertEquals(listOf(1, 2, 3, 4), ranges.map { it.fieldNumber })
        assertEquals(topRows.map { it.offset }, ranges.map { it.offset })
        assertEquals(topRows.map { it.length }, ranges.map { it.size })
        // LEN fields are not classified further, so field 2 (a string) and field 4 (a
        // submessage) both read as "len" where `decode` says "string" and "message".
        assertEquals(listOf("varint", "len", "i32", "len"), ranges.map { it.wireType })
        // Contiguous, and together they account for every byte of the message.
        assertEquals(0, ranges.first().offset)
        assertEquals(bytes.size, ranges.last().let { it.offset + it.size })
    }

    @Test
    fun topLevelRangesStopAtTruncatedField() {
        val valid = buildProtoMessage { varintField(1, 42L) }
        // A LEN field whose declared length runs past the end of the buffer.
        val bytes = valid + byteArrayOf(0x12, 0x40, 0x01, 0x02)
        val ranges = RawWireDecoder.topLevelFieldRanges(bytes.toCommonByteBuffer())

        assertEquals(1, ranges.size)
        assertEquals(1, ranges.single().fieldNumber)
        assertEquals(valid.size, ranges.single().size)
    }

    @Test
    fun topLevelRangesLeaveCursorUntouched() {
        val bytes = buildProtoMessage { varintField(1, 42L) }
        val buf = bytes.toCommonByteBuffer()
        RawWireDecoder.topLevelFieldRanges(buf)
        assertEquals(0, buf.position())
    }

    // ---- Long string truncation (STRING_PREVIEW_LIMIT branch) -----------

    @Test
    fun longStringTruncated() {
        val longText = "a".repeat(130) // > STRING_PREVIEW_LIMIT (120)
        val bytes = buildProtoMessage {
            lenField(1, longText.encodeToByteArray())
        }
        val rows = RawWireDecoder.decode(bytes.toCommonByteBuffer())

        assertEquals(1, rows.size)
        assertEquals("string", rows[0].typeGuess)
        assertTrue(rows[0].value.contains("… (+"), "expected truncation suffix, got ${rows[0].value}")
    }
}
