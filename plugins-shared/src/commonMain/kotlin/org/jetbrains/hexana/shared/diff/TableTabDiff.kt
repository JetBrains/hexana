package org.jetbrains.hexana.shared.diff

import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.PredefinedFilter
import org.jetbrains.hexana.shared.RowSelectionData
import org.jetbrains.hexana.shared.RowTint
import org.jetbrains.hexana.shared.TableTabDescriptor

/**
 * One table tab compared across two versions of a binary: a single table listing both
 * sides' rows, tinted by how each changed, with changed cells shown as `old → new`.
 *
 * @param descriptor the merged table, ready for any host's table renderer
 */
class TableTabDiff(
    val name: String,
    val descriptor: TableTabDescriptor,
    val added: Int,
    val removed: Int,
    val modified: Int,
) {
    val changed: Int get() = added + removed + modified
}

/** Where a merged row came from: row indices on each side (`null` where absent) and its tint. */
class TableRowPair(val left: Int?, val right: Int?, val tint: RowTint?)

/**
 * Diffs the *flat* table tabs a [org.jetbrains.hexana.shared.TableTabsProvider] produces for
 * two binaries — the tab-level counterpart of the hex diff, so a changed section header or
 * symbol shows as a changed row, not as a run of changed bytes.
 *
 * Tabs are paired by name, ignoring a trailing row count (`Functions (12)`); tree tables are
 * skipped. Rows need no key column:
 * 1. rows identical in every column are paired as unchanged, n-th copy with n-th copy;
 * 2. the rest are paired as modified through a column whose values are unique on both
 *    sides — the one whose pairs agree on the most other cells (a name, typically:
 *    addresses and sizes shift between builds, names do not), repeated while it pairs
 *    anything; rows whose value in that key column is duplicated then pair in order;
 * 3. what is left is removed (left only) or added (right only).
 */
object TableTabDiffer {
    /**
     * @param onRowClick called with both sides' selections for a clicked row, so the host can
     *   scroll its view to the row's bytes; `null` leaves rows inert
     */
    fun diffTabs(
        left: List<TableTabDescriptor>,
        right: List<TableTabDescriptor>,
        onRowClick: ((left: RowSelectionData?, right: RowSelectionData?) -> Unit)? = null,
    ): List<TableTabDiff> {
        val leftByName = left.filter(::isFlat).associateBy { baseName(it.name) }
        val rightByName = right.filter(::isFlat).associateBy { baseName(it.name) }
        val names = (leftByName.keys + rightByName.keys).distinct()
        return names.map { diff(it, leftByName[it], rightByName[it], onRowClick) }
    }

    fun isFlat(tab: TableTabDescriptor): Boolean = tab.childrenProvider == null

    fun diff(
        name: String,
        left: TableTabDescriptor?,
        right: TableTabDescriptor?,
        onRowClick: ((left: RowSelectionData?, right: RowSelectionData?) -> Unit)? = null,
    ): TableTabDiff {
        // Columns by name: the left side's order, then any the right side adds.
        val columnNames = ((left?.columns.orEmpty()) + (right?.columns.orEmpty())).map { it.name }.distinct()
        val leftCells = cells(left, columnNames)
        val rightCells = cells(right, columnNames)
        val rows = pairRows(leftCells, rightCells, columnNames.size)

        val columns = columnNames.mapIndexed { c, columnName ->
            val template = left?.columns?.firstOrNull { it.name == columnName }
                ?: right!!.columns.first { it.name == columnName }
            ColumnDescriptor(columnName, template.widthBehavior) { index ->
                val row = rows[index]
                val l = row.left?.let { leftCells[it][c] }
                val r = row.right?.let { rightCells[it][c] }
                when {
                    l == null -> r ?: EMPTY_CELL
                    r == null -> l
                    l.representation == r.representation -> l
                    else -> CellValue(
                        r.value,
                        "${l.representation} → ${r.representation}",
                        tooltip = "Before: ${l.representation}\nAfter: ${r.representation}",
                    )
                }
            }
        }

        val added = rows.count { it.tint == RowTint.Added }
        val removed = rows.count { it.tint == RowTint.Removed }
        val modified = rows.count { it.tint == RowTint.Modified }
        val template = left ?: right!!
        val descriptor = TableTabDescriptor(
            name = name,
            columns = columns,
            rowCount = rows.size,
            // The template's index addresses its own columns; the merged order may differ.
            defaultSortColumn = template.defaultSortColumn?.let { index ->
                if (index < 0) index else template.columns.getOrNull(index)?.let { columnNames.indexOf(it.name) }
            },
            defaultSortAscending = template.defaultSortAscending,
            rowClickAction = onRowClick?.let { click ->
                { index ->
                    val row = rows[index]
                    click(
                        row.left?.let { left?.rowSelectionProvider?.invoke(it) },
                        row.right?.let { right?.rowSelectionProvider?.invoke(it) },
                    )
                }
            },
            prologueText = prologue(left, right, added, removed, modified),
            emptyMessage = "No rows on either side.",
            alwaysShowSearch = true,
            showIdColumn = false,
            predefinedFilters = listOf(PredefinedFilter("Changed rows") { rows[it].tint != null }),
            // Nothing changed: open on every row rather than on an empty "changed" filter.
            defaultFilterShowAll = added + removed + modified == 0,
            rowTintProvider = { rows[it].tint },
        )
        return TableTabDiff(name, descriptor, added, removed, modified)
    }

