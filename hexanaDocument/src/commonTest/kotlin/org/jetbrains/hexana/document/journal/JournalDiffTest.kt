package org.jetbrains.hexana.document.journal

import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.jetbrains.hexana.io.toCommonByteBuffer

/**
 * Exercises [JournalDiff]. The headline property: `replay(a, diff(a, b)) == b`
 * for any pair `(a, b)`. Hand-crafted edge cases lock in the *shape* of the
 * output (which op, what offsets, what bytes); the fuzz loop guards against
 * corner cases the hand-crafted set missed.
 */
class JournalDiffTest {

    /** Replay helper that constructs a Journal so the public path is exercised. */
    private fun replay(a: ByteArray, ops: List<JournalOp>): ByteArray {
        if (ops.isEmpty()) return a.copyOf()
        val journal = Journal(
            originalHash = Journal.ORIGINAL_HASH_PREFIX + Sha256.hex(a.toCommonByteBuffer()),
            originalLength = a.size.toLong(),
            entries = listOf(JournalEntry("test", ops)),
        )
        return JournalReplay.replay(a, journal)
    }

    private fun bytes(vararg vs: Int): ByteArray = ByteArray(vs.size) { vs[it].toByte() }

    private fun decodeData(data: String): ByteArray = Base64.decode(data)

    // -- Op-shape assertions ----------------------------------------------

    @Test
    fun identical_emits_no_ops() {
        val a = bytes(0x01, 0x02, 0x03)
        assertEquals(emptyList(), JournalDiff.diff(a, a.copyOf()))
    }

    @Test
    fun empty_to_empty_emits_no_ops() {
        assertEquals(emptyList(), JournalDiff.diff(ByteArray(0), ByteArray(0)))
    }

    @Test
    fun empty_a_emits_single_insert_at_zero() {
        val ops = JournalDiff.diff(ByteArray(0), bytes(0xAA, 0xBB, 0xCC))
        assertEquals(1, ops.size)
        val ins = assertIs<JournalOp.Insert>(ops[0])
        assertEquals(0L, ins.offset)
        assertContentEquals(bytes(0xAA, 0xBB, 0xCC), decodeData(ins.data))
    }

    @Test
    fun empty_b_emits_single_delete_covering_a() {
        val a = bytes(0x10, 0x20, 0x30)
        val ops = JournalDiff.diff(a, ByteArray(0))
        assertEquals(1, ops.size)
        val del = assertIs<JournalOp.Delete>(ops[0])
        assertEquals(0L, del.start)
        assertEquals(3L, del.end)
    }

    @Test
    fun single_byte_replace_in_middle() {
        val a = bytes(0x01, 0x02, 0x03, 0x04, 0x05)
        val b = bytes(0x01, 0x02, 0xFF, 0x04, 0x05)
        val ops = JournalDiff.diff(a, b)
        assertEquals(1, ops.size)
        val rep = assertIs<JournalOp.Replace>(ops[0])
        assertEquals(2L, rep.start)
        assertEquals(3L, rep.end)
        assertContentEquals(bytes(0xFF), decodeData(rep.data))
    }

    @Test
    fun pure_prefix_change_does_not_collapse_into_replace_of_whole() {
        // Leading byte changes, all suffix matches. Op should cover only [0, 1).
        val a = bytes(0xAA, 0xBB, 0xCC, 0xDD)
        val b = bytes(0x11, 0xBB, 0xCC, 0xDD)
        val ops = JournalDiff.diff(a, b)
        val rep = assertIs<JournalOp.Replace>(ops.single())
        assertEquals(0L, rep.start)
        assertEquals(1L, rep.end)
        assertContentEquals(bytes(0x11), decodeData(rep.data))
    }

    @Test
    fun pure_suffix_change() {
        val a = bytes(0xAA, 0xBB, 0xCC, 0xDD)
        val b = bytes(0xAA, 0xBB, 0xCC, 0x99)
        val ops = JournalDiff.diff(a, b)
        val rep = assertIs<JournalOp.Replace>(ops.single())
        assertEquals(3L, rep.start)
        assertEquals(4L, rep.end)
        assertContentEquals(bytes(0x99), decodeData(rep.data))
    }

    @Test
    fun insert_in_middle_emits_insert_op() {
        val a = bytes(0x10, 0x20)
        val b = bytes(0x10, 0xFF, 0x20)
        val ops = JournalDiff.diff(a, b)
        val ins = assertIs<JournalOp.Insert>(ops.single())
        assertEquals(1L, ins.offset)
        assertContentEquals(bytes(0xFF), decodeData(ins.data))
    }

    @Test
    fun delete_in_middle_emits_delete_op() {
        val a = bytes(0x10, 0x20, 0x30, 0x40)
        val b = bytes(0x10, 0x40)
        val ops = JournalDiff.diff(a, b)
        val del = assertIs<JournalOp.Delete>(ops.single())
        assertEquals(1L, del.start)
        assertEquals(3L, del.end)
    }

    @Test
    fun appended_bytes_emit_insert_at_old_length() {
        val a = bytes(0x01, 0x02)
        val b = bytes(0x01, 0x02, 0x03, 0x04)
        val ops = JournalDiff.diff(a, b)
        val ins = assertIs<JournalOp.Insert>(ops.single())
        assertEquals(2L, ins.offset)
        assertContentEquals(bytes(0x03, 0x04), decodeData(ins.data))
    }

