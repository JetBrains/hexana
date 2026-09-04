package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [RowActionKind] exists so the two hosts can pick their own icon for an action. The default
 * is pinned because every row action predates kinds and must keep rendering as a run arrow.
 */
class RowActionKindTest {

    @Test
    fun `a row action defaults to the run kind`() {
        val action = RowActionDescriptor(label = "Run", tooltipProvider = { "Run" }, action = {})
        assertEquals(RowActionKind.Run, action.kind)
    }

    @Test
    fun `an explicit kind is carried through`() {
        val action = RowActionDescriptor(
            label = "Docs",
            tooltipProvider = { "Open the proposal documentation" },
            action = {},
            kind = RowActionKind.Documentation,
        )
        assertEquals(RowActionKind.Documentation, action.kind)
    }
}
