package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.EventListener
import org.w3c.dom.events.KeyboardEvent

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
                        (row.nextElementSibling as? HTMLElement)?.let {
                            it.focus()
                            it.click()
                        }
                    }
                    "ArrowUp" -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        (row.previousElementSibling as? HTMLElement)?.let {
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
