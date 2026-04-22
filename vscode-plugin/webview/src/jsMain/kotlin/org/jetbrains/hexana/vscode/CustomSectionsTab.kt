package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.bytesToAsciiPreview

@Composable
fun CustomSectionsTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
) {
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmCustomSections(buffer, selection)
        is BinaryProviderType.WasmComponent -> Div { Text("Custom sections tab is only available for core Wasm modules.") }
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmCustomSections(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val customSections = remember(provider) {
        provider.wasmModule?.customSections ?: emptyList()
    }

    if (customSections.isEmpty()) {
        Div({ style { property("opacity", "0.6"); property("padding", "8px 0") } }) {
            Text("No custom sections found.")
        }
        return
    }

    val payloads = remember(provider) {
        customSections.map { section ->
            val bytes = provider.readContent(section, 64)
            bytesToAsciiPreview(bytes)
        }
    }

    PanelTable(
        headers = listOf("Name", "Payload"),
        columnAligns = listOf("left", "left"),
    ) {
        for ((index, section) in customSections.withIndex()) {
            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == section.sectionOffset &&
                    selection.selectionEnd == section.sectionOffset + section.contentSize - 1,
                onClick = {
                    selection.anchorIndex = section.sectionOffset
                    selection.activeIndex = section.sectionOffset + section.contentSize - 1
                    selection.cursorIndex = section.sectionOffset
                    scrollHexViewerTo(section.sectionOffset)
                },
            ) {
                Td({ style { property("padding", "2px 8px") } }) { Text(section.name) }
                Td({ style { property("padding", "2px 8px"); property("opacity", "0.7") } }) {
                    Text(payloads[index])
                }
            }
        }
    }
}
