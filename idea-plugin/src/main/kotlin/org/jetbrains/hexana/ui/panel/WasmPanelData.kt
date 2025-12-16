package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WasmPanelData(wasmBinaryProvider: WasmBinaryProvider, state: HexViewStateHolder) {
    val dataRecords = remember(wasmBinaryProvider) { wasmBinaryProvider.data }

    val textMeasurer = rememberTextMeasurer()
    val minWidthKindColumnModifier = textMeasurer.measureWidthMonospace("1 (passive)")

    if (dataRecords.isEmpty()) return

    Table(listOf(
        TableColumn("Type", modifier = Modifier.widthIn(min = minWidthKindColumnModifier)) { index ->
            val type = dataRecords[index].type
            val typeDescription = when (type) {
                0u -> "0 (active)"
                1u -> "1 (passive)"
                2u -> "2 (active)"
                else -> "3 (unknown)"
            }
            TableCellValue(typeDescription)
        },
        TableColumn("Size", modifier = Modifier.fillMaxWidth()) { index ->
            TableCellValue(dataRecords[index].recordLength)
        }
    ), dataRecords.size) {
        val dataRecord = dataRecords[it]
        state.scrollHexViewTo(dataRecord.offset.toLong())

        val startLine = (dataRecord.offset / state.bytesPerRow.value)
        val startLineOffset = (dataRecord.offset % state.bytesPerRow.value)

        val endLine =
            ((dataRecord.offset + dataRecord.recordLength) / state.bytesPerRow.value)
        val endLineOffset =
            ((dataRecord.offset + dataRecord.recordLength) % state.bytesPerRow.value) - 1

        state.setSelection(
            startLine, startLineOffset,
            endLine, endLineOffset
        )
    }
}

