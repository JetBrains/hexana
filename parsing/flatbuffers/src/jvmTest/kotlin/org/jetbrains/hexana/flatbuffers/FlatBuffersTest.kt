package org.jetbrains.hexana.flatbuffers

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FlatBuffers wire-format fixture tests.
 *
 * All byte arrays are hand-built with explicit index comments so the layout is auditable.
 * Layout verified against flatbuffers.dev/internals:
 *   - Root: u32 offset (LE) to root table at buffer[0].
 *   - Table at pos: int32 soffset at pos; vtablePos = pos - soffset.
 *   - Vtable: [u16 vtableSize][u16 tableDataSize][u16 fieldOffset x N].
 *     vtableSize = 2+2+N*2 bytes (includes the two header u16s).
 *   - Field value at: pos + fieldOffset (from vtable slot; 0 = absent).
 *   - Sub-object offsets: u32 relative to their own location; target = offsetLoc + u32.
 *   - String: u32 length + bytes + null terminator.
 *   - Vector: u32 elementCount + elements inline.
 * All multi-byte values are little-endian.
 *
 * Layout rule: vtable and table must not overlap. Vtable occupies vtableSize bytes starting
 * at vtablePos. Table occupies tableDataSize bytes starting at tablePos. Since soffset =
 * tablePos - vtablePos and vtableSize >= 4, the minimum gap-free arrangement is:
 *   vtable at some pos V, table at V + vtableSize (so soffset = vtableSize).
 */
class FlatBuffersTest {

    /**
     * Table with:
     *   slot 0: int32  = 42        (present)
     *   slot 1: int16  = 7         (present)
     *   slot 2: absent             (vtable slot = 0)
     *   slot 3: string "hi"        (present)
     *
     * Layout (all indices verified):
     *   [0..3]   root offset = 4 -> tablePos = 0+4 = 4.
     *
     *   Wait: root offset is the u32 at buf[0]; tablePos = 0 + rootOffset.
     *   Let's place vtable at 4, table at 4+vtableSize.
     *   vtableSize = 2+2+4*2 = 12. So table at 4+12=16.
     *   root offset = 16 (since tablePos = 0+rootOffset = 16).
     *
     *   [0..3]   root offset = 16 (LE u32)
     *   [4..5]   vtableSize = 12 (LE u16)
     *   [6..7]   tableDataSize = 20 (large enough: soffset=4 + max(slot3 at +16)+4=20)
     *   [8..9]   slot 0 offset = 4  -> int32 at tablePos+4 = 20
     *   [10..11] slot 1 offset = 8  -> int16 at tablePos+8 = 24
     *   [12..13] slot 2 offset = 0  -> absent
     *   [14..15] slot 3 offset = 16 -> u32 sub-offset at tablePos+16 = 32
     *   --- table at tablePos=16 ---
     *   [16..19] soffset = 12 -> vtablePos = 16-12 = 4 correct
     *   [20..23] int32 = 42
     *   [24..25] int16 = 7
     *   [26..31] padding (6 bytes to reach tablePos+16=32)
     *   [32..35] u32 sub-offset at loc=32; string at 32+relOff
     *   --- string ---
     *   [38..41] string length = 2 (u32 LE)  -> relOff = 38-32 = 6
     *   [42..43] 'h','i'
     *   [44]     null terminator
     *
     *   So [32..35] = 6,0,0,0. [36..37] = gap (unused).
     */
    @Test
    fun `scalars present and absent field and string`() {
        val buf = byteArrayOf(
            /* [0..3]   */ 16, 0, 0, 0,
            /* [4..5]   */ 12, 0,                // vtableSize = 12
            /* [6..7]   */ 20, 0,                // tableDataSize = 20
            /* [8..9]   */ 4, 0,                 // slot 0 offset = 4
            /* [10..11] */ 8, 0,                 // slot 1 offset = 8
            /* [12..13] */ 0, 0,                 // slot 2 absent
            /* [14..15] */ 16, 0,                // slot 3 offset = 16
            /* [16..19] */ 12, 0, 0, 0,          // soffset=12 -> vtablePos=16-12=4
            /* [20..23] */ 42, 0, 0, 0,          // int32=42 at tablePos+4=20
            /* [24..25] */ 7, 0,                 // int16=7 at tablePos+8=24
            /* [26..31] */ 0, 0, 0, 0, 0, 0,    // padding
            /* [32..35] */ 6, 0, 0, 0,           // sub-offset=6 -> string at 32+6=38
            /* [36..37] */ 0, 0,                 // gap
            /* [38..41] */ 2, 0, 0, 0,           // string length=2
            /* [42..43] */ 'h'.code.toByte(), 'i'.code.toByte(),
            /* [44]     */ 0                     // null terminator
        ).toCommonByteBuffer()

        val root = FlatBuffers.root(buf)
        assertEquals(16, root.pos)
        assertEquals(42, root.getInt(0))
        assertEquals(7, root.getShort(1))
        assertEquals(0, root.getInt(2, default = 0))
        assertFalse(root.hasField(2))
        assertTrue(root.hasField(3))
        assertEquals("hi", root.getString(3))
    }

