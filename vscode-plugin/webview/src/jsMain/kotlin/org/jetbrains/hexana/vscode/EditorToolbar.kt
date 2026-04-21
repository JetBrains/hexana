package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
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
) {
    val isRunnable = remember(buffer, providerType) {
        if (buffer == null) return@remember false
        when (providerType) {
            is BinaryProviderType.Wasm -> {
                buffer.position(0)
                val provider = WasmBinaryProvider(buffer)
                provider.canProvideWasmModule() && provider.exports.any { export ->
                    export is WasmFunctionExportRecordEntry &&
                        (export.name == "_start" || export.name == "_initialize")
                }
            }
            is BinaryProviderType.WasmComponent -> {
                buffer.position(0)
                val provider = WasmComponentBinaryProvider(buffer)
                provider.canProvideWasmModule() && hasWasiCliRunExport(provider)
            }
            else -> false
        }
    }

    val isComponent = providerType is BinaryProviderType.WasmComponent

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
            RunArrowButton(
                tooltip = "Run Wasm module",
                onClick = {
                    postRunExportToHost(
                        exportName = "",
                        isComponent = isComponent,
                    )
                }
            )
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
