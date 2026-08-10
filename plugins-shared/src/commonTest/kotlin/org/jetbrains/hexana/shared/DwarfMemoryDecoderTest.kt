package org.jetbrains.hexana.shared

import org.jetbrains.hexana.dwarf.DwarfTypeInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DwarfMemoryDecoder] — pure decode logic with handcrafted byte arrays.
 *
 * No I/O, no fixture files. Covers: signed/unsigned integers, floats,
 * booleans, pointers, structs, arrays, enums, and LE byte order.
 */
class DwarfMemoryDecoderTest {

    // ---- helpers ----

    private fun i32LE(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        (v ushr 8 and 0xff).toByte(),
        (v ushr 16 and 0xff).toByte(),
        (v ushr 24 and 0xff).toByte(),
    )

    private fun i16LE(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        (v ushr 8 and 0xff).toByte(),
    )

    private fun i64LE(v: Long): ByteArray = byteArrayOf(
        (v and 0xff).toByte(),
        (v ushr 8 and 0xff).toByte(),
        (v ushr 16 and 0xff).toByte(),
        (v ushr 24 and 0xff).toByte(),
        (v ushr 32 and 0xff).toByte(),
        (v ushr 40 and 0xff).toByte(),
        (v ushr 48 and 0xff).toByte(),
        (v ushr 56 and 0xff).toByte(),
    )

    private fun baseType(name: String, byteSize: Int, encoding: Int) =
        DwarfTypeInfo.Base(name, byteSize, encoding)

    // DWARF ATE constants
    private val ATE_BOOLEAN = 0x02
    private val ATE_FLOAT = 0x04
    private val ATE_SIGNED = 0x05
    private val ATE_SIGNED_CHAR = 0x06
    private val ATE_UNSIGNED = 0x07

    // ---- signed integer tests ----

    @Test
    fun `signed i32 positive LE decode`() {
        val bytes = i32LE(0x01020304)
        val result = DwarfMemoryDecoder.decode(bytes, 0, baseType("i32", 4, ATE_SIGNED))
        assertNotNull(result)
        assertEquals("16909060", result.summary)  // 0x01020304
    }

    @Test
    fun `signed i32 negative LE decode`() {
        val bytes = i32LE(-1)
        val result = DwarfMemoryDecoder.decode(bytes, 0, baseType("i32", 4, ATE_SIGNED))
        assertNotNull(result)
        assertEquals("-1", result.summary)
    }

    @Test
    fun `signed i16 LE decode`() {
        val bytes = i16LE(-500)
        val result = DwarfMemoryDecoder.decode(bytes, 0, baseType("i16", 2, ATE_SIGNED))
        assertNotNull(result)
        assertEquals("-500", result.summary)
    }

    @Test
    fun `signed i8 decode`() {
        val bytes = byteArrayOf(-42)
        val result = DwarfMemoryDecoder.decode(bytes, 0, baseType("i8", 1, ATE_SIGNED_CHAR))
        assertNotNull(result)
        assertEquals("-42", result.summary)
    }

    @Test
    fun `signed i64 LE decode`() {
        val bytes = i64LE(Long.MIN_VALUE)
        val result = DwarfMemoryDecoder.decode(bytes, 0, baseType("i64", 8, ATE_SIGNED))
        assertNotNull(result)
        assertEquals(Long.MIN_VALUE.toString(), result.summary)
    }

    // ---- unsigned integer tests ----

    @Test
    fun `unsigned u32 LE decode`() {
        // 0xDEADBEEF as unsigned = 3735928559
        val bytes = i32LE(-559038737)  // 0xDEADBEEF as Int
        val result = DwarfMemoryDecoder.decode(bytes, 0, baseType("u32", 4, ATE_UNSIGNED))
        assertNotNull(result)
        assertEquals("3735928559", result.summary)
    }

    @Test
    fun `unsigned u8 decode`() {
        val bytes = byteArrayOf(255.toByte())
        val result = DwarfMemoryDecoder.decode(bytes, 0, baseType("u8", 1, ATE_UNSIGNED))
        assertNotNull(result)
        assertEquals("255", result.summary)
    }

