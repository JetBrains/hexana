package org.jetbrains.hexana.parsing.common

import org.jetbrains.hexana.io.CommonByteBuffer

class NativeBinaryReader(
    private val bytes: CommonByteBuffer,
    val endian: NativeBinaryEndian = NativeBinaryEndian.Little,
) {
    val size: Int
        get() = bytes.size

    fun byteOrNull(offset: Int): Int? =
        if (offset in 0 until size) bytes[offset].toInt() and 0xFF else null

    fun i32OrNull(offset: Int, endian: NativeBinaryEndian = this.endian): Int? =
        u32OrNull(offset, endian)?.toInt()

    fun u16OrNull(offset: Int, endian: NativeBinaryEndian = this.endian): Int? {
        val b0 = byteOrNull(offset) ?: return null
        val b1 = byteOrNull(offset + 1) ?: return null
        return when (endian) {
            NativeBinaryEndian.Little -> b0 or (b1 shl 8)
            NativeBinaryEndian.Big -> (b0 shl 8) or b1
            NativeBinaryEndian.Unknown -> null
        }
    }

    fun u32OrNull(offset: Int, endian: NativeBinaryEndian = this.endian): UInt? {
        val b0 = byteOrNull(offset) ?: return null
        val b1 = byteOrNull(offset + 1) ?: return null
        val b2 = byteOrNull(offset + 2) ?: return null
        val b3 = byteOrNull(offset + 3) ?: return null
        return when (endian) {
            NativeBinaryEndian.Little -> (
                b0.toUInt() or
                    (b1.toUInt() shl 8) or
                    (b2.toUInt() shl 16) or
                    (b3.toUInt() shl 24)
                )
            NativeBinaryEndian.Big -> (
                (b0.toUInt() shl 24) or
                    (b1.toUInt() shl 16) or
                    (b2.toUInt() shl 8) or
                    b3.toUInt()
                )
            NativeBinaryEndian.Unknown -> null
        }
    }

    fun u64OrNull(offset: Int, endian: NativeBinaryEndian = this.endian): ULong? {
        val low = u32OrNull(offset, endian) ?: return null
        val high = u32OrNull(offset + 4, endian) ?: return null
        return when (endian) {
            NativeBinaryEndian.Little -> low.toULong() or (high.toULong() shl 32)
            NativeBinaryEndian.Big -> (low.toULong() shl 32) or high.toULong()
            NativeBinaryEndian.Unknown -> null
        }
    }

    fun hasPrefix(offset: Int, vararg expected: Int): Boolean {
        if (offset < 0 || offset + expected.size > size) return false
        for (index in expected.indices) {
            if (byteOrNull(offset + index) != expected[index]) return false
        }
        return true
    }

    fun bytesOrNull(range: NativeByteRange): ByteArray? {
        if (!contains(range)) return null
        return ByteArray(range.size) { index -> bytes[range.offset + index] }
    }

    fun nullTerminatedStringOrNull(offset: Int, endExclusive: Int = size): String? {
        if (offset !in 0..size || endExclusive !in 0..size || offset > endExclusive) return null
        var cursor = offset
        while (cursor < endExclusive && byteOrNull(cursor) != 0) {
            cursor++
        }
        if (cursor >= endExclusive) return null
        return bytesOrNull(NativeByteRange(offset, cursor - offset))?.decodeToString()
    }

    fun rangeOrNull(offset: ULong, size: ULong): NativeByteRange? {
        if (offset > Int.MAX_VALUE.toULong()) return null
        if (size > Int.MAX_VALUE.toULong()) return null
        if (size > Int.MAX_VALUE.toULong() - offset) return null
        val intOffset = offset.toInt()
        val intSize = size.toInt()
        if (intOffset > this.size) return null
        if (intSize > this.size - intOffset) return null
        return NativeByteRange(intOffset, intSize)
    }

    fun contains(range: NativeByteRange): Boolean =
        range.offset <= size && range.size <= size - range.offset
}
