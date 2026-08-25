package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BinaryAnchorResolutionTest {

    // ---- instrOrdinalForOffset ----

    @Test
    fun ordinalForOffset_exactMatch_returnsOrdinal() {
        val offsets = listOf(10L, 20L, 30L)
        assertEquals(0, BinaryAnchorResolution.instrOrdinalForOffset(offsets, 10L))
        assertEquals(1, BinaryAnchorResolution.instrOrdinalForOffset(offsets, 20L))
        assertEquals(2, BinaryAnchorResolution.instrOrdinalForOffset(offsets, 30L))
    }

    @Test
    fun ordinalForOffset_noMatch_returnsNull() {
        val offsets = listOf(10L, 20L, 30L)
        assertNull(BinaryAnchorResolution.instrOrdinalForOffset(offsets, 15L))
        assertNull(BinaryAnchorResolution.instrOrdinalForOffset(offsets, 0L))
        assertNull(BinaryAnchorResolution.instrOrdinalForOffset(offsets, 31L))
    }

    @Test
    fun ordinalForOffset_emptyList_returnsNull() {
        assertNull(BinaryAnchorResolution.instrOrdinalForOffset(emptyList(), 0L))
        assertNull(BinaryAnchorResolution.instrOrdinalForOffset(emptyList(), 100L))
    }

    @Test
    fun ordinalForOffset_matchIsExactNotNearest() {
        // Offset 11 is between 10 and 20 — must return null, not 0 or 1.
        assertNull(BinaryAnchorResolution.instrOrdinalForOffset(listOf(10L, 20L), 11L))
    }

    // ---- offsetForInstrOrdinal ----

    @Test
    fun offsetForOrdinal_validOrdinal_returnsOffset() {
        val offsets = listOf(100L, 200L, 300L)
        assertEquals(100L, BinaryAnchorResolution.offsetForInstrOrdinal(offsets, 0))
        assertEquals(200L, BinaryAnchorResolution.offsetForInstrOrdinal(offsets, 1))
        assertEquals(300L, BinaryAnchorResolution.offsetForInstrOrdinal(offsets, 2))
    }

    @Test
    fun offsetForOrdinal_outOfRange_returnsNull() {
        val offsets = listOf(100L, 200L)
        assertNull(BinaryAnchorResolution.offsetForInstrOrdinal(offsets, 2))
        assertNull(BinaryAnchorResolution.offsetForInstrOrdinal(offsets, -1))
    }

    @Test
    fun offsetForOrdinal_emptyList_returnsNull() {
        assertNull(BinaryAnchorResolution.offsetForInstrOrdinal(emptyList(), 0))
    }

    // ---- round-trip ----

    @Test
    fun roundTrip_ordinalToOffsetToOrdinal() {
        val offsets = listOf(5L, 10L, 15L, 20L)
        for (ordinal in offsets.indices) {
            val offset = BinaryAnchorResolution.offsetForInstrOrdinal(offsets, ordinal)!!
            val recovered = BinaryAnchorResolution.instrOrdinalForOffset(offsets, offset)
            assertEquals(ordinal, recovered, "round-trip failed for ordinal $ordinal")
        }
    }

    @Test
    fun roundTrip_offsetToOrdinalToOffset() {
        val offsets = listOf(7L, 14L, 21L)
        for (offset in offsets) {
            val ordinal = BinaryAnchorResolution.instrOrdinalForOffset(offsets, offset)!!
            val recovered = BinaryAnchorResolution.offsetForInstrOrdinal(offsets, ordinal)
            assertEquals(offset, recovered, "round-trip failed for offset $offset")
        }
    }
}
