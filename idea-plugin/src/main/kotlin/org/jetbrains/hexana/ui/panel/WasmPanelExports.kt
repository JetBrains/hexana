package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmUnsupportedExportRecordEntry

@Composable
internal fun WasmPanelExports(wasmBinaryProvider: WasmBinaryProvider) {
    val exports = remember(wasmBinaryProvider) {
        wasmBinaryProvider.exports
    }

    val textMeasurer = rememberTextMeasurer()

    val minWidthKindColumnModifier = textMeasurer.measureWidthMonospace("function")

    Table(
        listOf(
            TableColumn("Kind", modifier = Modifier.widthIn(min = minWidthKindColumnModifier)) { index ->
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
        ), exports.size
    )
}