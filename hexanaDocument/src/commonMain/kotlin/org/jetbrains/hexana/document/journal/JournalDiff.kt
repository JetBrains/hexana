package org.jetbrains.hexana.document.journal

import kotlin.io.encoding.Base64

/**
 * Computes a list of [JournalOp]s that, when replayed on top of [a], produce
 * [b] exactly. The companion of [JournalReplay] on the producer side: enables
 * the use cases listed in the spec's §Diff path — round-trip testing, journal
 * compaction, external-patcher input, edit sharing.
 *
 * **Algorithm — prefix/suffix shrinking.** Finds the longest common prefix
 * and longest common suffix (each constrained not to overlap), then emits a
 * single op describing the differing middle region:
 *
 * ```
 *   replay(a, diff(a, b)) == b   for any (a, b)
 * ```
 *
 * The op vocabulary collapses by length:
 * - `aMid` empty + `bMid` empty → no ops (a and b are equal).
 * - `aMid` empty + `bMid` non-empty → `Insert(prefix, bMid)`.
 * - `aMid` non-empty + `bMid` empty → `Delete(prefix, prefix + aMid)`.
 * - both non-empty → `Replace(prefix, prefix + aMid, bMid)`.
 *
 * **Limitations.** A multi-region edit (e.g. byte 5 and byte 100 both
 * changed in a 10 KB file) collapses into one wide op covering everything
 * between the first and last difference. That's correct but loses
 * compactness. A bsdiff-style suffix-array implementation can come as a
 * follow-up if the journal's size growth becomes a problem.
 *
 * Complexity: O(min(|a|, |b|)) for prefix + suffix scans, plus one
 * `copyOfRange` and one base64 encode of the differing middle.
 */
public object JournalDiff {

    /**
     * Returns a list of [JournalOp]s such that applying them in order to a
     * mutable copy of [a] produces [b]. Returns an empty list if [a] and [b]
     * are byte-identical.
     */
    public fun diff(a: ByteArray, b: ByteArray): List<JournalOp> {
        if (a.contentEquals(b)) return emptyList()

        val prefix = commonPrefixLength(a, b)
        val suffix = commonSuffixLength(a, b, capacity = minOf(a.size - prefix, b.size - prefix))

        val aMidStart = prefix
        val aMidEnd = a.size - suffix
        val bMidStart = prefix
        val bMidEnd = b.size - suffix

        val aMidEmpty = aMidStart == aMidEnd
        val bMidEmpty = bMidStart == bMidEnd

        return when {
            aMidEmpty && bMidEmpty -> emptyList() // contentEquals already excluded this; defensive.
            aMidEmpty -> listOf(
                JournalOp.Insert(
                    offset = aMidStart.toLong(),
                    data = Base64.encode(b.copyOfRange(bMidStart, bMidEnd)),
                ),
            )
            bMidEmpty -> listOf(
                JournalOp.Delete(
                    start = aMidStart.toLong(),
                    end = aMidEnd.toLong(),
                ),
            )
            else -> listOf(
                JournalOp.Replace(
                    start = aMidStart.toLong(),
                    end = aMidEnd.toLong(),
                    data = Base64.encode(b.copyOfRange(bMidStart, bMidEnd)),
                ),
            )
        }
    }

    /**
     * Convenience: wraps [diff]'s output in a [JournalEntry] with the given
     * [timestamp]. Returns `null` if the diff is empty (no entry to record).
     */
    public fun diffAsEntry(a: ByteArray, b: ByteArray, timestamp: String): JournalEntry? {
        val ops = diff(a, b)
        return if (ops.isEmpty()) null else JournalEntry(timestamp, ops)
    }

    private fun commonPrefixLength(a: ByteArray, b: ByteArray): Int {
        val limit = minOf(a.size, b.size)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return i
    }

    private fun commonSuffixLength(a: ByteArray, b: ByteArray, capacity: Int): Int {
        var i = 0
        while (i < capacity && a[a.size - 1 - i] == b[b.size - 1 - i]) i++
        return i
    }
}
