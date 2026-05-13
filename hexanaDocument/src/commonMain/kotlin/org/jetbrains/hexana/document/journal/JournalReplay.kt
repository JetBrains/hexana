package org.jetbrains.hexana.document.journal

import kotlin.io.encoding.Base64

/**
 * Pure replay: takes the immutable original-file bytes and a [Journal], returns
 * the byte buffer that results from applying every entry's ops in order.
 *
 * Validates the journal's `originalHash` and `originalLength` against
 * [originalBytes] before starting — this is the "right journal for the right
 * file" guard that paired with the §8 mismatch UX in the design spec catches
 * stale logs. Failure is reported as [JournalReplayException]; callers are
 * expected to translate that into the IDE-level Discard / Backup-then-discard
 * notification.
 *
 * Logical-offset semantics: each op's offsets refer to the buffer state right
 * before that op runs. Within an entry, offsets advance as ops apply in order.
 *
 * Phase 1 performance: replay walks the journal once and applies each op via
 * [MutableByteSequence] — O(n) per op, O(n × ops) total. Acceptable because
 * replay is paid once per document open. The piece-table backing in a
 * follow-up phase provides faster editing-time mutation (and a faster replay
 * alongside if it becomes a bottleneck).
 */
public object JournalReplay {

    /**
     * Replays [journal] on top of [originalBytes].
     *
     * @throws JournalReplayException if the journal's `originalHash` /
     * `originalLength` doesn't match [originalBytes], or if any op references
     * an offset outside the buffer's bounds at the moment of application.
     */
    public fun replay(originalBytes: ByteArray, journal: Journal): ByteArray {
        validateOriginal(originalBytes, journal)
        val buf = MutableByteSequence(originalBytes)
        for ((entryIdx, entry) in journal.entries.withIndex()) {
            for ((opIdx, op) in entry.ops.withIndex()) {
                applyOp(buf, op, entryIdx, opIdx)
            }
        }
        return buf.toByteArray()
    }

    private fun validateOriginal(originalBytes: ByteArray, journal: Journal) {
        if (originalBytes.size.toLong() != journal.originalLength) {
            throw JournalReplayException.LengthMismatch(journal.originalLength, originalBytes.size.toLong())
        }
        val expected = journal.originalHash.removePrefix(Journal.ORIGINAL_HASH_PREFIX)
        val actual = Sha256.hex(originalBytes)
        if (!expected.equals(actual, ignoreCase = true)) {
            throw JournalReplayException.HashMismatch(expected, actual)
        }
    }

    private fun applyOp(buf: MutableByteSequence, op: JournalOp, entryIdx: Int, opIdx: Int) {
        try {
            when (op) {
                is JournalOp.Replace -> {
                    requireRange(op.start, op.end, buf.length)
                    buf.replace(op.start.toInt(), op.end.toInt(), Base64.decode(op.data))
                }
                is JournalOp.Insert -> {
                    requireOffset(op.offset, buf.length)
                    buf.insert(op.offset.toInt(), Base64.decode(op.data))
                }
                is JournalOp.Delete -> {
                    requireRange(op.start, op.end, buf.length)
                    buf.delete(op.start.toInt(), op.end.toInt())
                }
            }
        } catch (cause: IllegalArgumentException) {
            throw JournalReplayException.BadOp(entryIdx, opIdx, cause.message ?: "invalid op", cause)
        }
    }

    private fun requireRange(start: Long, end: Long, length: Int) {
        require(start in 0..length && end in start..length) {
            "range [$start, $end) out of [0, $length]"
        }
    }

    private fun requireOffset(offset: Long, length: Int) {
        require(offset in 0..length) {
            "offset $offset out of [0, $length]"
        }
    }
}

/** Replay failure. Pure-layer error; the IDE plugin translates these to user-facing notifications. */
public sealed class JournalReplayException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause) {

    /** Original-file byte length doesn't match the journal's `originalLength`. */
    public class LengthMismatch(public val expected: Long, public val actual: Long) :
        JournalReplayException("originalLength mismatch: expected $expected, actual $actual")

    /** Original-file sha256 doesn't match the journal's `originalHash`. */
    public class HashMismatch(public val expected: String, public val actual: String) :
        JournalReplayException("originalHash mismatch: expected sha256:$expected, actual sha256:$actual")

    /** A specific op referenced an out-of-range offset, malformed base64, etc. */
    public class BadOp(
        public val entryIndex: Int,
        public val opIndex: Int,
        public val reason: String,
        cause: Throwable?,
    ) : JournalReplayException(
        "entry[$entryIndex].ops[$opIndex]: $reason",
        cause,
    )
}
