package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.jewel.ui.component.Text

/** A `throw` site whose handler resolution was requested (code index + row byte offset). */
internal data class ThrowSiteRequest(val codeIdx: Int, val siteOffset: Long)

/**
 * Resolved payload for the exception-flow popup: the `throw`'s tag label plus
 * a one-line outcome ("caught by catch at @0x…" / "escapes this function").
 * [handlerOffset], when set, is a navigation target for the handling
 * `try_table` row.
 */
internal data class ThrowHandlerData(
    val header: String,
    val outcome: String,
    val handlerOffset: Long? = null,
    /** Interprocedural note (E2), e.g. "escapes the module at 2 exported entries: fetch, init". */
    val analysis: String? = null,
)

/**
 * Popup shown on a `throw` row: where the exception is handled within this
 * function, or that it escapes to callers (E1, intra-procedural). Same
 * affordance rules as the dispatch popup — hand cursor to open, DisableSelection
 * so the popup's text does not join the WAT SelectionContainer (a mouse
 * selection across hierarchies crashes the tab), never a Tooltip wrapper.
 */
@Composable
internal fun WasmThrowHandlerPopup(
    data: ThrowHandlerData,
    onGotoHandler: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    val textStyle = CHUNK_PANEL_ROW_TEXT_STYLE.copy(color = HexanaTheme.textColor)

    Popup(
        alignment = Alignment.TopCenter,
        focusable = true,
        onDismissRequest = onDismiss,
    ) {
        DisableSelection {
            Column(
                modifier = Modifier
                    .width(460.dp)
                    .background(HexanaTheme.backgroundColor)
                    .border(width = 1.dp, color = HexanaTheme.textColor, shape = RoundedCornerShape(4.dp))
                    .padding(12.dp)
                    .focusRequester(requester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
            ) {
                Text(text = data.header, style = textStyle)
                val handler = data.handlerOffset
                if (handler != null) {
                    Text(
                        text = "(; ${data.outcome} ;)",
                        style = textStyle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .clickable { onGotoHandler(handler) },
                    )
                } else {
                    Text(
                        text = "(; ${data.outcome} ;)",
                        style = textStyle,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                data.analysis?.let { analysis ->
                    Text(text = "(; $analysis ;)", style = textStyle, modifier = Modifier.padding(top = 4.dp))
                }
                Text(
                    text = if (handler != null) "click: go to handler  ·  Esc: close" else "Esc: close",
                    style = textStyle,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
