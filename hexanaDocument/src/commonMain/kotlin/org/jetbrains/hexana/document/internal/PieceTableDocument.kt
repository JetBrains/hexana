package org.jetbrains.hexana.document.internal

import kotlin.io.encoding.Base64
import org.jetbrains.hexana.document.BinaryDocumentSnapshot
import org.jetbrains.hexana.document.ByteSource
import org.jetbrains.hexana.document.DocumentChangeEvent
import org.jetbrains.hexana.document.DocumentListener
import org.jetbrains.hexana.document.EditableBinaryDocument
import org.jetbrains.hexana.document.EditTransaction
import org.jetbrains.hexana.document.OriginalLocation
import org.jetbrains.hexana.document.journal.Journal
import org.jetbrains.hexana.document.journal.JournalEntry
import org.jetbrains.hexana.document.journal.JournalOp
import org.jetbrains.hexana.document.journal.JournalPersister
import org.jetbrains.hexana.document.journal.Sha256
import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Editable [BinaryDocument] backed by a piece table. Implements the editing
 * state machine from §7 of the design spec — `entries` + `snapshots` + a
 * `cursor` that moves on every commit/undo/redo.
 *
 * The document is not thread-safe for writes; reads through [byteSource] /
 * [snapshot] are safe so long as edits are marshalled to a single thread
 * (typical IDE pattern).
 *
 * @param original immutable original-file bytes; `originalHash` and
 *   `originalLength` for any persisted journal are derived from these.
 * @param persister hook the document calls after every state change so the
 *   IDE plugin can write the sidecar via the §6 atomic-rename pattern.
 *   [JournalPersister.NoOp] suppresses persistence (tests, read-only callers).
 * @param timestampProvider optional clock injection for tests; default is
 *   `null`, which produces empty timestamps (the IDE plugin substitutes a
 *   real one before persisting).
 */
