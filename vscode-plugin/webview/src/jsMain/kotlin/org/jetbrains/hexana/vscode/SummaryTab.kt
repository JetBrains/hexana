package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H4
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmSection

@Composable
fun SummaryTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
) {
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmSummary(buffer, selection)
        is BinaryProviderType.WasmComponent -> ComponentSummary(buffer, selection)
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmSummary(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val sections = provider.wasmModule?.sections ?: emptyList()
    val importCount = remember(provider) { provider.imports.size }
    val exportCount = remember(provider) { provider.exports.size }
    val functionCount = remember(provider) { provider.functions.size }

    H4({ style { property("margin", "0 0 8px 0") } }) { Text("Sections") }

    SummarySectionsTable(sections, selection)

    H4({ style { property("margin", "16px 0 8px 0") } }) { Text("Summary") }
    Div({ style { property("font-size", "12px"); property("line-height", "1.6") } }) {
        Div { Text("Imports: $importCount") }
        Div { Text("Exports: $exportCount") }
        Div { Text("Functions: $functionCount") }
        Div { Text("Sections: ${sections.size}") }
    }
}

@Composable
private fun ComponentSummary(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmComponentBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse component-model binary.") }
        return
    }

    val sections = provider.wasmModule?.sections ?: emptyList()
    val importCount = remember(provider) { provider.imports.size }
    val exportCount = remember(provider) { provider.exports.size }
    val moduleCount = remember(provider) { provider.modules.size }

    H4({ style { property("margin", "0 0 8px 0") } }) { Text("Sections") }

    SummarySectionsTable(sections, selection)

    H4({ style { property("margin", "16px 0 8px 0") } }) { Text("Summary") }
    Div({ style { property("font-size", "12px"); property("line-height", "1.6") } }) {
        Div { Text("Imports: $importCount") }
        Div { Text("Exports: $exportCount") }
        Div { Text("Modules: $moduleCount") }
        Div { Text("Sections: ${sections.size}") }
    }
}

@Composable
private fun SummarySectionsTable(sections: List<WasmSection>, selection: HexSelectionState) {
    if (sections.isEmpty()) {
        Div({ style { property("opacity", "0.6"); property("padding", "8px 0") } }) {
            Text("No sections found.")
        }
        return
    }

    PanelTable(
        headers = listOf("Section", "Offset", "Size"),
        columnAligns = listOf("left", "right", "right"),
    ) {
        for (section in sections) {
            val sectionStart = section.sectionOffset
            val sectionEnd = sectionStart + section.sectionSize - 1

            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == sectionStart &&
                    selection.selectionEnd == sectionEnd,
                onClick = {
                    selection.anchorIndex = sectionStart
                    selection.activeIndex = sectionEnd
                    selection.cursorIndex = sectionStart
                    scrollHexViewerTo(sectionStart)
                },
            ) {
                Td({ style { property("padding", "2px 8px") } }) { Text(section.name) }
                Td({ style { property("text-align", "right"); property("padding", "2px 8px") } }) {
                    Text("0x${section.sectionOffset.toString(16)}")
                }
                Td({ style { property("text-align", "right"); property("padding", "2px 8px") } }) {
                    Text("${section.sectionSize} bytes")
                }
            }
        }
    }
}
