package org.jetbrains.hexana.vscode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.css.px
import org.jetbrains.compose.web.css.width
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.hexana.shared.looksLikeText
import org.jetbrains.hexana.shared.rowDetailClipboardContent
import org.jetbrains.hexana.shared.rowDetailHexDump
import org.jetbrains.hexana.shared.rowDetailText
import org.w3c.dom.HTMLTextAreaElement

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
        Div({
            style {
                property("display", "flex")
                property("justify-content", "space-between")
                property("align-items", "center")
                property("margin-top", "8px")
            }
        }) {
            ModeSwitch(asText) { asText = it }
            // Selecting the body by hand is possible but awkward - it scrolls
            // both ways and a record runs to thousands of bytes - so the whole
            // payload is one click away, in whichever mode is showing.
            CopyControl { rowDetailClipboardContent(detail.bytes, asText) }
        }

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

/**
 * Copies what [content] returns, then says how it went for a moment: a
 * clipboard write is invisible, and in a webview it can also be refused, so
 * silence would leave a failed copy indistinguishable from a clean one.
 */
@Composable
private fun CopyControl(content: () -> String) {
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status) {
        if (status == null) return@LaunchedEffect
        delay(1500)
        status = null
    }
    Span({
        style {
            property("cursor", "pointer")
            property("opacity", if (status == null) "0.8" else "1")
        }
        onClick { copyToClipboard(content()) { ok -> status = if (ok) "Copied" else "Copy failed" } }
    }) { Text(status ?: "Copy") }
}

/**
 * Writes [text] to the clipboard, reporting to [onDone] whether it landed. The
 * async Clipboard API is the intended path, but it is gated on permission and
 * on the document being focused, neither of which is guaranteed inside a VS
 * Code webview - so a refusal falls back to the selection trick, which is not.
 */
internal fun copyToClipboard(text: String, onDone: (Boolean) -> Unit) {
    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard == null || clipboard == undefined) {
        onDone(copyViaSelection(text))
        return
    }
    clipboard.writeText(text).then({ onDone(true) }, { onDone(copyViaSelection(text)) })
}

/**
 * The pre-Clipboard-API copy: put the text in an offscreen textarea, select it,
 * and let the browser copy the selection. Deprecated but universally
 * implemented, which is exactly what a fallback needs to be.
 */
private fun copyViaSelection(text: String): Boolean {
    val area = document.createElement("textarea") as HTMLTextAreaElement
    area.value = text
    // Offscreen rather than hidden: a display:none textarea cannot be selected.
    area.style.position = "fixed"
    area.style.left = "-9999px"
    document.body?.appendChild(area) ?: return false
    return try {
        area.select()
        document.asDynamic().execCommand("copy") as Boolean
    } catch (_: Throwable) {
        false
    } finally {
        area.remove()
    }
}

@Composable
private fun ModeSwitch(asText: Boolean, onToggle: (Boolean) -> Unit) {
    // Spacing lives on the row that holds this and the Copy control, so the two
    // sit on the same baseline.
    Div {
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
