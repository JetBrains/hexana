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
import org.jetbrains.hexana.shared.importKindLabel
import org.jetbrains.hexana.shared.componentExternDescriptionLabel

@Composable
fun ImportsTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
) {
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmImports(buffer, selection)
        is BinaryProviderType.WasmComponent -> ComponentImports(buffer, selection)
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmImports(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val imports = remember(provider) { provider.imports }

    if (imports.isEmpty()) {
        Div({ style { property("opacity", "0.6"); property("padding", "8px 0") } }) {
            Text("No imports found.")
        }
        return
    }

    PanelTable(
        headers = listOf("ID", "Kind", "Module", "Name"),
        columnAligns = listOf("right", "left", "left", "left"),
    ) {
        for ((index, imp) in imports.withIndex()) {
            val kind = importKindLabel(imp.type)

            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == imp.offset &&
                    selection.selectionEnd == imp.offset + imp.size - 1,
                onClick = {
                    selection.anchorIndex = imp.offset
                    selection.activeIndex = imp.offset + imp.size - 1
                    selection.cursorIndex = imp.offset
                    scrollHexViewerTo(imp.offset)
                },
            ) {
                Td({ style { property("text-align", "right"); property("padding", "2px 4px") } }) {
                    Text(index.toString())
                }
                Td({ style { property("padding", "2px 8px") } }) { Text(kind) }
                Td({ style { property("padding", "2px 8px"); property("opacity", "0.7") } }) { Text(imp.prefix) }
                Td({ style { property("padding", "2px 8px") } }) { Text(imp.name) }
            }
        }
    }
}

@Composable
private fun ComponentImports(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmComponentBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse component-model binary.") }
        return
    }

    val imports = remember(provider) { provider.imports }

    if (imports.isEmpty()) {
        Div({ style { property("opacity", "0.6"); property("padding", "8px 0") } }) {
            Text("No imports found.")
        }
        return
    }

    PanelTable(
        headers = listOf("ID", "Name", "Description"),
        columnAligns = listOf("right", "left", "left"),
    ) {
        for ((index, imp) in imports.withIndex()) {
            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == imp.offset &&
                    selection.selectionEnd == imp.offset + imp.size - 1,
                onClick = {
                    selection.anchorIndex = imp.offset
                    selection.activeIndex = imp.offset + imp.size - 1
                    selection.cursorIndex = imp.offset
                    scrollHexViewerTo(imp.offset)
                },
            ) {
                Td({ style { property("text-align", "right"); property("padding", "2px 4px") } }) {
                    Text(index.toString())
                }
                Td({ style { property("padding", "2px 8px") } }) {
                    Text(imp.importName + (imp.version?.let { " $it" } ?: ""))
                }
                Td({ style { property("padding", "2px 8px"); property("opacity", "0.7") } }) {
                    Text(componentExternDescriptionLabel(imp.externDescription))
                }
            }
        }
    }
}

