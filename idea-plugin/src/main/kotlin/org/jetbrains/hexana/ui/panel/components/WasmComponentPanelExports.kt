package org.jetbrains.hexana.ui.panel.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.panel.scrollHexViewTo

@Composable
fun WasmComponentPanelExports( wasmBinaryProvider: WasmComponentBinaryProvider,
                               state: HexViewStateHolder) {

    val exports = remember (wasmBinaryProvider) { wasmBinaryProvider.exports }
    Table(listOf(
        TableColumn("Data", modifier = Modifier.fillMaxWidth()) { index ->
            val exportRecord = exports[index].exportName + (exports[index].version ?: "")
            TableCellValue(exportRecord)
        },

        ), exports.size) { rowIndex ->
        val exportRecords = exports[rowIndex] ?: return@Table
        state.scrollHexViewTo(exportRecords.offset, exportRecords.size)
    }
}