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
import org.jetbrains.hexana.shared.formatSizeLong
import org.jetbrains.hexana.shared.garbageReasonLabel
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageResult

@Composable
fun GarbageTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
) {
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmGarbage(buffer, selection)
        is BinaryProviderType.WasmComponent -> Div { Text("Garbage tab is only available for core Wasm modules.") }
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmGarbage(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val garbage = remember(provider) { provider.garbageAnalysis }
    val importFuncCount = remember(provider) { provider.importFuncCount }
    val codeOffsets = remember(provider) { provider.codeOffsets }

    if (garbage == null) {
        Div({ classes("empty-hint") }) {
            Text("Analysis unavailable.")
        }
        return
    }

    // Functions section
    GarbageFunctionsSection(garbage, importFuncCount, codeOffsets, selection)

    // Types section
    GarbageTypesSection(garbage)

    // Data section
    GarbageDataSection(garbage)
}

@Composable
private fun GarbageFunctionsSection(
    garbage: GarbageResult,
    importFuncCount: Int,
    codeOffsets: List<org.jetbrains.hexana.wasm.sections.CodeEntryOffset>,
    selection: HexSelectionState,
) {
    val entries = garbage.entries
    val pct = if (garbage.codeSectionTotalBytes > 0) {
        val v = garbage.totalWastedBytes * 1000 / garbage.codeSectionTotalBytes
        "${v / 10}.${v % 10}%"
    } else "\u2014"

    Div({ style { property("margin-bottom", "4px"); property("font-size", "12px") } }) {
        Text("${entries.size} unreachable functions, ${formatSizeLong(garbage.totalWastedBytes)} wasted ($pct of code)")
    }

    if (entries.isEmpty()) return

    PanelTable(
        headers = listOf("ID", "Index", "Name", "Size", "Module", "Reason"),
        columnAligns = listOf("right", "right", "left", "right", "left", "left"),
    ) {
        for ((rowIndex, entry) in entries.withIndex()) {
            val codeIdx = entry.funcIndex - importFuncCount
            val offset = codeOffsets.getOrNull(codeIdx)

            PanelTableRow(
                isHighlighted = offset != null && selection.hasSelection &&
                    selection.selectionStart == offset.offset &&
                    selection.selectionEnd == offset.offset + offset.size - 1,
                onClick = {
                    if (offset != null && entry.size > 0) {
                        selection.anchorIndex = offset.offset
                        selection.activeIndex = offset.offset + offset.size - 1
                        selection.cursorIndex = offset.offset
                        scrollHexViewerTo(offset.offset)
                    }
                },
            ) {
                Td({ classes("cell-id") }) {
                    Text(rowIndex.toString())
                }
                Td({ classes("cell-right") }) {
                    Text(entry.funcIndex.toString())
                }
                Td({ classes("cell") }) { Text(entry.name) }
                Td({ classes("cell-right") }) {
                    Text(if (entry.size > 0) formatSize(entry.size) else "\u2014")
                }
                Td({ classes("cell") }) { Text(entry.module ?: "") }
                Td({ classes("cell-muted") }) {
                    Text(garbageReasonLabel(entry.reason))
                }
            }
        }
    }
}

@Composable
private fun GarbageTypesSection(garbage: GarbageResult) {
    val entries = garbage.typeEntries

    Div({ style { property("margin-top", "12px"); property("margin-bottom", "4px"); property("font-size", "12px") } }) {
        Text("${entries.size} unreachable types, ${formatSizeLong(garbage.typeWastedBytes)} wasted")
    }

    if (entries.isEmpty()) return

    PanelTable(
        headers = listOf("ID", "Index", "Size"),
        columnAligns = listOf("right", "right", "right"),
    ) {
        for ((rowIndex, entry) in entries.withIndex()) {
            PanelTableRow(isHighlighted = false, onClick = {}) {
                Td({ classes("cell-id") }) {
                    Text(rowIndex.toString())
                }
                Td({ classes("cell-right") }) {
                    Text(entry.typeIndex.toString())
                }
                Td({ classes("cell-right") }) {
                    Text("${entry.size} B")
                }
            }
        }
    }
}

@Composable
private fun GarbageDataSection(garbage: GarbageResult) {
    val entries = garbage.dataSegmentEntries

    Div({ style { property("margin-top", "12px"); property("margin-bottom", "4px"); property("font-size", "12px") } }) {
        Text("${entries.size} data segments, ${formatSizeLong(garbage.dataSegmentWastedBytes)} potential garbage")
    }

    if (entries.isEmpty()) return

    PanelTable(
        headers = listOf("ID", "Index", "Data Size", "Record Size"),
        columnAligns = listOf("right", "right", "right", "right"),
    ) {
        for ((rowIndex, entry) in entries.withIndex()) {
            PanelTableRow(isHighlighted = false, onClick = {}) {
                Td({ classes("cell-id") }) {
                    Text(rowIndex.toString())
                }
                Td({ classes("cell-right") }) {
                    Text(entry.index.toString())
                }
                Td({ classes("cell-right") }) {
                    Text(formatSize(entry.dataSize))
                }
                Td({ classes("cell-right") }) {
                    Text(formatSize(entry.recordSize))
                }
            }
        }
    }
}
