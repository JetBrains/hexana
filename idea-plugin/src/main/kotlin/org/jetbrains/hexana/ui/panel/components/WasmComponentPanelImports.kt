package org.jetbrains.hexana.ui.panel.components

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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

@Composable
internal fun WasmComponentPanelImports(
    wasmBinaryProvider: WasmComponentBinaryProvider,
    state: HexViewStateHolder) {
    val textMeasurer = rememberTextMeasurer()

    val nameColumnWidth = textMeasurer.measureWidthMonospace("component:subtractor11111/environent@2.9.0")

    val imports = remember (wasmBinaryProvider) { wasmBinaryProvider.imports }
    Table(listOf(
        TableColumn("Name", modifier = Modifier.width(nameColumnWidth)) { index ->
            val importRecord = imports[index].importName + (imports[index].version ?: "")
            TableCellValue(importRecord)
        },

        TableColumn("Extern Description", modifier = Modifier.fillMaxWidth()) { index ->
            val importRecord = imports[index]
            TableCellValue(importRecord.externDescription.toStringRepresentation())
        },

        ), imports.size,
        onRowContextMenu = { rowIndex ->
            listOfNotNull(
                ContextMenuItem("Navigate to WASM definition") {
                    navigateToWasmDefinition(imports[rowIndex].importName, state.project)
                },
                ContextMenuItem("Navigate to WIT definition") {
                    navigateToWitDefinition(imports[rowIndex].importName, state.project)
                }
            )}) { rowIndex ->
        val importRecord = imports[rowIndex] ?: return@Table
        state.scrollHexViewTo(importRecord.offset, importRecord.size)
    }
}
