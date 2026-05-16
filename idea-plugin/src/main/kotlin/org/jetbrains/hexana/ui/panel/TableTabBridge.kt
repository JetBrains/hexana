package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.shared.ColumnWidth
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.TableTabsProvider
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace

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

/**
 * Converts all table tabs from a [TableTabsProvider] into [Panel]s.
 */
internal fun TableTabsProvider.toPanels(
    provider: BinaryProvider,
    state: HexViewStateHolder,
): List<Panel> = tableTabs(provider).map { it.toPanel(state) }

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

    Table(
        columns = columns,
        dataCount = descriptor.rowCount,
        onRow = { rowIndex ->
            val selection = descriptor.rowSelectionProvider(rowIndex) ?: return@Table
            state.scrollHexViewTo(selection.offset, selection.size)
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
