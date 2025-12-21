package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn

@Composable
internal fun WasmPanelImports(wasmBinaryProvider: WasmBinaryProvider, state: HexViewStateHolder) {
    val imports = remember(wasmBinaryProvider) { wasmBinaryProvider.imports }

    Table(listOf(
        TableColumn("Data", modifier = Modifier.fillMaxWidth()) { index ->
            val importRecord = imports[index].name
            TableCellValue(importRecord)
        },

    ), imports.size) { rowIndex ->
        val importRecord = imports[rowIndex] ?: return@Table
        state.scrollHexViewTo(importRecord.offset, importRecord.size)
    }
}