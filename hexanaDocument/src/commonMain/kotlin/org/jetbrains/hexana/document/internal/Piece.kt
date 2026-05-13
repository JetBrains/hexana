package org.jetbrains.hexana.document.internal

/**
 * One contiguous span of bytes in a piece-table-backed document. Pieces are
 * immutable; mutation of the document is expressed as a new piece list that
 * references the same underlying buffers in different proportions.
 *
 * @property source which buffer the bytes live in — either the immutable
 *   original-file array or the append-only `added` buffer.
 * @property start  offset into the source buffer.
 * @property length number of bytes this piece contributes to the document.
 */
internal data class Piece(
    val source: Source,
    val start: Long,
    val length: Long,
) {
    init {
        require(start >= 0L) { "start must be non-negative: $start" }
        require(length > 0L) { "length must be positive: $length" }
    }
}

/**
 * Which buffer a [Piece] reads from.
 *
 * - [ORIGINAL] is the immutable byte array taken from the file on disk; never
 *   mutated for the lifetime of the document.
 * - [ADDED] is the append-only buffer ([GrowableByteBuffer]) that grows every
 *   time the user inserts or replaces with new bytes. Indexes into ADDED are
 *   stable: nothing is ever rewritten past the append point, so frozen piece
 *   lists held by snapshots remain valid.
 */
internal enum class Source { ORIGINAL, ADDED }
