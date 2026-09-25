package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.RowSelectionData
import org.jetbrains.hexana.shared.RowTint
import org.jetbrains.hexana.shared.TableTabChildRows
import org.jetbrains.hexana.shared.TableTabDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TableTabDiffTest {

    private fun table(name: String, columns: List<String>, rows: List<List<String>>, tree: Boolean = false) =
        TableTabDescriptor(
            name = name,
            columns = columns.mapIndexed { c, column -> ColumnDescriptor(column) { CellValue(rows[it][c]) } },
            rowCount = rows.size,
            rowSelectionProvider = { RowSelectionData(offset = it * 10, size = 10) },
            childrenProvider = if (tree) ({ _: Int -> null as TableTabChildRows? }) else null,
        )

    private fun TableTabDescriptor.cell(row: Int, column: Int) = columns[column].cellProvider(row).representation

    private fun TableTabDescriptor.tints() = (0 until rowCount).map { rowTintProvider!!(it) }

    @Test
    fun rowsArePairedByTheirMostDiscriminatingColumn() {
        // Addresses shift between builds; names do not.
        val left = table(
            "Sections",
            listOf("Address", "Name", "Size"),
            listOf(listOf("0x100", ".text", "64"), listOf("0x140", ".data", "16"), listOf("0x150", ".old", "8")),
        )
        val right = table(
            "Sections",
            listOf("Address", "Name", "Size"),
            listOf(listOf("0x100", ".text", "80"), listOf("0x150", ".data", "16"), listOf("0x160", ".bss", "4")),
        )

        val diff = TableTabDiffer.diff("Sections", left, right)
        val table = diff.descriptor

        // `.bss` follows `.data`, its predecessor on the right; `.old` keeps its left position.
        assertEquals(listOf(".text", ".data", ".bss", ".old"), (0 until table.rowCount).map { table.cell(it, 1) })
        assertEquals(listOf(RowTint.Modified, RowTint.Modified, RowTint.Added, RowTint.Removed), table.tints())
        assertEquals("64 → 80", table.cell(0, 2))
        assertEquals("0x100", table.cell(0, 0)) // unchanged cell shown once
        assertEquals(Triple(1, 1, 2), Triple(diff.added, diff.removed, diff.modified))
    }

    @Test
    fun changedRowsWithADuplicatedKeyPairInOrder() {
        // Two local symbols named `helper`, both moved; `main` identifies Name as the key column.
        val columns = listOf("Name", "Address")
        val left = table("Symbols", columns, listOf(listOf("main", "0x10"), listOf("helper", "0x20"), listOf("helper", "0x30")))
        val right = table("Symbols", columns, listOf(listOf("main", "0x14"), listOf("helper", "0x24"), listOf("helper", "0x34")))

        val table = TableTabDiffer.diff("Symbols", left, right).descriptor

        assertEquals(listOf(RowTint.Modified, RowTint.Modified, RowTint.Modified), table.tints())
        assertEquals(listOf("0x20 → 0x24", "0x30 → 0x34"), listOf(table.cell(1, 1), table.cell(2, 1)))
    }

    @Test
    fun identicalRowsStayUntintedAndDuplicatesPairInOrder() {
        val rows = listOf(listOf("a", "1"), listOf("a", "1"), listOf("b", "2"))
        val diff = TableTabDiffer.diff("T", table("T", listOf("K", "V"), rows), table("T", listOf("K", "V"), rows.take(2)))

        assertEquals(listOf(null, null, RowTint.Removed), diff.descriptor.tints())
        assertEquals(1, diff.changed)
    }

    @Test
    fun rightOnlyRowsFollowTheirRightSidePredecessor() {
        val left = table("T", listOf("Name"), listOf(listOf("a"), listOf("c")))
        val right = table("T", listOf("Name"), listOf(listOf("new"), listOf("a"), listOf("b"), listOf("c")))

        val table = TableTabDiffer.diff("T", left, right).descriptor

        assertEquals(listOf("new", "a", "b", "c"), (0 until table.rowCount).map { table.cell(it, 0) })
        assertEquals(listOf(RowTint.Added, null, RowTint.Added, null), table.tints())
    }

    @Test
    fun tabsPairByNameIgnoringCountsAndSkipTrees() {
        val diffs = TableTabDiffer.diffTabs(
            listOf(
                table("Functions (2)", listOf("Name"), listOf(listOf("f"), listOf("g"))),
                table("Tree", listOf("Name"), emptyList(), tree = true),
            ),
            listOf(
                table("Functions (3)", listOf("Name"), listOf(listOf("f"), listOf("g"), listOf("h"))),
                table("Only Right", listOf("Name"), listOf(listOf("x"))),
            ),
        )

        assertEquals(listOf("Functions", "Only Right"), diffs.map { it.name })
        assertEquals(1, diffs[0].added)
        assertEquals(listOf(RowTint.Added), diffs[1].descriptor.tints())
    }

    @Test
    fun rowClickReportsBothSidesSelections() {
        val left = table("T", listOf("Name"), listOf(listOf("a"), listOf("b")))
        val right = table("T", listOf("Name"), listOf(listOf("b")))
        var clicked: Pair<Int?, Int?>? = null

        val table = TableTabDiffer.diff("T", left, right) { l, r -> clicked = l?.offset to r?.offset }.descriptor
        table.rowClickAction!!(0) // "a", left only
        assertEquals(0 to null, clicked)
        table.rowClickAction!!(1) // "b", left row 1 / right row 0
        assertEquals(10 to 0, clicked)
        assertNull(table.rowTintProvider!!(1))
    }
}
