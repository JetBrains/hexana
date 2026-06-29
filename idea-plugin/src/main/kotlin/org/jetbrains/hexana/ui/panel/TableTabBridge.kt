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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.shared.CellValue
import org.jetbrains.hexana.shared.ColumnWidth
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
