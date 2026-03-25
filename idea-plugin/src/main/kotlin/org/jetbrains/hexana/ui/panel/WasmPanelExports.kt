package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.sections.*

@Composable
internal fun WasmPanelExports(wasmBinaryProvider: WasmBinaryProvider, state: HexViewStateHolder) {
    val exports = remember(wasmBinaryProvider) {
        wasmBinaryProvider.exports
    }

    val textMeasurer = rememberTextMeasurer()

    val kindColumnWidth = textMeasurer.measureWidthMonospace("function")

    Table(
        listOf(
            TableColumn("Kind", modifier = Modifier.width(kindColumnWidth)) { index ->
                val export = exports[index]
                val exportKind = when (export) {
                    is WasmFunctionExportRecordEntry -> "function"
                    is WasmGlobalExportRecordEntry -> "global"
                    is WasmMemoryExportRecordEntry -> "memory"
                    is WasmTableExportRecordEntry -> "table"
                    is WasmTagExportRecordEntry -> "tag"
                    is WasmUnsupportedExportRecordEntry -> "unknown"
                }

                TableCellValue(exportKind)
            },

            TableColumn("Name", modifier = Modifier.fillMaxWidth()) { index ->
                val export = exports[index]
                TableCellValue(export.name)
            }
        ), dataCount = exports.size,
        onRow = { rowIndex ->
            val exportRecord = exports[rowIndex] ?: return@Table
            state.scrollHexViewTo(exportRecord.offset, exportRecord.size)
        }
    )
}