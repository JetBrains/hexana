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
import org.jetbrains.hexana.shared.RowTint
import org.jetbrains.hexana.shared.RowActionKind
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
 * Small clickable glyph in a table row's action column.
 *
 * Uses Unicode glyphs rather than codicons: the webview loads no icon font (its CSP
 * allowlists no external `font-src`), so a glyph is the only thing guaranteed to render.
 */
@Composable
internal fun RowActionButton(kind: RowActionKind, tooltip: String, onClick: () -> Unit) {
    Span({
        classes("hexana-row-action")
        classes(
            when (kind) {
                RowActionKind.Run -> "hexana-row-action-run"
                RowActionKind.Documentation -> "hexana-row-action-docs"
            }
        )
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
            when (kind) {
                RowActionKind.Run ->
                    property("color", "var(--vscode-debugIcon-startForeground, #89d185)")
                // Link-coloured, since the action leaves the editor for a browser.
                RowActionKind.Documentation ->
                    property("color", "var(--vscode-textLink-foreground, #3794ff)")
            }
        }
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        Text(
            when (kind) {
                RowActionKind.Run -> "\u25B6"
                // North-east arrow: the same "opens elsewhere" affordance VS Code uses.
                RowActionKind.Documentation -> "\u2197"
            }
        )
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
    if (descriptor.prologueText != null || descriptor.prologueActions.isNotEmpty()) {
        Div({ style {
            property("margin-bottom", "8px")
            property("font-size", "12px")
        } }) {
            descriptor.prologueText?.let { Text(it) }
            descriptor.prologueActions.forEachIndexed { index, prologueAction ->
                // Inline, so the links flow with the prose; a bullet keeps two apart.
                Text(if (index == 0 && descriptor.prologueText == null) "" else if (index == 0) " " else " \u2022 ")
                Span({
                    // A link in everything but markup, so it has to say so and be reachable
                    // from the keyboard: a Span with an onClick is invisible to Tab and to a
                    // screen reader.
                    attr("role", "link")
                    tabIndex(0)
                    style {
                        property("cursor", "pointer")
                        property("color", "var(--vscode-textLink-foreground, #3794ff)")
                    }
                    onClick { event ->
                        event.stopPropagation()
                        prologueAction.action()
                    }
                    onKeyDown { event ->
                        val keyboardEvent = event.nativeEvent as? KeyboardEvent ?: return@onKeyDown
                        // Enter and Space are what a native link and a native button answer to.
                        if (keyboardEvent.key == "Enter" || keyboardEvent.key == " ") {
                            keyboardEvent.preventDefault()
                            keyboardEvent.stopPropagation()
                            prologueAction.action()
                        }
                    }
                }) {
                    Text(prologueAction.label)
                }
            }
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

    // --- Context menu state ---
    val hasContextMenu = descriptor.contextMenuItems.isNotEmpty() || descriptor.dynamicContextMenuProvider != null
    var contextMenuState by remember { mutableStateOf<ContextMenuState?>(null) }

    // --- Predefined filter state ---
    val filters = descriptor.predefinedFilters
    var selectedFilterIndex: Int? by remember(descriptor) {
        mutableStateOf(if (filters.isNotEmpty() && !descriptor.defaultFilterShowAll) 0 else null)
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

    // Independent of the ID column: a tab can hide row numbers and still offer a row action
    // (the Compatibility tab's "Docs" is one), and the two used to be rendered together.
    val showAction = descriptor.rowAction != null

    val headers = buildList {
        if (showAction) add("")
        if (showId) add("ID")
        descriptor.columns.forEach { add(it.name) }
    }

    val aligns = buildList {
        if (showAction) add("center")
        if (showId) add("right")
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
        if (showAction) add(2) // action column – just enough for the glyph
        if (showId) add(idWidthCh) // ID column – sized to fit the largest row number
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
    // The descriptor uses ID_COLUMN (-1) for the implicit ID column; the rendered headers
    // prepend an action-column placeholder and/or "ID" independently, so we resolve the
    // actual header index here.
    // When the descriptor does not specify a default sort column (null), fall back
    // to the ID column so the sorting arrow is always visible — matching the old
    // per-tab behaviour where SearchableTable auto-detected the "ID" header.
    val idHeaderIndex = headers.indexOf("ID").takeIf { it >= 0 }
    val headerOffset = (if (showAction) 1 else 0) + (if (showId) 1 else 0)
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
                            backgroundColor = descriptor.rowTintProvider?.invoke(index)?.let(::rowTintCss),
                            onClick = {
                                applyRowDetailVsc(descriptor, index, selection)
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
                            onContextMenu = if (hasContextMenu) { { x, y ->
                                contextMenuState = ContextMenuState(index, x, y)
                            } } else null,
                        ) {
                            val rowAction = descriptor.rowAction
                            if (rowAction != null) {
                                Td({ classes("cell-id"); style { property("width", "12px") } }) {
                                    val tooltip = rowAction.tooltipProvider(index)
                                    if (tooltip != null) {
                                        RowActionButton(
                                            kind = rowAction.kind,
                                            tooltip = tooltip,
                                            onClick = { rowAction.action(index) },
                                        )
                                    }
                                }
                            }
                            if (showId) {
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
        // Context menu overlay
        contextMenuState?.let { state ->
            RowContextMenu(
                rowIndex = state.rowIndex,
                x = state.x,
                y = state.y,
                descriptor = descriptor,
                onDismiss = { contextMenuState = null },
            )
        }
    }
}

/**
 * Tracks the position and row index of an open context menu.
 */
private data class ContextMenuState(val rowIndex: Int, val x: Int, val y: Int)

/**
 * Renders a floating context menu for a table row, showing items from
 * [TableTabDescriptor.contextMenuItems] and [TableTabDescriptor.dynamicContextMenuProvider].
 */
@Composable
private fun RowContextMenu(
    rowIndex: Int,
    x: Int,
    y: Int,
    descriptor: TableTabDescriptor,
    onDismiss: () -> Unit,
) {
    val items = descriptor.contextMenuItems.filter { it.isVisible(rowIndex) }
    val dynamicItems = descriptor.dynamicContextMenuProvider?.invoke(rowIndex) ?: emptyList()
    if (items.isEmpty() && dynamicItems.isEmpty()) {
        onDismiss()
        return
    }

    // Dismiss on any click outside the menu
    DisposableEffect(Unit) {
        val listener: (Event) -> Unit = { onDismiss() }
        kotlinx.browser.window.addEventListener("mousedown", listener)
        onDispose { kotlinx.browser.window.removeEventListener("mousedown", listener) }
    }

    Div({
        style {
            property("position", "fixed")
            property("left", "${x}px")
            property("top", "${y}px")
            property("z-index", "10000")
            property("background", "var(--vscode-menu-background, #252526)")
            property("color", "var(--vscode-menu-foreground, #ccc)")
            property("border", "1px solid var(--vscode-menu-border, #454545)")
            property("border-radius", "4px")
            property("padding", "4px 0")
            property("box-shadow", "0 2px 8px rgba(0,0,0,0.4)")
            property("font-size", "13px")
            property("min-width", "160px")
        }
        // Prevent the mousedown dismiss listener from firing on the menu itself
        onMouseDown { it.stopPropagation() }
    }) {
        for (item in items) {
            Div({
                classes("hexana-context-menu-item")
                style {
                    property("padding", "4px 16px")
                    property("cursor", "pointer")
                    property("white-space", "nowrap")
                }
                onClick {
                    onDismiss()
                    item.action(rowIndex)
                }
                onMouseOver { (it.target as? HTMLElement)?.style?.setProperty("background", "var(--vscode-menu-selectionBackground, #094771)") }
                onMouseOut { (it.target as? HTMLElement)?.style?.removeProperty("background") }
            }) {
                Text(item.labelProvider(rowIndex))
            }
        }
        for (entry in dynamicItems) {
            Div({
                classes("hexana-context-menu-item")
                style {
                    property("padding", "4px 16px")
                    property("cursor", "pointer")
                    property("white-space", "nowrap")
                }
                onClick {
                    onDismiss()
                    entry.action()
                }
                onMouseOver { (it.target as? HTMLElement)?.style?.setProperty("background", "var(--vscode-menu-selectionBackground, #094771)") }
                onMouseOut { (it.target as? HTMLElement)?.style?.removeProperty("background") }
            }) {
                Text(entry.label)
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

internal fun compareTableValues(first: Any?, second: Any?): Int = when {
    first is Number && second is Number -> {
        if (first.isIntegralTableValue() && second.isIntegralTableValue()) {
            first.toLong().compareTo(second.toLong())
        } else {
            first.toDouble().compareTo(second.toDouble())
        }
    }
    first is String && second is String -> first.compareTo(second)
    else -> 0
}

private fun Number.isIntegralTableValue(): Boolean =
    this is Byte || this is Short || this is Int || this is Long

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
            val cmp = compareTableValues(va, vb)
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

/**
 * Incrementally expands a single node in the flat row list by computing
 * only its direct/nested children and splicing them in after the parent row.
 * Returns the updated list with the parent row's [VscFlatRow.isExpanded]
 * flag set to true.
 */
private fun expandNodeIncremental(
    flatRows: List<VscFlatRow>,
    parentFlatIndex: Int,
    expandedPaths: Set<List<Int>>,
    descriptor: TableTabDescriptor,
): List<VscFlatRow> {
    if (parentFlatIndex < 0 || parentFlatIndex >= flatRows.size) return flatRows
    val parentRow = flatRows[parentFlatIndex]
    if (!parentRow.hasChildren || parentRow.isExpanded) return flatRows

    val children = resolveChildrenProvider(parentRow, descriptor)?.invoke(parentRow.index) ?: return flatRows
    if (children.rowCount == 0) return flatRows

    // Build the child rows.  For the direct children of the expanded node
    // we resolve hasChildren accurately (one childrenProvider call per child)
    // so that leaf nodes don't show a misleading expand arrow.  For deeper
    // levels (grandchildren) the fast path in addLevelIncremental is used.
    val childRows = mutableListOf<VscFlatRow>()
    val childDepth = parentRow.depth + 1
    val childProvider = children.childrenProvider
    for (i in 0 until children.rowCount) {
        val path = parentRow.treePath + i
        val grandchildren = childProvider?.invoke(i)
        val hasChildren = grandchildren != null && grandchildren.rowCount > 0
        val isExpanded = hasChildren && path in expandedPaths
        childRows += VscFlatRow(
            childDepth, i, children.columns, hasChildren, isExpanded,
            children.rowSelectionProvider, path,
        )
        if (isExpanded && grandchildren != null) {
            addLevelIncremental(
                childRows, grandchildren.rowCount, grandchildren.columns,
                grandchildren.childrenProvider, grandchildren.rowSelectionProvider,
                childDepth + 1, path, expandedPaths, descriptor,
            )
        }
    }

    // Splice: replace parent with expanded version, insert children after it.
    val result = ArrayList<VscFlatRow>(flatRows.size + childRows.size)
    // Copy rows before parent.
    for (i in 0 until parentFlatIndex) result.add(flatRows[i])
    // Expanded parent.
    result.add(VscFlatRow(
        parentRow.depth, parentRow.index, parentRow.columns,
        parentRow.hasChildren, isExpanded = true,
        parentRow.rowSelectionProvider, parentRow.treePath,
    ))
    // Spliced children.
    result.addAll(childRows)
    // Copy rows after parent.
    for (i in (parentFlatIndex + 1) until flatRows.size) result.add(flatRows[i])
    return result
}

/**
 * Incrementally collapses a single node by removing all descendant rows
 * (rows whose [VscFlatRow.treePath] starts with the parent's path).
 * Returns the updated list with the parent row's [VscFlatRow.isExpanded]
 * flag set to false.
 */
private fun collapseNodeIncremental(
    flatRows: List<VscFlatRow>,
    parentFlatIndex: Int,
): List<VscFlatRow> {
    if (parentFlatIndex < 0 || parentFlatIndex >= flatRows.size) return flatRows
    val parentRow = flatRows[parentFlatIndex]
    if (!parentRow.isExpanded) return flatRows

    val parentPath = parentRow.treePath
    val parentDepth = parentRow.depth

    // Find the range of descendant rows (all rows immediately after parent
    // that have depth > parentDepth).
    var endExclusive = parentFlatIndex + 1
    while (endExclusive < flatRows.size && flatRows[endExclusive].depth > parentDepth) {
        endExclusive++
    }

    val result = ArrayList<VscFlatRow>(flatRows.size - (endExclusive - parentFlatIndex - 1))
    for (i in flatRows.indices) {
        if (i == parentFlatIndex) {
            result.add(VscFlatRow(
                parentRow.depth, parentRow.index, parentRow.columns,
                parentRow.hasChildren, isExpanded = false,
                parentRow.rowSelectionProvider, parentRow.treePath,
            ))
        } else if (i > parentFlatIndex && i < endExclusive) {
            // skip descendants
        } else {
            result.add(flatRows[i])
        }
    }
    return result
}

/** Recursively adds rows for one level of the tree (used by incremental expand). */
private fun addLevelIncremental(
    result: MutableList<VscFlatRow>,
    rowCount: Int,
    columns: List<ColumnDescriptor>,
    childrenProvider: ((Int) -> TableTabChildRows?)?,
    rowSelectionProvider: (Int) -> RowSelectionData?,
    depth: Int,
    parentPath: List<Int>,
    expandedPaths: Set<List<Int>>,
    descriptor: TableTabDescriptor,
) {
    // Fast path: when no child paths are expanded, skip expensive
    // childrenProvider calls entirely.  We mark hasChildren = false
    // (no arrow) to avoid showing expand chevrons on leaf nodes.
    // The hasChildren flag is resolved lazily when the user expands
    // the parent via expandNodeIncremental (which calls the real
    // childrenProvider for the toggled node only).
    val anyChildExpanded = childrenProvider != null &&
        expandedPaths.any { it.size > parentPath.size && it.subList(0, parentPath.size) == parentPath }

    for (i in 0 until rowCount) {
        val path = parentPath + i
        if (anyChildExpanded && path in expandedPaths) {
            // This child is expanded — resolve its children.
            val children = childrenProvider?.invoke(i)
            val hasChildren = children != null && children.rowCount > 0
            result += VscFlatRow(depth, i, columns, hasChildren, isExpanded = hasChildren, rowSelectionProvider, path)
            if (hasChildren && children != null) {
                addLevelIncremental(
                    result, children.rowCount, children.columns, children.childrenProvider,
                    children.rowSelectionProvider, depth + 1, path, expandedPaths, descriptor,
                )
            }
        } else {
            // Not expanded — resolve hasChildren accurately so leaf
            // nodes don't show a misleading expand arrow.  This path
            // is only reached for children of already-expanded nodes
            // (small subtrees), not for the 42k+ top-level case which
            // goes through expandNodeIncremental directly.
            val children = childrenProvider?.invoke(i)
            val hasChildren = children != null && children.rowCount > 0
            result += VscFlatRow(depth, i, columns, hasChildren, isExpanded = false, rowSelectionProvider, path)
        }
    }
}

/**
 * Resolves the [TableTabChildRows] provider for a given flat row by walking
 * up the tree path through the descriptor's childrenProvider chain.
 */
private fun resolveChildrenProvider(
    row: VscFlatRow,
    descriptor: TableTabDescriptor,
): ((Int) -> TableTabChildRows?)? {
    if (row.depth == 0) return descriptor.childrenProvider
    // Walk the tree path to find the childrenProvider at the parent level.
    var currentProvider = descriptor.childrenProvider ?: return null
    for (i in 0 until row.depth) {
        val idx = row.treePath[i]
        val children = currentProvider(idx) ?: return null
        if (i < row.depth - 1) {
            currentProvider = children.childrenProvider ?: return null
        } else {
            return children.childrenProvider
        }
    }
    return null
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
 * Pushes the clicked row's out-of-file bytes to the hex view's detail panel,
 * opening it the first time a row has any. A tab without a
 * [TableTabDescriptor.rowDetailProvider] leaves the panel untouched, so
 * clicking around elsewhere never disturbs what is on show. A row that has no
 * detail clears the bytes but keeps the panel open: it says so, rather than
 * flickering shut or leaving the previous row's plaintext up.
 */
/** The VS Code diff-editor colour for a [RowTint], falling back to a fixed tint outside VS Code. */
internal fun rowTintCss(tint: RowTint): String = when (tint) {
    RowTint.Added -> "var(--vscode-diffEditor-insertedTextBackground, rgba(0,255,0,0.1))"
    RowTint.Removed -> "var(--vscode-diffEditor-removedTextBackground, rgba(255,0,0,0.1))"
    RowTint.Modified -> "rgba(255,165,0,0.1)"
    RowTint.Moved -> "rgba(100,149,237,0.1)"
}

private fun applyRowDetailVsc(descriptor: TableTabDescriptor, index: Int, selection: HexSelectionState) {
    val provider = descriptor.rowDetailProvider ?: return
    val detail = provider(index)
    selection.rowDetail = detail
    if (detail != null) selection.showRowDetail = true
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
        mutableStateOf(if (filters.isNotEmpty() && !descriptor.defaultFilterShowAll) 0 else null)
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

    // Flat rows are computed incrementally for expand/collapse (fast path)
    // and fully recomputed only when sort, search, or filter changes.
    var flatRows by remember(descriptor) {
        mutableStateOf(flattenTreeVsc(descriptor, emptySet(), sortColumnIndex, sortAscending, debouncedQuery, activeFilter))
    }
    // Full recomputation when sort/search/filter changes.
    LaunchedEffect(descriptor, sortColumnIndex, sortAscending, debouncedQuery, activeFilter) {
        delay(1)
        flatRows = flattenTreeVsc(descriptor, expandedPaths.toSet(), sortColumnIndex, sortAscending, debouncedQuery, activeFilter)
    }

    // Helper to expand a node incrementally (called from click/keyboard handlers).
    val expandNode = { path: List<Int> ->
        if (path !in expandedPaths) {
            expandedPaths.add(path)
            val idx = flatRows.indexOfFirst { it.treePath == path }
            if (idx >= 0) {
                val updated = expandNodeIncremental(flatRows, idx, expandedPaths.toSet(), descriptor)
                if (updated !== flatRows) {
                    flatRows = updated
                } else {
                    // Expansion produced no children (leaf node with optimistic
                    // hasChildren flag).  Remove the path so the node isn't stuck
                    // and correct the hasChildren flag in the flat row.
                    expandedPaths.remove(path)
                    flatRows = flatRows.toMutableList().also { list ->
                        val row = list[idx]
                        list[idx] = VscFlatRow(
                            row.depth, row.index, row.columns,
                            hasChildren = false, row.isExpanded,
                            row.rowSelectionProvider, row.treePath,
                        )
                    }
                }
            }
        }
    }
    val collapseNode = { path: List<Int> ->
        if (path in expandedPaths) {
            val idx = flatRows.indexOfFirst { it.treePath == path }
            if (idx >= 0) {
                flatRows = collapseNodeIncremental(flatRows, idx)
            }
            expandedPaths.remove(path)
        }
    }
    val toggleNode = { path: List<Int>, isCurrentlyExpanded: Boolean ->
        if (isCurrentlyExpanded) collapseNode(path) else expandNode(path)
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
            toggleNode = toggleNode,
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
            onScrollRevisionReset = { scrollRevision = 0 },
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
                        // Recompute flat rows so the incremental state
                        // reflects the newly expanded ancestors.
                        val updatedRows = flattenTreeVsc(
                            descriptor, expandedPaths.toSet(),
                            sortColumnIndex, sortAscending,
                            debouncedQuery, activeFilter,
                        )
                        flatRows = updatedRows
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
    toggleNode: (path: List<Int>, isCurrentlyExpanded: Boolean) -> Unit,
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
    onScrollRevisionReset: () -> Unit,
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
    // RAF handle for debouncing scroll-event state updates.  Multiple
    // scroll events within the same animation frame are coalesced into
    // a single Compose state update, preventing the feedback loop where
    // scrollTop change → spacer recomposition → browser scroll adjust →
    // another scrollTop change → infinite loop.
    val scrollRafHandle = remember { intArrayOf(0) }
    // When true, scroll events are silently ignored (no state update,
    // no re-pin) while spacer recomposition settles after a Compose
    // state commit.  Unlike `suppressScroll` (which re-pins), this
    // flag simply skips processing so the browser's natural scroll
    // adjustment is accepted without triggering another state cycle.
    val settlingAfterCommit = remember { booleanArrayOf(false) }
    // The last scrollTop value that was committed to Compose state.
    // Used by the scroll listener to compare against the value it last
    // wrote rather than the (potentially stale) Compose `scrollTop`
    // captured in the listener closure.  This breaks the feedback loop
    // where recomposition-driven spacer changes cause the browser to
    // adjust scrollTop by a small amount that exceeds the threshold
    // when compared to the old Compose state but not when compared to
    // the value we actually committed.
    val committedScrollTop = remember { doubleArrayOf(0.0) }

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
        committedScrollTop[0] = wrapper.scrollTop
        scrollTop = wrapper.scrollTop

        // Schedule unlock after a generous delay.  Recomposition on large
        // tables can take many frames; a fixed timeout is more reliable
        // than counting animation frames.
        unlockTimerHandle[0] = kotlinx.browser.window.setTimeout({
            committedScrollTop[0] = wrapper.scrollTop
            scrollTop = wrapper.scrollTop
            suppressScroll[0] = false
            unlockTimerHandle[0] = 0
            // Reset scrollRevision so that subsequent focusedFlatIndex
            // changes (from regular row clicks) don't re-trigger this
            // heavy programmatic scroll effect.
            onScrollRevisionReset()
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
                committedScrollTop[0] = tableWrapper.scrollTop
                scrollTop = tableWrapper.scrollTop

                val listener = EventListener {
                    if (suppressScroll[0]) {
                        // Re-pin: recomposition may have changed spacer
                        // heights causing the browser to adjust scrollTop.
                        // Force it back to the pinned position.
                        if (kotlin.math.abs(tableWrapper.scrollTop - pinnedScrollTop[0]) > 1.0) {
                            tableWrapper.scrollTop = pinnedScrollTop[0]
                        }
                    } else if (settlingAfterCommit[0]) {
                        // Recomposition is settling after a state commit;
                        // ignore this scroll event entirely so the
                        // browser's spacer-driven adjustment doesn't
                        // cascade into another state update.
                    } else {
                        // Debounce via requestAnimationFrame: coalesce
                        // multiple scroll events within the same frame
                        // into a single Compose state update.  This
                        // prevents the feedback loop where a scrollTop
                        // change triggers spacer recomposition which
                        // adjusts scroll which fires another event.
                        if (scrollRafHandle[0] != 0) {
                            kotlinx.browser.window.cancelAnimationFrame(scrollRafHandle[0])
                        }
                        scrollRafHandle[0] = kotlinx.browser.window.requestAnimationFrame {
                            scrollRafHandle[0] = 0
                            val newScrollTop = tableWrapper.scrollTop
                            val newViewportHeight = tableWrapper.clientHeight.toDouble()
                            // Only update Compose state when the scroll
                            // position changed enough to affect which
                            // rows are visible.  Compare against the
                            // last value we committed (not the Compose
                            // state snapshot) so that recomposition-
                            // driven spacer adjustments don't
                            // accumulate into a threshold breach.
                            if (kotlin.math.abs(newScrollTop - committedScrollTop[0]) >= rowHeight ||
                                kotlin.math.abs(newViewportHeight - viewportHeight) >= 1.0) {
                                committedScrollTop[0] = newScrollTop
                                viewportHeight = newViewportHeight
                                scrollTop = newScrollTop
                                // Spacer recomposition after this state
                                // update may cause the browser to silently
                                // adjust scrollTop.  Ignore scroll events
                                // for two frames so the adjustment doesn't
                                // trigger another state cycle — this breaks
                                // the cascading feedback loop on large
                                // tables (~2000+ nested rows).
                                settlingAfterCommit[0] = true
                                kotlinx.browser.window.requestAnimationFrame {
                                    kotlinx.browser.window.requestAnimationFrame {
                                        committedScrollTop[0] = tableWrapper.scrollTop
                                        settlingAfterCommit[0] = false
                                    }
                                }
                            }
                        }
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
                                if (row.depth == 0) applyRowDetailVsc(descriptor, row.index, selection)
                                // Single click only selects/highlights; expansion
                                // is triggered by double-click or keyboard (onActivate).
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
                            onDoubleClick = if (row.hasChildren) {
                                { toggleNode(row.treePath, row.isExpanded) }
                            } else null,
                            onSelect = {
                                onFocusedFlatIndexChange(flatIndex)
                                if (row.depth == 0) applyRowDetailVsc(descriptor, row.index, selection)
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
                                    toggleNode(row.treePath, row.isExpanded)
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
                                                    toggleNode(row.treePath, row.isExpanded)
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
