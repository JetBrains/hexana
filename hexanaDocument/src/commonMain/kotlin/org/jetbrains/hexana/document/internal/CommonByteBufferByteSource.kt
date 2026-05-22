package org.jetbrains.hexana.document.internal

import org.jetbrains.hexana.document.ByteSource
import org.jetbrains.hexana.document.ByteSourceCursor
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * [ByteSource] backed by an in-memory [CommonByteBuffer]. The buffer is not
 * copied — callers must not mutate it after handoff. Suitable for documents
 * whose total length fits in [Int.MAX_VALUE] (~2 GB); a Phase 2 mmap-backed
 * source covers larger files.
 *
 * Reads are cursor-safe: bulk [read] takes a [CommonByteBuffer.duplicate] so
 * concurrent readers (e.g. snapshots inspected from different threads) never
 * share the underlying buffer's position cursor.
 */
internal class CommonByteBufferByteSource(
    private val bytes: CommonByteBuffer,
) : ByteSource {
    override val length: Long = bytes.size.toLong()

    override fun get(offset: Long): Byte {
        if (offset < 0L || offset >= length) {
            throw IndexOutOfBoundsException("offset $offset out of bounds [0, $length)")
        }
        return bytes[offset.toInt()]
    }

    override fun read(offset: Long, dst: ByteArray, dstOff: Int, length: Int) {
        if (length < 0) throw IllegalArgumentException("negative length: $length")
        if (offset < 0L || offset + length > this.length) {
            throw IndexOutOfBoundsException("range [$offset, ${offset + length}) out of [0, ${this.length})")
        }
        if (dstOff < 0 || dstOff + length > dst.size) {
            throw IndexOutOfBoundsException("dst range [$dstOff, ${dstOff + length}) out of [0, ${dst.size})")
        }
        bytes.duplicate().position(offset.toInt()).get(dst, dstOff, length)
    }

    override fun cursor(at: Long): ByteSourceCursor = DefaultByteSourceCursor(this, at)
}