package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** One dispatch candidate row: display label + whether it can be jumped to. */
internal data class IndirectCandidateEntry(
    val funcIdx: Int,
    val label: String,
    /** Imports have no code-section body chunk to navigate to. */
    val navigable: Boolean,
)

/** Resolved popup payload for one `call_indirect` site's type immediate. */
internal data class IndirectCandidatesData(
    val signatureText: String,
    val entries: List<IndirectCandidateEntry>,
)

/**
 * Candidates picker for a `call_indirect` / `return_call_indirect` row.
 * Lists the statically known dispatch candidates (element-segment entries
 * whose canonical signature matches the call site's type immediate) in a
 * bounded, virtualized list; clicking a defined function navigates to its
 * declaration like the `call N` follow shortcut. Static under-approximation
 * by design: runtime `table.set` writes are invisible, hence "statically
 * known" in the header.
 */
@Composable
internal fun WasmIndirectCandidatesPopup(
    data: IndirectCandidatesData,
    onSelect: (Int) -> Unit,
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
            Text(
                text = "call_indirect ${data.signatureText}: ${data.entries.size} statically known candidates",
                style = textStyle,
            )
            if (data.entries.isEmpty()) {
                Text(
                    text = "(; no table entries match — this call site can only trap ;)",
                    style = textStyle,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .padding(top = 8.dp),
                ) {
                    items(count = data.entries.size, key = { data.entries[it].funcIdx }) { i ->
                        val entry = data.entries[i]
                        val suffix = if (entry.navigable) "" else "  (import)"
                        Text(
                            text = "func[${entry.funcIdx}]  ${entry.label}$suffix",
                            style = textStyle,
                            modifier = if (entry.navigable) {
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(entry.funcIdx) }
                            } else {
                                Modifier.fillMaxWidth()
                            },
                        )
                    }
                }
            }
            Text(
                text = "click: jump  ·  Esc: close",
                style = textStyle,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
