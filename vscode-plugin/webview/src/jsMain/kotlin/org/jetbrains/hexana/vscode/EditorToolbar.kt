package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.hasWasiCliRunExport
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry

/**
 * Top toolbar that shows the file name, size, detected binary kind,
 * and an optional run button for runnable modules.
 */
@Composable
fun EditorToolbar(
    fileName: String,
    size: Int,
    providerType: BinaryProviderType,
    buffer: CommonByteBuffer? = null,
    wasmBytes: ByteArray? = null,
) {
    val runnableExportName = remember(buffer, providerType) {
        if (buffer == null) return@remember null
        when (providerType) {
            is BinaryProviderType.Wasm -> {
                buffer.position(0)
                val provider = WasmBinaryProvider(buffer)
                if (!provider.canProvideWasmModule()) return@remember null
                val startExport = provider.exports.firstOrNull { export ->
                    export is WasmFunctionExportRecordEntry &&
                        (export.name == "_start" || export.name == "_initialize")
                }
                startExport?.name
            }
            is BinaryProviderType.WasmComponent -> {
                buffer.position(0)
                val provider = WasmComponentBinaryProvider(buffer)
                if (!provider.canProvideWasmModule() || !hasWasiCliRunExport(provider)) return@remember null
                "wasi:cli/run"
            }
            else -> null
        }
    }

    val isRunnable = runnableExportName != null
    val isComponent = providerType is BinaryProviderType.WasmComponent

    var showArgsDialog by remember { mutableStateOf(false) }

    if (showArgsDialog && runnableExportName != null) {
        ProgramArgsDialog(
            exportName = runnableExportName,
            onConfirm = { programArgs ->
                postRunExportToHost(
                    exportName = "",
                    isComponent = isComponent,
                    programArgs = programArgs,
                    wasmBytes = wasmBytes,
                )
                showArgsDialog = false
            },
            onCancel = { showArgsDialog = false },
        )
    }

    Div({
        classes("hexana-toolbar")
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("gap", "16px")
            property("padding", "6px 12px")
            property("border-bottom", "1px solid var(--hexana-border, #333)")
            property("flex-shrink", "0")
        }
    }) {
        Span({ style { property("font-weight", "600") } }) {
            Text(fileName.substringAfterLast('/').substringAfterLast('\\'))
        }

        Span({ style { property("opacity", "0.7") } }) {
            Text(formatSize(size))
        }

        Span({
            classes("hexana-badge")
            style {
                property("padding", "2px 8px")
                property("border-radius", "4px")
                property("font-size", "11px")
                property("background", "var(--vscode-badge-background, #4D4D4D)")
                property("color", "var(--vscode-badge-foreground, #FFF)")
            }
        }) {
            Text(
                when (providerType) {
                    is BinaryProviderType.Wasm -> "Core Wasm"
                    is BinaryProviderType.WasmComponent -> "Component Model"
                    is BinaryProviderType.Generic -> "Binary"
                }
            )
        }

        if (isRunnable) {
            ToolbarRunButton(
                tooltip = "Run Wasm module",
                onClick = { showArgsDialog = true }
            )
        }
    }
}

/**
 * Button-styled run control for the editor toolbar.
 * Displays a play arrow followed by a "Run" label, with a visible
 * border and hover highlight so it reads as a clickable button.
 */
@Composable
private fun ToolbarRunButton(tooltip: String = "Run", onClick: () -> Unit) {
    Span({
        attr("title", tooltip)
        style {
            property("cursor", "pointer")
            property("display", "inline-flex")
            property("align-items", "center")
            property("gap", "4px")
            property("padding", "3px 10px 3px 6px")
            property("border-radius", "4px")
            property("border", "1px solid var(--vscode-button-border, var(--vscode-contrastBorder, transparent))")
            property("background", "var(--vscode-button-background, #0e639c)")
            property("color", "var(--vscode-button-foreground, #fff)")
            property("font-size", "12px")
            property("line-height", "1")
            property("user-select", "none")
        }
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        Span({
            style {
                property("font-size", "11px")
                property("display", "inline-flex")
                property("align-items", "center")
            }
        }) {
            Text("\u25B6")
        }
        Span {
            Text("Run")
        }
    }
}

private fun formatSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> {
        val mb = bytes.toDouble() / (1024 * 1024)
        val rounded = (mb * 10).toLong() / 10.0
        "$rounded MB"
    }
}
