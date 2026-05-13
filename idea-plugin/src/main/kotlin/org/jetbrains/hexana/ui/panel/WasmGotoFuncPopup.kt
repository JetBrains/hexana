package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.ui.component.Text

/**
 * Compose-native goto-function picker. Floats over the WAT panel as a
 * [Popup]; a single [BasicTextField] inside accepts either a raw funcidx
 * (decimal or `0x…` hex), the `$func<N>` placeholder the renderer uses
 * when no real name is available, or a free-form name fragment matched
 * (case-insensitive `contains`) against [funcNamesIndex].
 *
 * @param funcNamesIndex Lowercased-name → funcidx pairs for non-imported
 *   functions. Imports are excluded since they have no body to jump to.
 * @param onResolved Invoked with the resolved funcidx when the user
 *   presses Enter on parseable input. The popup also closes itself.
 * @param onDismiss Called on Esc or unparseable Enter — the panel hides
 *   the popup.
 */
@Composable
internal fun WasmGotoFuncPopup(
    funcNamesIndex: Map<String, Int>,
    onResolved: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var lastResolved by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }

    Popup(
        alignment = Alignment.TopCenter,
        focusable = true,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .background(HexanaTheme.backgroundColor)
                .border(width = 1.dp, color = HexanaTheme.textColor, shape = RoundedCornerShape(4.dp))
                .padding(12.dp),
        ) {
            Text(text = "Go to function", style = ROW_TEXT_STYLE.copy(color = HexanaTheme.textColor))
            BasicTextField(
                value = query,
                onValueChange = { query = it; error = null },
                singleLine = true,
                cursorBrush = SolidColor(HexanaTheme.textColor),
                textStyle = ROW_TEXT_STYLE.copy(color = HexanaTheme.textColor),
                modifier = Modifier
                    .focusRequester(requester)
                    .padding(top = 8.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                val resolved = parseFuncQuery(query, funcNamesIndex)
                                if (resolved != null) {
                                    lastResolved = resolved
                                    onResolved(resolved)
                                } else {
                                    error = "no match for \"$query\""
                                }
                                true
                            }
                            else -> false
                        }
                    },
            )
            val msg = error
            if (msg != null) {
                Text(
                    text = "(; $msg ;)",
                    style = ROW_TEXT_STYLE.copy(color = HexanaTheme.textColor),
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text(
                    text = "Enter: jump  ·  Esc: cancel",
                    style = ROW_TEXT_STYLE.copy(color = HexanaTheme.textColor),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            @Suppress("UNUSED_VARIABLE") lastResolved
        }
    }
}

/**
 * Resolve [query] to a global funcidx. Recognised forms:
 *
 *  - Decimal: `1234` → 1234
 *  - Hex:     `0x4d2` → 1234
 *  - Renderer placeholder: `func$1234`, `$func$1234`, `$1234` → 1234
 *  - Name (case-insensitive `contains` match): `kotlin_alloc` → first hit
 *
 * Returns `null` when nothing parses or no name contains the query.
 */
internal fun parseFuncQuery(query: String, funcNamesIndex: Map<String, Int>): Int? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null

    val stripped = trimmed
        .removePrefix("$")
        .removePrefix("func$")
        .removePrefix("func")
        .removePrefix("$")
    parseNumeric(stripped)?.let { return it }
    parseNumeric(trimmed)?.let { return it }

    val needle = trimmed.lowercase()
    funcNamesIndex[needle]?.let { return it }
    return funcNamesIndex.entries.firstOrNull { needle in it.key }?.value
}

private fun parseNumeric(s: String): Int? {
    val raw = s.trim()
    if (raw.isEmpty()) return null
    val long = when {
        raw.startsWith("0x") || raw.startsWith("0X") -> raw.substring(2).toLongOrNull(radix = 16)
        else -> raw.toLongOrNull()
    } ?: return null
    return long.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
}
