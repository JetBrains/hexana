package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.wasm.analysis.diff.line.LineOp
import org.jetbrains.hexana.wasm.analysis.diff.line.LineOpKind

/**
 * A side-by-side rendering of one [DiffItemRef]: base on the left, head on the right.
 *
 * @param emptyMessage shown instead of [rows] when there is nothing to render (e.g. the
 *   item is not a code-section function)
 */
class SideBySideDiff(
    val title: String,
    val rows: List<SideBySideRow>,
    val emptyMessage: String? = null,
)

/** One aligned row. A `null` side is blank; [leftRemoved]/[rightAdded] mark changed lines. */
data class SideBySideRow(
    val left: String?,
    val leftRemoved: Boolean,
    val right: String?,
    val rightAdded: Boolean,
)

/**
 * Aligns flat line ops into side-by-side rows: SAME lines fill both columns; within a
 * changed hunk, removed and added lines are zipped so a modified line shows old | new
 * on one row, with any surplus occupying a single side.
 */
fun sideBySideRows(ops: List<LineOp>): List<SideBySideRow> = sideBySideRows(ops, null, null)

/**
 * [sideBySideRows] over ops diffed on comparison keys, displaying different text per side:
 * [leftText]/[rightText] map an op's `leftIdx`/`rightIdx` to the line shown. Lets a diff
 * that ignores addresses still show them — a SAME row then has two different texts.
 */
fun sideBySideRows(ops: List<LineOp>, leftText: ((Int) -> String)?, rightText: ((Int) -> String)?): List<SideBySideRow> {
    val out = ArrayList<SideBySideRow>(ops.size)
    val removed = ArrayList<String>()
    val added = ArrayList<String>()
    fun flushHunk() {
        for (i in 0 until maxOf(removed.size, added.size)) {
            val l = removed.getOrNull(i)
            val r = added.getOrNull(i)
            out.add(SideBySideRow(l, l != null, r, r != null))
        }
        removed.clear()
        added.clear()
    }
    for (op in ops) {
        when (op.kind) {
            LineOpKind.REMOVED -> removed.add(op.leftIdx?.let { leftText?.invoke(it) } ?: op.text)
            LineOpKind.ADDED -> added.add(op.rightIdx?.let { rightText?.invoke(it) } ?: op.text)
            LineOpKind.SAME -> {
                flushHunk()
                val l = op.leftIdx?.let { leftText?.invoke(it) } ?: op.text
                val r = op.rightIdx?.let { rightText?.invoke(it) } ?: op.text
                out.add(SideBySideRow(l, false, r, false))
            }
        }
    }
    flushHunk()
    return out
}
