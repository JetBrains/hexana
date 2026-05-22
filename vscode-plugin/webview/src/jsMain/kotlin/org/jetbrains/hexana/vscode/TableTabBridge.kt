package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsProvider

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
 * Renders a [TableTabDescriptor] as a Compose-for-Web HTML table using the
 * existing [SearchableTable] / [PanelTableRow] components.
 */
@Composable
fun TableTabContent(
    descriptor: TableTabDescriptor,
    selection: HexSelectionState,
) {
    if (descriptor.rowCount == 0) {
        Div({ classes("empty-hint") }) {
            Text("No data found.")
        }
        return
    }

    val headers = buildList {
        add("")
        add("ID")
        descriptor.columns.forEach { add(it.name) }
    }

    val aligns = buildList {
        add("center")
        add("right")
        descriptor.columns.forEach { col ->
            when (col.widthBehavior) {
                is ColumnWidth.Fill -> add("left")
                is ColumnWidth.Measured -> add("left")
                is ColumnWidth.MeasuredMin -> add("left")
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
    val resolvedSortColumn = descriptor.defaultSortColumn?.let { col ->
        if (col == TableTabDescriptor.ID_COLUMN) {
            idHeaderIndex
        } else {
            col + 2 // action placeholder + ID
        }
    } ?: idHeaderIndex ?: -1
    val resolvedSortDirection = if (resolvedSortColumn >= 0) {
        if (descriptor.defaultSortAscending) SortDirection.ASC else SortDirection.DESC
    } else SortDirection.NONE

    // Render rows incrementally in batches to avoid blocking the browser
    // main thread when the table has thousands of entries.
    val initialBatch = 100
    val batchSize = 300
    var renderedCount by remember(descriptor) { mutableStateOf(minOf(initialBatch, descriptor.rowCount)) }

    LaunchedEffect(descriptor, renderedCount) {
        if (renderedCount < descriptor.rowCount) {
            // Yield to the browser between batches so the UI stays responsive.
            delay(1)
            renderedCount = minOf(renderedCount + batchSize, descriptor.rowCount)
        }
    }

    val isLoading = renderedCount < descriptor.rowCount

    SearchableTable(
        headers = headers,
        columnAligns = aligns,
        defaultSortColumn = resolvedSortColumn,
        defaultSortDirection = resolvedSortDirection,
        footer = if (isLoading) {
            {
                Div({
                    classes("loading-hint")
                    style {
                        property("text-align", "center")
                        property("padding", "8px 0")
                        property("opacity", "0.7")
                        property("font-size", "12px")
                    }
                }) {
                    Text("Loading rows $renderedCount / ${descriptor.rowCount}\u2026")
                }
            }
        } else null,
    ) {
        for (index in 0 until renderedCount) {
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
                    } else if (selData != null) {
                        selection.anchorIndex = selData.offset
                        selection.activeIndex = selData.offset + selData.size - 1
                        selection.cursorIndex = selData.offset
                        scrollHexViewerTo(selData.offset)
                    }
                },
            ) {
                // Action column (run button or empty placeholder for consistent layout)
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

                // ID column
                Td({ classes("cell-id") }) {
                    Text(index.toString())
                }

                // Data columns
                val firstColumn = descriptor.columns.firstOrNull()
                for (col in descriptor.columns) {
                    val cell = col.cellProvider(index)
                    val cssClass = when (col.widthBehavior) {
                        is ColumnWidth.Fill -> "cell-muted"
                        is ColumnWidth.Measured -> "cell"
                        is ColumnWidth.MeasuredMin -> "cell"
                    }

                    if (descriptor.rowClickAction != null && col === firstColumn) {
                        // Clickable link style for rows with a custom click action (e.g. Modules)
                        Td({
                            classes(cssClass)
                            style {
                                property("cursor", "pointer")
                                color(Color("var(--vscode-textLink-foreground, #3794FF)"))
                            }
                            if (cell.tooltip != null) attr("title", cell.tooltip!!)
                        }) {
                            Text(cell.representation)
                        }
                    } else {
                        Td({
                            classes(cssClass)
                            if (cell.tooltip != null) attr("title", cell.tooltip!!)
                        }) {
                            Text(cell.representation)
                        }
                    }
                }
            }
        }
    }
}
