package org.jetbrains.hexana.document

/**
 * The IDE-agnostic byte-oriented document Hexana's binary tooling reads and edits.
 *
 * Format parsers consume bytes through [byteSource]; the chunk index, search
 * indices, and renderers all key off [BinaryDocumentSnapshot]s so background
 * work is never racing live edits. The piece-table-backed Phase 2 implementation
 * makes [snapshot] a constant-time copy-on-write capture; the Phase 1 stub
 * simply returns itself because there is no edit path yet.
 *
 * Implementations are **not** required to be thread-safe for writes — IDE-side
 * code marshals [edit] calls to a single thread (typically EDT). Reads through
 * [byteSource] and [snapshot] are safe from any thread.
 */
public interface BinaryDocument {
    /** Total length of the document in bytes. */
    public val length: Long

    /**
     * Monotonic counter incremented on every successful [EditTransaction.commit].
     * Phase 1: always `0L`. Useful as a cache key for renderers.
     */
    public val modificationStamp: Long

    /**
     * Allocates a fresh array of [length] bytes starting at [offset].
     * @throws IndexOutOfBoundsException on any out-of-range access.
     */
    public fun read(offset: Long, length: Int): ByteArray

    /**
     * Reads a single byte.
     * @throws IndexOutOfBoundsException if [offset] is outside `[0, length)`.
     */
    public fun readByte(offset: Long): Byte

    /** Copies [length] bytes starting at [offset] into [dst] at [dstOff]. */
    public fun readInto(offset: Long, dst: ByteArray, dstOff: Int, length: Int)

    /** Returns a [ByteSource] view over this document's current contents. */
    public fun byteSource(): ByteSource

    /**
     * Captures an immutable snapshot of the document at the current moment.
     * Phase 1: returns the document itself wrapped as a snapshot (the document
     * is already read-only). Phase 2: returns a frozen view of the piece tree.
     */
    public fun snapshot(): BinaryDocumentSnapshot

    /**
     * Begins a new edit transaction.
     * @throws UnsupportedOperationException in Phase 1 — editing arrives in Phase 2.
     */
    public fun edit(): EditTransaction

    /**
     * Maps a current logical offset back to its position in the original
     * (unmodified) source. Returns `null` for bytes that were inserted by the
     * user and have no original-file counterpart.
     *
     * Phase 1: identity — every logical offset maps to itself with length 1.
     */
    public fun originate(logicalOffset: Long): OriginalLocation?

    /** Registers a listener for post-commit notifications. */
    public fun addListener(listener: DocumentListener)

    /** Removes a previously-registered listener. No-op if not registered. */
    public fun removeListener(listener: DocumentListener)
}

/**
 * Read-only frozen view of a [BinaryDocument]. Returned by [BinaryDocument.snapshot]
 * and held by background workers (renderers, search) so concurrent edits to
 * the live document do not invalidate in-flight reads.
 *
 * Calling [edit] on a snapshot always throws.
 */
public interface BinaryDocumentSnapshot : BinaryDocument {
    /** Snapshots are read-only. Always throws [UnsupportedOperationException]. */
    override fun edit(): EditTransaction
}
