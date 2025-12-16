package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace

@Composable
internal fun WasmPanelImports(wasmBinaryProvider: WasmBinaryProvider) {
    val imports = remember(wasmBinaryProvider) { wasmBinaryProvider.imports }

    val textMeasurer = rememberTextMeasurer()
    val minWidthIdColumnModifier = textMeasurer.measureWidthMonospace(imports.size, 2)

    Table(listOf(
        TableColumn("ID", modifier = Modifier.widthIn(min = minWidthIdColumnModifier)) { index ->
            index.toString()
        },

        TableColumn("Data", modifier = Modifier.fillMaxWidth()) { index ->
            val importRecord = imports[index].name
            importRecord
        },

    ), imports.size)
}