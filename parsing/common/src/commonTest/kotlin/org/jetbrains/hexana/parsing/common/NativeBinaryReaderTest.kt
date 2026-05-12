package org.jetbrains.hexana.parsing.common

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeBinaryReaderTest {
    @Test
    fun `reads endian aware unsigned values without moving the source cursor`() {
        val buffer = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05).toCommonByteBuffer()
        buffer.position(3)

        val little = NativeBinaryReader(buffer, NativeBinaryEndian.Little)
        val big = NativeBinaryReader(buffer, NativeBinaryEndian.Big)

        assertEquals(0x0201, little.u16OrNull(0))
        assertEquals(0x0102, big.u16OrNull(0))
        assertEquals(0x04030201u, little.u32OrNull(0))
        assertEquals(0x01020304u, big.u32OrNull(0))
        assertEquals(3, buffer.position())
    }

    @Test
    fun `returns null for reads outside input`() {
        val reader = NativeBinaryReader(byteArrayOf(0x01, 0x02).toCommonByteBuffer())

        assertNull(reader.byteOrNull(-1))
        assertNull(reader.byteOrNull(2))
        assertNull(reader.u16OrNull(1))
        assertNull(reader.u32OrNull(0))
    }

    @Test
    fun `checks prefixes and extracts ranges`() {
        val reader = NativeBinaryReader(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0x00).toCommonByteBuffer())

        assertTrue(reader.hasPrefix(0, 0x7F, 0x45, 0x4C, 0x46))
        assertFalse(reader.hasPrefix(1, 0x7F, 0x45))
        assertContentEquals(byteArrayOf(0x45, 0x4C), reader.bytesOrNull(NativeByteRange(1, 2)))
        assertNull(reader.bytesOrNull(NativeByteRange(4, 2)))
    }

    @Test
    fun `reads null terminated strings inside bounds`() {
        val reader = NativeBinaryReader(byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 0, 'c'.code.toByte()).toCommonByteBuffer())

        assertEquals("ab", reader.nullTerminatedStringOrNull(0))
        assertNull(reader.nullTerminatedStringOrNull(3))
        assertNull(reader.nullTerminatedStringOrNull(0, endExclusive = 2))
    }

    @Test
    fun `converts checked ulong ranges`() {
        val reader = NativeBinaryReader(ByteArray(8).toCommonByteBuffer())

        assertEquals(NativeByteRange(2, 4), reader.rangeOrNull(2uL, 4uL))
        assertNull(reader.rangeOrNull(6uL, 4uL))
        assertNull(reader.rangeOrNull(UInt.MAX_VALUE.toULong() + 1uL, 1uL))
        assertNull(reader.rangeOrNull(Int.MAX_VALUE.toULong(), 1uL))
        assertFailsWith<IllegalArgumentException> {
            NativeByteRange(Int.MAX_VALUE, 1)
        }
    }
}
