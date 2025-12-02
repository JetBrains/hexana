package org.jetbrains.hexana.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.jewel.ui.component.Text
import kotlin.math.min

@Composable
internal fun HexViewer(buf: ByteArray, state: HexViewStateHolder, showOffsets: Boolean = true) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
        val codePanelListState = rememberLazyListState()

        val bytesPerRow by remember { state.bytesPerRow }

        val separator = 4.dp
        val spacing = 8.dp

        val rowCount = (buf.size + bytesPerRow - 1) / bytesPerRow
        val offsetWidth = 96.dp

        LaunchedEffect(state) {
            state.hexScrollFlow.collect { offset ->
                val lineNo = (offset / bytesPerRow).toInt()
                codePanelListState.animateScrollToItem(lineNo)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (showOffsets) {
                    Box(
                        modifier = Modifier
                            .width(offsetWidth)
                            .background(HexanaTheme.backgroundColor)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(" ", style = TextStyle(fontFamily = FontFamily.Monospace))
                    }
                }
                Box(
                    modifier = Modifier.background(HexanaTheme.backgroundColor)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        Text(
                            (0 until bytesPerRow).joinToString(" ") { it.asHexString() },
                            softWrap = false,
                            maxLines = 1,
                            style = TextStyle(
                                color = HexanaTheme.textColor,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 4.dp).background(HexanaTheme.backgroundColor)
                        .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                ) {
                    Text(
                        "ASCII",
                        softWrap = false,
                        maxLines = 1,
                        style = TextStyle(
                            color = HexanaTheme.textColor,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
            Box {
                LazyColumn(state = codePanelListState, modifier = Modifier.fillMaxSize()) {
                    items(count = rowCount) { rowIndex ->
                        Row(Modifier.fillMaxWidth()) {
                            if (showOffsets) {
                                Box(
                                    modifier = Modifier
                                        .width(offsetWidth)
                                        .background(HexanaTheme.backgroundColor)
                                        .padding(horizontal = 8.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Row {
                                        Text(
                                            (rowIndex * bytesPerRow).asHexString(padStart = 8),
                                            style = TextStyle(fontFamily = FontFamily.Monospace)
                                        )
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier.background(HexanaTheme.backgroundColor)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(spacing),
                                ) {
                                    val start = rowIndex * bytesPerRow
                                    val end = min(start + bytesPerRow, buf.size)

                                    Text(
                                        softWrap = false,
                                        maxLines = 1,
                                        style = TextStyle(fontFamily = FontFamily.Monospace),
                                        text = buildAnnotatedString {
                                            buf.slice(start until end).forEachIndexed { byteIndex, byte ->
                                                val byteText = (byte.toInt() and 0xFF).asHexString()
                                                val style = if (
                                                    byteIndex == state.cursorPositionLineOffset.value
                                                    &&
                                                    rowIndex == state.cursorPositionLine.value
                                                ) {
                                                     SpanStyle(color = HexanaTheme.hexTextColor, background = Color.Yellow)
                                                 } else {
                                                     SpanStyle(color = HexanaTheme.hexTextColor)
                                                 }

                                                withStyle(
                                                    style = style
                                                ) {
                                                    append(byteText)
                                                }

                                                if (byteIndex < end - start - 1) append(" ")
                                            }
                                        })
                                }
                            }
                            Box(modifier = Modifier.width(separator).background(Color.Gray))
                            Box(
                                modifier = Modifier.background(HexanaTheme.backgroundColor)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    val start = rowIndex * bytesPerRow
                                    val end = min(start + bytesPerRow, buf.size)
                                    val str = buf.slice(start until end).asASCII()
                                    Text(
                                        str,
                                        softWrap = false,
                                        maxLines = 1,
                                        style = TextStyle(
                                            color = HexanaTheme.textColor,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Show scrollbar only when content overflows the viewport
                val showScrollbar by remember(codePanelListState) {
                    derivedStateOf {
                        val layout = codePanelListState.layoutInfo
                        val total = layout.totalItemsCount
                        if (total == 0) return@derivedStateOf false
                        val first = layout.visibleItemsInfo.firstOrNull()?.index ?: 0
                        val last = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                        // If not all items are visible, show scrollbar
                        !(first == 0 && last >= total - 1)
                    }
                }

                if (showScrollbar) {
                    VerticalScrollbar(
                        modifier = Modifier
                            .align(alignment = Alignment.CenterEnd)
                            .fillMaxHeight()
                            .background(Color.Gray),
                        adapter = rememberScrollbarAdapter(scrollState = codePanelListState)
                    )
                }
            }
        }
    }
}

private fun Int.asHexString(padStart: Int = 2) = toString(16).padStart(padStart, '0').uppercase()

internal fun ByteArray.asASCII(): String {
    return joinToString("") {
        val b = it.toInt() and 0xFF
        if (b in 32..126) b.toChar().toString() else "."
    }
}

private fun List<Byte>.asASCII(): String {
    return joinToString("") {
        val b = it.toInt() and 0xFF
        if (b in 32..126) b.toChar().toString() else "."
    }
}

private fun Modifier.borderBottom(color: Color, width: Dp, verticalPadding: Dp): Modifier = this.drawBehind {
    val strokeWidthPx = width.toPx()
    val y = size.height + verticalPadding.toPx() - strokeWidthPx / 2

    drawLine(
        color = color,
        start = Offset(0f, y),
        end = Offset(size.width, y),
        strokeWidth = strokeWidthPx
    )
}