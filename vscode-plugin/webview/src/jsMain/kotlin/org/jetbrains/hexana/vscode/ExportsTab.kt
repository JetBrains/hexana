package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.exportKindLabel
import org.jetbrains.hexana.shared.exportIndex
import org.jetbrains.hexana.shared.resolveCoreSignature
import org.jetbrains.hexana.shared.wasmValueTypeName
import org.jetbrains.hexana.shared.isWasiCliRunExportName
import org.jetbrains.hexana.shared.isComponentFunctionExport
import org.jetbrains.hexana.shared.hasWasiCliRunExport as sharedHasWasiCliRunExport
import org.jetbrains.hexana.shared.isInternalName
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.hexana.wasm.sections.*

@Composable
fun ExportsTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
) {
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmExports(buffer, selection)
        is BinaryProviderType.WasmComponent -> ComponentExports(buffer, selection)
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmExports(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val exports = remember(provider) { provider.exports }
    val functions = remember(provider) { provider.functions }
    val importFuncCount = remember(provider) { provider.importFuncCount }

    if (exports.isEmpty()) {
        Div({ style { property("opacity", "0.6"); property("padding", "8px 0") } }) {
            Text("No exports found.")
        }
        return
    }

    PanelTable(
        headers = listOf("", "Kind", "Name", "Index", "Signature"),
        columnAligns = listOf("center", "left", "left", "right", "left"),
    ) {
        for (export in exports) {
            val kind = exportKindLabel(export)
            val sig = if (export is WasmFunctionExportRecordEntry) {
                resolveCoreSignature(export, functions, importFuncCount)
            } else ""

            val isRunnable = export is WasmFunctionExportRecordEntry && !isInternalName(export.name)

            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == export.offset &&
                    selection.selectionEnd == export.offset + export.size - 1,
                onClick = {
                    selection.anchorIndex = export.offset
                    selection.activeIndex = export.offset + export.size - 1
                    selection.cursorIndex = export.offset
                    scrollHexViewerTo(export.offset)
                },
            ) {
                Td({ style { property("padding", "2px 4px"); property("width", "24px") } }) {
                    if (isRunnable) {
                        RunArrowButton(
                            tooltip = "Run '${export.name}'",
                            onClick = {
                                postRunExportToHost(
                                    exportName = export.name,
                                    invokeArgs = "--invoke ${export.name}",
                                )
                            }
                        )
                    }
                }
                Td({ style { property("padding", "2px 8px") } }) { Text(kind) }
                Td({ style { property("padding", "2px 8px") } }) { Text(export.name) }
                Td({ style { property("text-align", "right"); property("padding", "2px 8px") } }) {
                    Text(exportIndex(export))
                }
                Td({ style { property("padding", "2px 8px"); property("opacity", "0.7") } }) { Text(sig) }
            }
        }
    }
}

@Composable
private fun ComponentExports(buffer: CommonByteBuffer, selection: HexSelectionState) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmComponentBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse component-model binary.") }
        return
    }

    val exports = remember(provider) { provider.exports }

    if (exports.isEmpty()) {
        Div({ style { property("opacity", "0.6"); property("padding", "8px 0") } }) {
            Text("No exports found.")
        }
        return
    }

    PanelTable(
        headers = listOf("", "Name", "Sort Index", "Description"),
        columnAligns = listOf("center", "left", "left", "left"),
    ) {
        for (export in exports) {
            val isWasiCliRun = isWasiCliRunExportName(export.exportName)
            val isFunctionExport = isComponentFuncExport(export, provider)
            val isRunnable = isWasiCliRun || isFunctionExport

            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == export.offset &&
                    selection.selectionEnd == export.offset + export.size - 1,
                onClick = {
                    selection.anchorIndex = export.offset
                    selection.activeIndex = export.offset + export.size - 1
                    selection.cursorIndex = export.offset
                    scrollHexViewerTo(export.offset)
                },
            ) {
                Td({ style { property("padding", "2px 4px"); property("width", "24px") } }) {
                    if (isRunnable) {
                        RunArrowButton(
                            tooltip = "Run '${export.exportName}'",
                            onClick = {
                                postRunExportToHost(
                                    exportName = export.exportName,
                                    isComponent = true,
                                )
                            }
                        )
                    }
                }
                Td({ style { property("padding", "2px 8px") } }) {
                    Text(export.exportName + (export.version?.let { " $it" } ?: ""))
                }
                Td({ style { property("padding", "2px 8px") } }) {
                    Text(componentSortIndexLabel(export.sortIndex))
                }
                Td({ style { property("padding", "2px 8px"); property("opacity", "0.7") } }) {
                    Text(export.externDescription?.let { componentExternDescriptionLabel(it) } ?: "")
                }
            }
        }
    }
}

/**
 * Small play-arrow button used as a run indicator in table rows.
 * Uses a Unicode play triangle (▶) styled to match VS Code's green run icon.
 */
@Composable
internal fun RunArrowButton(tooltip: String = "Run", onClick: () -> Unit) {
    Span({
        classes("hexana-run-arrow")
        attr("title", tooltip)
        style {
            property("cursor", "pointer")
            property("display", "inline-flex")
            property("align-items", "center")
            property("justify-content", "center")
            property("width", "16px")
            property("height", "16px")
            property("border-radius", "3px")
            property("font-size", "12px")
            property("color", "var(--vscode-debugIcon-startForeground, #89d185)")
        }
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        Text("\u25B6")
    }
}

internal fun isComponentFuncExport(export: WasmComponentExport, provider: WasmComponentBinaryProvider): Boolean =
    isComponentFunctionExport(export, provider)

internal fun hasWasiCliRunExport(provider: WasmComponentBinaryProvider): Boolean =
    sharedHasWasiCliRunExport(provider)
