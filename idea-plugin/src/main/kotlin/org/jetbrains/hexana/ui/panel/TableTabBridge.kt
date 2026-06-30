package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intellij.icons.AllIcons
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.GroupedTableTabDescriptor
import org.jetbrains.hexana.shared.RowSelectionData
import org.jetbrains.hexana.shared.TableTabChildRows
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.TabData
import org.jetbrains.jewel.ui.component.TabStrip
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.theme.defaultTabStyle

/**
 * Converts a [TableTabDescriptor] into a [Panel] by bridging the UI-free
 * abstraction to the Compose-based [Table] component.
 */
internal fun TableTabDescriptor.toPanel(
    state: HexViewStateHolder,
    searchBarLeading: (@Composable () -> Unit)? = null,
): Panel {
    val descriptor = this
    return Panel(descriptor.name) {
        TableTabContent(descriptor, state, searchBarLeading)
    }
}

/**
 * Converts a [GroupedTableTabDescriptor] into a [Panel] that renders an
 * inner tab strip switching between the contained table tabs.
 */
internal fun GroupedTableTabDescriptor.toPanel(state: HexViewStateHolder): Panel? {
    val grouped = this
    // Skip the entire group when there are no inner tabs and no emptyMessage.
    if (grouped.innerTabs.isEmpty() && grouped.emptyMessage == null) return null
    // When there are no inner tabs but an emptyMessage is set, show it.
    if (grouped.innerTabs.isEmpty()) {
        return Panel(grouped.name) {
            Text(grouped.emptyMessage ?: "")
        }
    }
    // When there is exactly one inner tab, render its content directly
    // without a sub-tab header, using a combined "Group: Sub" name.
    if (grouped.innerTabs.size == 1) {
        val single = grouped.innerTabs.single()
        return Panel("${grouped.name}: ${single.name}") {
            TableTabContent(single, state)
        }
    }
    return Panel(grouped.name) {
        GroupedTableTabContent(grouped, state)
    }
}

@Composable
private fun GroupedTableTabContent(
    grouped: GroupedTableTabDescriptor,
    state: HexViewStateHolder,
) {
    val innerTabs = grouped.innerTabs
    if (innerTabs.isEmpty()) return

    var selectedIndex by remember(grouped) { mutableStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        TabStrip(
            tabs = innerTabs.mapIndexed { index, tab ->
                TabData.Default(
                    selected = index == selectedIndex,
                    content = { Text(tab.name) },
                    onClick = { selectedIndex = index },
                    closable = false,
                )
            },
            style = JewelTheme.defaultTabStyle,
        )
        val currentTab = innerTabs.getOrNull(selectedIndex) ?: return
        TableTabContent(currentTab, state)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableTabContent(
    descriptor: TableTabDescriptor,
    state: HexViewStateHolder,
    searchBarLeading: (@Composable () -> Unit)? = null,
) {
    if (descriptor.prologueText != null || (descriptor.rowCount == 0 && descriptor.emptyMessage != null)) {
        Column(modifier = Modifier.fillMaxSize()) {
            descriptor.prologueText?.let {
                Text(it, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp), fontSize = 13.sp)
            }
            if (descriptor.rowCount == 0) {
                descriptor.emptyMessage?.let { Text(it) }
                return
            }
            TableTabTable(descriptor, state, searchBarLeading)
        }
        return
    }

    if (descriptor.rowCount == 0 && !descriptor.alwaysShowSearch) return

    TableTabTable(descriptor, state, searchBarLeading)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableTabTable(
    descriptor: TableTabDescriptor,
    state: HexViewStateHolder,
    searchBarLeading: (@Composable () -> Unit)? = null,
) {
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
                weight = columnLayouts[i].weight,
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
        alwaysShowSearch = descriptor.alwaysShowSearch,
        searchPlaceholder = descriptor.searchPlaceholder,
        searchBarLeading = searchBarLeading,
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
    sortColumnIndex: Int = -1,
    sortAscending: Boolean = true,
): List<FlatRow> {
    val result = mutableListOf<FlatRow>()

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
    var sortColumnIndex by remember { mutableStateOf(descriptor.defaultSortColumn ?: -1) }
    var sortAscending by remember { mutableStateOf(descriptor.defaultSortAscending) }
    val flatRows = remember(descriptor, expandedPaths.toList(), sortColumnIndex, sortAscending) {
        flattenTree(descriptor, expandedPaths.toSet(), sortColumnIndex, sortAscending)
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
                weight = layout.weight,
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
        defaultSortColumn = sortColumnIndex,
        defaultSortAscending = sortAscending,
        showIdColumn = false,
        onSortChanged = { colIdx, asc ->
            sortColumnIndex = colIdx
            sortAscending = asc
        },
        onRowSelect = { flatIndex ->
            val row = flatRows[flatIndex]
            val selection = row.rowSelectionProvider(row.index)
            if (selection != null) {
                state.scrollHexViewTo(selection.offset, selection.size)
            }
        },
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
internal fun ColumnWidth.toColumnLayout(
    textMeasurer: TextMeasurer,
    title: String,
    rowCount: Int,
    cellProvider: (Int) -> CellValue<*>,
): ColumnLayout = when (this) {
    is ColumnWidth.Fill -> ColumnLayout(weight = fraction)
    is ColumnWidth.Measured -> ColumnLayout(
        width = textMeasurer.measureWidthMonospace(widestSample(title, sample)),
    )
    is ColumnWidth.MeasuredMin -> ColumnLayout(
        width = textMeasurer.measureWidthMonospace(
            widestSample(title, sample, rowCount, cellProvider),
        ),
    )
}

internal data class ColumnLayout(
    val modifier: Modifier = Modifier,
    val width: Dp? = null,
    val weight: Float? = null,
)

internal fun widestSample(
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

internal const val MAX_MEASURED_COLUMN_CHARS = 80
