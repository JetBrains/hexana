package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTableElement
import org.w3c.dom.HTMLTableRowElement
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.KeyboardEvent

/** Sort direction for table columns. */
internal enum class SortDirection { NONE, ASC, DESC }

/** Mutable sort state shared between the header and the DOM reorder effect. */
internal class TableSortState(
    initialColumn: Int = -1,
    initialDirection: SortDirection = SortDirection.NONE,
) {
    var columnIndex by mutableStateOf(initialColumn)
    var direction by mutableStateOf(initialDirection)

    fun toggle(index: Int) {
        if (columnIndex == index) {
            direction = when (direction) {
                SortDirection.NONE -> SortDirection.ASC
                SortDirection.ASC -> SortDirection.DESC
                SortDirection.DESC -> SortDirection.ASC
            }
        } else {
            columnIndex = index
            direction = SortDirection.ASC
        }
    }
}

/**
 * Wraps a [PanelTable] with an inline search bar that appears on Cmd/Ctrl+F.
 * When a query is entered, table body rows whose text content does not contain
 * the query (case-insensitive) are hidden.
 */
@Composable
internal fun SearchableTable(
    headers: List<String>,
    columnAligns: List<String> = emptyList(),
    defaultSortColumn: Int = headers.indexOf("ID").takeIf { it >= 0 } ?: -1,
    defaultSortDirection: SortDirection = SortDirection.ASC,
    alwaysShowSearch: Boolean = false,
    searchBarLeading: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    onSortToggle: ((columnIndex: Int) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var searchVisible by remember { mutableStateOf(alwaysShowSearch) }
    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var containerRef by remember { mutableStateOf<HTMLElement?>(null) }

    // Debounce the search query so DOM filtering only runs after the user
    // pauses typing, avoiding UI freezes on large tables.
    LaunchedEffect(query) {
        delay(300)
        debouncedQuery = query
    }

    Div({
        classes("hexana-searchable-table")
        tabIndex(-1)
        style {
            property("outline", "none")
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            property("min-height", "0")
        }
        ref { container ->
            containerRef = container
            val keyListener = EventListener { event ->
                val ke = event as? KeyboardEvent ?: return@EventListener
                if (ke.key == "f" && (ke.metaKey || ke.ctrlKey)) {
                    ke.preventDefault()
                    ke.stopPropagation()
                    searchVisible = true
                    kotlinx.browser.window.requestAnimationFrame {
                        container.querySelector(".hexana-search-input")
                            ?.let { (it as? HTMLInputElement)?.focus() }
                    }
                }
            }
            container.addEventListener("keydown", keyListener, true)
            onDispose {
                containerRef = null
                container.removeEventListener("keydown", keyListener, true)
            }
        }
    }) {
        if (searchVisible) {
            SearchBar(
                query = query,
                onQueryChange = { query = it },
                onClose = {
                    if (!alwaysShowSearch) {
                        searchVisible = false
                    }
                    query = ""
                },
                leading = searchBarLeading,
            )
        }

        val sortState = remember {
            if (defaultSortColumn >= 0) TableSortState(defaultSortColumn, defaultSortDirection)
            else TableSortState()
        }

        Div {
            PanelTable(
                headers = headers,
                columnAligns = columnAligns,
                sortState = sortState,
                onSortToggle = onSortToggle,
                content = content,
            )
        }

        if (footer != null) {
            footer()
        }

        // Re-apply the filter and sort whenever the debounced query or sort state changes.
        // Use requestAnimationFrame to ensure the DOM is fully rendered before
        // manipulating row order – on the very first composition the table body
        // may not yet be populated when DisposableEffect fires synchronously.
        DisposableEffect(debouncedQuery, sortState.columnIndex, sortState.direction) {
            val colIdx = sortState.columnIndex
            val dir = sortState.direction
            val q = debouncedQuery
            val rafId = kotlinx.browser.window.requestAnimationFrame {
                val container = containerRef
                if (container != null) {
                    val table = container.querySelector("table.hexana-panel-table") as? HTMLTableElement
                    if (table != null) {
                        applySortOrder(table, colIdx, dir)
                        applySearchFilter(table, q)
                    }
                }
            }
            onDispose {
                kotlinx.browser.window.cancelAnimationFrame(rafId)
            }
        }
    }
}

/**
 * Inline search bar displayed above the table.
 */
@Composable
internal fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Div({
        classes("hexana-search-bar")
    }) {
        if (leading != null) {
            leading()
        }
        Input(InputType.Text) {
            classes("hexana-search-input")
            value(query)
            attr("placeholder", "Search\u2026")
            onInput { event ->
                onQueryChange(event.value)
            }
            onKeyDown { event ->
                val ke = event.nativeEvent as? KeyboardEvent
                if (ke != null && ke.key == "Escape") {
                    ke.preventDefault()
                    onClose()
                }
            }
            ref { input ->
                input.focus()
                onDispose { }
            }
        }
        Span({
            classes("hexana-search-close")
            attr("title", "Close search (Esc)")
            onClick { onClose() }
        }) {
            Text("\u2715")
        }
    }
}

