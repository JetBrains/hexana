package org.jetbrains.hexana.document.internal

/**
 * Mutable, ordered list of [Piece]s describing a piece-table-backed document.
 * Provides the byte-level primitives (`get`, `replace`, `insert`, `delete`)
 * that the spec's editing primitive lowers to.
 *
 * Coordinate space is **logical** — every offset names a position in the
 * document's current view, not in any underlying buffer. `pieces[0]`
 * contributes the first `pieces[0].length` logical bytes; `pieces[1]` the
 * next, and so on.
 *
 * The mutation operations are O(p) in the piece count: `split` walks the
 * list to find the piece containing a logical offset, and `replace` removes
 * a contiguous run of pieces. For Hexana's expected scale (≤ 50 MB binaries,
 * ≤ tens of thousands of edits) this is comfortably fast; an RB-tree-indexed
 * upgrade path is documented in the design spec §4 if profiling demands.
 */
internal class PieceList(initial: List<Piece> = emptyList()) {

    private val pieces: ArrayList<Piece> = ArrayList(initial)

    /** Total document length in bytes — sum of all piece lengths. */
    var length: Long = pieces.sumOf { it.length }
        private set

    /** Snapshots the current piece list as an immutable [List]. O(p). */
    fun snapshotPieces(): List<Piece> = pieces.toList()

    /**
     * Replaces the live piece list with [newPieces]. Used by undo/redo to
     * revert the document's live state to a previous snapshot.
     */
    fun replaceAll(newPieces: List<Piece>) {
        pieces.clear()
        pieces.addAll(newPieces)
        length = pieces.sumOf { it.length }
    }

    /**
     * Reads one byte at logical [offset], looking it up in the appropriate
     * source buffer. Linear scan over the piece list; O(p).
     *
     * @throws IndexOutOfBoundsException if [offset] is outside `[0, length)`.
     */
    fun getByte(offset: Long, original: ByteArray, added: GrowableByteBuffer): Byte {
        require(offset in 0L until length) { "offset $offset out of [0, $length)" }
        var pos = 0L
        for (p in pieces) {
            if (offset < pos + p.length) {
                val sourceOffset = (p.start + (offset - pos)).toInt()
                return when (p.source) {
                    Source.ORIGINAL -> original[sourceOffset]
                    Source.ADDED -> added.get(sourceOffset)
                }
            }
            pos += p.length
        }
        // Unreachable given the precondition; defensive only.
        throw IndexOutOfBoundsException("offset $offset (length=$length)")
    }

    /**
     * Copies [count] logical bytes starting at [offset] into [dst] at [dstOff].
     */
    fun copyInto(
        offset: Long,
        dst: ByteArray,
        dstOff: Int,
        count: Int,
        original: ByteArray,
        added: GrowableByteBuffer,
    ) {
        require(offset >= 0L && count >= 0 && offset + count <= length) {
            "copyInto out of range: offset=$offset, count=$count, length=$length"
        }
        if (count == 0) return

        var remaining = count
        var dstCursor = dstOff
        var logical = offset

        // Skip pieces wholly before `offset`.
        var pos = 0L
        var i = 0
        while (i < pieces.size && pos + pieces[i].length <= logical) {
            pos += pieces[i].length
            i++
        }
        // Then walk and copy.
        while (remaining > 0 && i < pieces.size) {
            val p = pieces[i]
            val skip = (logical - pos).toInt()
            val take = minOf(p.length.toInt() - skip, remaining)
            val src = (p.start + skip).toInt()
            when (p.source) {
                Source.ORIGINAL -> original.copyInto(dst, dstCursor, src, src + take)
                Source.ADDED -> added.copyInto(src, dst, dstCursor, take)
            }
            dstCursor += take
            remaining -= take
            logical += take
            pos += p.length
            i++
        }
    }

    /**
     * Replaces logical bytes `[start, end)` with [bytes] (bytes already
     * appended to [added]; this method receives the resulting [Piece] that
     * describes the new span — empty when nothing is being inserted).
     *
     * Splits the piece list at `start` and `end`, drops the pieces in
     * between, and (if non-null) inserts [insertion] at the cut point.
     */
    fun replace(start: Long, end: Long, insertion: Piece?) {
        require(start in 0L..length && end in start..length) {
            "replace($start, $end) out of [0, $length]"
        }
        val startIdx = splitAt(start)
        val endIdx = splitAt(end)
        // Drop the pieces fully inside [start, end).
        repeat(endIdx - startIdx) { pieces.removeAt(startIdx) }
        var removed = end - start
        if (insertion != null) {
            pieces.add(startIdx, insertion)
            length += insertion.length
        }
        length -= removed
    }

    /**
     * Splits the piece list so that the piece-list index returned has its
     * first piece starting at logical offset [offset]. If [offset] falls
     * inside an existing piece, that piece is split into two. Returns the
     * index where the split happened (insert/delete operations work
     * relative to that index).
     */
    private fun splitAt(offset: Long): Int {
        if (offset == 0L) return 0
        if (offset == length) return pieces.size

        var pos = 0L
        var i = 0
        while (i < pieces.size) {
            val p = pieces[i]
            if (offset == pos) return i
            if (offset < pos + p.length) {
                val delta = offset - pos
                pieces[i] = Piece(p.source, p.start, delta)
                pieces.add(i + 1, Piece(p.source, p.start + delta, p.length - delta))
                return i + 1
            }
            pos += p.length
            i++
        }
        // offset == length is handled at the top; if we reach here something has
        // gone wrong with the cached length vs. the piece sums.
        error("splitAt($offset): offset past document length $length")
    }
}
