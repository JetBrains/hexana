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

@Composable
fun WasmPanelCustom(wasmBinaryProvider: WasmBinaryProvider) {
    val customRecords = remember(wasmBinaryProvider) {
        wasmBinaryProvider.customRecords
    }

    if (customRecords.isEmpty()) return

    val longestRecord = customRecords.maxBy { customRecords -> customRecords.name.length }

    val textMeasurer = rememberTextMeasurer()
    val minWidthNameColumnModifier = textMeasurer.measureWidthMonospace(longestRecord.name)

    Table(
        listOf(
        TableColumn("Name", modifier = Modifier.widthIn(min = minWidthNameColumnModifier)) {
            TableCellValue(
                customRecords[it].name
            )
        },
        TableColumn("Payload", modifier = Modifier.fillMaxWidth()) { TableCellValue(customRecords[it].payload) }
    ), customRecords.size)
}