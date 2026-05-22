package org.jetbrains.hexana.document.io

import org.jetbrains.hexana.document.BinaryDocument
import org.jetbrains.hexana.document.binaryDocumentOf
import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun sample(size: Int = 16): ByteArray = ByteArray(size) { (it and 0xFF).toByte() }

/** Wraps test-fixture byte literals so they flow into the buffer-typed factory. */
private fun docOf(bytes: ByteArray): BinaryDocument = binaryDocumentOf(bytes.toCommonByteBuffer())

class ByteSourceCommonByteBufferTest {

    @Test
    fun limit_and_size_track_source_length() {
        val buf = docOf(sample(20)).toCommonByteBuffer()
        assertEquals(20, buf.limit())
        assertEquals(20, buf.size)
    }

    @Test
    fun get_advances_cursor() {
        val buf = docOf(sample(8)).toCommonByteBuffer()
        assertEquals(0, buf.position())
        assertEquals(0, buf.get())
        assertEquals(1, buf.get())
        assertEquals(2, buf.position())
    }

    @Test
    fun indexed_get_does_not_advance_cursor() {
        val buf = docOf(sample(8)).toCommonByteBuffer()
        buf.position(3)
        assertEquals(7, buf[7])
        assertEquals(3, buf.position())
    }

    @Test
    fun get_into_array() {
        val buf = docOf(sample(8)).toCommonByteBuffer()
        val dst = ByteArray(6) { 0x55 }
        buf.position(2)
        buf.get(dst, 1, 4)
        assertContentEquals(byteArrayOf(0x55, 2, 3, 4, 5, 0x55), dst)
        assertEquals(6, buf.position())
    }

    @Test
    fun put_methods_throw() {
        val buf = docOf(sample(4)).toCommonByteBuffer()
        assertFailsWith<UnsupportedOperationException> { buf.put(0xFF.toByte()) }
        assertFailsWith<UnsupportedOperationException> { buf.put(byteArrayOf(0, 0), 0, 2) }
        assertFailsWith<UnsupportedOperationException> { buf.putIntLE(42) }
    }

    @Test
    fun getIntLE_decodes_little_endian() {
        // 0x44332211 little-endian = bytes 0x11 0x22 0x33 0x44
        val data = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val buf = docOf(data).toCommonByteBuffer()
        assertEquals(0x44332211, buf.getIntLE())
    }

    @Test
    fun getFloatLE_round_trip() {
        val expected = 1.5f
        val bits = expected.toRawBits()
        val data = byteArrayOf(
            (bits and 0xFF).toByte(),
            ((bits shr 8) and 0xFF).toByte(),
            ((bits shr 16) and 0xFF).toByte(),
            ((bits shr 24) and 0xFF).toByte(),
        )
        val buf = docOf(data).toCommonByteBuffer()
        assertEquals(expected, buf.getFloatLE())
    }

    @Test
    fun getDoubleLE_round_trip() {
        val expected = 3.14159265358979
        val bits = expected.toRawBits()
        val data = ByteArray(8) { i -> ((bits shr (i * 8)) and 0xFF).toByte() }
        val buf = docOf(data).toCommonByteBuffer()
        assertEquals(expected, buf.getDoubleLE())
    }

    @Test
    fun remaining_and_hasRemaining_track_position() {
        val buf = docOf(sample(8)).toCommonByteBuffer()
        assertEquals(8, buf.remaining())
        assertTrue(buf.hasRemaining())
        buf.position(8)
        assertEquals(0, buf.remaining())
        assertFalse(buf.hasRemaining())
    }

    @Test
    fun position_validates_bounds() {
        val buf = docOf(sample(4)).toCommonByteBuffer()
        // Positioning at limit is legal (cursor at end).
        buf.position(4)
        assertFailsWith<IllegalArgumentException> { buf.position(5) }
        assertFailsWith<IllegalArgumentException> { buf.position(-1) }
    }

    @Test
    fun slice_translates_coordinates() {
        // Underlying: 0,1,2,3,4,5,6,7. Slice [3,7) → local view of 4 bytes (3,4,5,6).
        val buf = docOf(sample(8)).toCommonByteBuffer()
        val sliced = buf.slice(3, 4)
        assertEquals(4, sliced.limit())
        assertEquals(0, sliced.position())
        assertContentEquals(byteArrayOf(3, 4, 5, 6), sliced.toByteArray())
        // Sliced indexed access uses local coordinates.
        assertEquals(3, sliced[0])
        assertEquals(6, sliced[3])
    }

    @Test
    fun slice_validates_bounds() {
        val buf = docOf(sample(8)).toCommonByteBuffer()
        assertFailsWith<IllegalArgumentException> { buf.slice(-1, 1) }
        assertFailsWith<IllegalArgumentException> { buf.slice(0, 9) }
        assertFailsWith<IllegalArgumentException> { buf.slice(7, 2) }
    }

    @Test
    fun duplicate_preserves_absolute_coordinates() {
        val buf = docOf(sample(8)).toCommonByteBuffer()
        buf.position(5)
        val dup = buf.duplicate()
        // duplicate() preserves position in the same coord frame.
        assertEquals(5, dup.position())
        // And does not affect the original.
        assertEquals(5, buf.position())
        assertEquals(5, dup.get())
        assertEquals(5, buf.position()) // original cursor untouched
    }

    @Test
    fun slice_is_independent_of_origin_cursor() {
        val buf = docOf(sample(8)).toCommonByteBuffer()
        buf.position(2)
        val sliced = buf.slice(0, 4) // [0,4) → 0,1,2,3
        sliced.get() // advance sliced cursor
        assertEquals(2, buf.position()) // origin untouched
        assertEquals(1, sliced.position())
    }

    @Test
    fun ULEB128_works_through_adapter() {
        // 624485 ULEB128 = E5 8E 26 (canonical wasm test vector)
        val data = byteArrayOf(0xE5.toByte(), 0x8E.toByte(), 0x26.toByte())
        val buf = docOf(data).toCommonByteBuffer()
        assertEquals(624485L, buf.getULEB128())
    }
}
