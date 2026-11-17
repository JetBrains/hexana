package org.jetbrains.hexana.document.internal

import org.jetbrains.hexana.document.BinaryDocument
import org.jetbrains.hexana.document.BinaryDocumentSnapshot
import org.jetbrains.hexana.document.ByteSource
import org.jetbrains.hexana.document.DocumentListener
import org.jetbrains.hexana.document.EditTransaction
import org.jetbrains.hexana.document.OriginalLocation
import org.jetbrains.hexana.io.CommonByteBuffer

private const val EDIT_PHASE_2_MSG: String =
    "Editing arrives in Phase 2 (piece-table backed BinaryDocument). " +
        "The Phase 1 stub is read-only."

/**
 * Phase 1 stub: immutable, single-piece document backed by a [CommonByteBuffer].
 * Both the live document and its snapshot are the same object — there are no
 * edits to capture. Listeners can be registered but never fire.
 *
 * Phase 2 will replace this with a piece-tree-backed implementation; the public
 * [BinaryDocument] contract stays unchanged.
 */
internal class ImmutableBinaryDocument(
    bytes: CommonByteBuffer,
) : BinaryDocumentSnapshot {

    private val source: ByteSource = CommonByteBufferByteSource(bytes)

    override val length: Long get() = source.length

    override val modificationStamp: Long get() = 0L

    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        readInto(offset, out, 0, length)
        return out
    }

    override fun readByte(offset: Long): Byte = source.get(offset)

    override fun readInto(offset: Long, dst: ByteArray, dstOff: Int, length: Int) {
        source.read(offset, dst, dstOff, length)
    }

    override fun byteSource(): ByteSource = source

    override fun snapshot(): BinaryDocumentSnapshot = this

    override fun edit(): EditTransaction = throw UnsupportedOperationException(EDIT_PHASE_2_MSG)

    override fun originate(logicalOffset: Long): OriginalLocation? {
        if (logicalOffset < 0L || logicalOffset >= length) return null
        return OriginalLocation(logicalOffset, length - logicalOffset)
    }

    override fun addListener(listener: DocumentListener) {
        // Phase 1: immutable document — no edits will ever fire. Stored nowhere.
    }

    override fun removeListener(listener: DocumentListener) {
        // Phase 1: see addListener.
    }
}