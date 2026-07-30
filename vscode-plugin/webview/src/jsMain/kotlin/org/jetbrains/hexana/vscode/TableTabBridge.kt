package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Tr
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.GroupedTableTabDescriptor
import org.jetbrains.hexana.shared.HexRange
import org.jetbrains.hexana.shared.PredefinedFilter
import org.jetbrains.hexana.shared.RowSelectionData
import org.jetbrains.hexana.shared.TableTabChildRows
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsProvider
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Small play-arrow button used as a run indicator in table rows.
 * Uses a Unicode play triangle (▶) styled to match VS Code's green run icon.
 */
@Composable
internal fun RunArrowButton(tooltip: String = "Run", onClick: () -> Unit) {
    Span({
        classes("hexana-run-arrow")
        attr("title", tooltip)
        style {
            property("cursor", "pointer")
            property("display", "inline-flex")
            property("align-items", "center")
            property("justify-content", "center")
            property("width", "16px")
            property("height", "16px")
            property("border-radius", "3px")
            property("font-size", "12px")
            property("color", "var(--vscode-debugIcon-startForeground, #89d185)")
        }
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        Text("\u25B6")
    }
}

/**
 * Holds tab metadata produced by a [TableTabsProvider] so that [HexanaPanel]
 * can mix provider-generated tabs with handcrafted ones.
 */
internal class TableTab(
    val name: String,
    val content: @Composable (HexSelectionState) -> Unit,
)

/**
 * Converts all [TableTabDescriptor]s from a [TableTabsProvider] into
 * [TableTab] entries, applying an optional enrichment step that can attach
 * VS Code-specific actions (run buttons, module-open click handlers) before
 * the Compose-for-Web bridge renders them.
 */
internal fun TableTabsProvider.toTableTabs(
    provider: BinaryProvider,
    enrich: (List<TableTabDescriptor>) -> List<TableTabDescriptor> = { it },
): List<TableTab> = enrich(tableTabs(provider)).map { descriptor ->
    TableTab(name = descriptor.name) { selection ->
        TableTabContent(descriptor, selection)
    }
}

/**
 * Renders a [GroupedTableTabDescriptor] as an inner tab strip switching
 * between the contained table tabs.
 */
@Composable
fun GroupedTableTabContent(
    grouped: GroupedTableTabDescriptor,
    selection: HexSelectionState,
) {
    val innerTabs = grouped.innerTabs
    if (innerTabs.isEmpty()) return

    var selectedIndex by remember(grouped) { mutableStateOf(0) }

    // Inner tab strip
    Div({
        style {
            display(DisplayStyle.Flex)
            property("gap", "0")
            property("border-bottom", "1px solid var(--hexana-border, #333)")
            property("margin-bottom", "8px")
        }
    }) {
        for ((index, tab) in innerTabs.withIndex()) {
            val isActive = index == selectedIndex
            Div({
                style {
                    property("padding", "4px 12px")
                    property("cursor", "pointer")
                    property("font-size", "12px")
                    property("user-select", "none")
                    property("white-space", "nowrap")
                    property("border-bottom", if (isActive) "2px solid var(--vscode-focusBorder, #007FD4)" else "2px solid transparent")
                    property("color", if (isActive) "var(--hexana-fg)" else "var(--vscode-tab-inactiveForeground, rgba(255,255,255,0.5))")
                }
                onClick { selectedIndex = index }
            }) {
                Text(tab.name)
            }
        }
    }

    val currentTab = innerTabs.getOrNull(selectedIndex) ?: return
    TableTabContent(currentTab, selection)
}

/**
 * Renders a [TableTabDescriptor] as a Compose-for-Web HTML table using the
 * existing [SearchableTable] / [PanelTableRow] components.
 */
@Composable
fun TableTabContent(
    descriptor: TableTabDescriptor,
    selection: HexSelectionState,
    searchBarLeading: (@Composable () -> Unit)? = null,
) {
    descriptor.prologueText?.let { prologue ->
        Div({ style {
            property("margin-bottom", "8px")
            property("font-size", "12px")
        } }) {
            Text(prologue)
        }
    }

    if (descriptor.rowCount == 0) {
        Div({ classes("empty-hint") }) {
            Text(descriptor.emptyMessage ?: "No data found.")
        }
        return
    }

    if (descriptor.childrenProvider != null) {
        TreeTableTabContent(descriptor, selection)
        return
    }

    // --- Predefined filter state ---
    val filters = descriptor.predefinedFilters
    var selectedFilterIndex: Int? by remember(descriptor) {
        mutableStateOf(if (filters.isNotEmpty()) 0 else null)
    }
    val activeFilter: PredefinedFilter? = selectedFilterIndex?.let { filters.getOrNull(it) }

    val filterLeading: (@Composable () -> Unit)? = if (filters.isNotEmpty()) {
        {
            FilterDropdownVsc(
                filters = filters,
                selectedIndex = selectedFilterIndex,
                onSelected = { selectedFilterIndex = it },
            )
        }
    } else null

    val combinedLeading: (@Composable () -> Unit)? = when {
        filterLeading != null && searchBarLeading != null -> {
            { filterLeading(); searchBarLeading() }
        }
        filterLeading != null -> filterLeading
        else -> searchBarLeading
    }

    val showId = descriptor.showIdColumn

    val headers = buildList {
        if (showId) {
            add("")
            add("ID")
        }
        descriptor.columns.forEach { add(it.name) }
    }

    val aligns = buildList {
        if (showId) {
            add("center")
            add("right")
        }
        descriptor.columns.forEach { col ->
            when (col.widthBehavior) {
                is ColumnWidth.Fill -> add("left")
                is ColumnWidth.Measured -> add("left")
                is ColumnWidth.MeasuredMin -> add("left")
            }
        }
    }

    // Compute column width hints (in `ch` units) for Measured/MeasuredMin columns.
    val idWidthCh = if (showId) maxOf(2, descriptor.rowCount.toString().length) + 2 else 0
    val columnWidthsCh = buildList {
        if (showId) {
            add(2) // action column – just enough for the arrow
            add(idWidthCh) // ID column – sized to fit the largest row number
        }
        descriptor.columns.forEach { col ->
            when (val w = col.widthBehavior) {
                is ColumnWidth.Measured -> {
                    val sample = maxOf(col.name.length + 2, w.sample.length)
                    add(sample)
                }
                is ColumnWidth.MeasuredMin -> {
                    val sample = maxOf(col.name.length + 2, w.sample.length)
                    add(sample)
                }
                is ColumnWidth.Fill -> add(null)
            }
        }
    }

    // Map descriptor's defaultSortColumn to the header index in the rendered table.
    // The descriptor uses ID_COLUMN (-1) for the implicit ID column; the rendered
    // headers always prepend the action column placeholder and then "ID", so we
    // resolve the actual header index here.
    // When the descriptor does not specify a default sort column (null), fall back
    // to the ID column so the sorting arrow is always visible — matching the old
    // per-tab behaviour where SearchableTable auto-detected the "ID" header.
    val idHeaderIndex = headers.indexOf("ID").takeIf { it >= 0 }
    val headerOffset = if (showId) 2 else 0
    val resolvedSortColumn = descriptor.defaultSortColumn?.let { col ->
        if (col == TableTabDescriptor.ID_COLUMN) {
            idHeaderIndex
        } else {
            col + headerOffset
        }
    } ?: idHeaderIndex ?: -1
    val resolvedSortDirection = if (resolvedSortColumn >= 0) {
        if (descriptor.defaultSortAscending) SortDirection.ASC else SortDirection.DESC
    } else SortDirection.NONE

    // --- Data-level sorting & filtering for virtual scrolling ---
    var searchQuery by remember(descriptor) { mutableStateOf("") }
    var isSearchVisible by remember(descriptor) { mutableStateOf(descriptor.alwaysShowSearch) }
    var debouncedQuery by remember(descriptor) { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    val sortState = remember {
        if (resolvedSortColumn >= 0) TableSortState(resolvedSortColumn, resolvedSortDirection)
        else TableSortState()
    }

    // Build sorted & filtered index list at the data level.
    val visibleIndices = remember(descriptor, debouncedQuery, sortState.columnIndex, sortState.direction, activeFilter) {
        val indices = (0 until descriptor.rowCount).toMutableList()

        // Predefined filter
        if (activeFilter != null) {
            indices.retainAll { idx -> activeFilter.predicate(idx) }
        }

        // Search filter
        if (debouncedQuery.isNotEmpty()) {
            val lq = debouncedQuery.lowercase()
            indices.retainAll { idx ->
                descriptor.columns.any { col ->
                    col.cellProvider(idx).representation.lowercase().contains(lq)
                } || idx.toString().contains(lq)
            }
        }

        // Sort
        val colIdx = sortState.columnIndex
        val dir = sortState.direction
        if (colIdx >= 0 && dir != SortDirection.NONE) {
            val descColIdx = colIdx - headerOffset
            if (descColIdx >= 0 && descColIdx < descriptor.columns.size) {
                val col = descriptor.columns[descColIdx]
                indices.sortWith { a, b ->
                    val va = col.cellProvider(a).value
                    val vb = col.cellProvider(b).value
                    val cmp = when {
                        va is Comparable<*> && vb is Comparable<*> -> {
                            @Suppress("UNCHECKED_CAST")
                            (va as Comparable<Any>).compareTo(vb as Comparable<Any>)
                        }
                        else -> va.toString().compareTo(vb.toString())
                    }
                    if (dir == SortDirection.DESC) -cmp else cmp
                }
            } else if (colIdx == idHeaderIndex) {
                if (dir == SortDirection.DESC) indices.sortDescending() else indices.sort()
            }
        }

        indices
    }

    // --- Virtual scroll state ---
    val rowHeight = 24 // px, matches CSS row height
    val bufferRows = 20
    var scrollTop by remember { mutableStateOf(0.0) }
    var viewportHeight by remember { mutableStateOf(600.0) }

    val totalRows = visibleIndices.size
    val totalHeight = totalRows * rowHeight
    val firstVisible = maxOf(0, (scrollTop / rowHeight).toInt() - bufferRows)
    val lastVisible = minOf(totalRows - 1, ((scrollTop + viewportHeight) / rowHeight).toInt() + bufferRows)

    Div({
        classes("hexana-searchable-table")
        tabIndex(-1)
        style {
            property("outline", "none")
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("min-height", "0")
            property("flex", "1")
        }
        ref { container ->
            // Listen on window (capture phase) so we receive Cmd+F even when
            // focus is on the parent hexana-tab-content rather than this div.
            val keyListener = EventListener { event ->
                val ke = event as? KeyboardEvent ?: return@EventListener
                // Only handle when focus is inside (or on) this container
                val target = ke.target as? HTMLElement
                if (target != null && !container.contains(target) && target != container) {
                    // Also handle when focus is on the direct parent tab-content
                    val tabContent = container.closest(".hexana-tab-content")
                    if (target != tabContent) return@EventListener
                }
                if (ke.key == "f" && (ke.metaKey || ke.ctrlKey)) {
                    ke.preventDefault()
                    ke.stopPropagation()
                    isSearchVisible = true
                    kotlinx.browser.window.requestAnimationFrame {
                        container.querySelector(".hexana-search-input")
                            ?.let { (it as? org.w3c.dom.HTMLInputElement)?.focus() }
                    }
                }
                if (ke.key == "Escape" && isSearchVisible && !descriptor.alwaysShowSearch) {
                    isSearchVisible = false
                    searchQuery = ""
                }
            }
            kotlinx.browser.window.addEventListener("keydown", keyListener, true)
            onDispose {
                kotlinx.browser.window.removeEventListener("keydown", keyListener, true)
            }
        }
    }) {
        // Search bar (outside the scrollable area)
        if (isSearchVisible || searchQuery.isNotEmpty()) {
            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                onClose = {
                    searchQuery = ""
                    if (!descriptor.alwaysShowSearch) isSearchVisible = false
                },
                leading = combinedLeading,
            )
        }

        // Table with virtual scrolling — the table wrapper itself is the
        // scrollable container, keeping the search bar fixed above.
        Div({
            style {
                property("flex", "1")
                property("overflow-y", "auto")
                property("min-height", "0")
                // Account for the sticky <thead> so that scrollIntoView
                // positions rows below the header, not behind it.
                property("scroll-padding-top", "${rowHeight}px")
            }
            ref { tableWrapper ->
                viewportHeight = tableWrapper.clientHeight.toDouble()
                scrollTop = tableWrapper.scrollTop

                val listener = EventListener {
                    viewportHeight = tableWrapper.clientHeight.toDouble()
                    scrollTop = tableWrapper.scrollTop
                }
                tableWrapper.addEventListener("scroll", listener)
                onDispose { tableWrapper.removeEventListener("scroll", listener) }
            }
        }) {
            Table({
                classes("hexana-panel-table")
            }) {
                // Header
                Thead {
                    Tr({
                        style {
                            property("border-bottom", "1px solid var(--hexana-border, #333)")
                        }
                    }) {
                        headers.forEachIndexed { index, header ->
                            val align = aligns.getOrElse(index) { "left" }
                            val isNarrow = header == "ID" || header == ""
                            val isSorted = sortState.columnIndex == index
                            val indicator = when {
                                isSorted && sortState.direction == SortDirection.ASC -> " \u25B2"
                                isSorted && sortState.direction == SortDirection.DESC -> " \u25BC"
                                else -> ""
                            }
                            val widthCh = columnWidthsCh.getOrNull(index)
                            Th({
                                classes("panel-th")
                                if (isNarrow) classes("panel-th-narrow")
                                if (header.isNotEmpty()) classes("panel-th-sortable")
                                style {
                                    property("text-align", align)
                                    if (widthCh != null) {
                                        property("width", "${widthCh}ch")
                                    }
                                    if (header.isNotEmpty()) {
                                        property("cursor", "pointer")
                                        property("user-select", "none")
                                    }
                                }
                                if (header.isNotEmpty()) {
                                    onClick { sortState.toggle(index) }
                                }
                            }) { Text(header + indicator) }
                        }
                    }
                }

                // Virtualized body
                Tbody {
                    // Top spacer
                    if (firstVisible > 0) {
                        Tr({
                            style { property("height", "${firstVisible * rowHeight}px") }
                        }) {}
                    }

                    // Visible rows
                    for (vi in firstVisible..lastVisible) {
                        if (vi < 0 || vi >= visibleIndices.size) continue
                        val index = visibleIndices[vi]
                        val selData = descriptor.rowSelectionProvider(index)
                        val isHighlighted = selData != null && selection.hasSelection &&
                            selection.selectionStart == selData.offset &&
                            selection.selectionEnd == selData.offset + selData.size - 1

                        PanelTableRow(
                            isHighlighted = isHighlighted,
                            onClick = {
                                val clickAction = descriptor.rowClickAction
                                if (clickAction != null) {
                                    clickAction(index)
                                } else {
                                    val highlightProvider = descriptor.rowHighlightRangesProvider
                                    if (highlightProvider != null) {
                                        // Web hex view has no multi-range highlight API; degrade to the
                                        // bounding span covering all returned ranges.
                                        applyHighlightRangesVsc(highlightProvider(index), selection)
                                    } else if (selData != null) {
                                        selection.anchorIndex = selData.offset
                                        selection.activeIndex = selData.offset + selData.size - 1
                                        selection.cursorIndex = selData.offset
                                        scrollHexViewerTo(selData.offset)
                                    }
                                }
                            },
                        ) {
                            if (showId) {
                                Td({ classes("cell-id"); style { property("width", "12px") } }) {
                                    if (descriptor.rowAction != null) {
                                        val tooltip = descriptor.rowAction!!.tooltipProvider(index)
                                        if (tooltip != null) {
                                            RunArrowButton(
                                                tooltip = tooltip,
                                                onClick = { descriptor.rowAction!!.action(index) },
                                            )
                                        }
                                    }
                                }
                                Td({ classes("cell-id") }) {
                                    Text(index.toString())
                                }
                            }

                            val firstColumn = descriptor.columns.firstOrNull()
                            for (col in descriptor.columns) {
                                val cell = col.cellProvider(index)
                                val cssClass = when (col.widthBehavior) {
                                    is ColumnWidth.Fill -> "cell-muted"
                                    is ColumnWidth.Measured -> "cell"
                                    is ColumnWidth.MeasuredMin -> "cell"
                                }

                                if (descriptor.rowClickAction != null && col === firstColumn) {
                                    Td({
                                        classes(cssClass)
                                        style {
                                            property("cursor", "pointer")
                                        }
                                        attr("title", cell.tooltip ?: cell.representation)
                                    }) {
                                        Text(cell.representation)
                                    }
                                } else {
                                    Td({
                                        classes(cssClass)
                                        attr("title", cell.tooltip ?: cell.representation)
                                    }) {
                                        Text(cell.representation)
                                    }
                                }
                            }
                        }
                    }

                    // Bottom spacer
                    val bottomRows = totalRows - lastVisible - 1
                    if (bottomRows > 0) {
                        Tr({
                            style { property("height", "${bottomRows * rowHeight}px") }
                        }) {}
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------
// Tree table rendering for descriptors with childrenProvider
// -----------------------------------------------------------------------

/**
 * A flattened row in a tree table, used by the VSCode renderer.
 */
private class VscFlatRow(
    val depth: Int,
    val index: Int,
    val columns: List<ColumnDescriptor>,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
    val rowSelectionProvider: (Int) -> RowSelectionData?,
    val treePath: List<Int>,
)

private fun flattenTreeVsc(
    descriptor: TableTabDescriptor,
    expandedPaths: Set<List<Int>>,
    sortColumnIndex: Int = -1,
    sortAscending: Boolean = true,
    searchQuery: String = "",
    activeFilter: PredefinedFilter? = null,
): List<VscFlatRow> {
    val result = mutableListOf<VscFlatRow>()
    val lq = searchQuery.lowercase()
    val isSearching = lq.isNotEmpty()

    fun sortedIndices(rowCount: Int, columns: List<ColumnDescriptor>): List<Int> {
        val indices = (0 until rowCount).toMutableList()
        if (sortColumnIndex < 0 || sortColumnIndex >= columns.size) return indices
        val col = columns[sortColumnIndex]
        indices.sortWith { a, b ->
            val va = col.cellProvider(a).value
            val vb = col.cellProvider(b).value
            val cmp = when {
                va is Int && vb is Int -> va.compareTo(vb)
                va is String && vb is String -> va.compareTo(vb)
                else -> 0
            }
            if (sortAscending) cmp else -cmp
        }
        return indices
    }

    fun rowMatches(index: Int, columns: List<ColumnDescriptor>): Boolean {
        return columns.any { col ->
            col.cellProvider(index).representation.lowercase().contains(lq)
        }
    }

    fun subtreeMatches(
        index: Int,
        columns: List<ColumnDescriptor>,
        childrenProvider: ((Int) -> TableTabChildRows?)?,
    ): Boolean {
        if (rowMatches(index, columns)) return true
        val children = childrenProvider?.invoke(index) ?: return false
        if (children.rowCount == 0) return false
        return (0 until children.rowCount).any { childIdx ->
            subtreeMatches(childIdx, children.columns, children.childrenProvider)
        }
    }

    fun addLevel(
        rowCount: Int,
        columns: List<ColumnDescriptor>,
        childrenProvider: ((Int) -> TableTabChildRows?)?,
        rowSelectionProvider: (Int) -> RowSelectionData?,
        depth: Int,
        parentPath: List<Int>,
    ) {
        val order = if (depth == 0) sortedIndices(rowCount, columns) else (0 until rowCount).toList()
        for (i in order) {
            if (depth == 0 && activeFilter != null && !activeFilter.predicate(i)) continue
            if (isSearching && !subtreeMatches(i, columns, childrenProvider)) continue
            val path = parentPath + i
            val children = childrenProvider?.invoke(i)
            val hasChildren = children != null && children.rowCount > 0
            val isExpanded = hasChildren && (path in expandedPaths ||
                (isSearching && !rowMatches(i, columns)))
            result += VscFlatRow(depth, i, columns, hasChildren, isExpanded, rowSelectionProvider, path)
            if (isExpanded && children != null) {
                addLevel(
                    children.rowCount, children.columns, children.childrenProvider,
                    children.rowSelectionProvider, depth + 1, path,
                )
            }
        }
    }

    addLevel(
        descriptor.rowCount, descriptor.columns, descriptor.childrenProvider,
        descriptor.rowSelectionProvider, 0, emptyList(),
    )
    return result
}

private const val CHEVRON_RIGHT = "\u25B6"
private const val CHEVRON_DOWN = "\u25BC"

/**
 * Renders an HTML `<select>` dropdown for predefined row filters.
 */
@Composable
private fun FilterDropdownVsc(
    filters: List<PredefinedFilter>,
    selectedIndex: Int?,
    onSelected: (Int?) -> Unit,
) {
    val currentValue = selectedIndex?.toString() ?: "all"

    org.jetbrains.compose.web.dom.Select({
        classes("hexana-filter-dropdown")
        style {
            property("font-size", "12px")
            property("margin-right", "6px")
            property("background", "var(--vscode-dropdown-background, #3c3c3c)")
            property("color", "var(--vscode-dropdown-foreground, #ccc)")
            property("border", "1px solid var(--vscode-dropdown-border, #555)")
            property("border-radius", "2px")
            property("padding", "2px 4px")
        }
        onChange { event ->
            val value = (event.target as? org.w3c.dom.HTMLSelectElement)?.value
            onSelected(if (value == "all") null else value?.toIntOrNull())
        }
        attr("value", currentValue)
    }) {
        filters.forEachIndexed { index, filter ->
            org.jetbrains.compose.web.dom.Option(
                value = index.toString(),
                attrs = {
                    if (index.toString() == currentValue) attr("selected", "selected")
                },
            ) { Text(filter.label) }
        }
        org.jetbrains.compose.web.dom.Option(
            value = "all",
            attrs = {
                if (currentValue == "all") attr("selected", "selected")
            },
        ) { Text("Show all") }
    }
}

/**
 * Applies a multi-range highlight from [rowHighlightRangesProvider] to the VS Code hex view.
 * The web hex viewer has no multi-range highlight API, so this degrades to the bounding span
 * [min(offset), max(offset+size)) covering all ranges. When [ranges] is empty the selection
 * is left unchanged.
 */
private fun applyHighlightRangesVsc(ranges: List<HexRange>, selection: HexSelectionState) {
    if (ranges.isEmpty()) return
    val minOffset = ranges.minOf { it.offset }.toInt()
    val maxEnd = ranges.maxOf { it.offset + it.size }.toInt()
    selection.anchorIndex = minOffset
    selection.activeIndex = maxEnd - 1
    selection.cursorIndex = minOffset
    scrollHexViewerTo(minOffset)
}

@Composable
private fun TreeTableTabContent(
    descriptor: TableTabDescriptor,
    selection: HexSelectionState,
) {
    val expandedPaths = remember(descriptor) { mutableStateListOf<List<Int>>() }
    var sortColumnIndex by remember { mutableStateOf(descriptor.defaultSortColumn ?: -1) }
    var sortAscending by remember { mutableStateOf(descriptor.defaultSortAscending) }

    // --- Predefined filter state ---
    val filters = descriptor.predefinedFilters
    var selectedFilterIndex: Int? by remember(descriptor) {
        mutableStateOf(if (filters.isNotEmpty()) 0 else null)
    }
    val activeFilter: PredefinedFilter? = selectedFilterIndex?.let { filters.getOrNull(it) }

    // --- Search state ---
    var searchQuery by remember(descriptor) { mutableStateOf("") }
    var isSearchVisible by remember(descriptor) { mutableStateOf(descriptor.alwaysShowSearch) }
    var debouncedQuery by remember(descriptor) { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(300)
        debouncedQuery = searchQuery
    }

    val flatRows = remember(descriptor, expandedPaths.toList(), sortColumnIndex, sortAscending, debouncedQuery, activeFilter) {
        flattenTreeVsc(descriptor, expandedPaths.toSet(), sortColumnIndex, sortAscending, debouncedQuery, activeFilter)
    }

    // Track which flattened row is currently focused (keyboard-navigated or clicked).
    var focusedFlatIndex by remember(descriptor) { mutableStateOf(-1) }
    // Revision counter to force scroll even when focusedFlatIndex doesn't change.
    var scrollRevision by remember(descriptor) { mutableStateOf(0) }

    val headers = buildList {
        add("")
        descriptor.columns.forEach { add(it.name) }
    }

    val aligns = buildList {
        add("center")
        descriptor.columns.forEach { col ->
            when (col.widthBehavior) {
                is ColumnWidth.Fill -> add("left")
                is ColumnWidth.Measured -> add("left")
                is ColumnWidth.MeasuredMin -> add("left")
            }
        }
    }

    // Compute column width hints (in `ch` units) for Measured/MeasuredMin columns.
    val columnWidthsCh = buildList {
        add(null) // action column
        descriptor.columns.forEach { col ->
            when (val w = col.widthBehavior) {
                is ColumnWidth.Measured -> {
                    val sample = maxOf(col.name.length + 2, w.sample.length)
                    add(sample)
                }
                is ColumnWidth.MeasuredMin -> {
                    val sample = maxOf(col.name.length + 2, w.sample.length)
                    add(sample)
                }
                is ColumnWidth.Fill -> add(null)
            }
        }
    }

    // Sort state for header indicators; offset by +1 for the action column.
    val sortState = remember { TableSortState() }
    sortState.columnIndex = if (sortColumnIndex >= 0) sortColumnIndex + 1 else -1
    sortState.direction = if (sortColumnIndex >= 0) {
        if (sortAscending) SortDirection.ASC else SortDirection.DESC
    } else SortDirection.NONE

    // --- Virtual scroll state ---
    val rowHeight = 24
    val bufferRows = 20
    var scrollTop by remember { mutableStateOf(0.0) }
    var viewportHeight by remember { mutableStateOf(600.0) }

    val totalRows = flatRows.size
    val firstVisible = maxOf(0, (scrollTop / rowHeight).toInt() - bufferRows)
    val lastVisible = minOf(totalRows - 1, ((scrollTop + viewportHeight) / rowHeight).toInt() + bufferRows)

    val resultsLeading: (@Composable () -> Unit)? = if (debouncedQuery.isNotEmpty()) {
        {
            Span({ style { property("font-size", "11px"); color(Color("#888")) } }) {
                Text("${flatRows.size} results")
            }
        }
    } else null

    val filterLeading: (@Composable () -> Unit)? = if (filters.isNotEmpty()) {
        {
            FilterDropdownVsc(
                filters = filters,
                selectedIndex = selectedFilterIndex,
                onSelected = { selectedFilterIndex = it },
            )
        }
    } else null

    val searchBarLeading: (@Composable () -> Unit)? = when {
        filterLeading != null && resultsLeading != null -> {
            { filterLeading(); resultsLeading() }
        }
        filterLeading != null -> filterLeading
        else -> resultsLeading
    }

    val treemapData = descriptor.treemapData

    // When treemapData is present, wrap the table and treemap in a horizontal flex row.
    val tableContent: @Composable () -> Unit = {
        TreeTableBody(
            descriptor = descriptor,
            selection = selection,
            expandedPaths = expandedPaths,
            flatRows = flatRows,
            headers = headers,
            aligns = aligns,
            columnWidthsCh = columnWidthsCh,
            sortState = sortState,
            sortColumnIndex = sortColumnIndex,
            onSortColumnChange = { sortColumnIndex = it },
            sortAscending = sortAscending,
            onSortAscendingChange = { sortAscending = it },
            focusedFlatIndex = focusedFlatIndex,
            onFocusedFlatIndexChange = { focusedFlatIndex = it },
            scrollRevision = scrollRevision,
            isSearchVisible = isSearchVisible,
            onSearchVisibleChange = { isSearchVisible = it },
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            searchBarLeading = searchBarLeading,
        )
    }

    if (treemapData != null) {
        var treemapFraction by remember { mutableStateOf(DEFAULT_TREEMAP_FRACTION) }
        Div({
            style {
                display(DisplayStyle.Flex)
                property("flex-direction", "row")
                property("flex", "1")
                property("min-height", "0")
            }
        }) {
            Div({
                style {
                    property("flex", "${1f - treemapFraction}")
                    property("min-width", "0")
                    property("min-height", "0")
                    display(DisplayStyle.Flex)
                    property("flex-direction", "column")
                }
            }) {
                tableContent()
            }
            VerticalResizeDivider(
                onDrag = { deltaFraction ->
                    treemapFraction = (treemapFraction - deltaFraction)
                        .coerceIn(MIN_TREEMAP_FRACTION, MAX_TREEMAP_FRACTION)
                },
            )
            Div({
                style {
                    property("flex", "$treemapFraction")
                    property("min-width", "0")
                    property("min-height", "0")
                    property("padding-left", "4px")
                }
            }) {
                SizeTreemapCanvas(
                    treemapData = treemapData,
                    onNodeClicked = { treePath ->
                        if (treePath.isEmpty()) return@SizeTreemapCanvas
                        // Expand only the ancestor paths (not the target
                        // itself) so the target row becomes visible without
                        // expanding it.  This avoids the recomposition-
                        // driven scroll drift that occurred when the
                        // clicked node was also expanded.
                        for (i in 1 until treePath.size) {
                            val ancestorPath = treePath.subList(0, i)
                            if (ancestorPath !in expandedPaths) {
                                expandedPaths.add(ancestorPath)
                            }
                        }
                        // Find and select the target row.
                        val updatedRows = flattenTreeVsc(
                            descriptor, expandedPaths.toSet(),
                            sortColumnIndex, sortAscending,
                            debouncedQuery, activeFilter,
                        )
                        val targetIndex = updatedRows.indexOfFirst { it.treePath == treePath }
                        if (targetIndex >= 0) {
                            focusedFlatIndex = targetIndex
                            scrollRevision++
                            val row = updatedRows[targetIndex]
                            val selData = row.rowSelectionProvider(row.index)
                            if (selData != null) {
                                selection.anchorIndex = selData.offset
                                selection.activeIndex = selData.offset + selData.size - 1
                                selection.cursorIndex = selData.offset
                                scrollHexViewerTo(selData.offset)
                            }
                        }
                    },
                )
            }
        }
    } else {
        tableContent()
    }
}

private const val DEFAULT_TREEMAP_FRACTION = 0.35f
private const val MIN_TREEMAP_FRACTION = 0.15f
private const val MAX_TREEMAP_FRACTION = 0.6f

/**
 * A thin vertical divider that can be dragged horizontally to resize
 * the panels to its left and right.
 */
@Composable
private fun VerticalResizeDivider(onDrag: (deltaFraction: Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var startX by remember { mutableStateOf(0.0) }
    var containerWidth by remember { mutableStateOf(1.0) }

    DisposableEffect(dragging) {
        if (!dragging) return@DisposableEffect onDispose {}

        val moveHandler: (Event) -> Unit = { event ->
            val me = event as? MouseEvent
            if (me != null) {
                me.preventDefault()
                val delta = me.clientX.toDouble() - startX
                startX = me.clientX.toDouble()
                if (containerWidth > 0) {
                    onDrag((delta / containerWidth).toFloat())
                }
            }
        }

        val upHandler: (Event) -> Unit = {
            dragging = false
        }

        kotlinx.browser.window.addEventListener("mousemove", moveHandler)
        kotlinx.browser.window.addEventListener("mouseup", upHandler)
        onDispose {
            kotlinx.browser.window.removeEventListener("mousemove", moveHandler)
            kotlinx.browser.window.removeEventListener("mouseup", upHandler)
        }
    }

    Div({
        style {
            property("flex-shrink", "0")
            property("width", "8px")
            property("cursor", "col-resize")
            property("background", "transparent")
            property("position", "relative")
            property("z-index", "2")
            property("margin", "0 -3px")
            property("padding", "0 3px")
            display(DisplayStyle.Flex)
            property("align-items", "stretch")
            property("justify-content", "center")
        }
        onMouseDown { event ->
            if (event.nativeEvent.asDynamic().button as Int != 0) return@onMouseDown
            event.preventDefault()
            startX = (event.nativeEvent as MouseEvent).clientX.toDouble()
            val parent = (event.nativeEvent.target as? HTMLElement)?.parentElement
            containerWidth = parent?.getBoundingClientRect()?.width?.toDouble() ?: 1.0
            dragging = true
        }
    }) {
        Div({
            style {
                property("width", "1px")
                property("background", "var(--hexana-border, #333)")
                property("align-self", "stretch")
            }
        })
    }
}

/**
 * The inner tree table body extracted so it can be used both standalone
 * and inside a treemap split layout.
 */
@Composable
private fun TreeTableBody(
    descriptor: TableTabDescriptor,
    selection: HexSelectionState,
    expandedPaths: MutableList<List<Int>>,
    flatRows: List<VscFlatRow>,
    headers: List<String>,
    aligns: List<String>,
    columnWidthsCh: List<Int?>,
    sortState: TableSortState,
    sortColumnIndex: Int,
    onSortColumnChange: (Int) -> Unit,
    sortAscending: Boolean,
    onSortAscendingChange: (Boolean) -> Unit,
    focusedFlatIndex: Int,
    onFocusedFlatIndexChange: (Int) -> Unit,
    scrollRevision: Int,
    isSearchVisible: Boolean,
    onSearchVisibleChange: (Boolean) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchBarLeading: (@Composable () -> Unit)?,
) {
    // --- Virtual scroll state ---
    val rowHeight = 24
    val bufferRows = 20
    var scrollTop by remember { mutableStateOf(0.0) }
    var viewportHeight by remember { mutableStateOf(600.0) }
    var tableWrapperElement by remember { mutableStateOf<org.w3c.dom.Element?>(null) }
    // Programmatic-scroll lock.  While active, the scroll-event listener
    // does not update the Compose `scrollTop` state — instead it re-pins
    // the DOM scroll position to `pinnedScrollTop` so that recomposition-
    // driven spacer changes cannot drift the view away from the target row.
    // Using a plain array (not Compose state) so toggling won't recompose.
    val suppressScroll = remember { booleanArrayOf(false) }
    val pinnedScrollTop = remember { doubleArrayOf(0.0) }
    // Timer handle for the deferred unlock; stored so we can cancel it on
    // re-entry (rapid successive treemap clicks).
    val unlockTimerHandle = remember { intArrayOf(0) }

    // Scroll the table to make the focused row visible when it changes.
    // For treemap clicks (scrollRevision > 0) we use a multi-phase scroll:
    //   1. Set scrollTop so the virtual-scroll window renders the target row's
    //      region (spacers update on recomposition).
    //   2. After the DOM stabilises, locate the actual focused row element
    //      (marked with data-focused) and use scrollIntoView, then adjust
    //      for the sticky header.
    LaunchedEffect(focusedFlatIndex, scrollRevision) {
        val idx = focusedFlatIndex
        val wrapper = tableWrapperElement ?: return@LaunchedEffect
        if (idx < 0 || scrollRevision == 0) return@LaunchedEffect

        // Cancel any pending unlock from a previous programmatic scroll.
        if (unlockTimerHandle[0] != 0) {
            kotlinx.browser.window.clearTimeout(unlockTimerHandle[0])
            unlockTimerHandle[0] = 0
        }

        // Suppress the scroll listener and pin to the target position.
        suppressScroll[0] = true

        // Phase 1: set scrollTop so the virtual-scroll window renders the
        // region around the target row.  Use the raw row offset (without
        // header adjustment) — the sticky header is a viewport concern
        // handled in Phase 2 via scrollIntoView.
        val targetScrollTop = (idx * rowHeight).toDouble()
        pinnedScrollTop[0] = targetScrollTop
        scrollTop = targetScrollTop
        wrapper.scrollTop = targetScrollTop
        // Yield so Compose recomposes with the updated scrollTop and the
        // DOM (spacers) reflects the new virtual-scroll window.
        delay(1.toDuration(DurationUnit.MILLISECONDS))

        // Phase 2: locate the actual focused row element and use
        // scrollIntoView to position it correctly, then adjust for the
        // sticky header that overlaps the top of the scroll viewport.
        val focusedRow = wrapper.querySelector("tr[data-focused]")
        if (focusedRow != null) {
            focusedRow.asDynamic().scrollIntoView(
                js("({block: 'nearest', inline: 'nearest'})")
            )
            val headerHeight = wrapper.querySelector("thead")
                ?.getBoundingClientRect()?.height ?: 0.0
            val rowRect = focusedRow.getBoundingClientRect()
            val wrapperRect = wrapper.getBoundingClientRect()
            val visibleTop = wrapperRect.top + 3 * headerHeight
            if (rowRect.top < visibleTop) {
                wrapper.scrollTop -= (visibleTop - rowRect.top)
            }
        }

        // Pin to the final position so the re-pin listener keeps the
        // scroll stable during recomposition settle.
        pinnedScrollTop[0] = wrapper.scrollTop
        scrollTop = wrapper.scrollTop

        // Schedule unlock after a generous delay.  Recomposition on large
        // tables can take many frames; a fixed timeout is more reliable
        // than counting animation frames.
        unlockTimerHandle[0] = kotlinx.browser.window.setTimeout({
            scrollTop = wrapper.scrollTop
            suppressScroll[0] = false
            unlockTimerHandle[0] = 0
        }, 500)
    }

    // Re-focus the [data-focused] row after recomposition so that keyboard
    // navigation continues to work.  Expand/collapse (ArrowLeft/Right) and
    // ArrowUp/Down in tree tables trigger recomposition that may destroy the
    // previously focused <tr>; without this effect, focus falls to <body>
    // and subsequent arrow keys are intercepted by the hex viewer.
    // Only re-focus — do NOT scrollIntoView here.  The
    // PanelTableRowKeyboardHandler already scrolls the target row into
    // view; doing it again from this effect causes a feedback loop with
    // the virtual-scroll spacer recalculation on large tables, leading
    // to infinite scrolling.
    LaunchedEffect(focusedFlatIndex, flatRows) {
        if (focusedFlatIndex < 0) return@LaunchedEffect
        // Yield a frame so Compose settles the DOM.
        delay(1)
        val wrapper = tableWrapperElement ?: return@LaunchedEffect
        val focusedRow = wrapper.querySelector("tr[data-focused]") as? HTMLElement
        if (focusedRow != null && focusedRow != kotlinx.browser.document.activeElement) {
            focusedRow.asDynamic().focus(js("({preventScroll: true})"))
        }
    }

    val totalRows = flatRows.size
    val firstVisible = maxOf(0, (scrollTop / rowHeight).toInt() - bufferRows)
    val lastVisible = minOf(totalRows - 1, ((scrollTop + viewportHeight) / rowHeight).toInt() + bufferRows)

    Div({
        classes("hexana-searchable-table")
        tabIndex(-1)
        style {
            property("outline", "none")
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("min-height", "0")
            property("flex", "1")
        }
        ref { container ->
            val keyListener = EventListener { event ->
                val ke = event as? KeyboardEvent ?: return@EventListener
                val target = ke.target as? HTMLElement
                if (target != null && !container.contains(target) && target != container) {
                    val tabContent = container.closest(".hexana-tab-content")
                    if (target != tabContent) return@EventListener
                }
                if (ke.key == "f" && (ke.metaKey || ke.ctrlKey)) {
                    ke.preventDefault()
                    ke.stopPropagation()
                    onSearchVisibleChange(true)
                    kotlinx.browser.window.requestAnimationFrame {
                        container.querySelector(".hexana-search-input")
                            ?.let { (it as? org.w3c.dom.HTMLInputElement)?.focus() }
                    }
                }
                if (ke.key == "Escape" && isSearchVisible && !descriptor.alwaysShowSearch) {
                    onSearchVisibleChange(false)
                    onSearchQueryChange("")
                }
            }
            kotlinx.browser.window.addEventListener("keydown", keyListener, true)
            onDispose {
                kotlinx.browser.window.removeEventListener("keydown", keyListener, true)
            }
        }
    }) {
        // Search bar (outside the scrollable area)
        if (isSearchVisible || searchQuery.isNotEmpty()) {
            SearchBar(
                query = searchQuery,
                onQueryChange = { onSearchQueryChange(it) },
                onClose = {
                    onSearchQueryChange("")
                    if (!descriptor.alwaysShowSearch) onSearchVisibleChange(false)
                },
                leading = searchBarLeading,
            )
        }

        // Table with virtual scrolling
        Div({
            style {
                property("flex", "1")
                property("overflow-y", "auto")
                property("min-height", "0")
                // Account for the sticky <thead> so that scrollIntoView
                // positions rows below the header, not behind it.
                property("scroll-padding-top", "${rowHeight}px")
            }
            ref { tableWrapper ->
                tableWrapperElement = tableWrapper
                viewportHeight = tableWrapper.clientHeight.toDouble()
                scrollTop = tableWrapper.scrollTop

                val listener = EventListener {
                    if (suppressScroll[0]) {
                        // Re-pin: recomposition may have changed spacer
                        // heights causing the browser to adjust scrollTop.
                        // Force it back to the pinned position.
                        if (kotlin.math.abs(tableWrapper.scrollTop - pinnedScrollTop[0]) > 1.0) {
                            tableWrapper.scrollTop = pinnedScrollTop[0]
                        }
                    } else {
                        viewportHeight = tableWrapper.clientHeight.toDouble()
                        scrollTop = tableWrapper.scrollTop
                    }
                }
                tableWrapper.addEventListener("scroll", listener)
                onDispose {
                    tableWrapper.removeEventListener("scroll", listener)
                    tableWrapperElement = null
                }
            }
        }) {
            Table({
                classes("hexana-panel-table")
            }) {
                // Header
                Thead {
                    Tr({
                        style {
                            property("border-bottom", "1px solid var(--hexana-border, #333)")
                        }
                    }) {
                        headers.forEachIndexed { index, header ->
                            val align = aligns.getOrElse(index) { "left" }
                            val isNarrow = header == "ID" || header == ""
                            val isSorted = sortState.columnIndex == index
                            val indicator = when {
                                isSorted && sortState.direction == SortDirection.ASC -> " \u25B2"
                                isSorted && sortState.direction == SortDirection.DESC -> " \u25BC"
                                else -> ""
                            }
                            val widthCh = columnWidthsCh.getOrNull(index)
                            Th({
                                classes("panel-th")
                                if (isNarrow) classes("panel-th-narrow")
                                if (header.isNotEmpty()) classes("panel-th-sortable")
                                style {
                                    property("text-align", align)
                                    if (widthCh != null) {
                                        property("width", "${widthCh}ch")
                                    }
                                    if (header.isNotEmpty()) {
                                        property("cursor", "pointer")
                                        property("user-select", "none")
                                    }
                                }
                                if (header.isNotEmpty()) {
                                    onClick {
                                        val dataColIndex = index - 1
                                        if (dataColIndex >= 0) {
                                            if (sortColumnIndex == dataColIndex) {
                                                onSortAscendingChange(!sortAscending)
                                            } else {
                                                onSortColumnChange(dataColIndex)
                                                onSortAscendingChange(true)
                                            }
                                        }
                                    }
                                }
                            }) { Text(header + indicator) }
                        }
                    }
                }

                // Virtualized body
                Tbody {
                    // Top spacer
                    if (firstVisible > 0) {
                        Tr({
                            style { property("height", "${firstVisible * rowHeight}px") }
                        }) {}
                    }

                    for (flatIndex in firstVisible..lastVisible) {
                        if (flatIndex < 0 || flatIndex >= flatRows.size) continue
                        val row = flatRows[flatIndex]
                        val selData = row.rowSelectionProvider(row.index)
                        val isHighlighted = if (focusedFlatIndex >= 0) {
                            flatIndex == focusedFlatIndex
                        } else {
                            selData != null && selection.hasSelection &&
                                selection.selectionStart == selData.offset &&
                                selection.selectionEnd == selData.offset + selData.size - 1
                        }

                        PanelTableRow(
                            isHighlighted = isHighlighted,
                            attrs = if (isHighlighted) mapOf("data-focused" to "") else emptyMap(),
                            onClick = {
                                onFocusedFlatIndexChange(flatIndex)
                                if (row.hasChildren) {
                                    if (row.isExpanded) {
                                        expandedPaths.remove(row.treePath)
                                    } else {
                                        expandedPaths.add(row.treePath)
                                    }
                                } else {
                                    val highlightProvider = descriptor.rowHighlightRangesProvider
                                    if (highlightProvider != null && row.depth == 0) {
                                        applyHighlightRangesVsc(highlightProvider(row.index), selection)
                                    } else if (selData != null) {
                                        selection.anchorIndex = selData.offset
                                        selection.activeIndex = selData.offset + selData.size - 1
                                        selection.cursorIndex = selData.offset
                                        scrollHexViewerTo(selData.offset)
                                    }
                                }
                            },
                            onSelect = {
                                onFocusedFlatIndexChange(flatIndex)
                                val highlightProvider = descriptor.rowHighlightRangesProvider
                                if (highlightProvider != null && row.depth == 0) {
                                    applyHighlightRangesVsc(highlightProvider(row.index), selection)
                                } else if (selData != null) {
                                    selection.anchorIndex = selData.offset
                                    selection.activeIndex = selData.offset + selData.size - 1
                                    selection.cursorIndex = selData.offset
                                    scrollHexViewerTo(selData.offset)
                                }
                            },
                            onActivate = {
                                if (row.hasChildren) {
                                    if (row.isExpanded) {
                                        expandedPaths.remove(row.treePath)
                                    } else {
                                        expandedPaths.add(row.treePath)
                                    }
                                }
                            },
                        ) {
                            // Action column placeholder
                            Td({ classes("cell-id"); style { property("width", "12px") } }) {}

                            // Data columns
                            for ((colIndex, col) in descriptor.columns.withIndex()) {
                                val cell = row.columns[colIndex].cellProvider(row.index)
                                val cssClass = when (col.widthBehavior) {
                                    is ColumnWidth.Fill -> "cell-muted"
                                    is ColumnWidth.Measured -> "cell"
                                    is ColumnWidth.MeasuredMin -> "cell"
                                }

                                Td({
                                    classes(cssClass)
                                    attr("title", cell.tooltip ?: cell.representation)
                                }) {
                                    if (colIndex == 0) {
                                        val indentPx = row.depth * 16
                                        if (indentPx > 0) {
                                            Span({ style { property("display", "inline-block"); property("width", "${indentPx}px") } }) {}
                                        }
                                        if (row.hasChildren) {
                                            Span({
                                                style {
                                                    property("cursor", "pointer")
                                                    property("display", "inline-block")
                                                    property("width", "16px")
                                                    property("text-align", "center")
                                                    property("font-size", "10px")
                                                    property("user-select", "none")
                                                }
                                                onClick { event ->
                                                    event.stopPropagation()
                                                    if (row.isExpanded) {
                                                        expandedPaths.remove(row.treePath)
                                                    } else {
                                                        expandedPaths.add(row.treePath)
                                                    }
                                                }
                                            }) {
                                                Text(if (row.isExpanded) CHEVRON_DOWN else CHEVRON_RIGHT)
                                            }
                                        } else {
                                            Span({ style { property("display", "inline-block"); property("width", "16px") } }) {}
                                        }
                                    }
                                    Text(cell.representation)
                                }
                            }
                        }
                    }

                    // Bottom spacer
                    val bottomRows = totalRows - lastVisible - 1
                    if (bottomRows > 0) {
                        Tr({
                            style { property("height", "${bottomRows * rowHeight}px") }
                        }) {}
                    }
                }
            }
        }
    }
}
