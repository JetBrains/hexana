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
        Div({ style { property("opacity", "0.6"); property("padding", "8px 0") } }) {
            Text("No data segments found.")
        }
        return
    }

    PanelTable(
        headers = listOf("Type", "Size"),
        columnAligns = listOf("left", "right"),
    ) {
        for (record in dataRecords) {
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
                Td({ style { property("padding", "2px 8px") } }) {
                    Text(dataSegmentTypeLabel(record.type))
                }
                Td({ style { property("text-align", "right"); property("padding", "2px 8px") } }) {
                    Text("${record.recordLength} bytes")
                }
            }
        }
    }
}
