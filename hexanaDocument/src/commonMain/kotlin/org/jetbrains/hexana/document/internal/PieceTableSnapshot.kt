package org.jetbrains.hexana.document.internal

import org.jetbrains.hexana.document.BinaryDocumentSnapshot
import org.jetbrains.hexana.document.ByteSource
import org.jetbrains.hexana.document.ByteSourceCursor
import org.jetbrains.hexana.document.DocumentListener
import org.jetbrains.hexana.document.EditTransaction
import org.jetbrains.hexana.document.OriginalLocation

/**
 * Immutable view of a [BinaryDocument] at a particular point in time.
 *
 * Holds a frozen [List] of [Piece]s plus a stamp identifying when it was
 * captured. The underlying buffers ([original], [added]) are shared with the
 * live document — that's safe because [original] is immutable and [added] is
 * append-only, so any byte that this snapshot's pieces reference will never
 * be overwritten.
 */
internal class PieceTableSnapshot(
    internal val pieces: List<Piece>,
    private val original: ByteArray,
    private val added: GrowableByteBuffer,
    override val modificationStamp: Long,
) : BinaryDocumentSnapshot {

    override val length: Long = pieces.sumOf { it.length }

    private val source: ByteSource = PieceTableByteSource(this)

    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        readInto(offset, out, 0, length)
        return out
    }

    override fun readByte(offset: Long): Byte {
        require(offset in 0L until length) { "offset $offset out of [0, $length)" }
        var pos = 0L
        for (p in pieces) {
            if (offset < pos + p.length) {
                val src = (p.start + (offset - pos)).toInt()
                return when (p.source) {
                    Source.ORIGINAL -> original[src]
                    Source.ADDED -> added.get(src)
                }
            }
            pos += p.length
        }
        throw IndexOutOfBoundsException("offset $offset (length=$length)")
    }

    override fun readInto(offset: Long, dst: ByteArray, dstOff: Int, length: Int) {
        require(offset >= 0L && length >= 0 && offset + length <= this.length) {
            "readInto out of range: offset=$offset, length=$length, available=${this.length}"
        }
        if (length == 0) return

        var remaining = length
        var dstCursor = dstOff
        var pos = 0L
        var logical = offset
        var i = 0
        while (i < pieces.size && pos + pieces[i].length <= logical) {
            pos += pieces[i].length
            i++
        }
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

    override fun byteSource(): ByteSource = source

    override fun snapshot(): BinaryDocumentSnapshot = this

    override fun edit(): EditTransaction =
        throw UnsupportedOperationException("Snapshots are read-only.")

    override fun originate(logicalOffset: Long): OriginalLocation? {
        if (logicalOffset < 0L || logicalOffset >= length) return null
        var pos = 0L
        for (p in pieces) {
            if (logicalOffset < pos + p.length) {
                if (p.source != Source.ORIGINAL) return null
                val withinPiece = logicalOffset - pos
                val originStart = p.start + withinPiece
                val remaining = p.length - withinPiece
                return OriginalLocation(originStart, remaining)
            }
            pos += p.length
        }
        return null
    }

    override fun addListener(listener: DocumentListener) {
        // Snapshots are immutable — no edits will ever fire.
    }

    override fun removeListener(listener: DocumentListener) {
        // See addListener.
    }

}

/** [ByteSource] that reads through a snapshot's piece list. */
private class PieceTableByteSource(
    private val snapshot: PieceTableSnapshot,
) : ByteSource {

    override val length: Long get() = snapshot.length

    override fun get(offset: Long): Byte = snapshot.readByte(offset)

    override fun read(offset: Long, dst: ByteArray, dstOff: Int, length: Int) {
        snapshot.readInto(offset, dst, dstOff, length)
    }

    override fun cursor(at: Long): ByteSourceCursor = DefaultByteSourceCursor(this, at)
}
