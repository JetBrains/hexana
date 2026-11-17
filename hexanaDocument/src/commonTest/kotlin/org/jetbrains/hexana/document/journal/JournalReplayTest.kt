package org.jetbrains.hexana.document.journal

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.hexana.io.toCommonByteBuffer

/**
 * Replay correctness. Tests the logical-offset semantics that the design spec
 * locked in: each op's offsets refer to the buffer state right before that op
 * runs, with offsets shifting as prior ops in the same entry are applied.
 */
class JournalReplayTest {

    private fun journalFor(original: ByteArray, entries: List<JournalEntry>): Journal = Journal(
        originalHash = Journal.ORIGINAL_HASH_PREFIX + Sha256.hex(original.toCommonByteBuffer()),
        originalLength = original.size.toLong(),
        entries = entries,
    )

    private fun b64(vararg bytes: Int): String = Base64.encode(ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun empty_journal_returns_original_unchanged() {
        val original = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val replayed = JournalReplay.replay(original, journalFor(original, emptyList()))
        assertContentEquals(original, replayed)
    }

    @Test
    fun single_replace_swaps_a_byte_range() {
        val original = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44)
        val journal = journalFor(
            original,
            listOf(
                JournalEntry("t", listOf(JournalOp.Replace(start = 1, end = 3, data = b64(0xAA, 0xBB)))),
            ),
        )
        val replayed = JournalReplay.replay(original, journal)
        assertContentEquals(byteArrayOf(0x00, 0xAA.toByte(), 0xBB.toByte(), 0x33, 0x44), replayed)
    }

    @Test
    fun replace_with_longer_payload_grows_the_buffer() {
        val original = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val journal = journalFor(
            original,
            listOf(
                JournalEntry("t", listOf(JournalOp.Replace(0L, 1L, b64(0x10, 0x20, 0x30)))),
            ),
        )
        val replayed = JournalReplay.replay(original, journal)
        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30, 0xBB.toByte()), replayed)
    }

    @Test
    fun insert_then_later_delete_uses_logical_offsets() {
        // Original:               00 01 02 03 04
        // After insert(2, [FF FF]): 00 01 FF FF 02 03 04
        // After delete(2, 3):       00 01 FF 02 03 04        ← logical offset, not original
        val original = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        val journal = journalFor(
            original,
            listOf(
                JournalEntry(
                    "t",
                    listOf(
                        JournalOp.Insert(offset = 2, data = b64(0xFF, 0xFF)),
                        JournalOp.Delete(start = 2, end = 3),
                    ),
                ),
            ),
        )
        val replayed = JournalReplay.replay(original, journal)
        assertContentEquals(byteArrayOf(0x00, 0x01, 0xFF.toByte(), 0x02, 0x03, 0x04), replayed)
    }

    @Test
    fun insert_after_inserted_bytes_is_expressible_via_logical_offsets() {
        // This is the case that ORIGINAL-OFFSET coordinates couldn't express
        // without CRDT-style position IDs: insert FF at offset 2, then insert
        // EE *after* the FF at logical offset 3.
        val original = byteArrayOf(0x00, 0x11, 0x22)
        val journal = journalFor(
            original,
            listOf(
                JournalEntry(
                    "t",
                    listOf(
                        JournalOp.Insert(offset = 2, data = b64(0xFF)),
                        JournalOp.Insert(offset = 3, data = b64(0xEE)),
                    ),
                ),
            ),
        )
        val replayed = JournalReplay.replay(original, journal)
        assertContentEquals(byteArrayOf(0x00, 0x11, 0xFF.toByte(), 0xEE.toByte(), 0x22), replayed)
    }

    @Test
    fun delete_at_end_shrinks_buffer() {
        val original = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val journal = journalFor(
            original,
            listOf(JournalEntry("t", listOf(JournalOp.Delete(start = 3, end = 5)))),
        )
        val replayed = JournalReplay.replay(original, journal)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03), replayed)
    }

    @Test
    fun multiple_entries_apply_in_order() {
        val original = byteArrayOf(0x10, 0x20, 0x30)
        val journal = journalFor(
            original,
            listOf(
                JournalEntry("t1", listOf(JournalOp.Insert(0L, b64(0x00)))),       // 00 10 20 30
                JournalEntry("t2", listOf(JournalOp.Replace(2L, 3L, b64(0x99)))), // 00 10 99 30
                JournalEntry("t3", listOf(JournalOp.Delete(0L, 1L))),              // 10 99 30
            ),
        )
        val replayed = JournalReplay.replay(original, journal)
        assertContentEquals(byteArrayOf(0x10, 0x99.toByte(), 0x30), replayed)
    }

    @Test
    fun rejects_mismatched_hash() {
        val original = byteArrayOf(0x01, 0x02, 0x03)
        val journal = Journal(
            originalHash = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
            originalLength = 3L,
            entries = emptyList(),
        )
        assertFailsWith<JournalReplayException.HashMismatch> {
            JournalReplay.replay(original, journal)
        }
    }

    @Test
    fun rejects_mismatched_length() {
        val original = byteArrayOf(0x01, 0x02, 0x03)
        val journal = Journal(
            originalHash = "sha256:" + Sha256.hex(original.toCommonByteBuffer()),
            originalLength = 999L, // wrong length, fails before hash even runs
            entries = emptyList(),
        )
        assertFailsWith<JournalReplayException.LengthMismatch> {
            JournalReplay.replay(original, journal)
        }
    }

    @Test
    fun out_of_range_op_is_reported_with_position() {
        val original = byteArrayOf(0x01, 0x02, 0x03)
        val journal = journalFor(
            original,
            listOf(
                JournalEntry("ok", listOf(JournalOp.Insert(0L, b64(0xFF)))),
                JournalEntry("oops", listOf(JournalOp.Replace(start = 100L, end = 101L, data = b64(0x00)))),
            ),
        )
        val ex = assertFailsWith<JournalReplayException.BadOp> {
            JournalReplay.replay(original, journal)
        }
        assertTrue(ex.entryIndex == 1, "entryIndex should point at the offending entry, was ${ex.entryIndex}")
        assertTrue(ex.opIndex == 0, "opIndex should be 0, was ${ex.opIndex}")
    }

    @Test
    fun replay_is_idempotent_on_the_same_journal() {
        val original = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val journal = journalFor(
            original,
            listOf(
                JournalEntry(
                    "t",
                    listOf(
                        JournalOp.Replace(1L, 3L, b64(0xAA, 0xBB, 0xCC)),
                        JournalOp.Delete(0L, 1L),
                        JournalOp.Insert(0L, b64(0xFE)),
                    ),
                ),
            ),
        )
        val first = JournalReplay.replay(original, journal)
        val second = JournalReplay.replay(original, journal)
        assertContentEquals(first, second)
    }
}