    // ---- boolean tests ----

    @Test
    fun `boolean true decode`() {
        val result = DwarfMemoryDecoder.decode(byteArrayOf(1), 0, baseType("bool", 1, ATE_BOOLEAN))
        assertNotNull(result)
        assertEquals("true", result.summary)
    }

    @Test
    fun `boolean false decode`() {
        val result = DwarfMemoryDecoder.decode(byteArrayOf(0), 0, baseType("bool", 1, ATE_BOOLEAN))
        assertNotNull(result)
        assertEquals("false", result.summary)
    }

    // ---- float tests ----

    @Test
    fun `float f32 decode`() {
        val f = 3.14f
        val bytes = i32LE(f.toBits())
        val result = DwarfMemoryDecoder.decode(bytes, 0, baseType("f32", 4, ATE_FLOAT))
        assertNotNull(result)
        // Parse back and compare numerically; toString representation differs by platform.
        val parsed = result.summary.toFloat()
        assertTrue(kotlin.math.abs(parsed - f) < 0.001f, "Expected ~$f, got ${result.summary}")
    }

    @Test
    fun `float f64 decode`() {
        val d = 2.718281828
        val bytes = i64LE(d.toBits())
        val result = DwarfMemoryDecoder.decode(bytes, 0, baseType("f64", 8, ATE_FLOAT))
        assertNotNull(result)
        val parsed = result.summary.toDouble()
        assertTrue(kotlin.math.abs(parsed - d) < 1e-9, "Expected ~$d, got ${result.summary}")
    }

    // ---- pointer test ----

    @Test
    fun `pointer shows hex address`() {
        val bytes = i32LE(0x1000)
        val result = DwarfMemoryDecoder.decode(bytes, 0, DwarfTypeInfo.Pointer(null))
        assertNotNull(result)
        assertEquals("0x1000", result.summary)
    }

    // ---- struct tests ----

    @Test
    fun `struct Point decode with x i32 y i16 z i8`() {
        // Point { x: i32 @0, y: i16 @4, z: i8 @6 }, total 8 bytes
        val memberX = DwarfTypeInfo.Struct.Member("x", 0, DwarfTypeInfo.Base("i32", 4, ATE_SIGNED))
        val memberY = DwarfTypeInfo.Struct.Member("y", 4, DwarfTypeInfo.Base("i16", 2, ATE_SIGNED))
        val memberZ = DwarfTypeInfo.Struct.Member("z", 6, DwarfTypeInfo.Base("i8", 1, ATE_SIGNED_CHAR))
        val pointType = DwarfTypeInfo.Struct("Point", 8, listOf(memberX, memberY, memberZ))

        // x = 42 (i32 LE), y = -7 (i16 LE), z = 3 (i8), 1 padding byte
        val bytes = byteArrayOf(
            42, 0, 0, 0,   // x = 42
            0xf9.toByte(), 0xff.toByte(),  // y = -7 (0xFFF9)
            3,              // z = 3
            0,              // padding
        )
        val result = DwarfMemoryDecoder.decode(bytes, 0, pointType)
        assertNotNull(result)
        assertEquals("Point {...}", result.summary)
        assertEquals(3, result.children.size)

        assertEquals("x", result.children[0].name)
        assertEquals("42", result.children[0].value.summary)

        assertEquals("y", result.children[1].name)
        assertEquals("-7", result.children[1].value.summary)

        assertEquals("z", result.children[2].name)
        assertEquals("3", result.children[2].value.summary)
    }

    @Test
    fun `struct too short returns null`() {
        val member = DwarfTypeInfo.Struct.Member("x", 0, DwarfTypeInfo.Base("i32", 4, ATE_SIGNED))
        val pointType = DwarfTypeInfo.Struct("Point", 8, listOf(member))
        val bytes = byteArrayOf(1, 2, 3) // only 3 bytes, struct needs 8
        val result = DwarfMemoryDecoder.decode(bytes, 0, pointType)
        assertNull(result)
    }

    // ---- array tests ----

