package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.dataSegmentTypeLabel

@Composable
fun DataTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
) {
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmData(buffer, selection)
        is BinaryProviderType.WasmComponent -> Div { Text("Data tab is only available for core Wasm modules.") }
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmData(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val dataRecords = remember(provider) { provider.data }

    if (dataRecords.isEmpty()) {
        Div({ classes("empty-hint") }) {
            Text("No data segments found.")
        }
        return
    }

    SearchableTable(
        headers = listOf("ID", "Type", "Size"),
        columnAligns = listOf("right", "left", "right"),
    ) {
        for ((index, record) in dataRecords.withIndex()) {
            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == record.offset &&
                    selection.selectionEnd == record.offset + record.recordLength - 1,
                onClick = {
                    selection.anchorIndex = record.offset
                    selection.activeIndex = record.offset + record.recordLength - 1
                    selection.cursorIndex = record.offset
                    scrollHexViewerTo(record.offset)
                },
            ) {
                Td({ classes("cell-id") }) {
                    Text(index.toString())
                }
                Td({ classes("cell") }) {
                    Text(dataSegmentTypeLabel(record.type))
                }
                Td({ classes("cell-right") }) {
                    Text("${record.recordLength} bytes")
                }
            }
        }
    }
}
