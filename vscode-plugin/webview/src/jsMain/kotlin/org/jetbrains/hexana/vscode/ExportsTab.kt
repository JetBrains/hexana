package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
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
        headers = listOf("Kind", "Name", "Index", "Signature"),
        columnAligns = listOf("left", "left", "right", "left"),
    ) {
        for (export in exports) {
            val kind = exportKindLabel(export)
            val sig = if (export is WasmFunctionExportRecordEntry) {
                resolveCoreSignature(export, functions, importFuncCount)
            } else ""

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
        headers = listOf("Name", "Sort Index", "Description"),
        columnAligns = listOf("left", "left", "left"),
    ) {
        for (export in exports) {
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

private fun exportKindLabel(export: WasmExportRecordEntry): String = when (export) {
    is WasmFunctionExportRecordEntry -> "function"
    is WasmGlobalExportRecordEntry -> "global"
    is WasmMemoryExportRecordEntry -> "memory"
    is WasmTableExportRecordEntry -> "table"
    is WasmTagExportRecordEntry -> "tag"
    is WasmUnsupportedExportRecordEntry -> "unknown"
}

private fun exportIndex(export: WasmExportRecordEntry): String = when (export) {
    is WasmFunctionExportRecordEntry -> export.index.toString()
    is WasmGlobalExportRecordEntry -> export.index.toString()
    is WasmMemoryExportRecordEntry -> export.index.toString()
    is WasmTableExportRecordEntry -> export.index.toString()
    is WasmTagExportRecordEntry -> export.index.toString()
    is WasmUnsupportedExportRecordEntry -> export.index.toString()
}

private fun resolveCoreSignature(
    export: WasmFunctionExportRecordEntry,
    functions: List<WasmTypeRecord>,
    importFuncCount: Int,
): String {
    val localIndex = export.index.toInt() - importFuncCount
    if (localIndex < 0) return ""
    val sig = functions.getOrNull(localIndex) as? WasmSectionFunctionTypeRecord ?: return ""
    val params = sig.params.joinToString(", ") { wasmValueTypeName(it) }
    val result = sig.retTypes.firstOrNull()?.let { wasmValueTypeName(it) } ?: "void"
    return "($params) → $result"
}

internal fun wasmValueTypeName(type: WasmValueType<*>): String = when (type) {
    is WasmI32Type -> "i32"
    is WasmI64Type -> "i64"
    is WasmF32Type -> "f32"
    is WasmF64Type -> "f64"
    is WasmVecType -> "v128"
    else -> type.toString()
}
