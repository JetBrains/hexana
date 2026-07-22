package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Thin status bar below the hex viewer showing cursor position and selection info.
 */
@Composable
fun SelectionStatusBar(
    selection: HexSelectionState,
    totalBytes: Int,
    showDataInspector: Boolean = false,
    onToggleDataInspector: () -> Unit = {},
) {
    Div({
        classes("hexana-status-bar")
        style {
            display(DisplayStyle.Flex)
            property("align-items", "center")
            property("gap", "16px")
            property("padding", "2px 12px")
            property("border-top", "1px solid var(--hexana-border, #333)")
            property("border-bottom", "1px solid var(--hexana-border, #333)")
            property("font-size", "11px")
            property("opacity", "0.8")
            property("flex-shrink", "0")
            property("min-height", "20px")
        }
    }) {
        if (selection.cursorIndex >= 0) {
            Span {
                Text("Offset: 0x${selection.cursorIndex.toString(16).padStart(8, '0')}")
            }
            Span {
                Text("Byte: ${selection.cursorIndex} / $totalBytes")
            }
        }

        if (selection.hasSelection) {
            val count = selection.selectionEnd - selection.selectionStart + 1
            Span {
                Text("Selected: $count byte${if (count != 1) "s" else ""}")
            }
            Span {
                Text("Range: 0x${selection.selectionStart.toString(16)} – 0x${selection.selectionEnd.toString(16)}")
            }
        }

        if (selection.cursorIndex < 0) {
            Span {
                Text("Click a byte to begin")
            }
        }

        // Spacer to push toggle to the right
        Span({ style { property("flex", "1") } }) {}

        Span({
            style {
                property("cursor", "pointer")
                property("text-decoration", "underline")
            }
            onClick { onToggleDataInspector() }
        }) {
            Text(if (showDataInspector) "Hide Inspector" else "Data Inspector")
        }
    }
}
