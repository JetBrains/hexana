package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.wasm.analysis.SizeNode

@Composable
fun SizeTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
) {
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmSize(buffer, selection)
        is BinaryProviderType.WasmComponent -> ComponentSize(buffer, selection)
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmSize(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val module = remember(provider) { provider.sizeAnalysis }

    if (module == null) {
        Div({ classes("empty-hint") }) {
            Text("Size analysis unavailable.")
        }
        return
    }

    SizeNodeTable(module, selection)
}

@Composable
private fun ComponentSize(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmComponentBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse component-model binary.") }
        return
    }

    val module = remember(provider) { provider.sizeAnalysis }

    if (module == null) {
        Div({ classes("empty-hint") }) {
            Text("Size analysis unavailable.")
        }
        return
    }

    SizeNodeTable(module, selection)
}

@Composable
private fun SizeNodeTable(module: SizeNode.Module, selection: HexSelectionState) {
    Div({ style { property("margin-bottom", "8px"); property("font-size", "12px") } }) {
        Text("Total: ${formatSize(module.size)}")
    }

    if (module.children.isEmpty()) {
        Div({ classes("empty-hint") }) {
            Text("No size data available.")
        }
        return
    }

    SearchableTable(
        headers = listOf("ID", "Section", "Size", "%", "Offset"),
        columnAligns = listOf("right", "left", "right", "right", "right"),
        defaultSortColumn = 2,
        defaultSortDirection = SortDirection.DESC,
    ) {
        for ((index, child) in module.children.withIndex()) {
            val pct = child.percentage(module.size)
            val pctStr = "${((pct * 10).toInt() / 10)}.${((pct * 10).toInt() % 10)}%"
            val hex = "0x${child.offset.toString(16).uppercase()}"

            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == child.offset &&
                    selection.selectionEnd == child.offset + child.size - 1,
                onClick = {
                    if (child.size > 0) {
                        selection.anchorIndex = child.offset
                        selection.activeIndex = child.offset + child.size - 1
                        selection.cursorIndex = child.offset
                        scrollHexViewerTo(child.offset)
                    }
                },
            ) {
                Td({ classes("cell-id") }) {
                    Text(index.toString())
                }
                Td({ classes("cell") }) { Text(child.name) }
                Td({ classes("cell-right") }) {
                    Text(formatSize(child.size))
                }
                Td({ classes("cell-right") }) {
                    Text(pctStr)
                }
                Td({ classes("cell-right-muted") }) {
                    Text(hex)
                }
            }
        }
    }
}