/**
 * Reorders `<tr>` rows inside a [table]'s `<tbody>` based on the text
 * content of the cell at [columnIndex]. Compares numerically when both
 * values parse as numbers, otherwise falls back to lexicographic order.
 */
private fun applySortOrder(table: HTMLTableElement, columnIndex: Int, direction: SortDirection) {
    val tbodyElement = table.querySelector("tbody") ?: return
    if (columnIndex < 0 || direction == SortDirection.NONE) {
        // Restore original order using the data attribute stored on first sort
        val rows = tbodyElement.querySelectorAll("tr[data-orig-index]")
        val sorted = (0 until rows.length)
            .mapNotNull { rows.item(it) as? HTMLTableRowElement }
            .sortedBy { it.getAttribute("data-orig-index")?.toIntOrNull() ?: 0 }
        for (row in sorted) {
            tbodyElement.appendChild(row)
        }
        return
    }

    val rows = tbodyElement.querySelectorAll("tr")
    val rowList = (0 until rows.length).mapNotNull { rows.item(it) as? HTMLTableRowElement }

    // Stamp original indices on first encounter
    for ((i, row) in rowList.withIndex()) {
        if (!row.hasAttribute("data-orig-index")) {
            row.setAttribute("data-orig-index", i.toString())
        }
    }

    val sorted = rowList.sortedWith(Comparator { a, b ->
        val aText = a.querySelectorAll("td").let { cells ->
            if (columnIndex < cells.length) (cells.item(columnIndex) as? HTMLElement)?.textContent?.trim() ?: ""
            else ""
        }
        val bText = b.querySelectorAll("td").let { cells ->
            if (columnIndex < cells.length) (cells.item(columnIndex) as? HTMLElement)?.textContent?.trim() ?: ""
            else ""
        }
        val cmp = compareValues(aText, bText)
        if (direction == SortDirection.DESC) -cmp else cmp
    })
    for (row in sorted) {
        tbodyElement.appendChild(row)
    }
}

/**
 * Compares two cell text values. Attempts numeric comparison first
 * (handling common suffixes like "B", "KB", "%"), then falls back to
 * case-insensitive lexicographic order.
 */
private fun compareValues(a: String, b: String): Int {
    val aNum = parseNumericValue(a)
    val bNum = parseNumericValue(b)
    if (aNum != null && bNum != null) return aNum.compareTo(bNum)
    return a.compareTo(b, ignoreCase = true)
}

/** Extracts a numeric value from text, normalizing size suffixes to bytes. */
private fun parseNumericValue(text: String): Double? {
    if (text.isEmpty()) return null
    // Strip hex prefix
    if (text.startsWith("0x", ignoreCase = true)) {
        return text.substring(2).toLongOrNull(16)?.toDouble()
    }
    // Detect unit suffixes and convert to a common base so that, e.g.,
    // "1 KB" (= 1024) sorts correctly after "900 bytes" (= 900).
    val mbMatch = Regex("^([\\d,.]+)\\s*MB$", RegexOption.IGNORE_CASE).find(text)
    if (mbMatch != null) return mbMatch.groupValues[1].replace(",", "").toDoubleOrNull()?.times(1024.0 * 1024)
    val kbMatch = Regex("^([\\d,.]+)\\s*KB$", RegexOption.IGNORE_CASE).find(text)
    if (kbMatch != null) return kbMatch.groupValues[1].replace(",", "").toDoubleOrNull()?.times(1024.0)
    // Strip remaining suffixes: "123 bytes", "45.6%", "12 B"
    val stripped = text
        .replace(Regex("\\s*bytes$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*B$", RegexOption.IGNORE_CASE), "")
        .replace(Regex("%$"), "")
        .replace(",", "")
        .trim()
    return stripped.toDoubleOrNull()
}

/**
 * Hides/shows `<tr>` rows inside a [table] based on whether their text
 * content matches [query] (case-insensitive).
 *
 * Each row's lowercase text is cached in a `data-search-text` attribute
 * on first access so that subsequent filter passes avoid the expensive
 * DOM `.textContent` traversal.
 */
private fun applySearchFilter(table: HTMLTableElement, query: String) {
    val tbodyElement = table.querySelector("tbody") ?: return
    val rows = tbodyElement.querySelectorAll("tr")
    val lowerQuery = query.lowercase()
    for (i in 0 until rows.length) {
        val row = rows.item(i) as? HTMLTableRowElement ?: continue
        if (lowerQuery.isEmpty()) {
            row.style.removeProperty("display")
        } else {
            val cached = row.getAttribute("data-search-text")
            val text = if (cached != null) cached else {
                val t = row.textContent?.lowercase() ?: ""
                row.setAttribute("data-search-text", t)
                t
            }
            row.style.display = if (text.contains(lowerQuery)) "" else "none"
        }
    }
}

/**
 * Reusable table component for panel tabs. Renders a styled HTML <table>
 * with a sticky header row.
 */
