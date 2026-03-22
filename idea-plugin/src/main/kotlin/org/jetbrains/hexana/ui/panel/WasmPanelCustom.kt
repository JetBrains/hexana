package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.asASCII
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace


private data class Payload(val offset: Int, val size: Int, val payload: String)

@Composable
fun WasmPanelCustom(wasmBinaryProvider: WasmBinaryProvider, state: HexViewStateHolder) {
    val customSections = remember(wasmBinaryProvider) {
        wasmBinaryProvider.wasmModule?.customSections ?: emptyList()
    }
    if (customSections.isEmpty()) return

    val longestSection = customSections.maxBy { customSection -> customSection.name.length }

    val payloads = remember(wasmBinaryProvider) {
        customSections.map {
            val bytes = wasmBinaryProvider.readContent(it, 64)
            return@map Payload(it.sectionOffset, it.contentSize, bytes.joinToString(separator = "") { b -> b.asASCII() ?: "." })
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val minWidthNameColumnModifier = textMeasurer.measureWidthMonospace(longestSection.name)

    Table(
        listOf(
        TableColumn("Name", modifier = Modifier.widthIn(min = minWidthNameColumnModifier)) {
            TableCellValue(
                customSections[it].name
            )
        },
        TableColumn("Payload", modifier = Modifier.fillMaxWidth()) {
            val payload = payloads[it].payload
            TableCellValue(payload)
        }
    ), customSections.size) { rowIndex ->
        state.scrollHexViewTo(payloads[rowIndex].offset, payloads[rowIndex].size)
    }
}