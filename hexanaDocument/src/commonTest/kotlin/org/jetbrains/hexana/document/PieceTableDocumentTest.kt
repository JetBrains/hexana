package org.jetbrains.hexana.document

import kotlin.io.encoding.Base64
import org.jetbrains.hexana.document.journal.Journal
import org.jetbrains.hexana.document.journal.JournalEntry
import org.jetbrains.hexana.document.journal.JournalOp
import org.jetbrains.hexana.document.journal.JournalPersister
import org.jetbrains.hexana.document.journal.Sha256
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for the editable [BinaryDocument] backing — the editing
 * state machine from §7 of the design spec. Covers the user-visible
 * lifecycle (edit → commit → read), undo/redo, persister callback ordering,
 * and the un-redoable-tail rule.
 */
class PieceTableDocumentTest {

    private fun bytesOf(doc: BinaryDocument): ByteArray {
        val out = ByteArray(doc.length.toInt())
        doc.readInto(0L, out, 0, out.size)
        return out
    }

    @Test
    fun fresh_document_reads_original_unchanged() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x01, 0x02, 0x03))
        assertEquals(3L, doc.length)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), bytesOf(doc))
    }

    @Test
    fun commit_replace_changes_visible_bytes() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x10, 0x20, 0x30))
        doc.edit().apply {
            replace(1L, 2L, byteArrayOf(0xFF.toByte()))
            commit()
        }
        assertContentEquals(byteArrayOf(0x10, 0xFF.toByte(), 0x30), bytesOf(doc))
    }

    @Test
    fun commit_insert_grows_document() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x01, 0x02))
        doc.edit().apply {
            insert(1L, byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
            commit()
        }
        assertContentEquals(byteArrayOf(0x01, 0xAA.toByte(), 0xBB.toByte(), 0x02), bytesOf(doc))
    }

    @Test
    fun commit_delete_shrinks_document() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        doc.edit().apply {
            delete(1L, 3L)
            commit()
        }
        assertContentEquals(byteArrayOf(0x01, 0x04), bytesOf(doc))
    }

    @Test
    fun close_without_commit_rolls_back() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x10, 0x20))
        val tx = doc.edit()
        tx.replace(0L, 1L, byteArrayOf(0xFF.toByte()))
        tx.close()
        assertContentEquals(byteArrayOf(0x10, 0x20), bytesOf(doc))
    }

    @Test
    fun multiple_ops_in_one_transaction_apply_in_logical_order() {
        // After insert(1, [FF FF]): 10 FF FF 20 30
        // After delete(1, 2): 10 FF 20 30
        val doc = editableBinaryDocumentOf(byteArrayOf(0x10, 0x20, 0x30))
        doc.edit().apply {
            insert(1L, byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            delete(1L, 2L)
            commit()
        }
        assertContentEquals(
            byteArrayOf(0x10, 0xFF.toByte(), 0x20, 0x30),
            bytesOf(doc),
        )
    }

    @Test
    fun listener_fires_once_per_commit_with_batched_ops() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x10, 0x20, 0x30))
        val events = mutableListOf<DocumentChangeEvent>()
        doc.addListener { events += it }

        doc.edit().apply {
            insert(0L, byteArrayOf(0xAA.toByte()))
            replace(2L, 3L, byteArrayOf(0xBB.toByte()))
            commit()
        }

        assertEquals(1, events.size)
        assertEquals(2, events[0].edits.size)
    }

    @Test
    fun undo_reverts_to_pre_commit_state() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x01, 0x02, 0x03))
        doc.edit().apply {
            replace(1L, 2L, byteArrayOf(0xFF.toByte()))
            commit()
        }
        assertTrue(doc.canUndo())
        doc.undo()
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), bytesOf(doc))
        assertFalse(doc.canUndo())
        assertTrue(doc.canRedo())
    }

    @Test
    fun redo_reapplies_undone_commit() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x01, 0x02, 0x03))
        doc.edit().apply {
            replace(1L, 2L, byteArrayOf(0xFF.toByte()))
            commit()
        }
        doc.undo()
        doc.redo()
        assertContentEquals(byteArrayOf(0x01, 0xFF.toByte(), 0x03), bytesOf(doc))
        assertTrue(doc.canUndo())
        assertFalse(doc.canRedo())
    }

    @Test
    fun new_commit_after_undo_discards_redo_tail() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x01, 0x02, 0x03))
        doc.edit().apply { replace(0L, 1L, byteArrayOf(0xAA.toByte())); commit() }
        doc.edit().apply { replace(2L, 3L, byteArrayOf(0xBB.toByte())); commit() }
        doc.undo()
        assertTrue(doc.canRedo())
        // New commit while there's a redo stack — that stack must be discarded.
        doc.edit().apply { replace(1L, 2L, byteArrayOf(0xCC.toByte())); commit() }
        assertFalse(doc.canRedo())
        assertContentEquals(byteArrayOf(0xAA.toByte(), 0xCC.toByte(), 0x03), bytesOf(doc))
    }

    @Test
    fun persister_called_with_journal_on_commit() {
        var lastSeen: Journal? = null
        val persister = JournalPersister { j -> lastSeen = j }
        val doc = editableBinaryDocumentOf(
            bytes = byteArrayOf(0x01, 0x02),
            persister = persister,
            timestampProvider = { "T" },
        )

        doc.edit().apply { replace(0L, 1L, byteArrayOf(0xFF.toByte())); commit() }

        val seen = assertNotNull(lastSeen, "persister should have been called")
        assertEquals(1, seen.entries.size)
        assertEquals(2L, seen.originalLength)
        assertEquals("T", seen.entries[0].timestamp)
    }

    @Test
    fun persister_called_with_null_when_undone_back_to_original() {
        val saved = mutableListOf<Journal?>()
        val doc = editableBinaryDocumentOf(
            bytes = byteArrayOf(0x01, 0x02),
            persister = { saved += it },
        )

        doc.edit().apply { replace(0L, 1L, byteArrayOf(0xFF.toByte())); commit() }
        doc.undo()

        // Two persister calls: one for the commit, one for the undo back to nothing.
        assertEquals(2, saved.size)
        assertNotNull(saved[0])
        assertNull(saved[1], "expected null journal after undoing back to zero entries")
    }

    @Test
    fun loadJournal_replays_entries_without_persisting() {
        val saved = mutableListOf<Journal?>()
        val doc = editableBinaryDocumentOf(
            bytes = byteArrayOf(0x01, 0x02, 0x03),
            persister = { saved += it },
        )

        // Build a journal that flips byte 0 to 0xFF then byte 2 to 0xEE.
        val journal = Journal(
            originalHash = "sha256:" + Sha256.hex(byteArrayOf(0x01, 0x02, 0x03)),
            originalLength = 3L,
            entries = listOf(
                JournalEntry(
                    timestamp = "T1",
                    ops = listOf(JournalOp.Replace(0L, 1L, Base64.encode(byteArrayOf(0xFF.toByte())))),
                ),
                JournalEntry(
                    timestamp = "T2",
                    ops = listOf(JournalOp.Replace(2L, 3L, Base64.encode(byteArrayOf(0xEE.toByte())))),
                ),
            ),
        )

        doc.loadJournal(journal)

        // State reflects the replayed edits.
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x02, 0xEE.toByte()), bytesOf(doc))
        assertTrue(doc.canUndo())

        // Persister was NOT called during replay — we just read the journal
        // from disk; rewriting it would loop.
        assertTrue(saved.isEmpty(), "loadJournal must not invoke the persister")

        // Undo still works against the loaded entries.
        doc.undo()
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x02, 0x03), bytesOf(doc))
        // The post-replay undo DOES persist (it's a real document state change).
        assertEquals(1, saved.size)
    }

    @Test
    fun clearPersistedJournal_invokes_persister_with_null_without_touching_state() {
        val saved = mutableListOf<Journal?>()
        val doc = editableBinaryDocumentOf(
            bytes = byteArrayOf(0x01, 0x02),
            persister = { saved += it },
        )

        doc.edit().apply { replace(0L, 1L, byteArrayOf(0xFF.toByte())); commit() }
        // First save happens on commit and is non-null.
        assertEquals(1, saved.size)
        assertNotNull(saved[0])

        doc.clearPersistedJournal()

        // clearPersistedJournal asks the persister to delete the sidecar.
        assertEquals(2, saved.size)
        assertNull(saved[1], "expected null journal after clearPersistedJournal")

        // In-memory state is untouched: undo still works, byte 0 still 0xFF.
        assertTrue(doc.canUndo())
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x02), bytesOf(doc))

        // The next commit re-persists from the live entries (cumulative).
        doc.edit().apply { replace(1L, 2L, byteArrayOf(0xEE.toByte())); commit() }
        val seen = assertNotNull(saved.last())
        assertEquals(2, seen.entries.size, "persister gets the full journal on next commit")
    }

    @Test
    fun snapshot_independence_after_subsequent_edit() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x10, 0x20, 0x30))
        val before = doc.snapshot()

        doc.edit().apply { replace(1L, 2L, byteArrayOf(0xFF.toByte())); commit() }

        // The previous snapshot still reflects the pre-commit state.
        val before0 = ByteArray(before.length.toInt())
        before.readInto(0L, before0, 0, before0.size)
        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30), before0)
        assertContentEquals(byteArrayOf(0x10, 0xFF.toByte(), 0x30), bytesOf(doc))
    }

    @Test
    fun originate_returns_null_for_inserted_bytes() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x10, 0x20))
        doc.edit().apply { insert(1L, byteArrayOf(0xFF.toByte())); commit() }

        // Logical layout now: 10 (orig 0) | FF (added) | 20 (orig 1)
        assertNotNull(doc.originate(0L), "byte 0 came from original")
        assertNull(doc.originate(1L), "byte 1 was inserted; no origin")
        assertNotNull(doc.originate(2L), "byte 2 came from original")
    }

    @Test
    fun modificationStamp_advances_on_commit_and_undo() {
        val doc = editableBinaryDocumentOf(byteArrayOf(0x10))
        val s0 = doc.modificationStamp
        doc.edit().apply { replace(0L, 1L, byteArrayOf(0xFF.toByte())); commit() }
        val s1 = doc.modificationStamp
        doc.undo()
        val s2 = doc.modificationStamp
        assertTrue(s1 > s0)
        assertTrue(s2 > s1)
    }
}