@Composable
internal fun PanelTable(
    headers: List<String>,
    columnAligns: List<String> = emptyList(),
    sortState: TableSortState? = null,
    onSortToggle: ((columnIndex: Int) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Table({
        classes("hexana-panel-table")
    }) {
        Thead {
            Tr({
                style {
                    property("border-bottom", "1px solid var(--hexana-border, #333)")
                }
            }) {
                headers.forEachIndexed { index, header ->
                    val align = columnAligns.getOrElse(index) { "left" }
                    val isNarrow = header == "ID" || header == ""
                    val isSorted = sortState != null && sortState.columnIndex == index
                    val indicator = when {
                        isSorted && sortState!!.direction == SortDirection.ASC -> " \u25B2"
                        isSorted && sortState!!.direction == SortDirection.DESC -> " \u25BC"
                        else -> ""
                    }
                    Th({
                        classes("panel-th")
                        if (isNarrow) classes("panel-th-narrow")
                        if (sortState != null && header.isNotEmpty()) classes("panel-th-sortable")
                        style {
                            property("text-align", align)
                            if (sortState != null && header.isNotEmpty()) {
                                property("cursor", "pointer")
                                property("user-select", "none")
                            }
                        }
                        if (sortState != null && header.isNotEmpty()) {
                            onClick {
                                if (onSortToggle != null) {
                                    onSortToggle(index)
                                } else {
                                    sortState.toggle(index)
                                }
                            }
                        }
                    }) { Text(header + indicator) }
                }
            }
        }
        Tbody {
            content()
        }
    }
}

/**
 * Mutable holder for row callbacks so that a stable keydown listener
 * always invokes the latest lambdas without re-registering.
 */
private class RowCallbacks {
    var onClick: (() -> Unit)? = null
    var onSelect: (() -> Unit)? = null
    var onActivate: (() -> Unit)? = null
}

/**
 * Reusable table row that supports click-to-highlight behaviour.
 * Rows are focusable and support ArrowUp / ArrowDown keyboard navigation
 * between sibling rows, preventing the hex viewer from stealing focus.
 */
@Composable
internal fun PanelTableRow(
    isHighlighted: Boolean = false,
    onClick: () -> Unit = {},
    onSelect: (() -> Unit)? = null,
    onActivate: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // Keep a stable callbacks holder across recompositions; update its
    // fields on every recomposition so the keydown listener (registered
    // once in ref{}) always sees the current lambdas.
    val callbacks = remember { RowCallbacks() }
    callbacks.onClick = onClick
    callbacks.onSelect = onSelect
    callbacks.onActivate = onActivate

    Tr({
        classes("hexana-panel-row")
        tabIndex(0)
        style {
            property("cursor", "pointer")
            if (isHighlighted) {
                property("background", "var(--hexana-selection, rgba(156, 183, 255, 0.2))")
            }
        }
        onClick { onClick() }
        // Store the stable callbacks holder on the DOM element so that
        // arrow-key navigation to a sibling row can read the *target*
        // row's current callbacks.
        ref { element ->
            element.asDynamic().__callbacks = callbacks
            val keyListener = EventListener { event ->
                val ke = event as? KeyboardEvent ?: return@EventListener
                val row = ke.currentTarget as? HTMLElement ?: return@EventListener
                val cb = row.asDynamic().__callbacks as? RowCallbacks ?: return@EventListener
                when (ke.key) {
                    "ArrowDown" -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        nextVisibleRow(row)?.let { target ->
                            target.focus()
                            val targetCb = target.asDynamic().__callbacks as? RowCallbacks
                            if (targetCb?.onSelect != null) {
                                targetCb.onSelect!!()
                            } else {
                                target.click()
                            }
                        }
                    }
                    "ArrowUp" -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        previousVisibleRow(row)?.let { target ->
                            target.focus()
                            val targetCb = target.asDynamic().__callbacks as? RowCallbacks
                            if (targetCb?.onSelect != null) {
                                targetCb.onSelect!!()
                            } else {
                                target.click()
                            }
                        }
                    }
                    "ArrowLeft", "ArrowRight" -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        if (cb.onActivate != null) {
                            cb.onActivate!!()
                        } else {
                            cb.onClick?.invoke()
                        }
                    }
                    "Enter", " " -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        cb.onClick?.invoke()
                    }
                }
            }
            element.addEventListener("keydown", keyListener)
            onDispose {
                element.removeEventListener("keydown", keyListener)
            }
        }
    }) {
        content()
    }
}

/** Returns the next visible sibling row, skipping hidden ones. */
private fun nextVisibleRow(row: HTMLElement): HTMLElement? {
    var sibling = row.nextElementSibling as? HTMLElement
    while (sibling != null) {
        if (sibling.style.display != "none") return sibling
        sibling = sibling.nextElementSibling as? HTMLElement
    }
    return null
}

/** Returns the previous visible sibling row, skipping hidden ones. */
private fun previousVisibleRow(row: HTMLElement): HTMLElement? {
    var sibling = row.previousElementSibling as? HTMLElement
    while (sibling != null) {
        if (sibling.style.display != "none") return sibling
        sibling = sibling.previousElementSibling as? HTMLElement
    }
    return null
}
