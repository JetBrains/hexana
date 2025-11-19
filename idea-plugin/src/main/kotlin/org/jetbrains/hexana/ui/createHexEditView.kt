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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import kotlin.math.min

@Composable
fun createHexEditView(buf: ByteArray) {
    val listState = rememberLazyListState()
    val backgroundColor = if (JewelTheme.isDark) Color.Black else Color.White
    val textColor = if (JewelTheme.isDark) Color.White else Color.Black
    val hexTextColor = if (JewelTheme.isDark) Color.Gray else Color.DarkGray

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(backgroundColor)) {
        val separator = 1.dp
        val half = (maxWidth - separator) / 2
        val bytesPerRow = 16
        val spacing = 8.dp
        val rowCount = (buf.size + bytesPerRow - 1) / bytesPerRow
        val offsetWidth = 96.dp

        Column(modifier = Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(offsetWidth)
                        .background(backgroundColor)
                        .padding(horizontal = 8.dp)
                ) {}
                Box(modifier = Modifier.width(half).background(backgroundColor).padding(horizontal = 8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(spacing)
                    ) {
                        for (i in 0 until bytesPerRow) {
                            Text(
                                i.asHexString(),
                                style = TextStyle(
                                    color = textColor,
                                    fontFamily = FontFamily.Monospace
                                )
                            )
                        }
                    }
                }
                Box(modifier = Modifier.width(separator).background(Color.Gray))
                Box(modifier = Modifier.width(half).background(backgroundColor).padding(horizontal = 8.dp)) {
                    Text(
                        "ASCII",
                        style = TextStyle(
                            color = textColor,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
            Box {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(count = rowCount) { rowIndex ->
                        Row(Modifier.fillMaxWidth()) {
                            Box(
                                modifier = Modifier
                                    .width(offsetWidth)
                                    .background(backgroundColor)
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.CenterEnd
                            ) {
                                Row {
                                     Text((rowIndex * bytesPerRow).asHexString(padStart = 8),
                                        style = TextStyle(fontFamily = FontFamily.Monospace)
                                     )
                                }
                            }

                            Box(modifier = Modifier.width(half).background(backgroundColor).padding(horizontal = 8.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(spacing),
                                ) {
                                    val start = rowIndex * bytesPerRow
                                    val end = min(start + bytesPerRow, buf.size)
                                    for (idx in start until end) {
                                        Text(
                                            (buf[idx].toInt() and 0xFF).asHexString(),
                                            style = TextStyle(
                                                color = hexTextColor,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                }
                            }
                            Box(modifier = Modifier.width(separator).background(Color.Gray))
                            Box(modifier = Modifier.width(half).background(backgroundColor).padding(horizontal = 8.dp)) {
                                Row(
                                    horizontalArrangement = Arrangement.Start
                                ) {
                                    val start = rowIndex * bytesPerRow
                                    val end = min(start + bytesPerRow, buf.size)
                                    for (idx in start until end) {
                                        val b = buf[idx].toInt() and 0xFF
                                        val ch = if (b in 32..126) b.toChar().toString() else "."
                                        Text(
                                            ch,
                                            style = TextStyle(
                                                color = textColor,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                VerticalScrollbar(
                    modifier = Modifier.align(alignment = Alignment.CenterEnd).fillMaxHeight().background(Color.Gray),
                    adapter = rememberScrollbarAdapter(scrollState = listState)
                )
            }
        }
    }
}

private fun Int.asHexString(padStart: Int = 2) = toString(16).padStart(padStart, '0').uppercase()