    @Test
    fun trimmed_bytes_emit_delete_at_new_length() {
        val a = bytes(0x01, 0x02, 0x03, 0x04)
        val b = bytes(0x01, 0x02)
        val ops = JournalDiff.diff(a, b)
        val del = assertIs<JournalOp.Delete>(ops.single())
        assertEquals(2L, del.start)
        assertEquals(4L, del.end)
    }

    @Test
    fun prefix_and_suffix_dont_overlap_when_b_is_shorter() {
        // a = "AB", b = "A". Naive: prefix=1 ("A"), suffix would also try "A" → overlap.
        // The capacity bound on common-suffix prevents overlap.
        val a = bytes(0x41, 0x42)
        val b = bytes(0x41)
        val ops = JournalDiff.diff(a, b)
        val del = assertIs<JournalOp.Delete>(ops.single())
        assertEquals(1L, del.start)
        assertEquals(2L, del.end)
    }

    // -- Round-trip property over hand-crafted cases ----------------------

    @Test
    fun roundtrip_hand_crafted() {
        val cases = listOf(
            bytes(0x00) to bytes(0x00),
            bytes() to bytes(0x01, 0x02),
            bytes(0x01, 0x02) to bytes(),
            bytes(0xAA, 0xBB) to bytes(0xCC, 0xBB),
            bytes(0xAA, 0xBB) to bytes(0xAA, 0xCC),
            bytes(0x01, 0x02, 0x03) to bytes(0x01, 0xFF, 0x03),
            bytes(0x10, 0x20, 0x30) to bytes(0x10, 0x20, 0x30, 0x40),
            bytes(0x10, 0x20, 0x30, 0x40) to bytes(0x10, 0x20, 0x30),
            bytes(0xDE, 0xAD, 0xBE, 0xEF) to bytes(0xCA, 0xFE),
            bytes() to bytes(),
        )
        for ((a, b) in cases) {
            val ops = JournalDiff.diff(a, b)
            val replayed = replay(a, ops)
            assertContentEquals(b, replayed, "case a=${a.contentToString()} b=${b.contentToString()}")
        }
    }

    // -- Fuzz --------------------------------------------------------------

    @Test
    fun fuzz_roundtrip_random_pairs() {
        val rng = Random(42)
        repeat(200) { iter ->
            val a = randomBytes(rng, max = 64)
            val b = mutateRandomly(a, rng)
            val ops = JournalDiff.diff(a, b)
            val replayed = replay(a, ops)
            assertContentEquals(
                b, replayed,
                "iter=$iter\n  a=${a.toList()}\n  b=${b.toList()}\n  ops=$ops\n  replayed=${replayed.toList()}",
            )
        }
    }

    @Test
    fun fuzz_roundtrip_independent_pairs() {
        // Distinct random a, b — exercises the "many regions differ" case.
        val rng = Random(1337)
        repeat(100) { iter ->
            val a = randomBytes(rng, max = 64)
            val b = randomBytes(rng, max = 64)
            val ops = JournalDiff.diff(a, b)
            val replayed = replay(a, ops)
            assertContentEquals(
                b, replayed,
                "iter=$iter independent",
            )
        }
    }

    @Test
    fun diffAsEntry_returns_null_when_inputs_match() {
        val a = bytes(0x01, 0x02)
        assertEquals(null, JournalDiff.diffAsEntry(a, a.copyOf(), "t"))
    }

    @Test
    fun diffAsEntry_carries_timestamp_and_ops_when_inputs_differ() {
        val a = bytes(0x01, 0x02, 0x03)
        val b = bytes(0x01, 0xFF, 0x03)
        val entry = JournalDiff.diffAsEntry(a, b, "2026-05-09T00:00:00Z")
        assertTrue(entry != null)
        assertEquals("2026-05-09T00:00:00Z", entry.timestamp)
        assertEquals(1, entry.ops.size)
    }

    private fun randomBytes(rng: Random, max: Int): ByteArray {
        val size = rng.nextInt(0, max + 1)
        return ByteArray(size) { rng.nextInt(256).toByte() }
    }

    /**
     * Produces a `b` derived from [a] by performing a random small edit:
     * replace a span, insert bytes, delete a span, append, trim, or no-op.
     * The result is fed back through diff + replay to assert round-trip.
     */
    private fun mutateRandomly(a: ByteArray, rng: Random): ByteArray {
        if (a.isEmpty()) return randomBytes(rng, max = 8)
        return when (rng.nextInt(6)) {
            0 -> a.copyOf() // no-op
            1 -> {
                // replace random span
                val start = rng.nextInt(a.size)
                val end = rng.nextInt(start, a.size + 1)
                val payload = ByteArray(rng.nextInt(0, 6)) { rng.nextInt(256).toByte() }
                a.copyOfRange(0, start) + payload + a.copyOfRange(end, a.size)
            }
            2 -> {
                // insert at random offset
                val at = rng.nextInt(0, a.size + 1)
                val payload = ByteArray(rng.nextInt(1, 6)) { rng.nextInt(256).toByte() }
                a.copyOfRange(0, at) + payload + a.copyOfRange(at, a.size)
            }
            3 -> {
                // delete random span
                val start = rng.nextInt(a.size)
                val end = rng.nextInt(start, a.size + 1)
                a.copyOfRange(0, start) + a.copyOfRange(end, a.size)
            }
            4 -> {
                // append
                val payload = ByteArray(rng.nextInt(1, 6)) { rng.nextInt(256).toByte() }
                a + payload
            }
            else -> {
                // trim
                val keep = rng.nextInt(0, a.size + 1)
                a.copyOfRange(0, keep)
            }
        }
    }
}
