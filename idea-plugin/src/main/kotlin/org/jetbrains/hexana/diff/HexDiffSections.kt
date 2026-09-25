package org.jetbrains.hexana.diff

import org.jetbrains.hexana.providers.StructureRange

/**
 * A structural region (section, segment, …) of one side of a [HexDiff], with how much of
 * it changed — what lets the hex diff say *which sections* were modified, not just which
 * bytes.
 *
 * @param colorIndex position in the side's offset-sorted section list; the view picks the
 *   tint by it, so adjacent sections always differ
 * @param firstChangedOffset first byte of the section that differs, `null` when unchanged
 */
internal class DiffSection(
    val offset: Long,
    val size: Long,
    val label: String,
    val colorIndex: Int,
    val changedBytes: Long,
    val firstChangedOffset: Long?,
) {
    val end: Long get() = offset + size
    val isModified: Boolean get() = changedBytes > 0
}

/**
 * The [ranges] of one side of [diff], sorted by offset and clipped to that side's
 * [sideSize], each with its changed-byte count. Scans only the changed blocks, and only
 * bytes both sides hold — bytes past the shorter side are changed by definition.
 */
internal fun diffSections(
    diff: HexDiff,
    ranges: List<StructureRange>,
    sideSize: Int,
    checkCanceled: () -> Unit = {},
): List<DiffSection> {
    val common = minOf(diff.leftSize, diff.rightSize).toLong()
    val bytesPerRow = diff.bytesPerRow.toLong()
    val sorted = ranges.filter { it.size > 0 && it.offset in 0 until sideSize }.sortedBy { it.offset }
    return sorted.mapIndexed { index, range ->
        checkCanceled()
        val start = range.offset
        val end = minOf(range.offset + range.size, sideSize.toLong())
        var changed = 0L
        var firstChanged: Long? = null
        // First block ending after the section start; blocks are sorted and disjoint.
        var b = diff.blockEnds.binarySearch(((start / bytesPerRow) + 1).toInt()).let { if (it >= 0) it else -it - 1 }
        while (b < diff.blockCount && diff.blockStarts[b] * bytesPerRow < end) {
            val from = maxOf(start, diff.blockStarts[b] * bytesPerRow)
            val to = minOf(end, diff.blockEnds[b] * bytesPerRow)
            for (i in from until minOf(to, common)) {
                if (diff.changeAt(i.toInt()) != ByteChange.NONE) {
                    changed++
                    if (firstChanged == null) firstChanged = i
                }
            }
            val oneSidedFrom = maxOf(from, common)
            if (to > oneSidedFrom) {
                changed += to - oneSidedFrom
                if (firstChanged == null) firstChanged = oneSidedFrom
            }
            b++
        }
        DiffSection(start, end - start, range.label, index, changed, firstChanged)
    }
}

/** The section of the offset-sorted [sections] holding [offset], or `null` in a gap. */
internal fun sectionAt(sections: List<DiffSection>, offset: Long): DiffSection? {
    var low = 0
    var high = sections.size - 1
    var found = -1
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (sections[mid].offset <= offset) {
            found = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return sections.getOrNull(found)?.takeIf { offset < it.end }
}

/** Labels of the offset-sorted [sections] overlapping `[from, to)`, in order. */
internal fun sectionLabelsIn(sections: List<DiffSection>, from: Long, to: Long): List<String> {
    // Sections do not nest in practice, so the one holding `from` (or the first after it) is where overlap starts.
    var i = firstStartingAtOrAfter(sections, from).let { if (it > 0 && sections[it - 1].end > from) it - 1 else it }
    val result = ArrayList<String>()
    while (i < sections.size && sections[i].offset < to) {
        result += sections[i].label
        i++
    }
    return result
}

private fun firstStartingAtOrAfter(sections: List<DiffSection>, offset: Long): Int {
    var low = 0
    var high = sections.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (sections[mid].offset < offset) low = mid + 1 else high = mid
    }
    return low
}

/** How a section differs between the two sides of the diff. */
internal enum class SectionStatus { UNCHANGED, MODIFIED, LEFT_ONLY, RIGHT_ONLY }

/**
 * One entry of the section bar: a section of either side, paired with the same-named
 * section of the other side when there is one. Both halves share [colorIndex], so a
 * section has one tint on both sides.
 */
internal class MergedSection(val label: String, val colorIndex: Int, val left: DiffSection?, val right: DiffSection?) {
    val status: SectionStatus = when {
        left == null -> SectionStatus.RIGHT_ONLY
        right == null -> SectionStatus.LEFT_ONLY
        left.isModified || right.isModified -> SectionStatus.MODIFIED
        else -> SectionStatus.UNCHANGED
    }

    val changedBytes: Long get() = maxOf(left?.changedBytes ?: 0, right?.changedBytes ?: 0)

    /** Where a click scrolls: the earliest change on either side, else where the section starts. */
    val target: Long
        get() = listOfNotNull(left?.firstChangedOffset, right?.firstChangedOffset).minOrNull()
            ?: minOf(left?.offset ?: Long.MAX_VALUE, right?.offset ?: Long.MAX_VALUE)
}

/** Both sides' sections, recolored so a pair shares a tint, plus the merged bar. */
internal class DiffSections(
    val left: List<DiffSection>,
    val right: List<DiffSection>,
    val merged: List<MergedSection>,
) {
    companion object {
        val EMPTY = DiffSections(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Pairs [left] and [right] sections by name — the n-th section called `.text` on one side
 * with the n-th on the other — in the left side's order, with right-only sections placed
 * after the section that precedes them on the right. Linear, since archives can have tens
 * of thousands of entries.
 */
internal fun mergeSections(left: List<DiffSection>, right: List<DiffSection>): DiffSections {
    fun keys(sections: List<DiffSection>): List<String> {
        val seen = HashMap<String, Int>()
        return sections.map { s -> "${s.label}\u0000${seen.merge(s.label, 1, Int::plus)}" }
    }
    val leftKeys = keys(left)
    val rightKeys = keys(right)
    val rightByKey = rightKeys.withIndex().associate { (i, key) -> key to right[i] }
    val leftKeySet = leftKeys.toHashSet()

    // Right-only sections, grouped under the last shared key before them (null: before any).
    val attached = HashMap<String?, MutableList<Int>>()
    var previousShared: String? = null
    for ((i, key) in rightKeys.withIndex()) {
        if (key in leftKeySet) previousShared = key else attached.getOrPut(previousShared) { ArrayList() } += i
    }

    val merged = ArrayList<MergedSection>(left.size + right.size)
    fun addRightOnly(after: String?) {
        for (i in attached[after].orEmpty()) merged += MergedSection(right[i].label, merged.size, null, right[i])
    }
    addRightOnly(null)
    for ((i, key) in leftKeys.withIndex()) {
        val pair = rightByKey[key]
        merged += MergedSection(left[i].label, merged.size, left[i], pair)
        if (pair != null) addRightOnly(key)
    }
    // DiffSection has identity equality, so these maps key on the exact instances.
    val leftColor = merged.filter { it.left != null }.associate { it.left!! to it.colorIndex }
    val rightColor = merged.filter { it.right != null }.associate { it.right!! to it.colorIndex }
    return DiffSections(
        left.map { it.recolored(leftColor.getValue(it)) },
        right.map { it.recolored(rightColor.getValue(it)) },
        merged,
    )
}

private fun DiffSection.recolored(colorIndex: Int) =
    DiffSection(offset, size, label, colorIndex, changedBytes, firstChangedOffset)
