package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Byte-by-byte comparison of two binaries at the same offsets, grouped into hex rows.
 *
 * The comparison is positional — byte `i` of [left] is compared with byte `i` of [right] —
 * which is what a hex diff shows and what stays cheap on multi-megabyte binaries. An
 * insertion therefore shows as every following row changed, not as a shifted block.
 * Bytes past the end of the shorter side are counted as changed (removed / added).
 *
 * @param blockStarts first row of each run of consecutive changed rows, ascending
 * @param blockEnds exclusive end row of the run at the same index
 */
class HexDiff(
    val left: CommonByteBuffer,
    val right: CommonByteBuffer,
    val bytesPerRow: Int,
    val blockStarts: IntArray,
    val blockEnds: IntArray,
    val changedBytes: Long,
) {
    val leftSize: Int get() = left.limit()
    val rightSize: Int get() = right.limit()
    val rowCount: Int = rowsFor(maxOf(leftSize, rightSize), bytesPerRow)
    val blockCount: Int get() = blockStarts.size
    val isIdentical: Boolean get() = changedBytes == 0L

    /** How byte [offset] differs; [ByteChange.NONE] also for an offset past both ends. */
    fun changeAt(offset: Int): ByteChange = when {
        offset >= leftSize && offset >= rightSize -> ByteChange.NONE
        offset >= rightSize -> ByteChange.REMOVED
        offset >= leftSize -> ByteChange.ADDED
        left[offset] != right[offset] -> ByteChange.MODIFIED
        else -> ByteChange.NONE
    }

    fun isRowChanged(row: Int): Boolean {
        val i = blockStarts.binarySearchCommon(row).let { if (it >= 0) it else -it - 2 }
        return i >= 0 && row < blockEnds[i]
    }

    companion object {
        /**
         * Compares [left] and [right]. Blocking and linear in the shorter side; rows past it
         * are changed without being read. [checkCanceled] is called between rows.
         */
        fun compute(
            left: CommonByteBuffer,
            right: CommonByteBuffer,
            bytesPerRow: Int = 16,
            checkCanceled: () -> Unit = {},
        ): HexDiff {
            val leftSize = left.limit()
            val rightSize = right.limit()
            val common = minOf(leftSize, rightSize)
            val rowCount = rowsFor(maxOf(leftSize, rightSize), bytesPerRow)
            val starts = IntArrayBuilder()
            val ends = IntArrayBuilder()
            var changedBytes = (maxOf(leftSize, rightSize) - common).toLong()
            var blockStart = -1
            for (row in 0 until rowCount) {
                if (row and 0xFFF == 0) checkCanceled()
                val from = row * bytesPerRow
                val to = minOf(from + bytesPerRow, common)
                // A row reaching past the shorter side holds bytes only one side has.
                var changed = leftSize != rightSize && from + bytesPerRow > common
                for (i in from until to) {
                    if (left[i] != right[i]) {
                        changed = true
                        changedBytes++
                    }
                }
                if (changed && blockStart < 0) {
                    blockStart = row
                } else if (!changed && blockStart >= 0) {
                    starts.add(blockStart)
                    ends.add(row)
                    blockStart = -1
                }
            }
            if (blockStart >= 0) {
                starts.add(blockStart)
                ends.add(rowCount)
            }
            return HexDiff(left, right, bytesPerRow, starts.toArray(), ends.toArray(), changedBytes)
        }

        private fun rowsFor(size: Int, bytesPerRow: Int): Int = (size + bytesPerRow - 1) / bytesPerRow
    }
}

enum class ByteChange { NONE, MODIFIED, REMOVED, ADDED }

/**
 * What the hex diff list displays: runs of rows, with long unchanged stretches between
 * changed blocks collapsed into a single fold item — the hex counterpart of the text diff's
 * "N unchanged lines" folding.
 *
 * Each segment is a half-open row range `[segmentStart, segmentEnd)`; a folded segment is
 * one list item, any other segment is one item per row.
 */
