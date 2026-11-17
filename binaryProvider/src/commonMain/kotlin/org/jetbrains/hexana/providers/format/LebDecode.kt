package org.jetbrains.hexana.providers.format

import org.jetbrains.hexana.document.ByteSource

/**
 * Result of decoding a single LEB128-encoded integer from a [ByteSource].
 *
 * @property value the decoded integer value (signed for SLEB128, unsigned-as-Long for ULEB128).
 * @property byteCount number of bytes consumed.
 * @property rawBytes the encoded byte sequence (length == [byteCount]). Used for
 *   dual-representation annotations in the WAT renderer.
 */
internal data class LebDecode(
    val value: Long,
    val byteCount: Int,
    val rawBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LebDecode) return false
        return value == other.value &&
            byteCount == other.byteCount &&
            rawBytes.contentEquals(other.rawBytes)
    }

    override fun hashCode(): Int = (31 * (31 * value.hashCode() + byteCount) + rawBytes.contentHashCode())
}

/**
 * Decodes an unsigned LEB128 starting at [at] in [source]. Stops when the
 * continuation bit is clear or [maxBytes] is exhausted (LEB128 is bounded
 * to 10 bytes for 64-bit values).
 */
internal fun decodeULEB128(source: ByteSource, at: Long, maxBytes: Int = 10): LebDecode {
    var pos = at
    var result = 0L
    var shift = 0
    val bytes = ArrayList<Byte>(maxBytes.coerceAtMost(10))

    while (bytes.size < maxBytes) {
        val b = source.get(pos)
        bytes += b
        pos++
        val byteVal = b.toInt() and 0xFF
        result = result or ((byteVal and 0x7F).toLong() shl shift)
        if (byteVal and 0x80 == 0) break
        shift += 7
    }

    return LebDecode(value = result, byteCount = bytes.size, rawBytes = bytes.toByteArray())
}

/**
 * Decodes a signed LEB128 starting at [at] in [source]. Sign-extends the
 * final byte's bit 6 across the unused high bits.
 */
internal fun decodeSLEB128(source: ByteSource, at: Long, maxBytes: Int = 10): LebDecode {
    var pos = at
    var result = 0L
    var shift = 0
    val bytes = ArrayList<Byte>(maxBytes.coerceAtMost(10))
    var lastByte = 0

    while (bytes.size < maxBytes) {
        val b = source.get(pos)
        bytes += b
        pos++
        val byteVal = b.toInt() and 0xFF
        lastByte = byteVal
        result = result or ((byteVal and 0x7F).toLong() shl shift)
        if (byteVal and 0x80 == 0) {
            shift += 7
            // Sign extend if the sign bit (bit 6 of the final byte) is set.
            if (shift < 64 && (lastByte and 0x40) != 0) {
                result = result or ((-1L) shl shift)
            }
            break
        }
        shift += 7
    }

    return LebDecode(value = result, byteCount = bytes.size, rawBytes = bytes.toByteArray())
}

/** Formats a byte array as space-separated uppercase hex pairs, e.g. `"E5 8E 02"`. */
internal fun ByteArray.toHexBytes(): String =
    joinToString(" ") { (it.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0') }
