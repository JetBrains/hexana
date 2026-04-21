package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

private const val BYTES_PER_ROW = 16

/**
 * Compose for Web hex viewer panel.
 *
 * Renders the raw binary content in a classic hex-dump layout:
 * offset | hex bytes | ASCII decode.
 *
 * This is a draft implementation — the IntelliJ plugin's full-featured
 * [org.jetbrains.hexana.ui.HexViewer] can be incrementally ported here.
 */
@Composable
fun HexViewerPanel(bytes: ByteArray) {
    var visibleStart by remember { mutableStateOf(0) }
    val totalRows = (bytes.size + BYTES_PER_ROW - 1) / BYTES_PER_ROW
    val visibleRows = 64 // number of rows to render at once

    Div({
        classes("hex-viewer")
        style {
            property("overflow-y", "auto")
            width(100.percent)
            property("flex", "1")
            property("font-family", "monospace")
            property("font-size", "13px")
            property("line-height", "1.5")
        }
    }) {
        val endRow = minOf(visibleStart + visibleRows, totalRows)
        for (row in visibleStart until endRow) {
            HexRow(bytes, row)
        }

        if (totalRows > visibleRows) {
            Div({
                style {
                    property("text-align", "center")
                    property("padding", "8px")
                    property("opacity", "0.5")
                }
            }) {
                Text("Showing rows $visibleStart–${endRow - 1} of $totalRows. Scroll to load more.")
            }
        }
    }
}

@Composable
private fun HexRow(bytes: ByteArray, row: Int) {
    val offset = row * BYTES_PER_ROW
    val end = minOf(offset + BYTES_PER_ROW, bytes.size)
    val rowBytes = bytes.sliceArray(offset until end)

    Div({
        classes("hex-row")
        style {
            display(DisplayStyle.Flex)
            property("gap", "12px")
        }
    }) {
        // Offset column
        Span({
            classes("hex-offset")
            style { color(Color("#6A9955")) }
        }) {
            Text(offset.toString(16).padStart(8, '0'))
        }

        // Hex bytes column
        Span({ classes("hex-bytes") }) {
            Text(rowBytes.joinToString(" ") { it.toUByte().toString(16).padStart(2, '0') })
            // Pad incomplete last row
            if (rowBytes.size < BYTES_PER_ROW) {
                Text("   ".repeat(BYTES_PER_ROW - rowBytes.size))
            }
        }

        // ASCII column
        Span({
            classes("hex-ascii")
            style { color(Color("#CE9178")) }
        }) {
            Text(rowBytes.map { b ->
                val c = b.toInt().and(0xFF).toChar()
                if (c in ' '..'~') c else '.'
            }.joinToString(""))
        }
    }
}
