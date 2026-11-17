package org.jetbrains.hexana.codec

/**
 * Pure-Kotlin Snappy *block* decompressor.
 *
 * Implements the format described at:
 * https://github.com/google/snappy/blob/main/format_description.txt
 *
 * The block format is:
 *   preamble: uncompressed length as unsigned LEB128 varint
 *   body: sequence of literal/copy elements until input is exhausted
 *
 * Element tag byte (low 2 bits):
 *   0b00 = literal
 *   0b01 = copy-1 (1-byte offset)
 *   0b10 = copy-2 (2-byte offset)
 *   0b11 = copy-4 (4-byte offset)
 *
 * Literals (tag bits[7:2] = extra):
 *   extra <= 59 → length = extra + 1; literal data follows immediately
 *   extra == 60 → 1 additional byte encodes (length-1) LE
 *   extra == 61 → 2 additional bytes encode (length-1) LE
 *   extra == 62 → 3 additional bytes encode (length-1) LE
 *   extra == 63 → 4 additional bytes encode (length-1) LE
 *
 * Copy-1 (tag bits[4:2] = length-4, bits[7:5] = offset[10:8], next byte = offset[7:0]):
 *   length in 4..11, offset in 0..2047
 *
 * Copy-2/4 (tag bits[7:2] = length-1, next 2/4 bytes = offset LE):
 *   length in 1..64
 *
 * @throws CodecException on malformed input or size mismatch.
 */
fun snappyUncompress(input: ByteArray, expectedSize: Int): ByteArray {
    var pos = 0

    fun readByte(): Int {
        if (pos >= input.size) throw CodecException("snappy: unexpected end of input")
        return input[pos++].toInt() and 0xFF
    }

    // Decode uncompressed length as unsigned LEB128.
    var uncompressedLength = 0
    var shift = 0
    while (true) {
        val b = readByte()
        uncompressedLength = uncompressedLength or ((b and 0x7F) shl shift)
        shift += 7
        if (b and 0x80 == 0) break
        if (shift >= 32) throw CodecException("snappy: varint overflow in preamble")
    }

    if (uncompressedLength != expectedSize) {
        throw CodecException(
            "snappy: preamble length $uncompressedLength != expected $expectedSize"
        )
    }

    val out = ByteArray(uncompressedLength)
    var outPos = 0

    while (pos < input.size) {
        val tag = readByte()
        val elementType = tag and 0x03

        when (elementType) {
            0 -> {
                // Literal
                val extra = (tag ushr 2) and 0x3F
                val length = when {
                    extra <= 59 -> extra + 1
                    extra == 60 -> readByte() + 1
                    extra == 61 -> {
                        val b0 = readByte()
                        val b1 = readByte()
                        (b0 or (b1 shl 8)) + 1
                    }
                    extra == 62 -> {
                        val b0 = readByte()
                        val b1 = readByte()
                        val b2 = readByte()
                        (b0 or (b1 shl 8) or (b2 shl 16)) + 1
                    }
                    else -> { // 63
                        val b0 = readByte()
                        val b1 = readByte()
                        val b2 = readByte()
                        val b3 = readByte()
                        (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)) + 1
                    }
                }
                if (pos + length > input.size) throw CodecException("snappy: literal overruns input")
                if (outPos + length > uncompressedLength) throw CodecException("snappy: output overflow")
                input.copyInto(out, outPos, pos, pos + length)
                pos += length
                outPos += length
            }

            1 -> {
                // Copy-1: 1-byte offset
                val length = ((tag ushr 2) and 0x07) + 4
                val offsetHigh = (tag ushr 5) and 0x07
                val offsetLow = readByte()
                val offset = (offsetHigh shl 8) or offsetLow
                if (offset == 0) throw CodecException("snappy: copy with zero offset")
                if (offset > outPos) throw CodecException("snappy: copy offset beyond output position")
                if (outPos + length > uncompressedLength) throw CodecException("snappy: output overflow")
                copyBytes(out, outPos, outPos - offset, length)
                outPos += length
            }

            2 -> {
                // Copy-2: 2-byte offset
                val length = ((tag ushr 2) and 0x3F) + 1
                val b0 = readByte()
                val b1 = readByte()
                val offset = b0 or (b1 shl 8)
                if (offset == 0) throw CodecException("snappy: copy with zero offset")
                if (offset > outPos) throw CodecException("snappy: copy offset beyond output position")
                if (outPos + length > uncompressedLength) throw CodecException("snappy: output overflow")
                copyBytes(out, outPos, outPos - offset, length)
                outPos += length
            }

            else -> {
                // Copy-4: 4-byte offset
                val length = ((tag ushr 2) and 0x3F) + 1
                val b0 = readByte()
                val b1 = readByte()
                val b2 = readByte()
                val b3 = readByte()
                val offset = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                if (offset == 0) throw CodecException("snappy: copy with zero offset")
                if (offset > outPos) throw CodecException("snappy: copy offset beyond output position")
                if (outPos + length > uncompressedLength) throw CodecException("snappy: output overflow")
                copyBytes(out, outPos, outPos - offset, length)
                outPos += length
            }
        }
    }

    if (outPos != uncompressedLength) {
        throw CodecException("snappy: decompressed $outPos bytes but preamble declared $uncompressedLength")
    }

    return out
}

/**
 * Copy [length] bytes within [buf] from [src] to [dst], byte-by-byte to
 * support overlapping runs (run-length encoding where length > offset).
 */
private fun copyBytes(buf: ByteArray, dst: Int, src: Int, length: Int) {
    for (i in 0 until length) {
        buf[dst + i] = buf[src + i]
    }
}
