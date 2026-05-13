package org.jetbrains.hexana.document.journal

/**
 * Minimal mutable byte buffer with `replace` / `insert` / `delete` — used as
 * the in-place accumulator during journal replay.
 *
 * Phase 1 deliberately keeps this naive: each op shifts bytes in a single
 * `ByteArray` and grows the array when capacity is exceeded. O(n) per op,
 * O(n × ops) total. Replay is only paid once per document open, so the slow
 * path is acceptable until the piece-table backing lands and provides the
 * editing-time data structure (and a faster replay alongside).
 *
 * Not thread-safe.
 */
internal class MutableByteSequence(initial: ByteArray) {

    private var data: ByteArray = initial.copyOf(initial.size.coerceAtLeast(MIN_CAPACITY))
    private var count: Int = initial.size

    val length: Int get() = count

    fun replace(start: Int, end: Int, bytes: ByteArray) {
        require(start in 0..count && end in start..count) {
            "replace($start, $end) out of range [0, $count]"
        }
        val removed = end - start
        val newCount = count - removed + bytes.size
        ensureCapacity(newCount)
        // Shift the tail (bytes after `end`) to its new position so the gap
        // between `start` and `start + bytes.size` is the right size for the
        // replacement payload.
        val tailLen = count - end
        if (tailLen > 0 && bytes.size != removed) {
            data.copyInto(
                destination = data,
                destinationOffset = start + bytes.size,
                startIndex = end,
                endIndex = end + tailLen,
            )
        }
        if (bytes.isNotEmpty()) {
            bytes.copyInto(data, destinationOffset = start)
        }
        count = newCount
    }

    fun insert(at: Int, bytes: ByteArray) {
        replace(at, at, bytes)
    }

    fun delete(start: Int, end: Int) {
        replace(start, end, EMPTY)
    }

    fun toByteArray(): ByteArray = data.copyOf(count)

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var newCap = data.size.coerceAtLeast(MIN_CAPACITY)
        while (newCap < required) newCap *= 2
        data = data.copyOf(newCap)
    }

    private companion object {
        const val MIN_CAPACITY = 64
        val EMPTY = ByteArray(0)
    }
}
