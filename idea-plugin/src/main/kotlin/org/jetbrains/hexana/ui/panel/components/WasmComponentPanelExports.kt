package org.jetbrains.hexana.ui.panel.components

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.ui.panel.universalQualifiedNameMeasure
import org.jetbrains.hexana.wasm.components.ComponentSortIndex

@Composable
fun WasmComponentPanelExports( wasmBinaryProvider: WasmComponentBinaryProvider,
                               state: HexViewStateHolder) {
    val textMeasurer = rememberTextMeasurer()
    val nameColumnWidth = textMeasurer.measureWidthMonospace(universalQualifiedNameMeasure)
    val sortIndexColumnWidth = textMeasurer.measureWidthMonospace("Component(000)")

    val exports = remember (wasmBinaryProvider) { wasmBinaryProvider.exports }
    Table(listOf(
        TableColumn("Name", modifier = Modifier.width(nameColumnWidth)) { index ->
            val exportRecord = exports[index].exportName + (exports[index].version ?: "")
            TableCellValue(exportRecord)
        },
        TableColumn("Sort Index", modifier = Modifier.widthIn(min = sortIndexColumnWidth)) { index ->
            val exportRecord = exports[index]
            TableCellValue(exportRecord.sortIndex.toStringRepresentation() ?: "")
        },
        TableColumn("Extern Description", modifier = Modifier.fillMaxWidth()) { index ->
            val exportRecord = exports[index]
            TableCellValue(exportRecord.externDescription?.toStringRepresentation() ?: "")
        },

        ), exports.size,
        onRowContextMenu = { rowIndex ->
            listOfNotNull(
                ContextMenuItem("Navigate to WIT definition") {
                    val exportRecord = exports[rowIndex]
                    navigateToWitDefinition(exportRecord.exportName, state.project)
                }
            )}) { rowIndex ->
        val exportRecords = exports[rowIndex] ?: return@Table
        state.scrollHexViewTo(exportRecords.offset, exportRecords.size)
    }
}