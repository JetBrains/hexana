package org.jetbrains.hexana.parquet

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * LSB-first bit reader over a [CommonByteBuffer] slice.
 *
 * Parquet uses LSB-first bit packing throughout (PLAIN BOOLEAN, RLE/bit-packed
 * hybrid bit widths, dictionary indices). Each byte is consumed low-bit-first.
 *
 * Thread-safety: not thread-safe; each reader owns its buffer cursor.
 */
class BitReader(private val buf: CommonByteBuffer) {
    private var currentByte: Int = 0
    private var bitsLeft: Int = 0

    /**
     * Read [width] bits (0..32) from the stream, LSB first.
     * Returns the bits in the low [width] positions of the result.
     */
    fun readBitsLE(width: Int): Int {
        require(width in 0..32) { "readBitsLE: width must be 0..32, got $width" }
        if (width == 0) return 0
        var result = 0
        var bitsNeeded = width
        var shift = 0
        while (bitsNeeded > 0) {
            if (bitsLeft == 0) {
                currentByte = buf.get().toInt() and 0xFF
                bitsLeft = 8
            }
            val take = minOf(bitsNeeded, bitsLeft)
            result = result or ((currentByte and ((1 shl take) - 1)) shl shift)
            currentByte = currentByte ushr take
            bitsLeft -= take
            shift += take
            bitsNeeded -= take
        }
        return result
    }

    /** Discard any partially-read byte; align the cursor to the next byte boundary. */
    fun byteAlign() {
        bitsLeft = 0
        currentByte = 0
    }

    /** Current byte position of the underlying buffer (not accounting for buffered bits). */
    fun position(): Int = buf.position()
}

// ---------------------------------------------------------------------------
// RLE / bit-packed HYBRID (Parquet §Encoding.RLE)
// ---------------------------------------------------------------------------

/**
 * Minimum number of bits needed to represent values in [0, maxValue].
 * Used to derive the bit width for definition/repetition levels.
 */
internal fun bitWidthNeeded(maxValue: Int): Int {
    if (maxValue <= 0) return 0
    var bits = 1
    while ((1 shl bits) <= maxValue) bits++
    return bits
}

/**
 * Decode up to [count] values from a v1 Parquet data-page RLE/bit-packed hybrid stream.
 *
 * Layout (v1): 4-byte LE length prefix, then a sequence of runs. Each run header
 * is a ULEB128 whose low bit selects the run type:
 *   - low bit 0 → RLE run: header >> 1 = repeat count, then `ceil(bitWidth/8)` LE bytes for value.
 *   - low bit 1 → bit-packed run: (header >> 1) * 8 = value count, packed LSB-first.
 *
 * Used for definition levels, repetition levels, BOOLEAN columns, and dictionary indices.
 *
 * @param buf        Buffer positioned at the start of the length-prefixed RLE stream.
 * @param bitWidth   Bits per value (0..32). If 0, every value is 0 and no bytes are consumed.
 * @param count      Maximum number of values to decode.
 * @return           IntArray of length ≤ [count].
 */
internal fun readRleBitPackedHybrid(buf: CommonByteBuffer, bitWidth: Int, count: Int): IntArray {
    if (bitWidth == 0) return IntArray(count)  // no bits means all-zero
    // v1: 4-byte LE length prefix
    val streamLen = buf.getIntLE()
    val streamEnd = buf.position() + streamLen
    return readRleHybridBody(buf, streamEnd, bitWidth, count)
}

/**
 * Decode the body of an RLE/bit-packed stream that has NO length prefix.
 *
 * Used for dictionary-index data after the bit-width byte in a PLAIN_DICTIONARY / RLE_DICTIONARY
 * data page: the rest of the page body is the hybrid stream with no length prefix.
 *
 * @param buf        Buffer positioned at the first run header.
 * @param streamEnd  Absolute buffer position of the byte past the stream.
 * @param bitWidth   Bits per value.
 * @param count      Maximum number of values to decode.
 */
internal fun readRleHybridBody(buf: CommonByteBuffer, streamEnd: Int, bitWidth: Int, count: Int): IntArray {
    val out = IntArray(count)
    var filled = 0
    while (filled < count && buf.position() < streamEnd) {
        val header = readULEB128(buf).toInt()
        if (header and 1 == 1) {
            // Bit-packed run: (header >> 1) * 8 values
            val groupCount = (header ushr 1) * 8
            val take = minOf(groupCount, count - filled)
            decodeBitPackedGroup(buf, bitWidth, take, out, filled)
            // Advance past any remaining bytes in the group not consumed by decodeBitPackedGroup.
            // Total bytes for the group = ceil(groupCount * bitWidth / 8).
            // Bytes consumed for `take` values = ceil(take * bitWidth / 8).
            // (For bitWidth=1: a full 8-value group is 1 byte; if take < 8, those bits were in the
            //  same byte already consumed, so bytesSkip = 0.)
            val totalGroupBytes = (groupCount * bitWidth + 7) / 8
            val consumedBytes = (take * bitWidth + 7) / 8
            val bytesSkip = totalGroupBytes - consumedBytes
            if (bytesSkip > 0) repeat(bytesSkip) { buf.get() }
            filled += take
        } else {
            // RLE run: (header >> 1) repetitions of a fixed value
            val runCount = header ushr 1
            val nBytes = (bitWidth + 7) / 8
            var value = 0
            for (i in 0 until nBytes) value = value or ((buf.get().toInt() and 0xFF) shl (i * 8))
            val take = minOf(runCount, count - filled)
            out.fill(value, filled, filled + take)
            filled += take
        }
    }
    return if (filled == count) out else out.copyOf(filled)
}

/** Read a single unsigned LEB128 (ULEB128) from [buf]. */
private fun readULEB128(buf: CommonByteBuffer): Long {
    var result = 0L
    var shift = 0
    while (true) {
        val b = buf.get().toInt() and 0xFF
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) break
        shift += 7
    }
    return result
}

/**
 * Decode [count] bit-packed values (LSB-first, [bitWidth] bits each) from [buf]
 * into [out] starting at [outOffset].
 *
 * Consumes exactly `ceil(count * bitWidth / 8)` bytes, rounded up to the nearest
 * full group byte (group = 8 values). The Parquet spec writes full 8-value groups
 * even if the last group is partial; excess values in the last group are discarded.
 */
private fun decodeBitPackedGroup(
    buf: CommonByteBuffer, bitWidth: Int, count: Int, out: IntArray, outOffset: Int,
) {
    var current = 0
    var bitsLeft = 0
    for (i in 0 until count) {
        var bitsNeeded = bitWidth
        var value = 0
        var valueShift = 0
        while (bitsNeeded > 0) {
            if (bitsLeft == 0) {
                current = buf.get().toInt() and 0xFF
                bitsLeft = 8
            }
            val take = minOf(bitsNeeded, bitsLeft)
            value = value or ((current and ((1 shl take) - 1)) shl valueShift)
            current = current ushr take
            bitsLeft -= take
            valueShift += take
            bitsNeeded -= take
        }
        out[outOffset + i] = value
    }
    // Consume the remaining bits of the last partial byte
    // so buf.position() is aligned to a byte boundary.
    // (Parquet bit-packing always pads to the next full byte within a group.)
    // No action needed here — bitsLeft > 0 means bits were buffered but no byte
    // was partially consumed from the buffer. The buf cursor IS already past the last byte.
}
