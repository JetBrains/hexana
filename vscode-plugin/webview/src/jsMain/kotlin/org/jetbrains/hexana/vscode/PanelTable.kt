package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTableElement
import org.w3c.dom.HTMLTableRowElement
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.KeyboardEvent

/**
 * Wraps a [PanelTable] with an inline search bar that appears on Cmd/Ctrl+F.
 * When a query is entered, table body rows whose text content does not contain
 * the query (case-insensitive) are hidden.
 */
@Composable
internal fun SearchableTable(
    headers: List<String>,
    columnAligns: List<String> = emptyList(),
    content: @Composable () -> Unit,
) {
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var containerRef by remember { mutableStateOf<HTMLElement?>(null) }

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
                    searchVisible = false
                    query = ""
                },
            )
        }

        Div {
            PanelTable(
                headers = headers,
                columnAligns = columnAligns,
                content = content,
            )
        }

        // Re-apply the filter whenever the query changes
        DisposableEffect(query) {
            val container = containerRef
            if (container != null) {
                val table = container.querySelector("table.hexana-panel-table") as? HTMLTableElement
                if (table != null) {
                    applySearchFilter(table, query)
                }
            }
            onDispose { }
        }
    }
}

/**
 * Inline search bar displayed above the table.
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Div({
        classes("hexana-search-bar")
    }) {
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
 * Hides/shows `<tr>` rows inside a [table] based on whether their text
 * content matches [query] (case-insensitive).
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
            val text = row.textContent?.lowercase() ?: ""
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
                    Th({
                        classes("panel-th")
                        if (isNarrow) classes("panel-th-narrow")
                        style {
                            property("text-align", align)
                        }
                    }) { Text(header) }
                }
            }
        }
        Tbody {
            content()
        }
    }
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
    content: @Composable () -> Unit,
) {
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
        // Attach keydown and click handlers directly on the DOM element so
        // that preventDefault() fires before the browser performs native
        // overflow-scroll on the parent container.
        ref { element ->
            val keyListener = EventListener { event ->
                val ke = event as? KeyboardEvent ?: return@EventListener
                val row = ke.currentTarget as? HTMLElement ?: return@EventListener
                when (ke.key) {
                    "ArrowDown" -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        nextVisibleRow(row)?.let {
                            it.focus()
                            it.click()
                        }
                    }
                    "ArrowUp" -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        previousVisibleRow(row)?.let {
                            it.focus()
                            it.click()
                        }
                    }
                    "Enter", " " -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        onClick()
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