    @Test
    fun `array i32 4 elements decode`() {
        val elemType = DwarfTypeInfo.Base("i32", 4, ATE_SIGNED)
        val arrayType = DwarfTypeInfo.Array(elemType, 4)
        val bytes = byteArrayOf(
            1, 0, 0, 0,
            2, 0, 0, 0,
            3, 0, 0, 0,
            4, 0, 0, 0,
        )
        val result = DwarfMemoryDecoder.decode(bytes, 0, arrayType)
        assertNotNull(result)
        assertEquals("[4 elements]", result.summary)
        assertEquals(4, result.children.size)
        assertEquals("[0]", result.children[0].name)
        assertEquals("1", result.children[0].value.summary)
        assertEquals("[3]", result.children[3].name)
        assertEquals("4", result.children[3].value.summary)
    }

    @Test
    fun `array truncates at MAX_ARRAY_CHILDREN`() {
        val elemType = DwarfTypeInfo.Base("i8", 1, ATE_SIGNED)
        val count = DwarfMemoryDecoder.MAX_ARRAY_CHILDREN + 5
        val arrayType = DwarfTypeInfo.Array(elemType, count)
        val bytes = ByteArray(count) { it.toByte() }
        val result = DwarfMemoryDecoder.decode(bytes, 0, arrayType)
        assertNotNull(result)
        assertTrue(result.truncated, "Expected truncated flag for oversized array")
        assertEquals(DwarfMemoryDecoder.MAX_ARRAY_CHILDREN, result.children.size)
        assertTrue(result.summary.contains("$count elements"))
    }

    // ---- enum tests ----

    @Test
    fun `enum matches enumerator name`() {
        val enumType = DwarfTypeInfo.Enumeration(
            "Color",
            4,
            listOf(
                DwarfTypeInfo.Enumeration.Enumerator("Red", 0),
                DwarfTypeInfo.Enumeration.Enumerator("Green", 1),
                DwarfTypeInfo.Enumeration.Enumerator("Blue", 2),
            ),
        )
        val bytes = i32LE(1)
        val result = DwarfMemoryDecoder.decode(bytes, 0, enumType)
        assertNotNull(result)
        assertEquals("Green (1)", result.summary)
    }

    @Test
    fun `enum unknown value shows raw number`() {
        val enumType = DwarfTypeInfo.Enumeration(
            "Color",
            4,
            listOf(DwarfTypeInfo.Enumeration.Enumerator("Red", 0)),
        )
        val bytes = i32LE(99)
        val result = DwarfMemoryDecoder.decode(bytes, 0, enumType)
        assertNotNull(result)
        assertEquals("99 (unknown enumerator)", result.summary)
    }

    // ---- offset tests ----

    @Test
    fun `decode respects offset within bytes`() {
        // 4 padding bytes, then i32 = 7
        val bytes = byteArrayOf(0, 0, 0, 0, 7, 0, 0, 0)
        val result = DwarfMemoryDecoder.decode(bytes, 4, baseType("i32", 4, ATE_SIGNED))
        assertNotNull(result)
        assertEquals("7", result.summary)
    }

    @Test
    fun `decode returns null when slab too short for offset`() {
        val bytes = byteArrayOf(1, 2, 3)
        val result = DwarfMemoryDecoder.decode(bytes, 2, baseType("i32", 4, ATE_SIGNED))
        assertNull(result, "Expected null when slab is too short at offset")
    }

    // ---- typeByteSize tests ----

    @Test
    fun `typeByteSize returns correct sizes`() {
        assertEquals(4, DwarfMemoryDecoder.typeByteSize(DwarfTypeInfo.Base("i32", 4, ATE_SIGNED)))
        assertEquals(4, DwarfMemoryDecoder.typeByteSize(DwarfTypeInfo.Pointer(null)))
        assertEquals(8, DwarfMemoryDecoder.typeByteSize(DwarfTypeInfo.Array(DwarfTypeInfo.Base("i32", 4, ATE_SIGNED), 2)))
        assertNull(DwarfMemoryDecoder.typeByteSize(DwarfTypeInfo.Unknown(org.jetbrains.hexana.dwarf.Tag.DW_TAG_unspecified_type)))
    }
}
