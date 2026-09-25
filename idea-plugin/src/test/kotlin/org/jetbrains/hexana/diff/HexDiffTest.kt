package org.jetbrains.hexana.diff

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.io.toCommonByteBuffer

class HexDiffTest : UsefulTestCase() {

    fun testIdenticalInputsHaveNoBlocksAndFoldEntirely() {
        val bytes = ByteArray(16 * 100) { it.toByte() }
        val diff = diff(bytes, bytes.copyOf())

        assertTrue(diff.isIdentical)
        assertEquals(0, diff.blockCount)
        val layout = HexDiffLayout.build(diff, collapse = true)
        assertEquals(1, layout.itemCount)
        assertEquals(HexDiffLayout.Item.Fold(0, 100), layout.itemAt(0))
    }

    fun testChangedBytesFormBlocksAndUnchangedStretchesFold() {
        val left = ByteArray(16 * 100)
        val right = left.copyOf()
        right[16 * 10 + 3] = 1 // row 10
        right[16 * 11 + 0] = 1 // row 11, same block
        right[16 * 50 + 15] = 1 // row 50

        val diff = diff(left, right)
        assertEquals(3L, diff.changedBytes)
        assertOrderedEquals(diff.blockStarts.toList(), listOf(10, 50))
        assertOrderedEquals(diff.blockEnds.toList(), listOf(12, 51))
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

    fun testExpandedFoldAndNoCollapseShowEveryRow() {
        val left = ByteArray(16 * 40)
        val right = left.copyOf().also { it[16 * 20] = 7 }
        val diff = diff(left, right)

        assertEquals(40, HexDiffLayout.build(diff, collapse = false).itemCount)
        val expanded = HexDiffLayout.build(diff, collapse = true, expanded = setOf(0))
        assertEquals(HexDiffLayout.Item.Row(0), expanded.itemAt(0))
        assertTrue((0 until expanded.itemCount).map(expanded::itemAt).last() is HexDiffLayout.Item.Fold)
    }

    fun testShortUnchangedStretchStaysVisible() {
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

    fun testSizeDifferenceCountsTailAsChanged() {
        val left = ByteArray(20) { 1 }
        val right = ByteArray(40) { 1 }
        val diff = diff(left, right)

        assertEquals(20L, diff.changedBytes)
        assertEquals(3, diff.rowCount)
        // Row 0 is equal; row 1 holds bytes 16..19 on both sides and 20..31 only on the right.
        assertOrderedEquals(diff.blockStarts.toList(), listOf(1))
        assertOrderedEquals(diff.blockEnds.toList(), listOf(3))
        assertEquals(ByteChange.NONE, diff.changeAt(19))
        assertEquals(ByteChange.ADDED, diff.changeAt(20))
        assertEquals(ByteChange.REMOVED, diff(right, left).changeAt(39))
    }

    fun testEmptySideIsEntirelyAdded() {
        val diff = diff(ByteArray(0), ByteArray(33))
        assertEquals(33L, diff.changedBytes)
        assertOrderedEquals(diff.blockStarts.toList(), listOf(0))
        assertOrderedEquals(diff.blockEnds.toList(), listOf(3))
    }

    private fun diff(left: ByteArray, right: ByteArray) =
        HexDiff.compute(left.toCommonByteBuffer(), right.toCommonByteBuffer())
}
