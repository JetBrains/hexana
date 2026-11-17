package org.jetbrains.hexana.document

/**
 * Transactional edit over a [BinaryDocument]. Edits are batched and become
 * visible only after [commit]; [close] without commit rolls back.
 *
 * **Phase 1**: The interface is declared so call sites compile against the
 * eventual signature, but [BinaryDocument.edit] throws
 * [UnsupportedOperationException] — editing arrives in Phase 2 along with the
 * piece-table backing.
 *
 * Use:
 * ```
 * doc.edit().use { tx ->
 *     tx.replace(0x10, 0x14, byteArrayOf(0, 0, 0, 0))
 *     tx.commit()
 * }
 * ```
 */
public interface EditTransaction : AutoCloseable {
    /**
     * Replaces the bytes in `[start, endExclusive)` with [bytes].
     * Length of the replacement does not need to match the replaced range.
     */
    public fun replace(start: Long, endExclusive: Long, bytes: ByteArray)

    /** Inserts [bytes] at [offset]. Equivalent to `replace(offset, offset, bytes)`. */
    public fun insert(offset: Long, bytes: ByteArray)

    /** Deletes the range `[start, endExclusive)`. Equivalent to `replace(start, endExclusive, EMPTY)`. */
    public fun delete(start: Long, endExclusive: Long)

    /**
     * Applies this transaction's edits atomically and notifies registered
     * [DocumentListener]s. Returns the post-commit snapshot.
     *
     * Calling [commit] more than once on the same transaction is an error.
     */
    public fun commit(): BinaryDocumentSnapshot

    /**
     * Rolls back uncommitted edits if [commit] has not been called.
     * After [close], the transaction is no longer usable.
     */
    override fun close()
}

/**
 * One indivisible byte-level edit recorded by an [EditTransaction]. Surfaced
 * post-commit through [DocumentChangeEvent] so listeners (re-parsers, chunk
 * indexers, search indices) can update incrementally instead of recomputing
 * from scratch.
 *
 * Offsets refer to the **pre-commit** logical document. [newLength] describes
 * the post-commit state at the same starting offset.
 */
public sealed interface EditOp {
    /** Pre-commit offset where the edit begins. */
    public val start: Long

    /** Pre-commit offset where the edit ends (exclusive). */
    public val endExclusive: Long

    /** Post-commit length of the edited region. Zero for [Delete], equals the inserted size for [Insert]. */
    public val newLength: Long

    public data class Replace(
        override val start: Long,
        override val endExclusive: Long,
        override val newLength: Long,
    ) : EditOp

    public data class Insert(
        override val start: Long,
        override val newLength: Long,
    ) : EditOp {
        override val endExclusive: Long get() = start
    }

    public data class Delete(
        override val start: Long,
        override val endExclusive: Long,
    ) : EditOp {
        override val newLength: Long get() = 0L
    }
}
