package org.jetbrains.hexana.document.internal

/**
 * Append-only growable byte buffer for the `added` storage of a piece-table-
 * backed [BinaryDocument][org.jetbrains.hexana.document.BinaryDocument].
 *
 * Indexes are stable: callers (pieces in snapshots) hold absolute offsets
 * into this buffer, and [append] never moves prior bytes — it only adds new
 * ones at the end. The underlying [data] array may be reallocated on growth,
 * but no [Piece] holds a reference to that array directly; they reference
 * the buffer object plus an index range, which is grow-safe.
 *
 * Not thread-safe for writes; reads through [get] / [copyInto] are safe so
 * long as the writer thread is quiesced.
 */
internal class GrowableByteBuffer(initialCapacity: Int = MIN_CAPACITY) {

    private var data: ByteArray = ByteArray(initialCapacity.coerceAtLeast(MIN_CAPACITY))
    private var count: Int = 0

    /** Number of bytes currently stored. Indexes `[0, length)` are readable. */
    val length: Int get() = count

    /**
     * Appends [bytes] to the end of the buffer.
     * @return the offset at which the appended span starts (i.e. the value of
     *   [length] *before* the append).
     */
    fun append(bytes: ByteArray): Int {
        val start = count
        ensureCapacity(count + bytes.size)
        bytes.copyInto(destination = data, destinationOffset = count)
        count += bytes.size
        return start
    }

    /** Reads one byte at the given absolute offset. */
    fun get(index: Int): Byte {
        require(index in 0 until count) { "index $index out of [0, $count)" }
        return data[index]
    }

    /** Copies [length] bytes starting at [srcOffset] into [dst] at [dstOffset]. */
    fun copyInto(srcOffset: Int, dst: ByteArray, dstOffset: Int, length: Int) {
        require(srcOffset >= 0 && length >= 0 && srcOffset + length <= count) {
            "copyInto out of range: srcOffset=$srcOffset, length=$length, available=$count"
        }
        data.copyInto(destination = dst, destinationOffset = dstOffset, startIndex = srcOffset, endIndex = srcOffset + length)
    }

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var newCap = data.size.coerceAtLeast(MIN_CAPACITY)
        while (newCap < required) newCap *= 2
        data = data.copyOf(newCap)
    }

    private companion object {
        const val MIN_CAPACITY: Int = 64
    }
}
