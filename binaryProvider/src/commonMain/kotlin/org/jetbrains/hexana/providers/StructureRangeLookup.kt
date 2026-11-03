package org.jetbrains.hexana.providers

/**
 * Finds the [StructureRange] covering a byte offset in [ranges], which must be sorted by
 * offset, even when the ranges overlap.
 *
 * Built-in parsers report disjoint ranges, but a scripted provider's regions are an overlay
 * on its base parser's, and may nest inside or straddle them. A plain binary search for the
 * last range starting at or before an offset then answers wrongly: past the end of a nested
 * range it finds nothing, although the enclosing range still covers the byte.
 *
 * Where ranges overlap, the one starting last wins — the innermost of nested ranges — and of
 * ranges starting at the same offset, the later in the list (so overlay ranges appended
 * after the base ones and sorted stably take precedence).
 *
 * The ranges are flattened once into disjoint segments, so [indexAt] stays a binary search.
 */
class StructureRangeLookup(private val ranges: List<StructureRange>) {
    private val segmentStarts: LongArray
    private val segmentEnds: LongArray
    private val segmentOwners: IntArray

    init {
        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        val owners = ArrayList<Int>()
        fun end(index: Int) = ranges[index].offset + ranges[index].size

        // Ranges that have started, latest on top. One that has ended is dropped once it
        // surfaces; until then a later range above it owns the bytes anyway.
        val open = ArrayList<Int>()
        var next = 0
        var position = Long.MIN_VALUE
        while (true) {
            while (open.isNotEmpty() && end(open.last()) <= position) open.removeAt(open.lastIndex)
            if (next < ranges.size && ranges[next].offset <= position) {
                if (ranges[next].size > 0) open.add(next)
                next++
                continue
            }
            val nextStart = if (next < ranges.size) ranges[next].offset else null
            if (open.isEmpty()) {
                position = nextStart ?: break
                continue
            }
            val owner = open.last()
            val segmentEnd = if (nextStart != null) minOf(end(owner), nextStart) else end(owner)
            if (owners.isNotEmpty() && owners.last() == owner && ends.last() == position) {
                ends[ends.lastIndex] = segmentEnd
            } else {
                starts.add(position)
                ends.add(segmentEnd)
                owners.add(owner)
            }
            position = segmentEnd
        }
        segmentStarts = starts.toLongArray()
        segmentEnds = ends.toLongArray()
        segmentOwners = owners.toIntArray()
    }

    /** Whether no range covers any byte. */
    val isEmpty: Boolean get() = segmentStarts.isEmpty()

    /** The index into the ranges of the one covering [offset], or -1 when none does. */
    fun indexAt(offset: Long): Int {
        var lo = 0
        var hi = segmentStarts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                offset < segmentStarts[mid] -> hi = mid - 1
                offset >= segmentEnds[mid] -> lo = mid + 1
                else -> return segmentOwners[mid]
            }
        }
        return -1
    }

    companion object {
        val EMPTY = StructureRangeLookup(emptyList())
    }
}
