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
 * A UI-free description of a per-row action button (e.g. "Run" icon).
 *
 * The UI layer renders this as a small clickable icon in an action column.
 *
 * @param label short identifier for the action (e.g. "Run")
 * @param tooltipProvider returns tooltip text for the given row, or `null` to hide the action for that row
 * @param action callback invoked when the action button is clicked for the given row index
 */
class RowActionDescriptor(
    val label: String,
    val tooltipProvider: (index: Int) -> String?,
    val action: (index: Int) -> Unit,
)

/**
 * A UI-free description of a single context menu item available on table rows.
 *
 * @param labelProvider returns the menu item label for the given row index
 * @param action callback invoked when the menu item is selected for the given row index
 */
class RowContextMenuItemDescriptor(
    val labelProvider: (index: Int) -> String,
    val action: (index: Int) -> Unit,
)

/**
 * A UI-free description of a table-based tab.
 *
 * @param name the tab title shown in the tab strip
 * @param columns the column descriptors for this table
 * @param rowCount the number of rows in this table
 * @param defaultSortColumn initial sort column index, where [ID_COLUMN] means the
 *   implicit ID column and `0..columns.size-1` addresses a data column;
 *   `null` disables default sorting
 * @param defaultSortAscending initial sort direction; `true` for ascending
 * @param rowSelectionProvider returns the binary offset/size for a given row index,
 *   or `null` if the row click should be ignored
 * @param rowAction optional per-row action button descriptor (e.g. "Run" icon)
 * @param contextMenuItems per-row context menu items; empty list means no context menu
 * @param rowClickAction optional alternative row-click handler; when set, the UI layer
 *   calls this instead of scrolling to [rowSelectionProvider] data
 */
class TableTabDescriptor(
    val name: String,
    val columns: List<ColumnDescriptor>,
    val rowCount: Int,
    val defaultSortColumn: Int? = null,
    val defaultSortAscending: Boolean = true,
    val rowSelectionProvider: (index: Int) -> RowSelectionData? = { null },
    val rowAction: RowActionDescriptor? = null,
    val contextMenuItems: List<RowContextMenuItemDescriptor> = emptyList(),
    val rowClickAction: ((index: Int) -> Unit)? = null,
) {
    companion object {
        /** Sentinel value for [defaultSortColumn] that targets the implicit ID column. */
        const val ID_COLUMN: Int = -1
    }
}

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

object TableTabsProviderRegistry {
    val providers: List<TableTabsProvider> by lazy {
        listOf(
            WasmComponentTableTabsProvider(),
            WasmTableTabsProvider(),
            NativeTableTabsProvider(),
        )
    }
}