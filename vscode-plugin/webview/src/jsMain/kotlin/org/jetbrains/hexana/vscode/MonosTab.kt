package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.formatSize

@Composable
fun MonosTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
) {
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmMonos(buffer, selection)
        is BinaryProviderType.WasmComponent -> Div { Text("Monos tab is only available for core Wasm modules.") }
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmMonos(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val monos = remember(provider) { provider.monosAnalysis }
    val importFuncCount = remember(provider) { provider.importFuncCount }
    val codeOffsets = remember(provider) { provider.codeOffsets }

    if (monos == null || monos.groups.isEmpty()) {
        Div({ classes("empty-hint") }) {
            Text(if (monos == null) "No name section available." else "No monomorphizations detected.")
        }
        return
    }

    PanelTable(
        headers = listOf("ID", "Base Name", "Count", "Total Size", "Avg Size"),
        columnAligns = listOf("right", "left", "right", "right", "right"),
    ) {
        for ((index, group) in monos.groups.withIndex()) {
            val firstFuncIdx = group.funcIndices.firstOrNull()
            val codeIdx = if (firstFuncIdx != null) firstFuncIdx - importFuncCount else -1
            val entry = codeOffsets.getOrNull(codeIdx)

            PanelTableRow(
                isHighlighted = entry != null && selection.hasSelection &&
                    selection.selectionStart == entry.offset &&
                    selection.selectionEnd == entry.offset + entry.size - 1,
                onClick = {
                    if (entry != null) {
                        selection.anchorIndex = entry.offset
                        selection.activeIndex = entry.offset + entry.size - 1
                        selection.cursorIndex = entry.offset
                        scrollHexViewerTo(entry.offset)
                    }
                },
            ) {
                Td({ classes("cell-id") }) {
                    Text(index.toString())
                }
                Td({ classes("cell") }) { Text(group.baseName) }
                Td({ classes("cell-right") }) {
                    Text(group.count.toString())
                }
                Td({ classes("cell-right") }) {
                    Text(formatSize(group.totalSize))
                }
                Td({ classes("cell-right") }) {
                    Text(formatSize(group.avgSize))
                }
            }
        }
    }
}