    /**
     * Pairs [left] rows with [right] rows (each row is its cells, by merged column) and lays
     * them out in the left side's order, a right-only row following the right-side row
     * before it.
     */
    fun pairRows(left: List<List<CellValue<*>?>>, right: List<List<CellValue<*>?>>, columnCount: Int): List<TableRowPair> {
        val leftPair = IntArray(left.size) { -1 }
        val rightPair = IntArray(right.size) { -1 }
        val modified = BooleanArray(left.size)

        // 1. Identical rows.
        fun content(row: List<CellValue<*>?>) = row.joinToString("\u0000") { it?.representation.orEmpty() }
        val rightByContent = HashMap<String, ArrayDeque<Int>>()
        for (j in right.indices) rightByContent.getOrPut(content(right[j])) { ArrayDeque() }.addLast(j)
        for (i in left.indices) {
            val j = rightByContent[content(left[i])]?.removeFirstOrNull() ?: continue
            leftPair[i] = j
            rightPair[j] = i
        }

        // 2. Modified rows, through the most discriminating column.
        // Each round removes rows, which can make another column's values unique.
        var keyColumn = -1
        for (round in 0 until columnCount) {
            // Scored by how many cells the pairs it makes agree on, not by how many pairs: an
            // address column can match as often as a name column, but pairs rows that agree
            // on nothing else.
            var bestColumn = -1
            var bestPairs: List<Pair<Int, Int>> = emptyList()
            var bestScore = 0
            for (c in 0 until columnCount) {
                val pairs = uniqueMatches(left, leftPair, right, rightPair, c)
                val score = pairs.sumOf { (i, j) -> equalCells(left[i], right[j]) }
                if (score > bestScore) {
                    bestColumn = c
                    bestPairs = pairs
                    bestScore = score
                }
            }
            if (bestPairs.isEmpty()) break
            if (keyColumn < 0) keyColumn = bestColumn
            for ((i, j) in bestPairs) {
                leftPair[i] = j
                rightPair[j] = i
                modified[i] = true
            }
        }
        // Rows sharing a key value (two local `static helper` symbols) were skipped above as
        // ambiguous; pair them n-th with n-th. Only on the column the data identified as the
        // key — doing it on any column would let a "Type" column pair unrelated rows.
        if (keyColumn >= 0) {
            for ((i, j) in occurrenceMatches(left, leftPair, right, rightPair, keyColumn)) {
                leftPair[i] = j
                rightPair[j] = i
                modified[i] = true
            }
        }

        // 3. Layout: right-only rows grouped under the last paired right row before them.
        val attached = HashMap<Int, MutableList<Int>>()
        var lastPaired = -1
        for (j in right.indices) {
            if (rightPair[j] >= 0) lastPaired = j else attached.getOrPut(lastPaired) { ArrayList() } += j
        }
        val result = ArrayList<TableRowPair>(left.size + right.size)
        fun addRightOnly(after: Int) {
            for (j in attached[after].orEmpty()) result += TableRowPair(null, j, RowTint.Added)
        }
        addRightOnly(-1)
        for (i in left.indices) {
            val j = leftPair[i]
            if (j < 0) {
                result += TableRowPair(i, null, RowTint.Removed)
            } else {
                result += TableRowPair(i, j, if (modified[i]) RowTint.Modified else null)
                addRightOnly(j)
            }
        }
        return result
    }

