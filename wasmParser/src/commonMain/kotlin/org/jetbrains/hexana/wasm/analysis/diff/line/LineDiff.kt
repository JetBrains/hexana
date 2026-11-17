package org.jetbrains.hexana.wasm.analysis.diff.line

/** The role of a line in a [LineDiff] result. */
public enum class LineOpKind { SAME, ADDED, REMOVED }

/**
 * One line of a line-level diff. [leftIdx] is the source index in the base list
 * (null for [LineOpKind.ADDED]); [rightIdx] the index in the head list (null for
 * [LineOpKind.REMOVED]).
 */
public data class LineOp(
    val kind: LineOpKind,
    val text: String,
    val leftIdx: Int?,
    val rightIdx: Int?,
)

/**
 * Longest-common-subsequence line diff over two string lists. Emits, in reading
 * order, SAME for common lines and REMOVED/ADDED for divergences (REMOVED before
 * ADDED within a hunk, matching unified-diff convention).
 *
 * Memory is bounded: common prefix/suffix are matched in O(min) and excluded from
 * the DP, and if the remaining middle still exceeds [MAX_CELLS] cells (a degenerate
 * case — e.g. two multi-hundred-thousand-line generated functions) the middle falls
 * back to "all removed then all added" in O(n+m) instead of allocating an O(n·m)
 * matrix. Without this a single huge function allocated multi-gigabyte `int[]`s.
 */
public object LineDiff {

    /** ~8 MB matrix ceiling; beyond this the differing middle is not LCS-aligned. */
    private const val MAX_CELLS = 2_000_000L

    public fun diff(left: List<String>, right: List<String>): List<LineOp> {
        val n = left.size
        val m = right.size
        if (n == 0 && m == 0) return emptyList()

        // Match the common prefix and suffix outside the DP — most real diffs share
        // almost everything, so this shrinks the matrix to the genuinely-changed span.
        var prefix = 0
        while (prefix < n && prefix < m && left[prefix] == right[prefix]) prefix++
        var suffix = 0
        while (suffix < n - prefix && suffix < m - prefix && left[n - 1 - suffix] == right[m - 1 - suffix]) suffix++

        val out = ArrayList<LineOp>(n + m)
        for (i in 0 until prefix) out.add(LineOp(LineOpKind.SAME, left[i], i, i))

        val midLeftEnd = n - suffix
        val midRightEnd = m - suffix
        val midN = midLeftEnd - prefix
        val midM = midRightEnd - prefix
        if (midN > 0 || midM > 0) {
            val cells = (midN + 1).toLong() * (midM + 1).toLong()
            if (cells > MAX_CELLS) {
                for (i in prefix until midLeftEnd) out.add(LineOp(LineOpKind.REMOVED, left[i], i, null))
                for (j in prefix until midRightEnd) out.add(LineOp(LineOpKind.ADDED, right[j], null, j))
            } else {
                lcsMiddle(left, right, prefix, midLeftEnd, midRightEnd, out)
            }
        }

        for (k in 0 until suffix) {
            val i = midLeftEnd + k
            val j = midRightEnd + k
            out.add(LineOp(LineOpKind.SAME, left[i], i, j))
        }
        return out
    }

    /** LCS-aligns `left[prefix until leftEnd]` vs `right[prefix until rightEnd]`, appending to [out]. */
    private fun lcsMiddle(
        left: List<String>,
        right: List<String>,
        prefix: Int,
        leftEnd: Int,
        rightEnd: Int,
        out: MutableList<LineOp>,
    ) {
        val n = leftEnd - prefix
        val m = rightEnd - prefix
        // lcs[a][b] = LCS length of left[prefix+a .. leftEnd) and right[prefix+b .. rightEnd).
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (a in n - 1 downTo 0) {
            for (b in m - 1 downTo 0) {
                lcs[a][b] = if (left[prefix + a] == right[prefix + b]) {
                    lcs[a + 1][b + 1] + 1
                } else {
                    maxOf(lcs[a + 1][b], lcs[a][b + 1])
                }
            }
        }
        var a = 0
        var b = 0
        while (a < n && b < m) {
            val i = prefix + a
            val j = prefix + b
            when {
                left[i] == right[j] -> { out.add(LineOp(LineOpKind.SAME, left[i], i, j)); a++; b++ }
                lcs[a + 1][b] >= lcs[a][b + 1] -> { out.add(LineOp(LineOpKind.REMOVED, left[i], i, null)); a++ }
                else -> { out.add(LineOp(LineOpKind.ADDED, right[j], null, j)); b++ }
            }
        }
        while (a < n) { out.add(LineOp(LineOpKind.REMOVED, left[prefix + a], prefix + a, null)); a++ }
        while (b < m) { out.add(LineOp(LineOpKind.ADDED, right[prefix + b], null, prefix + b)); b++ }
    }
}
