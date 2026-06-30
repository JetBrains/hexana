package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.RowSelectionData
import org.jetbrains.hexana.shared.TableTabChildRows
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
    descriptor.prologueText?.let { prologue ->
        Div({ style { property("margin-bottom", "8px") } }) {
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
): List<VscFlatRow> {
    val result = mutableListOf<VscFlatRow>()

    fun addLevel(
        rowCount: Int,
        columns: List<ColumnDescriptor>,
        childrenProvider: ((Int) -> TableTabChildRows?)?,
        rowSelectionProvider: (Int) -> RowSelectionData?,
        depth: Int,
        parentPath: List<Int>,
    ) {
        for (i in 0 until rowCount) {
            val path = parentPath + i
            val children = childrenProvider?.invoke(i)
            val hasChildren = children != null && children.rowCount > 0
            val isExpanded = hasChildren && path in expandedPaths
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

@Composable
private fun TreeTableTabContent(
    descriptor: TableTabDescriptor,
    selection: HexSelectionState,
) {
    val expandedPaths = remember(descriptor) { mutableStateListOf<List<Int>>() }
    val flatRows = remember(descriptor, expandedPaths.toList()) {
        flattenTreeVsc(descriptor, expandedPaths.toSet())
    }

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

    SearchableTable(
        headers = headers,
        columnAligns = aligns,
        defaultSortColumn = -1,
        defaultSortDirection = SortDirection.NONE,
    ) {
        for ((flatIndex, row) in flatRows.withIndex()) {
            val selData = row.rowSelectionProvider(row.index)
            val isHighlighted = selData != null && selection.hasSelection &&
                selection.selectionStart == selData.offset &&
                selection.selectionEnd == selData.offset + selData.size - 1

            PanelTableRow(
                isHighlighted = isHighlighted,
                onClick = {
                    if (row.hasChildren) {
                        if (row.isExpanded) {
                            expandedPaths.remove(row.treePath)
                        } else {
                            expandedPaths.add(row.treePath)
                        }
                    } else if (selData != null) {
                        selection.anchorIndex = selData.offset
                        selection.activeIndex = selData.offset + selData.size - 1
                        selection.cursorIndex = selData.offset
                        scrollHexViewerTo(selData.offset)
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
                        if (cell.tooltip != null) attr("title", cell.tooltip!!)
                    }) {
                        if (colIndex == 0) {
                            // Indentation and expand/collapse chevron
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
    }
}
