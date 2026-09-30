package org.jetbrains.hexana.shared

import org.jetbrains.hexana.shared.inspections.BinaryProblem
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility

/**
 * Priority level for tab ordering.
 *
 * Tabs are sorted by priority first (High before Medium before Low),
 * then by provider registration order within the same priority level.
 */
enum class TabPriority {
    High, Medium, Low
}

/**
 * A UI-free description of a single cell value in a table tab.
 *
 * @param value the underlying data value (used for sorting)
 * @param representation the text shown in the cell
 * @param tooltip optional tooltip text, `null` if no tooltip is needed
 * @param tint optional background tint for this cell alone, drawn over any row tint; used
 *   to point at the one cell of a row that carries the verdict
 */
class CellValue<T>(
    val value: T,
    val representation: String = value.toString(),
    val tooltip: String? = null,
    val tint: RowTint? = null,
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
 * Bytes belonging to a single row that exist nowhere in the file being viewed,
 * so they cannot be shown by selecting a range in the hex view. Decrypted TLS
 * plaintext is the motivating case: the hex view can only point at ciphertext.
 * The UI layer renders these in a side panel next to the hex bytes.
 *
 * @param title panel heading, e.g. "Decrypted TLS"
 * @param bytes the row's payload, shown as text or as a hex/ASCII dump
 * @param subtitle optional line under the heading naming the row and size
 */
class RowDetailData(val title: String, val bytes: ByteArray, val subtitle: String? = null)

/** Role of a [HexRange] within a multi-range row highlight. */
enum class HexRangeRole { PRIMARY, SECONDARY }

/**
 * A single highlighted byte range within the hex view, produced by
 * [TableTabDescriptor.rowHighlightRangesProvider].
 *
 * @param offset byte offset from the start of the file
 * @param size number of bytes covered
 * @param role visual weight of this range; the UI layer maps roles to colors
 */
class HexRange(val offset: Long, val size: Int, val role: HexRangeRole = HexRangeRole.PRIMARY)

/**
 * Background tint of a table row, produced by [TableTabDescriptor.rowTintProvider], or of a
 * single cell ([CellValue.tint]).
 *
 * A role rather than a colour, like [HexRangeRole]: IntelliJ maps it to a theme colour and
 * the web UI to a CSS variable. The diff tables use the first four to mark what changed;
 * [Warning] marks a cell that needs attention, such as a runtime that cannot run a proposal.
 */
enum class RowTint { Added, Removed, Modified, Moved, Warning }

/**
 * What a [RowActionDescriptor] does, so each host can pick its own icon for it.
 *
 * Named for the action rather than for a picture, because these descriptors stay free of
 * UI-framework concepts: IntelliJ resolves a kind to an `AllIcons` key and the web UI to a
 * glyph, and neither mapping belongs here.
 */
enum class RowActionKind {
    /** Runs or executes the row's subject. Rendered as a play arrow. */
    Run,

    /** Opens the row's documentation, in a browser. */
    Documentation,
}

/**
 * A UI-free description of a per-row action button (e.g. "Run" icon).
 *
 * The UI layer renders this as a small clickable icon in an action column.
 *
 * @param label short identifier for the action (e.g. "Run")
 * @param tooltipProvider returns tooltip text for the given row, or `null` to hide the action for that row
 * @param action callback invoked when the action button is clicked for the given row index
 * @param kind what the action does; the UI layer maps it to an icon. Defaults to [RowActionKind.Run],
 *   which is what every action was before kinds existed.
 */
class RowActionDescriptor(
    val label: String,
    val tooltipProvider: (index: Int) -> String?,
    val action: (index: Int) -> Unit,
    val kind: RowActionKind = RowActionKind.Run,
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
 * A UI-free description of a clickable link rendered after [TableTabDescriptor.prologueText].
 *
 * Exists so a prologue can point at something the user can act on — a settings page, the
 * source its data came from — rather than naming a path or a URL and leaving them to find
 * it. The UI layer renders it as a link (Jewel `Link` in IntelliJ, a link-coloured span in
 * the web UI).
 *
 * The label carries the whole meaning, because neither host can put a link *inside* a
 * paragraph: Compose `Text` needs an `AnnotatedString` for that, so IntelliJ renders these
 * on their own lines below the prose.
 *
 * @param label the link text
 * @param action callback invoked when the link is clicked
 */
class PrologueActionDescriptor(
    val label: String,
    val action: () -> Unit,
)

/**
 * A named row filter that can be selected from a dropdown above the table.
 *
 * When [predefinedFilters] is non-empty on a [TableTabDescriptor], the UI
 * layer renders a dropdown before the search bar. The first entry is
 * pre-selected. A built-in "Show all" option is always appended by the UI.
 *
 * @param label display text in the dropdown
 * @param predicate returns `true` for rows that should be visible
 */
class PredefinedFilter(
    val label: String,
    val predicate: (index: Int) -> Boolean,
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
 * @param prologueActions clickable links rendered after [prologueText]; empty means none
 * @param emptyMessage optional message shown when [rowCount] is zero; `null` falls back
 *   to the default empty-table behavior of the UI layer
 * @param childrenProvider optional provider of nested child rows for a given row index.
 *   When non-null, the UI layer renders the table as a tree: rows that return a non-null
 *   [TableTabChildRows] are expandable, and their children share the same column layout.
 *   Each [TableTabChildRows] can itself carry a [TableTabChildRows.childrenProvider] for
 *   recursive nesting. Returns `null` for leaf rows (no expand chevron).
 * @param rowHighlightRangesProvider optional provider of multiple disjoint byte ranges for
 *   a given row index. When non-null, the UI layer highlights all returned spans in the hex
 *   view on row select (using color determined by [HexRangeRole]) and scrolls to the first
 *   range. An empty list clears the current highlight. When null, the single-range
 *   [rowSelectionProvider] path is used unchanged.
 * @param rowDetailProvider optional provider of out-of-file bytes for a given row index.
 *   When non-null, the UI layer shows the returned [RowDetailData] in a side panel beside
 *   the hex view on row select; `null` for a row means that row has nothing to show.
 * @param rowTintProvider optional background tint for a given row index; `null` for a row
 *   means no tint. When the provider itself is `null`, rows are not tinted.
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
    val prologueActions: List<PrologueActionDescriptor> = emptyList(),
    val emptyMessage: String? = null,
    val childrenProvider: ((index: Int) -> TableTabChildRows?)? = null,
    val alwaysShowSearch: Boolean = false,
    val searchPlaceholder: String? = null,
    val showIdColumn: Boolean = true,
    val priority: TabPriority = TabPriority.High,
    val rowHighlightRangesProvider: ((index: Int) -> List<HexRange>)? = null,
    val predefinedFilters: List<PredefinedFilter> = emptyList(),
    val defaultFilterShowAll: Boolean = false,
    val treemapData: TreemapData? = null,
    val rowDetailProvider: ((index: Int) -> RowDetailData?)? = null,
    val rowTintProvider: ((index: Int) -> RowTint?)? = null,
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
    val priority: TabPriority = TabPriority.High,
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

    /**
     * Cheap heuristic returning tab names that this provider will likely produce
     * for the given [provider], without performing expensive parsing or analysis.
     *
     * Returns a pair of (ordinary tab names, grouped tab names). When `null`,
     * the caller must fall back to the full [tableTabs]/[groupedTabs] to discover names.
     *
     * The default implementation returns `null` (unknown). Override in providers
     * where the full computation is expensive but availability can be checked cheaply.
     */
    fun availableTabNames(
        provider: BinaryProvider,
        context: TableTabsContext = TableTabsContext(),
    ): Pair<List<String>, List<String>>? = null

    /**
     * Resolves a single ordinary tab by [name]. Returns `null` if the tab
     * doesn't exist or the provider doesn't support per-tab resolution.
     *
     * The default implementation falls back to [tableTabs] and filters by name,
     * which is no better than batch resolution. Override in providers where
     * individual tabs can be computed independently to avoid blocking on
     * unrelated expensive tabs.
     */
    fun singleTab(
        name: String,
        provider: BinaryProvider,
        context: TableTabsContext = TableTabsContext(),
    ): TableTabDescriptor? = tableTabs(provider, context).firstOrNull { it.name == name }
}

/**
 * A UI-free node for treemap (size visualization) rendering.
 *
 * Each node represents a named region with a byte [size], optionally
 * containing [children] that break the region down further.
 *
 * @param name display label for this region
 * @param size byte size of this region
 * @param children sub-regions; empty for leaf nodes
 * @param treePath path of row indices from the root to this node in the
 *   tree table, used by the UI layer to expand and select the corresponding
 *   table row when the treemap area is clicked
 */
class TreemapNode(
    val name: String,
    val size: Long,
    val children: List<TreemapNode> = emptyList(),
    val treePath: List<Int> = emptyList(),
)

/**
 * Data for rendering a treemap visualization alongside a table tab.
 *
 * @param root the root node of the treemap tree; its [TreemapNode.children]
 *   are the top-level rectangles
 * @param totalSize total size of the binary, used for percentage calculations
 */
class TreemapData(
    val root: TreemapNode,
    val totalSize: Long,
)

/**
 * Host-supplied context for tab construction.
 *
 * @param runtimeCompatibility the feature catalog paired with the runtime targets declared
 *   in `.hexana/runtimes.json`, when the host has both. `null` means the host cannot supply
 *   them (the browser webapp has no project directory), and the Compatibility tab is then
 *   not produced.
 * @param openUrl opens a URL in the user's browser. Supplied here rather than attached by a
 *   host enrichment step so the Compatibility tab's documentation links are built once.
 * @param openRuntimeSettings opens the host's runtime-targets settings UI; `null` when the
 *   host has none, in which case the tab omits the action.
 * @param hostProblems problems only the host knows about (IntelliJ's last script-check
 *   findings), listed in the Problems tab alongside the inspections' own.
 * @param problemNavigator how a Problems row takes the user to the problem; `null` leaves
 *   the host's default row click, which selects the problem's bytes.
 */
class TableTabsContext(
    val nativeDebugData: NativeDebugTabData? = null,
    val fileName: String? = null,
    val runtimeCompatibility: WasmRuntimeCompatibility? = null,
    val openUrl: ((String) -> Unit)? = null,
    val openRuntimeSettings: (() -> Unit)? = null,
    val hostProblems: List<BinaryProblem> = emptyList(),
    val problemNavigator: ProblemNavigator? = null,
)

object TableTabsProviderRegistry {
    val providers by lazy {
        listOf(
            WasmComponentTableTabsProvider(),
            WasmTableTabsProvider(),
            NativeTableTabsProvider(),
            ArchiveTableTabsProvider(),
            ZipTableTabsProvider(),
            TarTableTabsProvider(),
            ProtobufTableTabsProvider(),
            ProtobufMessageTableTabsProvider(),
            DexTableTabsProvider(),
            ArscTableTabsProvider(),
            PcapTableTabsProvider(),
            StringsTabProvider(),
            SizeTableTabsProvider(),
            WasmProfilerTabsProvider(),
            ParquetTableTabsProvider(),
            ArrowTableTabsProvider(),
            // Last, so Problems follows every format's own tabs.
            BinaryProblemsTableTabsProvider(),
        )
    }
}
