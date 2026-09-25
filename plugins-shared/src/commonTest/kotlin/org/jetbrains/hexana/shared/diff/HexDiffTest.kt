package org.jetbrains.hexana.shared.diff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.hexana.io.toCommonByteBuffer

class HexDiffTest {

    @Test
    fun identicalInputsHaveNoBlocksAndFoldEntirely() {
        val bytes = ByteArray(16 * 100) { it.toByte() }
        val diff = diff(bytes, bytes.copyOf())

        assertTrue(diff.isIdentical)
        assertEquals(0, diff.blockCount)
        val layout = HexDiffLayout.build(diff, collapse = true)
        assertEquals(1, layout.itemCount)
        assertEquals(HexDiffLayout.Item.Fold(0, 100), layout.itemAt(0))
    }

    @Test
    fun changedBytesFormBlocksAndUnchangedStretchesFold() {
        val left = ByteArray(16 * 100)
        val right = left.copyOf()
        right[16 * 10 + 3] = 1 // row 10
        right[16 * 11 + 0] = 1 // row 11, same block
        right[16 * 50 + 15] = 1 // row 50

        val diff = diff(left, right)
        assertEquals(3L, diff.changedBytes)
        assertEquals(diff.blockStarts.toList(), listOf(10, 50))
        assertEquals(diff.blockEnds.toList(), listOf(12, 51))
        assertEquals(ByteChange.MODIFIED, diff.changeAt(16 * 10 + 3))
        assertEquals(ByteChange.NONE, diff.changeAt(16 * 10 + 4))

        val context = HexDiffLayout.CONTEXT_ROWS
        val layout = HexDiffLayout.build(diff, collapse = true)
        val items = (0 until layout.itemCount).map(layout::itemAt)
        assertEquals(HexDiffLayout.Item.Fold(0, 10 - context), items.first())
        assertTrue(HexDiffLayout.Item.Fold(12 + context, 50 - context) in items)
        assertEquals(HexDiffLayout.Item.Fold(51 + context, 100), items.last())
        // Every visible row is listed once, in order, with context around each block.
        val rows = items.filterIsInstance<HexDiffLayout.Item.Row>().map { it.row }
        assertEquals(((10 - context) until (12 + context)) + ((50 - context) until (51 + context)), rows)
        assertEquals(layout.itemOf(50), items.indexOf(HexDiffLayout.Item.Row(50)))
    }

    @Test
    fun expandedFoldAndNoCollapseShowEveryRow() {
        val left = ByteArray(16 * 40)
        val right = left.copyOf().also { it[16 * 20] = 7 }
        val diff = diff(left, right)

        assertEquals(40, HexDiffLayout.build(diff, collapse = false).itemCount)
        val expanded = HexDiffLayout.build(diff, collapse = true, expanded = setOf(0))
        assertEquals(HexDiffLayout.Item.Row(0), expanded.itemAt(0))
        assertTrue((0 until expanded.itemCount).map(expanded::itemAt).last() is HexDiffLayout.Item.Fold)
    }

    @Test
    fun listKeysAreUniqueAndSurviveExpandingAFold() {
        val left = ByteArray(16 * 60)
        val right = left.copyOf().also { it[16 * 30] = 1 }
        val diff = diff(left, right)
        val collapsed = HexDiffLayout.build(diff, collapse = true)
        val expanded = HexDiffLayout.build(diff, collapse = true, expanded = setOf(0))

        for (layout in listOf(collapsed, expanded, HexDiffLayout.build(diff, collapse = false))) {
            val keys = (0 until layout.itemCount).map { layout.itemAt(it).key }
            assertEquals(keys.size, keys.toSet().size)
        }
        // The changed row keeps its key whichever folds are open, which is what holds the scroll position.
        val changedKey = HexDiffLayout.Item.Row(30).key
        assertEquals(changedKey, collapsed.itemAt(collapsed.itemOf(30)).key)
        assertEquals(changedKey, expanded.itemAt(expanded.itemOf(30)).key)
        assertFalse(collapsed.itemAt(0).key == expanded.itemAt(0).key) // fold 0 vs row 0
    }

    @Test
    fun shortUnchangedStretchStaysVisible() {
        val left = ByteArray(16 * 20)
        val right = left.copyOf().also {
            it[16 * 5] = 1
            it[16 * 12] = 1 // 6 unchanged rows between, all covered by context
        }
        val layout = HexDiffLayout.build(diff(left, right), collapse = true)
        val items = (0 until layout.itemCount).map(layout::itemAt)
        // Rows 0..1 are too few to fold; rows 16..19 are enough.
        assertEquals((0 until 16).toList(), items.filterIsInstance<HexDiffLayout.Item.Row>().map { it.row })
        assertEquals(HexDiffLayout.Item.Fold(16, 20), items.last())
    }

    @Test
    fun sizeDifferenceCountsTailAsChanged() {
        val left = ByteArray(20) { 1 }
        val right = ByteArray(40) { 1 }
        val diff = diff(left, right)

        assertEquals(20L, diff.changedBytes)
        assertEquals(3, diff.rowCount)
        // Row 0 is equal; row 1 holds bytes 16..19 on both sides and 20..31 only on the right.
        assertEquals(diff.blockStarts.toList(), listOf(1))
        assertEquals(diff.blockEnds.toList(), listOf(3))
        assertEquals(ByteChange.NONE, diff.changeAt(19))
        assertEquals(ByteChange.ADDED, diff.changeAt(20))
        assertEquals(ByteChange.REMOVED, diff(right, left).changeAt(39))
    }

    @Test
    fun emptySideIsEntirelyAdded() {
        val diff = diff(ByteArray(0), ByteArray(33))
        assertEquals(33L, diff.changedBytes)
        assertEquals(diff.blockStarts.toList(), listOf(0))
        assertEquals(diff.blockEnds.toList(), listOf(3))
    }

    private fun diff(left: ByteArray, right: ByteArray) =
        HexDiff.compute(left.toCommonByteBuffer(), right.toCommonByteBuffer())
}
