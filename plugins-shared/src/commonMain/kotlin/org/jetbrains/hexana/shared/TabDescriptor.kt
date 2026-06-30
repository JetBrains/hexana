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
 * @param isVisible whether the item should be shown for the given row index; defaults to
 *   always-visible. Lets a descriptor offer a row-kind-specific action (e.g. "navigate to
 *   the function body") only on the rows it applies to.
 */
class RowContextMenuItemDescriptor(
    val labelProvider: (index: Int) -> String,
    val action: (index: Int) -> Unit,
    val isVisible: (index: Int) -> Boolean = { true },
)

/**
 * A single flat context-menu entry generated dynamically for one row.
 *
 * Unlike [RowContextMenuItemDescriptor] — a fixed set of items shared by every
 * row — a [TableTabDescriptor.dynamicContextMenuProvider] returns a
 * variable-length list of these per row, e.g. one navigable entry per usage
 * site of the row's symbol.
 *
 * @param label the menu item text
 * @param action callback invoked when the item is selected
 */
class RowMenuEntry(
    val label: String,
    val action: () -> Unit,
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
 * @param dynamicContextMenuProvider optional provider of additional, per-row context
 *   menu entries whose count may vary by row (e.g. one entry per usage site). The UI
 *   layer renders these after [contextMenuItems]; `null` means none.
 * @param prologueText optional text displayed above the table; `null` means no prologue
 * @param emptyMessage optional message shown when [rowCount] is zero; `null` falls back
 *   to the default empty-table behavior of the UI layer
 * @param childrenProvider optional provider of nested child rows for a given row index.
 *   When non-null, the UI layer renders the table as a tree: rows that return a non-null
 *   [TableTabChildRows] are expandable, and their children share the same column layout.
 *   Each [TableTabChildRows] can itself carry a [TableTabChildRows.childrenProvider] for
 *   recursive nesting. Returns `null` for leaf rows (no expand chevron).
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
    val dynamicContextMenuProvider: ((index: Int) -> List<RowMenuEntry>)? = null,
    val prologueText: String? = null,
    val emptyMessage: String? = null,
    val childrenProvider: ((index: Int) -> TableTabChildRows?)? = null,
) {
    companion object {
        /** Sentinel value for [defaultSortColumn] that targets the implicit ID column. */
        const val ID_COLUMN: Int = -1
    }
}

/**
 * A set of nested child rows for a tree-table row.
 *
 * Shares the same column layout as the parent [TableTabDescriptor]: each
 * column's [ColumnDescriptor.cellProvider] is called with child row indices
 * `0 ..< rowCount`.
 *
 * @param columns column descriptors for child rows; must match the parent's
 *   column count so the UI can align cells. Typically the same column list
 *   with different cell providers.
 * @param rowCount number of child rows
 * @param rowSelectionProvider binary selection for a child row click
 * @param childrenProvider optional recursive nesting; returns further
 *   children for a child row, or `null` for leaf rows
 */
class TableTabChildRows(
    val columns: List<ColumnDescriptor>,
    val rowCount: Int,
    val rowSelectionProvider: (index: Int) -> RowSelectionData? = { null },
    val childrenProvider: ((index: Int) -> TableTabChildRows?)? = null,
)

/**
 * A tab that contains inner sub-tabs, each of which is a [TableTabDescriptor].
 *
 * The UI layer renders this as a single outer tab with an inner tab strip
 * that switches between the contained table tabs.
 *
 * When [innerTabs] is empty and [emptyMessage] is `null`, the UI layer skips
 * rendering this group tab entirely.
 *
 * @param name the outer tab title shown in the main tab strip
 * @param innerTabs the list of inner table tab descriptors
 * @param emptyMessage optional message shown when [innerTabs] is empty; when
 *   non-null the group tab is still rendered with this message instead of being
 *   hidden
 */
class GroupedTableTabDescriptor(
    val name: String,
    val innerTabs: List<TableTabDescriptor>,
    val emptyMessage: String? = null,
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

    fun tableTabs(provider: BinaryProvider, context: TableTabsContext): List<TableTabDescriptor> =
        tableTabs(provider)

    fun groupedTabs(provider: BinaryProvider): List<GroupedTableTabDescriptor> = emptyList()

    fun groupedTabs(provider: BinaryProvider, context: TableTabsContext): List<GroupedTableTabDescriptor> =
        groupedTabs(provider)
}

class TableTabsContext(
    val nativeDebugData: NativeDebugTabData? = null,
)

object TableTabsProviderRegistry {
    val providers by lazy {
        listOf(
            WasmComponentTableTabsProvider(),
            WasmTableTabsProvider(),
            NativeTableTabsProvider(),
            SizeTableTabsProvider(),
            WasmProfilerTabsProvider(),
        )
    }
}
