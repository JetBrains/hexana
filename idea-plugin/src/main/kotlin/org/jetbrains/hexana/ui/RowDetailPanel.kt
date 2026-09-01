package org.jetbrains.hexana.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.shared.looksLikeText
import org.jetbrains.hexana.shared.rowDetailHexDump
import org.jetbrains.hexana.shared.rowDetailText
import org.jetbrains.jewel.ui.component.Text
import java.awt.Cursor

/** Width of the panel; enough for a 16-byte hex dump line without wrapping. */
private val PANEL_WIDTH = 340.dp

/**
 * A side panel showing the bytes of the last clicked table row that are not in
 * the file itself — decrypted TLS plaintext, above all. The hex view can only
 * ever point at the ciphertext those bytes came from, so this is the only place
 * they can be read.
 */
@Composable
internal fun RowDetailPanel(state: HexViewStateHolder) {
    val detail = state.rowDetail

    Column(
        modifier = Modifier
            .width(PANEL_WIDTH)
            .fillMaxHeight()
            .background(HexanaTheme.backgroundColor)
            .padding(8.dp),
    ) {
        val headerStyle = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HexanaTheme.hexTextColor,
        )
        val labelStyle = headerStyle.copy(color = HexanaTheme.hexTextColor.copy(alpha = 0.6f))

        RowDetailHeader(detail?.title ?: "Row Detail", headerStyle) { state.setShowRowDetail(false) }

        if (detail == null) {
            Spacer(Modifier.height(8.dp))
            Text("No decrypted content for this row", style = labelStyle)
            return@Column
        }

        detail.subtitle?.let {
            Text(it, style = labelStyle)
        }
        Spacer(Modifier.height(8.dp))

        // Text first when the payload reads as text, which for a decrypted HTTP
        // exchange is the whole point; remembered per payload so switching to
        // Hex survives a scroll but not a jump to a different row.
        var asText by remember(detail) { mutableStateOf(looksLikeText(detail.bytes)) }
        ModeSwitch(asText, headerStyle) { asText = it }
        Spacer(Modifier.height(8.dp))

        val body = remember(detail, asText) {
            if (asText) rowDetailText(detail.bytes) else rowDetailHexDump(detail.bytes)
        }
        // A hex dump line is wider than the panel and a record runs to
        // thousands of bytes, so the body scrolls both ways, with visible
        // scrollbars: nothing else says the payload continues past the edge.
        val verticalScroll = rememberScrollState()
        val horizontalScroll = rememberScrollState()
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            SelectionContainer {
                Text(
                    body,
                    style = headerStyle,
                    modifier = Modifier
                        .fillMaxSize()
                        // Keeps the text clear of the scrollbars, which overlay
                        // the edges of the viewport rather than take space.
                        .padding(end = SCROLLBAR_GUTTER, bottom = SCROLLBAR_GUTTER)
                        .verticalScroll(verticalScroll)
                        .horizontalScroll(horizontalScroll),
                )
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(verticalScroll),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = scrollbarStyle(),
            )
            HorizontalScrollbar(
                adapter = rememberScrollbarAdapter(horizontalScroll),
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                style = scrollbarStyle(),
            )
        }
    }
}

/** Room reserved at the viewport edges for the overlaying scrollbars. */
private val SCROLLBAR_GUTTER = 10.dp

@Composable
private fun scrollbarStyle(): ScrollbarStyle {
    val color = HexanaTheme.hexTextColor
    return ScrollbarStyle(
        minimalHeight = 16.dp,
        thickness = 8.dp,
        shape = RoundedCornerShape(4.dp),
        hoverDurationMillis = 300,
        unhoverColor = color.copy(alpha = 0.50f),
        hoverColor = color.copy(alpha = 0.80f),
    )
}

@Composable
private fun RowDetailHeader(title: String, style: TextStyle, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = style.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold))
        Text(
            "✕",
            style = style,
            modifier = handCursor().then(Modifier.clickable { onClose() }),
        )
    }
}

@Composable
private fun ModeSwitch(asText: Boolean, style: TextStyle, onToggle: (Boolean) -> Unit) {
    val activeStyle = style.copy(fontWeight = FontWeight.Bold)
    val inactiveStyle = style.copy(color = style.color.copy(alpha = 0.5f))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            "Text",
            style = if (asText) activeStyle else inactiveStyle,
            modifier = handCursor().then(Modifier.clickable { onToggle(true) }),
        )
        Text("/", style = style)
        Text(
            "Hex",
            style = if (!asText) activeStyle else inactiveStyle,
            modifier = handCursor().then(Modifier.clickable { onToggle(false) }),
        )
    }
}

private fun handCursor(): Modifier =
    Modifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