    /** Unpaired rows with equal [column] values, the n-th such row on the left with the n-th on the right. */
    private fun occurrenceMatches(
        left: List<List<CellValue<*>?>>,
        leftPair: IntArray,
        right: List<List<CellValue<*>?>>,
        rightPair: IntArray,
        column: Int,
    ): List<Pair<Int, Int>> {
        val rightByValue = HashMap<String, ArrayDeque<Int>>()
        for (j in right.indices) {
            if (rightPair[j] >= 0) continue
            val value = right[j][column]?.representation?.takeIf { it.isNotBlank() } ?: continue
            rightByValue.getOrPut(value) { ArrayDeque() }.addLast(j)
        }
        val result = ArrayList<Pair<Int, Int>>()
        for (i in left.indices) {
            if (leftPair[i] >= 0) continue
            val value = left[i][column]?.representation?.takeIf { it.isNotBlank() } ?: continue
            val j = rightByValue[value]?.removeFirstOrNull() ?: continue
            result += i to j
        }
        return result
    }

    private fun equalCells(a: List<CellValue<*>?>, b: List<CellValue<*>?>): Int =
        a.indices.count { a[it]?.representation == b[it]?.representation }

    /** Unpaired rows whose [column] value occurs exactly once among the unpaired rows of each side. */
    private fun uniqueMatches(
        left: List<List<CellValue<*>?>>,
        leftPair: IntArray,
        right: List<List<CellValue<*>?>>,
        rightPair: IntArray,
        column: Int,
    ): List<Pair<Int, Int>> {
        fun unique(rows: List<List<CellValue<*>?>>, paired: IntArray): Map<String, Int> {
            val seen = HashMap<String, Int>()
            val duplicate = HashSet<String>()
            for (i in rows.indices) {
                if (paired[i] >= 0) continue
                val value = rows[i][column]?.representation?.takeIf { it.isNotBlank() } ?: continue
                if (seen.put(value, i) != null) duplicate += value
            }
            return seen - duplicate
        }
        val leftUnique = unique(left, leftPair)
        if (leftUnique.isEmpty()) return emptyList()
        val rightUnique = unique(right, rightPair)
        return leftUnique.mapNotNull { (value, i) -> rightUnique[value]?.let { i to it } }.sortedBy { it.first }
    }

    private fun cells(tab: TableTabDescriptor?, columnNames: List<String>): List<List<CellValue<*>?>> {
        if (tab == null) return emptyList()
        val byName = columnNames.map { name -> tab.columns.firstOrNull { it.name == name } }
        return List(tab.rowCount) { row -> byName.map { it?.cellProvider?.invoke(row) } }
    }

    private fun prologue(left: TableTabDescriptor?, right: TableTabDescriptor?, added: Int, removed: Int, modified: Int): String {
        val presence = when {
            left == null -> "Only in the right binary. "
            right == null -> "Only in the left binary. "
            else -> ""
        }
        if (added + removed + modified == 0) return "${presence}No changes."
        val parts = listOfNotNull(
            modified.takeIf { it > 0 }?.let { "$it modified" },
            added.takeIf { it > 0 }?.let { "$it added" },
            removed.takeIf { it > 0 }?.let { "$it removed" },
        )
        return presence + parts.joinToString(", ") + "."
    }

    /** `Functions (12)` → `Functions`: counts differ between versions, the tab is the same. */
    fun baseName(name: String): String = name.replace(TRAILING_COUNT, "")

    private val TRAILING_COUNT = Regex("""\s*\(\d[\d,]*\)$""")
    private val EMPTY_CELL = CellValue("", "")
}
