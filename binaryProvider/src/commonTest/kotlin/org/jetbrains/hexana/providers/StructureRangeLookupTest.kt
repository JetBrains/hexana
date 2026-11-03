package org.jetbrains.hexana.providers

import kotlin.test.Test
import kotlin.test.assertEquals

class StructureRangeLookupTest {
    private fun range(offset: Long, size: Long, label: String) = StructureRange(offset, size, label)

    private fun StructureRangeLookup.owners(range: LongRange) = range.map { indexAt(it) }

    @Test
    fun disjointRangesResolveToTheirOwnIndexAndGapsToNone() {
        val lookup = StructureRangeLookup(listOf(range(0, 2, "a"), range(4, 2, "b")))
        assertEquals(listOf(0, 0, -1, -1, 1, 1, -1), lookup.owners(0L..6L))
    }

    @Test
    fun aNestedRangeWinsInsideAndTheEnclosingOneResumesAfterIt() {
        // Before, inside and after an overlay nested in a base range.
        val lookup = StructureRangeLookup(listOf(range(0, 10, "base"), range(3, 2, "overlay")))
        assertEquals(listOf(0, 0, 0, 1, 1, 0, 0, 0, 0, 0, -1), lookup.owners(0L..10L))
    }

    @Test
    fun ofRangesStartingTogetherTheLaterInTheListWins() {
        val lookup = StructureRangeLookup(listOf(range(0, 8, "base"), range(0, 4, "overlay")))
        assertEquals(listOf(1, 1, 1, 1, 0, 0, 0, 0, -1), lookup.owners(0L..8L))
    }

    @Test
    fun aStraddlingRangeOwnsTheBytesFromItsStart() {
        val lookup = StructureRangeLookup(listOf(range(0, 4, "base"), range(2, 4, "overlay")))
        assertEquals(listOf(0, 0, 1, 1, 1, 1, -1), lookup.owners(0L..6L))
    }

    @Test
    fun anEnclosingRangeResumesAfterSeveralInnerOnesEnd() {
        val lookup = StructureRangeLookup(
            listOf(range(0, 20, "outer"), range(2, 10, "middle"), range(4, 2, "inner"), range(14, 2, "late")),
        )
        assertEquals(
            listOf(0, 0, 1, 1, 2, 2, 1, 1, 1, 1, 1, 1, 0, 0, 3, 3, 0, 0, 0, 0, -1),
            lookup.owners(0L..20L),
        )
    }

    @Test
    fun emptyRangesOwnNothing() {
        val lookup = StructureRangeLookup(listOf(range(0, 0, "empty"), range(0, 2, "a")))
        assertEquals(listOf(1, 1, -1), lookup.owners(0L..2L))
        assertEquals(-1, StructureRangeLookup.EMPTY.indexAt(0))
    }
}