    /**
     * File identifier: 4 ASCII bytes at base+4.
     */
    @Test
    fun `readFileIdentifier returns 4-byte ASCII identifier`() {
        val buf = (byteArrayOf(
            20, 0, 0, 0,
            'T'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte()
        ) + ByteArray(40)).toCommonByteBuffer()
        assertEquals("TEST", FlatBuffers.readFileIdentifier(buf))
    }

    /**
     * All-zero identifier bytes -> null.
     */
    @Test
    fun `readFileIdentifier returns null for zero bytes`() {
        val buf = byteArrayOf(4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0).toCommonByteBuffer()
        assertNull(FlatBuffers.readFileIdentifier(buf))
    }

    /**
     * Vector of three int32 scalars [10, 20, 30].
     *
     *   vtable at [4..9] (6 bytes): vtableSize=6, tableDataSize=8, slot0offset=4.
     *   Table at tablePos=10 (root offset=10, soffset=6 -> vtablePos=10-6=4).
     *   [10..13] soffset=6
     *   [14..17] u32 sub-offset at loc=14 -> vector at 14+4=18
     *   [18..21] vector length=3
     *   [22..25] element 0=10
     *   [26..29] element 1=20
     *   [30..33] element 2=30
     */
    @Test
    fun `vector of int32 scalars`() {
        val buf = byteArrayOf(
            /* [0..3]   */ 10, 0, 0, 0,
            /* [4..5]   */ 6, 0,
            /* [6..7]   */ 8, 0,
            /* [8..9]   */ 4, 0,
            /* [10..13] */ 6, 0, 0, 0,
            /* [14..17] */ 4, 0, 0, 0,
            /* [18..21] */ 3, 0, 0, 0,
            /* [22..25] */ 10, 0, 0, 0,
            /* [26..29] */ 20, 0, 0, 0,
            /* [30..33] */ 30, 0, 0, 0
        ).toCommonByteBuffer()

        val root = FlatBuffers.root(buf)
        val vec = root.getVector(0)
        assertNotNull(vec)
        assertEquals(3, vec.length)
        assertEquals(10, vec.int(0))
        assertEquals(20, vec.int(1))
        assertEquals(30, vec.int(2))
    }

    /**
     * Nested table: outer.slot0=int32 99, outer.slot1=inner table, inner.slot0=int32 55.
     *
     *   outer vtable at [4..11] (8 bytes): vtableSize=8, tableDataSize=12,
     *     slot0offset=4, slot1offset=8.
     *   outer table at tablePos=12 (root offset=12, soffset=8 -> vtablePos=12-8=4):
     *   [12..15] soffset=8
     *   [16..19] int32=99 (slot 0 at tablePos+4=16)
     *   [20..23] u32 sub-offset at loc=20 -> innerPos=20+relOff
     *   inner vtable at [24..29] (6 bytes): vtableSize=6, tableDataSize=8, slot0offset=4.
     *   inner table at innerPos=30 (soffset=6 -> vtablePos=30-6=24):
     *   [30..33] soffset=6
     *   [34..37] int32=55 (slot 0 at innerPos+4=34)
     *   relOff at loc=20: innerPos-20=30-20=10.
     */
    @Test
    fun `nested table`() {
        val buf = byteArrayOf(
            /* [0..3]   */ 12, 0, 0, 0,
            /* [4..5]   */ 8, 0,
            /* [6..7]   */ 12, 0,
            /* [8..9]   */ 4, 0,
            /* [10..11] */ 8, 0,
            /* [12..15] */ 8, 0, 0, 0,
            /* [16..19] */ 99, 0, 0, 0,
            /* [20..23] */ 10, 0, 0, 0,
            /* [24..25] */ 6, 0,
            /* [26..27] */ 8, 0,
            /* [28..29] */ 4, 0,
            /* [30..33] */ 6, 0, 0, 0,
            /* [34..37] */ 55, 0, 0, 0
        ).toCommonByteBuffer()

        val root = FlatBuffers.root(buf)
        assertEquals(99, root.getInt(0))
        val inner = root.getTable(1)
        assertNotNull(inner)
        assertEquals(55, inner.getInt(0))
    }

    /**
     * Long (int64) scalar field.
     *
     *   vtable at [4..9] (6 bytes): vtableSize=6, tableDataSize=12, slot0offset=4.
     *   Table at tablePos=10:
     *   [10..13] soffset=6 -> vtablePos=10-6=4
     *   [14..21] int64=0x0123456789ABCDEF (LE: EF,CD,AB,89,67,45,23,01)
     */
    @Test
    fun `long scalar field`() {
        val value = 0x0123456789ABCDEFL
        val buf = byteArrayOf(
            /* [0..3]   */ 10, 0, 0, 0,
            /* [4..5]   */ 6, 0,
            /* [6..7]   */ 12, 0,
            /* [8..9]   */ 4, 0,
            /* [10..13] */ 6, 0, 0, 0,
            /* [14..21] */ 0xEF.toByte(), 0xCD.toByte(), 0xAB.toByte(), 0x89.toByte(),
            0x67, 0x45, 0x23, 0x01
        ).toCommonByteBuffer()

        val root = FlatBuffers.root(buf)
        assertEquals(10, root.pos)
        assertEquals(value, root.getLong(0))
    }
}