internal class PieceTableDocument(
    private val original: CommonByteBuffer,
    private val persister: JournalPersister = JournalPersister.NoOp,
    private val timestampProvider: () -> String = { "" },
) : EditableBinaryDocument {

    private val originalHash: String =
        Journal.ORIGINAL_HASH_PREFIX + Sha256.hex(original)

    private val added = GrowableByteBuffer()

    /** Live piece list; mutated as the user edits, frozen when snapshots are taken. */
    private val pieces: PieceList = PieceList(
        if (original.isEmpty()) emptyList()
        else listOf(Piece(Source.ORIGINAL, 0L, original.size.toLong())),
    )

    /** Every commit ever made; entries past `cursor` are the redo stack. */
    private val entries: ArrayList<JournalEntry> = ArrayList()

    /** snapshots[i] = state after entries[0..i] applied. */
    private val snapshots: ArrayList<PieceTableSnapshot> = ArrayList()

    /** Snapshot of the original (no edits applied). Used when cursor is `-1`. */
    private val originalSnapshot: PieceTableSnapshot = freezeSnapshot()

    private var cursor: Int = -1

    /** The snapshot currently presented as the document's state. */
    private var current: PieceTableSnapshot = originalSnapshot

    private var stamp: Long = 0L

    private val listeners: ArrayList<DocumentListener> = ArrayList()

    // -- BinaryDocument -----------------------------------------------------

    override val length: Long get() = current.length

    override val modificationStamp: Long get() = stamp

    override fun read(offset: Long, length: Int): ByteArray = current.read(offset, length)

    override fun readByte(offset: Long): Byte = current.readByte(offset)

    override fun readInto(offset: Long, dst: ByteArray, dstOff: Int, length: Int) {
        current.readInto(offset, dst, dstOff, length)
    }

    override fun byteSource(): ByteSource = current.byteSource()

    override fun snapshot(): BinaryDocumentSnapshot = current

    override fun edit(): EditTransaction = PieceTableEditTransaction()

    override fun originate(logicalOffset: Long): OriginalLocation? =
        current.originate(logicalOffset)

    override fun addListener(listener: DocumentListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: DocumentListener) {
        listeners.remove(listener)
    }

    override fun clearPersistedJournal() {
        // Asks the persister to drop the sidecar without touching in-memory
        // state. Future commits will rewrite it from the current `entries`,
        // so the user's undo history isn't lost — the file just disappears
        // from disk until they make another edit.
        persister.save(null)
    }

    override fun loadJournal(journal: Journal) {
        check(cursor == -1) {
            "loadJournal must be called on a fresh document; current cursor=$cursor"
        }
        require(journal.originalLength == original.size.toLong()) {
            "journal originalLength ${journal.originalLength} doesn't match document " +
                "original length ${original.size}; caller should have validated via JournalCodec"
        }
        for (entry in journal.entries) replayEntry(entry)
    }

    /**
     * Applies [entry] from a loaded sidecar: mutate live pieces, append to
     * `entries` + `snapshots`, advance `cursor`. Skips [persistCurrent] (we
     * just read the journal off disk) and skips [fireEvent] (listeners
     * attach after construction; the load happens before any listener exists).
     */
    private fun replayEntry(entry: JournalEntry) {
        for (op in entry.ops) applyToLive(op)
        val newSnapshot = freezeSnapshot()
        entries.add(entry)
        snapshots.add(newSnapshot)
        cursor = entries.size - 1
        current = newSnapshot
        stamp += 1L
    }

    // -- Undo / redo --------------------------------------------------------

    override fun canUndo(): Boolean = cursor >= 0

    override fun canRedo(): Boolean = cursor < entries.size - 1

    /**
     * Reverts the last applied entry. The popped entry stays in [entries]
     * so a subsequent [redo] can re-apply it. Persists the new state.
     */
    override fun undo() {
        check(canUndo()) { "nothing to undo" }
        val poppedEntry = entries[cursor]
        val before = current
        cursor -= 1
        current = if (cursor < 0) originalSnapshot else snapshots[cursor]
        // Revert the live piece list to match the now-current snapshot so a
        // subsequent commit applies on top of the right state.
        pieces.replaceAll(current.pieces)
        stamp += 1L
        persistCurrent()
        fireEvent(before, current, inverseOf(poppedEntry, before, current))
    }

    /** Re-applies the next entry in the redo stack. Persists the new state. */
    override fun redo() {
        check(canRedo()) { "nothing to redo" }
        cursor += 1
        val before = current
        current = snapshots[cursor]
        // Revert the live piece list to match the now-current snapshot so a
        // subsequent commit applies on top of the right state.
        pieces.replaceAll(current.pieces)
        stamp += 1L
        persistCurrent()
        fireEvent(before, current, entries[cursor].ops.toEditOps())
    }

    // -- Internal: applying a transaction ----------------------------------

    /** Called by an [EditTransaction] when its `commit()` fires. */
    internal fun applyCommit(ops: List<JournalOp>) {
        if (ops.isEmpty()) return  // nothing to record

        // Drop the un-redoable tail before appending the new entry.
        if (cursor < entries.size - 1) {
            // entries[cursor+1..end] become unreachable.
            while (entries.size > cursor + 1) entries.removeAt(entries.size - 1)
            while (snapshots.size > cursor + 1) snapshots.removeAt(snapshots.size - 1)
        }

        val before = current
        for (op in ops) applyToLive(op)
        val newSnapshot = freezeSnapshot()
        val newEntry = JournalEntry(timestamp = timestampProvider(), ops = ops)
        entries.add(newEntry)
        snapshots.add(newSnapshot)
        cursor = entries.size - 1
        current = newSnapshot
        stamp += 1L
        persistCurrent()
        fireEvent(before, current, ops.toEditOps())
    }

    /** Applies a single op to the LIVE piece list and ADDED buffer. */
    private fun applyToLive(op: JournalOp) {
        when (op) {
            is JournalOp.Replace -> {
                val bytes = Base64.decode(op.data)
                val insertion = if (bytes.isEmpty()) null else appendPiece(bytes)
                pieces.replace(op.start, op.end, insertion)
            }
            is JournalOp.Insert -> {
                val bytes = Base64.decode(op.data)
                if (bytes.isEmpty()) return
                pieces.replace(op.offset, op.offset, appendPiece(bytes))
            }
            is JournalOp.Delete -> pieces.replace(op.start, op.end, insertion = null)
        }
    }

    private fun appendPiece(bytes: ByteArray): Piece {
        val start = added.append(bytes).toLong()
        return Piece(Source.ADDED, start, bytes.size.toLong())
    }

    private fun freezeSnapshot(): PieceTableSnapshot =
        PieceTableSnapshot(
            pieces = pieces.snapshotPieces(),
            original = original,
            added = added,
            modificationStamp = stamp,
        )

    private fun persistCurrent() {
        val active = entries.subList(0, cursor + 1).toList()
        if (active.isEmpty()) {
            persister.save(null)
        } else {
            persister.save(
                Journal(
                    originalHash = originalHash,
                    originalLength = original.size.toLong(),
                    entries = active,
                ),
            )
        }
    }

    private fun fireEvent(before: PieceTableSnapshot, after: PieceTableSnapshot, edits: List<org.jetbrains.hexana.document.EditOp>) {
        if (listeners.isEmpty()) return
        val event = DocumentChangeEvent(before = before, after = after, edits = edits)
        // Snapshot the listener list to tolerate addListener/removeListener
        // reentrancy from inside a handler.
        for (l in listeners.toList()) l.documentChanged(event)
    }

    /**
     * Computes the inverse [org.jetbrains.hexana.document.EditOp] list for an
     * undo: the bytes that need to be re-inserted to go from `after` back to
     * `before`.
     *
     * Phase 1 ships a coarse approximation — for each op in the popped entry
     * we report a single "the document was edited; here are the affected
     * ranges" `Replace` event. Listeners that incrementally remap chunks
     * receive enough information to re-derive their state by reading from
     * the after-snapshot.
     */
    private fun inverseOf(
        poppedEntry: JournalEntry,
        before: PieceTableSnapshot,
        after: PieceTableSnapshot,
    ): List<org.jetbrains.hexana.document.EditOp> {
        // Use the original entry's ops to derive the inverse ranges. Since the
        // post-undo state is `after` (the OLD state) and pre-undo was `before`,
        // the inverse op for a forward Replace(start, end, newLen) is
        // Replace(start, start+newLen, oldLen). Forward Insert → Delete.
        // Forward Delete → Insert.
        //
        // We don't have the "old bytes" recorded, but listeners can re-read
        // them from `after` — the EditOp shape only carries lengths and
        // offsets, not the bytes themselves.
        val inverse = ArrayList<org.jetbrains.hexana.document.EditOp>(poppedEntry.ops.size)
        // Walk backwards: the LAST op of the entry was applied last to produce
        // `before`, so its inverse is applied FIRST when going backwards.
        for (op in poppedEntry.ops.asReversed()) {
            inverse += when (op) {
                is JournalOp.Replace -> org.jetbrains.hexana.document.EditOp.Replace(
                    start = op.start,
                    endExclusive = op.start + Base64.decode(op.data).size.toLong(),
                    newLength = op.end - op.start,
                )
                is JournalOp.Insert -> org.jetbrains.hexana.document.EditOp.Delete(
                    start = op.offset,
                    endExclusive = op.offset + Base64.decode(op.data).size.toLong(),
                )
                is JournalOp.Delete -> org.jetbrains.hexana.document.EditOp.Insert(
                    start = op.start,
                    newLength = op.end - op.start,
                )
            }
        }
        @Suppress("UNUSED_PARAMETER") before
        @Suppress("UNUSED_PARAMETER") after
        return inverse
    }

    // -- Inner: the EditTransaction implementation -------------------------

    private inner class PieceTableEditTransaction : EditTransaction {
        private val ops = ArrayList<JournalOp>()
        private var virtualLength: Long = current.length
        private var closed = false

        override fun replace(start: Long, endExclusive: Long, bytes: ByteArray) {
            checkOpen()
            require(start in 0L..virtualLength && endExclusive in start..virtualLength) {
                "replace($start, $endExclusive) out of [0, $virtualLength]"
            }
            ops += JournalOp.Replace(start, endExclusive, Base64.encode(bytes))
            virtualLength = virtualLength - (endExclusive - start) + bytes.size
        }

        override fun insert(offset: Long, bytes: ByteArray) {
            checkOpen()
            require(offset in 0L..virtualLength) { "insert($offset) out of [0, $virtualLength]" }
            if (bytes.isEmpty()) return
            ops += JournalOp.Insert(offset, Base64.encode(bytes))
            virtualLength += bytes.size
        }

        override fun delete(start: Long, endExclusive: Long) {
            checkOpen()
            require(start in 0L..virtualLength && endExclusive in start..virtualLength) {
                "delete($start, $endExclusive) out of [0, $virtualLength]"
            }
            if (start == endExclusive) return
            ops += JournalOp.Delete(start, endExclusive)
            virtualLength -= endExclusive - start
        }

        override fun commit(): BinaryDocumentSnapshot {
            checkOpen()
            closed = true
            applyCommit(ops)
            return current
        }

        override fun close() {
            // Close-without-commit rolls back: ops are dropped, no events fire.
            closed = true
        }

        private fun checkOpen() {
            check(!closed) { "transaction is closed" }
        }
    }

    private fun List<JournalOp>.toEditOps(): List<org.jetbrains.hexana.document.EditOp> = map { op ->
        when (op) {
            is JournalOp.Replace -> org.jetbrains.hexana.document.EditOp.Replace(
                start = op.start,
                endExclusive = op.end,
                newLength = Base64.decode(op.data).size.toLong(),
            )
            is JournalOp.Insert -> org.jetbrains.hexana.document.EditOp.Insert(
                start = op.offset,
                newLength = Base64.decode(op.data).size.toLong(),
            )
            is JournalOp.Delete -> org.jetbrains.hexana.document.EditOp.Delete(
                start = op.start,
                endExclusive = op.end,
            )
        }
    }
}
