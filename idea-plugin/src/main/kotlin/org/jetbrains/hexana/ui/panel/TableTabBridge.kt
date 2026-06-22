package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import org.jetbrains.hexana.HexViewStateHolder
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
    if (descriptor.rowCount == 0) return

    val textMeasurer = rememberTextMeasurer()

    val modifiers = descriptor.columns.map { col ->
        col.widthBehavior.toModifier(textMeasurer)
    }

    val columns = remember(descriptor, modifiers) {
        descriptor.columns.mapIndexed { i, col ->
            TableColumn(
                title = col.name,
                modifier = modifiers[i],
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
private fun ColumnWidth.toModifier(
    textMeasurer: TextMeasurer,
): Modifier = when (this) {
    is ColumnWidth.Fill -> Modifier.fillMaxWidth(fraction)
    is ColumnWidth.Measured -> Modifier.width(textMeasurer.measureWidthMonospace(sample))
    is ColumnWidth.MeasuredMin -> Modifier.widthIn(min = textMeasurer.measureWidthMonospace(sample))
}
