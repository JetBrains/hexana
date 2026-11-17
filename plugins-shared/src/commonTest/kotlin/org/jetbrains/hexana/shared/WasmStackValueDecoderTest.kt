package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WasmStackValueDecoderTest {

    // -------------------------------------------------------------------------
    // decodeWasmValueHex
    // -------------------------------------------------------------------------

    @Test
    fun i32SimpleValue() {
        // little-endian 03 00 00 00 = 3
        assertEquals("3", decodeWasmValueHex("03000000", "i32"))
    }

    @Test
    fun i32SevenValue() {
        // little-endian 07 00 00 00 = 7
        assertEquals("7", decodeWasmValueHex("07000000", "i32"))
    }

    @Test
    fun i32NegativeValue() {
        // 0xFFFFFFFF = -1 in signed i32
        assertEquals("-1", decodeWasmValueHex("ffffffff", "i32"))
    }

    @Test
    fun i64SimpleValue() {
        // little-endian 05 00 00 00 00 00 00 00 = 5
        assertEquals("5", decodeWasmValueHex("0500000000000000", "i64"))
    }

    @Test
    fun i64LargeValue() {
        // little-endian 01 00 00 00 01 00 00 00 = 0x100000001 = 4294967297
        assertEquals("4294967297", decodeWasmValueHex("0100000001000000", "i64"))
    }

    @Test
    fun f32OnePointZero() {
        // 0x3F800000 little-endian = 1.0f
        assertEquals("1.0", decodeWasmValueHex("0000803f", "f32"))
    }

    @Test
    fun f32Zero() {
        assertEquals("0.0", decodeWasmValueHex("00000000", "f32"))
    }

    @Test
    fun f64OnePointZero() {
        // 0x3FF0000000000000 little-endian = 1.0
        assertEquals("1.0", decodeWasmValueHex("000000000000f03f", "f64"))
    }

    @Test
    fun emptyHexReturnsNull() {
        assertNull(decodeWasmValueHex("", "i32"))
    }

    @Test
    fun wrongLengthForI32ReturnsNull() {
        // 8 bytes for i32 (should be 4) -> null
        assertNull(decodeWasmValueHex("0000000000000000", "i32"))
    }

    @Test
    fun wrongLengthForI64ReturnsNull() {
        // 4 bytes for i64 (should be 8) -> null
        assertNull(decodeWasmValueHex("03000000", "i64"))
    }

    @Test
    fun unknownTypeReturnsNull() {
        assertNull(decodeWasmValueHex("03000000", "v128"))
    }

    @Test
    fun refTypeReturnsNull() {
        assertNull(decodeWasmValueHex("03000000", "ref"))
    }

    @Test
    fun oddLengthHexReturnsNull() {
        assertNull(decodeWasmValueHex("030", "i32"))
    }

    @Test
    fun invalidHexCharsReturnNull() {
        assertNull(decodeWasmValueHex("0300zz00", "i32"))
    }

    // -------------------------------------------------------------------------
    // parseRawPacketResponse
    // -------------------------------------------------------------------------

    @Test
    fun extractsPayloadFromTypicalTwoLineOutput() {
        val lines = listOf(
            "  packet: qWasmStackValue:0;0",
            "  response: 03000000",
        )
        assertEquals("03000000", parseRawPacketResponse(lines))
    }

    @Test
    fun emptyResponseLineReturnsNull() {
        val lines = listOf(
            "  packet: qWasmStackValue:0;5",
            "  response: ",
        )
        assertNull(parseRawPacketResponse(lines))
    }

    @Test
    fun missingResponseLineReturnsNull() {
        val lines = listOf("  packet: qWasmStackValue:0;5")
        assertNull(parseRawPacketResponse(lines))
    }

    @Test
    fun emptyLinesReturnsNull() {
        assertNull(parseRawPacketResponse(emptyList()))
    }

    @Test
    fun picksLastResponseLine() {
        // In practice only one response line appears, but the function should
        // be robust if LLDB echoes multiple lines.
        val lines = listOf(
            "  response: 01000000",
            "  packet: qWasmStackValue:0;1",
            "  response: 07000000",
        )
        assertEquals("07000000", parseRawPacketResponse(lines))
    }
}
