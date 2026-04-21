package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*

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
        style {
            width(100.percent)
            property("border-collapse", "collapse")
            property("font-size", "12px")
        }
    }) {
        Thead {
            Tr({
                style {
                    property("border-bottom", "1px solid var(--hexana-border, #333)")
                }
            }) {
                headers.forEachIndexed { index, header ->
                    val align = columnAligns.getOrElse(index) { "left" }
                    Th({
                        style {
                            property("text-align", align)
                            property("padding", "4px 8px")
                            property("font-weight", "600")
                            property("white-space", "nowrap")
                            property("position", "sticky")
                            property("top", "0")
                            property("background", "var(--hexana-bg, #1e1e1e)")
                            property("z-index", "1")
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
 */
@Composable
internal fun PanelTableRow(
    isHighlighted: Boolean = false,
    onClick: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Tr({
        classes("hexana-panel-row")
        style {
            property("cursor", "pointer")
            if (isHighlighted) {
                property("background", "var(--hexana-selection, rgba(156, 183, 255, 0.2))")
            }
        }
        onClick { onClick() }
    }) {
        content()
    }
}
