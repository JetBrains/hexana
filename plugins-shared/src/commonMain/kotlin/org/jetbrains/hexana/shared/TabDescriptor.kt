package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider

/**
 * A UI-free description of a single cell value in a table tab.
 *
 * @param value the underlying data value (used for sorting)
 * @param representation the text shown in the cell
 * @param tooltip optional tooltip text, `null` if no tooltip is needed
 */
class CellValue<T>(
    val value: T,
    val representation: String = value.toString(),
    val tooltip: String? = null,
)

/**
 * A UI-free description of a single column in a table tab.
 *
 * @param name column header text
 * @param widthBehavior how the column should be sized
 * @param cellProvider returns the [CellValue] for the given row index
 */
class ColumnDescriptor(
    val name: String,
    val widthBehavior: ColumnWidth = ColumnWidth.Fill(),
    val cellProvider: (index: Int) -> CellValue<*>,
)

/**
 * Specifies how a table column should be sized in the UI layer.
 *
 * The UI layer is responsible for translating these hints into
 * platform-specific layout modifiers (e.g. Compose `Modifier.width()`,
 * `Modifier.fillMaxWidth()`, etc.).
 */
sealed class ColumnWidth {
    /** Column fills all remaining horizontal space (optionally a fraction of it). */
    class Fill(val fraction: Float = 1f) : ColumnWidth()

    /** Column width is measured to fit the given sample text in a monospace font. */
    class Measured(val sample: String) : ColumnWidth()

    /** Column width is measured as a minimum; it may grow beyond this. */
    class MeasuredMin(val sample: String) : ColumnWidth()
}

/**
 * Binary offset and size of a row's data in the original binary.
 * Used by the UI layer to scroll the hex view to the selected record.
 */
class RowSelectionData(val offset: Int, val size: Int)

/**
 * A UI-free description of a table-based tab.
 *
 * @param name the tab title shown in the tab strip
 * @param columns the column descriptors for this table
 * @param rowCount the number of rows in this table
 * @param rowSelectionProvider returns the binary offset/size for a given row index,
 *   or `null` if the row click should be ignored
 */
class TableTabDescriptor(
    val name: String,
    val columns: List<ColumnDescriptor>,
    val rowCount: Int,
    val rowSelectionProvider: (index: Int) -> RowSelectionData? = { null },
)

/**
 * Produces [TableTabDescriptor]s for a given [BinaryProvider].
 *
 * Each binary format (core Wasm, component-model Wasm, future formats)
 * implements this interface to declare which table-based tabs it supports.
 * Non-table tabs (WAT view, size treemap, dominators, etc.) remain outside
 * this abstraction and are handled by the UI layer directly.
 */
interface TableTabsProvider {
    fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor>
}
