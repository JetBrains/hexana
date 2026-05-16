package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.css.Color
import org.jetbrains.compose.web.css.color
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
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
    buffer: CommonByteBuffer,
    wasmBytes: ByteArray,
    fileName: String,
    enrich: (List<TableTabDescriptor>) -> List<TableTabDescriptor> = { it },
): List<TableTab> = enrich(tableTabs(provider)).map { descriptor ->
    TableTab(name = descriptor.name) { selection ->
        TableTabContent(descriptor, selection, buffer)
    }
}

/**
 * Renders a [TableTabDescriptor] as a Compose-for-Web HTML table using the
 * existing [SearchableTable] / [PanelTableRow] components.
 */
@Composable
private fun TableTabContent(
    descriptor: TableTabDescriptor,
    selection: HexSelectionState,
    buffer: CommonByteBuffer,
) {
    if (descriptor.rowCount == 0) {
        Div({ classes("empty-hint") }) {
            Text("No data found.")
        }
        return
    }

    val headers = buildList {
        if (descriptor.rowAction != null) add("")
        add("ID")
        descriptor.columns.forEach { add(it.name) }
    }

    val aligns = buildList {
        if (descriptor.rowAction != null) add("center")
        add("right")
        descriptor.columns.forEach { col ->
            when (col.widthBehavior) {
                is ColumnWidth.Fill -> add("left")
                is ColumnWidth.Measured -> add("left")
                is ColumnWidth.MeasuredMin -> add("left")
            }
        }
    }

    SearchableTable(headers = headers, columnAligns = aligns) {
        for (index in 0 until descriptor.rowCount) {
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
                // Action column (run button)
                if (descriptor.rowAction != null) {
                    val tooltip = descriptor.rowAction!!.tooltipProvider(index)
                    Td({ classes("cell-id"); style { property("width", "12px") } }) {
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
                for (col in descriptor.columns) {
                    val cell = col.cellProvider(index)
                    val cssClass = when (col.widthBehavior) {
                        is ColumnWidth.Fill -> "cell-muted"
                        is ColumnWidth.Measured -> "cell"
                        is ColumnWidth.MeasuredMin -> "cell"
                    }

                    if (descriptor.rowClickAction != null && col == descriptor.columns.first()) {
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
