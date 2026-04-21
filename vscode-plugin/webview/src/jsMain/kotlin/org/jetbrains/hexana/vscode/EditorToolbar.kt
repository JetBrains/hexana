package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.providers.BinaryProviderType

/**
 * Top toolbar that shows the file name, size, and detected binary kind.
 */
@Composable
fun EditorToolbar(
    fileName: String,
    size: Int,
    providerType: BinaryProviderType,
) {
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
