package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.RowSelectionData
import org.jetbrains.hexana.shared.TableTabChildRows
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Converts a [TableTabDescriptor] into a [Panel] by bridging the UI-free
 * abstraction to the Compose-based [Table] component.
 */
internal fun TableTabDescriptor.toPanel(state: HexViewStateHolder): Panel {
    val descriptor = this
    return Panel(descriptor.name) {
        TableTabContent(descriptor, state)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableTabContent(descriptor: TableTabDescriptor, state: HexViewStateHolder) {
    if (descriptor.prologueText != null || (descriptor.rowCount == 0 && descriptor.emptyMessage != null)) {
        Column(modifier = Modifier.fillMaxSize()) {
            descriptor.prologueText?.let {
                Text(it, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (descriptor.rowCount == 0) {
                descriptor.emptyMessage?.let { Text(it) }
                return
            }
            TableTabTable(descriptor, state)
        }
        return
    }

    if (descriptor.rowCount == 0) return

    TableTabTable(descriptor, state)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableTabTable(descriptor: TableTabDescriptor, state: HexViewStateHolder) {
    if (descriptor.childrenProvider != null) {
        TreeTableTabTable(descriptor, state)
        return
    }

    val textMeasurer = rememberTextMeasurer()

    val columnLayouts = descriptor.columns.map { col ->
        col.widthBehavior.toColumnLayout(
            textMeasurer = textMeasurer,
            title = col.name,
            rowCount = descriptor.rowCount,
            cellProvider = col.cellProvider,
        )
    }

    val columns = remember(descriptor, columnLayouts) {
        descriptor.columns.mapIndexed { i, col ->
            TableColumn(
                title = col.name,
                modifier = columnLayouts[i].modifier,
                width = columnLayouts[i].width,
            ) { index ->
                val cell = col.cellProvider(index)
                TableCellValue(cell.value, cell.representation, cell.tooltip)
            }
        }
    }

    val actionColumnWidth = textMeasurer.measureWidthMonospace("__")

    Table(
        columns = columns,
        dataCount = descriptor.rowCount,
        onRowContextMenu = { rowIndex ->
            val staticItems = descriptor.contextMenuItems
                .filter { item -> item.isVisible(rowIndex) }
                .map { item ->
                    ContextMenuItem(item.labelProvider(rowIndex)) {
                        item.action(rowIndex)
                    }
                }
            val dynamicItems = descriptor.dynamicContextMenuProvider?.invoke(rowIndex)
                ?.map { entry -> ContextMenuItem(entry.label, entry.action) }
                ?: emptyList()
            staticItems + dynamicItems
        },
        actionColumn = descriptor.rowAction?.let { rowAction -> { index ->
            val tooltip = rowAction.tooltipProvider(index)
            if (tooltip != null) {
                Tooltip(tooltip = { Text(tooltip) }) {
                    Icon(
                        AllIconsKeys.Actions.Execute,
                        contentDescription = rowAction.label,
                        iconClass = AllIcons::class.java,
                        modifier = Modifier.size(16.dp).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            rowAction.action(index)
                        },
                    )
                }
            }
        } },
        actionColumnWidth = actionColumnWidth,
        defaultSortColumn = descriptor.defaultSortColumn ?: -1,
        defaultSortAscending = descriptor.defaultSortAscending,
        onRow = { rowIndex ->
            val clickAction = descriptor.rowClickAction
            if (clickAction != null) {
                clickAction(rowIndex)
            } else {
                val selection = descriptor.rowSelectionProvider(rowIndex) ?: return@Table
                state.scrollHexViewTo(selection.offset, selection.size)
            }
        },
    )
}

/**
 * A flattened row in a tree table. Each visible row (including nested children)
 * is represented as one [FlatRow] with its nesting [depth], the [columns] to
 * read cell values from, and tree-expansion metadata.
 */
private class FlatRow(
    val depth: Int,
    val index: Int,
    val columns: List<ColumnDescriptor>,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
    val rowSelectionProvider: (Int) -> RowSelectionData?,
    /** Stable path from root used as the key in the expanded-set. */
    val treePath: List<Int>,
)

/**
 * Builds the list of currently visible [FlatRow]s by walking the tree
 * depth-first, expanding only nodes whose path is in [expandedPaths].
 */
private fun flattenTree(
    descriptor: TableTabDescriptor,
    expandedPaths: Set<List<Int>>,
): List<FlatRow> {
    val result = mutableListOf<FlatRow>()

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
            result += FlatRow(depth, i, columns, hasChildren, isExpanded, rowSelectionProvider, path)
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

private const val INDENT_CHARS = 4
private const val CHEVRON_RIGHT = "\u25B6 "
private const val CHEVRON_DOWN = "\u25BC "
private const val CHEVRON_PLACEHOLDER = "  "

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeTableTabTable(descriptor: TableTabDescriptor, state: HexViewStateHolder) {
    val expandedPaths = remember(descriptor) { mutableStateListOf<List<Int>>() }
    val flatRows = remember(descriptor, expandedPaths.toList()) {
        flattenTree(descriptor, expandedPaths.toSet())
    }

    val textMeasurer = rememberTextMeasurer()

    val columnLayouts = descriptor.columns.map { col ->
        col.widthBehavior.toColumnLayout(
            textMeasurer = textMeasurer,
            title = col.name,
            rowCount = descriptor.rowCount,
            cellProvider = col.cellProvider,
        )
    }

    val columns = remember(descriptor, flatRows, columnLayouts) {
        descriptor.columns.mapIndexed { colIndex, col ->
            val layout = columnLayouts[colIndex]
            TableColumn(
                title = col.name,
                modifier = layout.modifier,
                width = layout.width,
            ) { flatIndex ->
                val row = flatRows[flatIndex]
                val cell = row.columns[colIndex].cellProvider(row.index)
                if (colIndex == 0) {
                    val indent = " ".repeat(row.depth * INDENT_CHARS)
                    val chevron = when {
                        row.hasChildren && row.isExpanded -> CHEVRON_DOWN
                        row.hasChildren -> CHEVRON_RIGHT
                        else -> CHEVRON_PLACEHOLDER
                    }
                    TableCellValue(cell.value, indent + chevron + cell.representation, cell.tooltip)
                } else {
                    TableCellValue(cell.value, cell.representation, cell.tooltip)
                }
            }
        }
    }

    val actionColumnWidth = textMeasurer.measureWidthMonospace("__")

    Table(
        columns = columns,
        dataCount = flatRows.size,
        defaultSortColumn = descriptor.defaultSortColumn ?: -1,
        defaultSortAscending = descriptor.defaultSortAscending,
        onRow = { flatIndex ->
            val row = flatRows[flatIndex]
            if (row.hasChildren) {
                if (row.isExpanded) {
                    expandedPaths.remove(row.treePath)
                } else {
                    expandedPaths.add(row.treePath)
                }
            } else {
                val selection = row.rowSelectionProvider(row.index) ?: return@Table
                state.scrollHexViewTo(selection.offset, selection.size)
            }
        },
    )
}

@Composable
private fun ColumnWidth.toColumnLayout(
    textMeasurer: TextMeasurer,
    title: String,
    rowCount: Int,
    cellProvider: (Int) -> CellValue<*>,
): ColumnLayout = when (this) {
    is ColumnWidth.Fill -> ColumnLayout(modifier = Modifier.fillMaxWidth(fraction))
    is ColumnWidth.Measured -> ColumnLayout(
        width = textMeasurer.measureWidthMonospace(widestSample(title, sample)),
    )
    is ColumnWidth.MeasuredMin -> ColumnLayout(
        width = textMeasurer.measureWidthMonospace(
            widestSample(title, sample, rowCount, cellProvider),
        ),
    )
}

private data class ColumnLayout(
    val modifier: Modifier = Modifier,
    val width: Dp? = null,
)

private fun widestSample(
    title: String,
    sample: String,
    rowCount: Int = 0,
    cellProvider: ((Int) -> CellValue<*>)? = null,
): String {
    var widest = "$title ▾"

    fun consider(value: String) {
        val boundedValue = value.take(MAX_MEASURED_COLUMN_CHARS)
        if (boundedValue.length > widest.length) {
            widest = boundedValue
        }
    }

    consider(sample)

    if (cellProvider != null) {
        repeat(rowCount) { index ->
            consider(cellProvider(index).representation)
        }
    }

    return widest
}

private const val MAX_MEASURED_COLUMN_CHARS = 80
