package org.jetbrains.hexana.document

/**
 * Read-only view over a stretch of bytes addressed by absolute logical offset.
 *
 * Format parsers (WASM, ELF, PE/COFF, JVM classfile, LLVM bitcode) consume
 * bytes through this interface — never through the underlying [BinaryDocument]
 * directly. This is the IDE-agnosticism boundary: parsers stay independent of
 * the editing layer, the piece table, IntelliJ services, and Compose state.
 *
 * Offsets are absolute within the source. Implementations may back this view
 * with a [ByteArray], a memory-mapped file, or — once Phase 2 lands — a piece
 * table over the original buffer plus appended edits.
 *
 * @see BinaryDocument.byteSource for the canonical way to obtain one.
 */
public interface ByteSource {
    /** Total number of bytes addressable through this source. */
    public val length: Long

    /**
     * Reads a single byte at [offset].
     * @throws IndexOutOfBoundsException if [offset] is outside `[0, length)`.
     */
    public fun get(offset: Long): Byte

    /**
     * Copies [length] bytes starting at [offset] into [dst] at [dstOff].
     * @throws IndexOutOfBoundsException on any out-of-range access.
     */
    public fun read(offset: Long, dst: ByteArray, dstOff: Int, length: Int)

    /**
     * Allocates and returns a fresh array of [length] bytes starting at [offset].
     */
    public fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        read(offset, out, 0, length)
        return out
    }

    /** Returns a sequential cursor positioned at [at]. */
    public fun cursor(at: Long = 0L): ByteSourceCursor
}

/**
 * Forward-reading cursor over a [ByteSource]. Tracks position; advances on every
 * read. Cheap to construct — implementations should not eagerly allocate.
 */
public interface ByteSourceCursor {
    /** The underlying source. */
    public val source: ByteSource

    /** Current position. Mutable; updated by every read or [seek]. */
    public var position: Long

    /** Reads one byte and advances by 1. */
    public fun readByte(): Byte

    /** Reads [length] bytes into a fresh array and advances. */
    public fun readBytes(length: Int): ByteArray

    /** Sets [position]; equivalent to assigning to it but reads more naturally in chains. */
    public fun seek(offset: Long) {
        position = offset
    }

    /** True if more bytes remain readable. */
    public fun hasRemaining(): Boolean = position < source.length
}
