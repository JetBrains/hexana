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
internal fun WasmComponentPanelImports(
    wasmBinaryProvider: WasmComponentBinaryProvider,
    state: HexViewStateHolder) {

    val imports = remember (wasmBinaryProvider) { wasmBinaryProvider.imports }
    Table(listOf(
        TableColumn("Data", modifier = Modifier.fillMaxWidth()) { index ->
            val importRecord = imports[index].importName + (imports[index].version ?: "")
            TableCellValue(importRecord)
        },

        ), imports.size) { rowIndex ->
        val importRecord = imports[rowIndex] ?: return@Table
        state.scrollHexViewTo(importRecord.offset, importRecord.size)
    }
}