class HexDiffLayout private constructor(
    private val segmentStart: IntArray,
    private val segmentEnd: IntArray,
    private val segmentFolded: BooleanArray,
    /** List index of the first item of each segment. */
    private val segmentFirstItem: IntArray,
    val itemCount: Int,
) {
    sealed interface Item {
        /** Stable list key: a row by its index, a fold by its start row (negative, so the two never collide). */
        val key: Long

        data class Row(val row: Int) : Item {
            override val key: Long get() = row.toLong()
        }

        data class Fold(val startRow: Int, val endRow: Int) : Item {
            override val key: Long get() = -1L - startRow
        }
    }

    fun itemAt(index: Int): Item {
        val s = segmentFirstItem.binarySearchCommon(index).let { if (it >= 0) it else -it - 2 }
        return if (segmentFolded[s]) {
            Item.Fold(segmentStart[s], segmentEnd[s])
        } else {
            Item.Row(segmentStart[s] + (index - segmentFirstItem[s]))
        }
    }

    /** List index showing [row]: its own item, or the fold hiding it. */
    fun itemOf(row: Int): Int {
        if (segmentStart.isEmpty()) return 0
        val s = segmentStart.binarySearchCommon(row).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
        return if (segmentFolded[s]) segmentFirstItem[s] else segmentFirstItem[s] + (row - segmentStart[s])
    }

    companion object {
        /** Rows of unchanged context kept visible on each side of a changed block. */
        const val CONTEXT_ROWS: Int = 3

        /** Shorter unchanged stretches are not worth a fold item and stay visible. */
        private const val MIN_FOLDED_ROWS: Int = 4

        /**
         * @param collapse fold unchanged stretches; `false` shows every row
         * @param expanded start rows of folds the user expanded; they stay visible
         */
        fun build(diff: HexDiff, collapse: Boolean, expanded: Set<Int> = emptySet()): HexDiffLayout {
            val rowCount = diff.rowCount
            val starts = IntArrayBuilder()
            val ends = IntArrayBuilder()
            val folded = ArrayList<Boolean>()

            fun add(start: Int, end: Int, fold: Boolean) {
                if (start >= end) return
                // Merge adjacent visible runs so the item count per segment stays row-exact.
                if (!fold && folded.isNotEmpty() && !folded.last() && ends.last() == start) {
                    ends.setLast(end)
                    return
                }
                starts.add(start)
                ends.add(end)
                folded.add(fold)
            }

            fun addGap(start: Int, end: Int) {
                val fold = collapse && end - start >= MIN_FOLDED_ROWS && start !in expanded
                add(start, end, fold)
            }

            if (!collapse) {
                add(0, rowCount, fold = false)
            } else {
                var cursor = 0
                for (b in 0 until diff.blockCount) {
                    val visibleStart = maxOf(cursor, diff.blockStarts[b] - CONTEXT_ROWS)
                    val visibleEnd = minOf(rowCount, diff.blockEnds[b] + CONTEXT_ROWS)
                    addGap(cursor, visibleStart)
                    add(visibleStart, visibleEnd, fold = false)
                    cursor = visibleEnd
                }
                addGap(cursor, rowCount)
            }

            val firstItem = IntArray(starts.size)
            var items = 0
            for (i in 0 until starts.size) {
                firstItem[i] = items
                items += if (folded[i]) 1 else ends[i] - starts[i]
            }
            return HexDiffLayout(starts.toArray(), ends.toArray(), folded.toBooleanArray(), firstItem, items)
        }
    }
}

/** Growable `IntArray`, so per-row bookkeeping on multi-million-row diffs does not box. */
private class IntArrayBuilder {
    private var data = IntArray(16)
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]
    fun last(): Int = data[size - 1]
    fun setLast(value: Int) {
        data[size - 1] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}

/**
 * `java.util.Arrays.binarySearch` semantics for a sorted array, in common code: the index of
 * [key], or `-(insertion point) - 1` when absent.
 */
internal fun IntArray.binarySearchCommon(key: Int): Int {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        val value = this[mid]
        when {
            value < key -> low = mid + 1
            value > key -> high = mid - 1
            else -> return mid
        }
    }
    return -(low + 1)
}
