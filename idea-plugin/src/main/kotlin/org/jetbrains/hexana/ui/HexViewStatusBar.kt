package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import java.awt.Cursor

@Composable
internal fun HexViewStatusBar(
    state: HexViewStateHolder,
    totalBytes: Long,
) {
    val bytesPerRow by state.bytesPerRow
    val cursor = state.cursorPosition
    val selection = state.selection

    val hasCursor = cursor.line >= 0 && cursor.lineOffset >= 0
    val cursorOffset = if (hasCursor) cursor.getByteOffset(bytesPerRow) + state.displayBaseOffset else -1L

    val hasSelection = selection.start.line >= 0 && selection.end.line >= 0 &&
            (selection.start.line != selection.end.line || selection.start.lineOffset != selection.end.lineOffset)

    Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal, thickness = 2.dp, modifier = Modifier.fillMaxWidth())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(HexanaTheme.backgroundColor)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val textStyle = TextStyle(
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = HexanaTheme.hexTextColor,
        )

        if (hasCursor) {
            Text(
                text = "Offset: 0x${cursorOffset.toString(16).padStart(8, '0')}",
                style = textStyle,
            )
            Text(
                text = "Byte: $cursorOffset / $totalBytes",
                style = textStyle,
            )
        }

        if (hasSelection) {
            val selStart = selection.start.getByteOffset(bytesPerRow) + state.displayBaseOffset
            val selEnd = selection.end.getByteOffset(bytesPerRow) + state.displayBaseOffset
            val count = selEnd - selStart + 1
            Text(
                text = "Selected: $count byte${if (count != 1L) "s" else ""}",
                style = textStyle,
            )
            Text(
                text = "Range: 0x${selStart.toString(16)} \u2013 0x${selEnd.toString(16)}",
                style = textStyle,
            )
        }

        if (!hasCursor) {
            Text(
                text = "Click a byte to begin",
                style = textStyle,
            )
        }

        Spacer(Modifier.weight(1f))

        val inspectorLabel = if (state.showDataInspector) "Hide Inspector" else "Data Inspector"
        Text(
            text = inspectorLabel,
            style = textStyle,
            modifier = Modifier
                .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)))
                .clickable { state.setShowDataInspector(!state.showDataInspector) },
        )
    }
}
