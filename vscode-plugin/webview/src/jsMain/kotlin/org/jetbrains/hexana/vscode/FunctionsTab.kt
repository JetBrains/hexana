package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.getDescription
import org.jetbrains.hexana.shared.resolveFunctionName

@Composable
fun FunctionsTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
) {
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmFunctions(buffer, selection)
        is BinaryProviderType.WasmComponent -> Div { Text("Functions tab is only available for core Wasm modules.") }
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmFunctions(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val functions = remember(provider) { provider.functions }
    val nameSection = remember(provider) { provider.nameSection }
    val functionNamesMap = nameSection?.functionSubsection?.namemap
    val typeNames = nameSection?.typesSection?.namemap

    if (functions.isEmpty()) {
        Div({ classes("empty-hint") }) {
            Text("No functions found.")
        }
        return
    }

    var selectedIndex by remember { mutableStateOf(-1) }

    val hasNames = functionNamesMap != null

    val headers = buildList {
        add("ID")
        if (hasNames) add("Name")
        add("Signature")
    }
    val aligns = buildList {
        add("right")
        if (hasNames) add("left")
        add("left")
    }

    SearchableTable(headers = headers, columnAligns = aligns) {
        for ((index, func) in functions.withIndex()) {
            val name = if (hasNames) resolveFunctionName(functionNamesMap, index) else null
            val sig = func.getDescription(index, typeNames)

            PanelTableRow(
                isHighlighted = selectedIndex == index,
                onClick = {
                    selectedIndex = index
                    selection.anchorIndex = func.offset
                    selection.activeIndex = func.offset + func.size - 1
                    selection.cursorIndex = func.offset
                    scrollHexViewerTo(func.offset)
                },
            ) {
                Td({ classes("cell-id") }) {
                    Text(index.toString())
                }
                if (hasNames) {
                    Td({ classes("cell"); style { property("word-break", "break-all") } }) { Text(name ?: "") }
                }
                Td({ classes("cell-muted"); style { property("word-break", "break-all") } }) { Text(sig) }
            }
        }
    }
}
