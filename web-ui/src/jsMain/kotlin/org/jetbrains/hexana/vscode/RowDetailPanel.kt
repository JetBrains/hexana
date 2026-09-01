package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.shared.looksLikeText
import org.jetbrains.hexana.shared.rowDetailHexDump
import org.jetbrains.hexana.shared.rowDetailText

/** Width of the panel; enough for a 16-byte hex dump line without wrapping. */
private const val PANEL_WIDTH_PX = 340

/**
 * Side panel showing the bytes of the last clicked table row that are not in
 * the file itself — decrypted TLS plaintext, above all. The hex view can only
 * ever point at the ciphertext those bytes came from, so this is the only place
 * they can be read.
 */
@Composable
fun RowDetailPanel(selection: HexSelectionState) {
    val detail = selection.rowDetail

    Div({
        classes("hexana-row-detail")
        style {
            width(PANEL_WIDTH_PX.px)
            property("min-width", "${PANEL_WIDTH_PX}px")
            property("flex-shrink", "0")
            // The panel itself does not scroll; the body below does, so the
            // heading and the Text/Hex switch stay put while it moves.
            property("display", "flex")
            property("flex-direction", "column")
            property("overflow", "hidden")
            property("border-left", "1px solid var(--hexana-border, #333)")
            property("padding", "8px")
            property("font-family", "monospace")
            property("font-size", "11px")
            property("user-select", "text")
        }
    }) {
        Div({
            style {
                property("display", "flex")
                property("justify-content", "space-between")
                property("align-items", "center")
                property("font-size", "12px")
            }
        }) {
            Text(detail?.title ?: "Row Detail")
            Span({
                style { property("cursor", "pointer") }
                onClick { selection.showRowDetail = false }
            }) { Text("✕") }
        }

        if (detail == null) {
            Div({ style { property("opacity", "0.6"); property("margin-top", "8px") } }) {
                Text("No decrypted content for this row")
            }
            return@Div
        }

        detail.subtitle?.let {
            Div({ style { property("opacity", "0.6") } }) { Text(it) }
        }

        // Text first when the payload reads as text, which for a decrypted HTTP
        // exchange is the whole point; reset whenever a different row is clicked.
        var asText by remember(detail) { mutableStateOf(looksLikeText(detail.bytes)) }
        ModeSwitch(asText) { asText = it }

        val body = remember(detail, asText) {
            if (asText) rowDetailText(detail.bytes) else rowDetailHexDump(detail.bytes)
        }
        // A hex dump line is wider than the panel and a record runs to thousands
        // of bytes, so the body scrolls both ways with scrollbars of its own:
        // nothing else says the payload continues past the edge.
        Pre({
            classes("hexana-row-detail-body")
            style {
                property("margin", "8px 0 0 0")
                property("flex", "1 1 0")
                property("min-height", "0")
                property("overflow", "auto")
                property("white-space", "pre")
                property("font-family", "monospace")
                property("font-size", "11px")
            }
        }) { Text(body) }
    }
}

@Composable
private fun ModeSwitch(asText: Boolean, onToggle: (Boolean) -> Unit) {
    Div({ style { property("margin-top", "8px") } }) {
        ModeOption("Text", active = asText) { onToggle(true) }
        Span { Text(" / ") }
        ModeOption("Hex", active = !asText) { onToggle(false) }
    }
}

@Composable
private fun ModeOption(label: String, active: Boolean, onClick: () -> Unit) {
    Span({
        style {
            property("cursor", "pointer")
            property("font-weight", if (active) "bold" else "normal")
            property("opacity", if (active) "1" else "0.5")
        }
        onClick { onClick() }
    }) { Text(label) }
}
