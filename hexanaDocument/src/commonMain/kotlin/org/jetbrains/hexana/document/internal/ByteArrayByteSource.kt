package org.jetbrains.hexana.document.internal

import org.jetbrains.hexana.document.ByteSource
import org.jetbrains.hexana.document.ByteSourceCursor

/**
 * [ByteSource] backed by a single in-memory [ByteArray]. The array is not
 * copied — callers must not mutate it after handoff. Suitable for documents
 * whose total length fits in [Int.MAX_VALUE] (~2 GB); Phase 2 introduces an
 * mmap-backed source for larger files.
 */
internal class ByteArrayByteSource(
    private val bytes: ByteArray,
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
        bytes.copyInto(dst, dstOff, offset.toInt(), offset.toInt() + length)
    }

    override fun cursor(at: Long): ByteSourceCursor = DefaultByteSourceCursor(this, at)
}
