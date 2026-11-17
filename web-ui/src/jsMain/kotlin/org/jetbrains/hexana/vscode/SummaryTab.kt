package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.css.DisplayStyle
import org.jetbrains.compose.web.css.display
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H4
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.wasm.analysis.GraalWebImageInfo
import org.jetbrains.hexana.wasm.sections.WasmSection

@Composable
fun SummaryTabContent(
    provider: BinaryProvider,
    selection: HexSelectionState,
) {
    when (provider) {
        is WasmBinaryProvider -> CoreWasmSummary(provider, selection)
        is WasmComponentBinaryProvider -> ComponentSummary(provider, selection)
        else -> Div { Text("Not a WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmSummary(provider: WasmBinaryProvider, selection: HexSelectionState) {
    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val sections = provider.wasmModule?.sections ?: emptyList()
    val importCount = remember(provider) { provider.imports.size }
    val exportCount = remember(provider) { provider.exports.size }
    val functionCount = remember(provider) { provider.functions.size }
    val graalInfo = remember(provider) { provider.graalWebImageInfo }

    SummaryLayout(
        sections = sections,
        selection = selection,
        stats = {
            Div { Text("Imports: $importCount") }
            Div { Text("Exports: $exportCount") }
            Div { Text("Functions: $functionCount") }
            Div { Text("Sections: ${sections.size}") }
            if (graalInfo.isWebImage) {
                GraalWebImageRow(graalInfo)
            }
        },
    )
}

/**
 * Summary card row shown when the module is detected as a GraalVM Web Image binary.
 * Displays the matched import-module fingerprints that triggered the detection.
 */
@Composable
private fun GraalWebImageRow(graalInfo: GraalWebImageInfo) {
    val modules = graalInfo.matchedImportModules.sorted().joinToString(", ")
    Div({
        style {
            property("margin-top", "8px")
            property("padding-top", "6px")
            property("border-top", "1px solid var(--hexana-border, #333)")
        }
    }) {
        Div({
            style {
                property("font-weight", "600")
                property("margin-bottom", "2px")
            }
        }) { Text("Toolchain: GraalVM Web Image") }
        Div({
            style {
                property("color", "var(--vscode-descriptionForeground, #888)")
                property("font-size", "11px")
            }
        }) { Text("via: $modules") }
    }
}

@Composable
private fun ComponentSummary(provider: WasmComponentBinaryProvider, selection: HexSelectionState) {
    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse component-model binary.") }
        return
    }

    val sections = provider.wasmModule?.sections ?: emptyList()
    val importCount = remember(provider) { provider.imports.size }
    val exportCount = remember(provider) { provider.exports.size }
    val moduleCount = remember(provider) { provider.modules.size }

    SummaryLayout(
        sections = sections,
        selection = selection,
        stats = {
            Div { Text("Imports: $importCount") }
            Div { Text("Exports: $exportCount") }
            Div { Text("Modules: $moduleCount") }
            Div { Text("Sections: ${sections.size}") }
        },
    )
}

@Composable
private fun SummaryLayout(
    sections: List<WasmSection>,
    selection: HexSelectionState,
    stats: @Composable () -> Unit,
) {
    Div({
        style {
            display(DisplayStyle.Flex)
            property("gap", "24px")
            property("align-items", "flex-start")
        }
    }) {
        // Sections table – shrinks to fit available space
        Div({ style {
            property("flex", "1 1 0%")
            property("min-width", "0")
            property("overflow", "auto")
        } }) {
            H4({ style { property("margin", "0 0 8px 0") } }) { Text("Sections") }
            SummarySectionsTable(sections, selection)
        }

        // Summary card – fixed width, sticky
        Div({
            style {
                property("flex", "0 0 auto")
                property("position", "sticky")
                property("top", "0")
                property("align-self", "flex-start")
                property("min-width", "140px")
                property("font-size", "12px")
                property("line-height", "1.6")
                property("padding", "8px 12px")
                property("border-radius", "4px")
                property("background", "rgba(255,255,255,0.03)")
                property("border", "1px solid var(--hexana-border, #333)")
            }
        }) {
            H4({ style { property("margin", "0 0 8px 0") } }) { Text("Summary") }
            stats()
        }
    }
}

@Composable
private fun SummarySectionsTable(sections: List<WasmSection>, selection: HexSelectionState) {
    if (sections.isEmpty()) {
        Div({ classes("empty-hint") }) {
            Text("No sections found.")
        }
        return
    }

    SearchableTable(
        headers = listOf("Section", "Offset", "Size"),
        columnAligns = listOf("left", "right", "right"),
        defaultSortColumn = 1,
    ) {
        for (section in sections) {
            val sectionStart = section.sectionOffset.toLong()
            val sectionEnd = maxOf(sectionStart, sectionStart + section.sectionSize - 1)

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
                Td({ classes("cell") }) { Text(section.name) }
                Td({ classes("cell-right") }) {
                    Text("0x${section.sectionOffset.toString(16)}")
                }
                Td({ classes("cell-right") }) {
                    Text("${section.sectionSize} bytes")
                }
            }
        }
    }
}
