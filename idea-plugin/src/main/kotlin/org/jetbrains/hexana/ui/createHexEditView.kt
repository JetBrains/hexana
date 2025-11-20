package org.jetbrains.hexana.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import java.awt.Cursor
import kotlin.math.min

@Composable
fun createHexEditView(buf: ByteArray) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var topFraction by remember { mutableFloatStateOf(0.6f) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(HexanaTheme.backgroundColor)) {
        val totalHeightPx = with(density) { maxHeight.toPx() }
        val maxW = maxWidth

        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxWidth().weight(topFraction)) {
                val separator = 1.dp
                val half = (maxW - separator) / 2
                val bytesPerRow = 16
                val spacing = 8.dp
                val rowCount = (buf.size + bytesPerRow - 1) / bytesPerRow
                val offsetWidth = 96.dp

                Column(modifier = Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .width(offsetWidth)
                                .background(HexanaTheme.backgroundColor)
                                .padding(horizontal = 8.dp)
                        ) {}
                        Box(modifier = Modifier.width(half).background(HexanaTheme.backgroundColor).padding(horizontal = 8.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(spacing)
                            ) {
                                for (i in 0 until bytesPerRow) {
                                    Text(
                                        i.asHexString(),
                                        style = TextStyle(
                                            color = HexanaTheme.textColor,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    )
                                }
                            }
                        }
                        Box(modifier = Modifier.width(separator).background(Color.Gray))
                        Box(modifier = Modifier.width(half).background(HexanaTheme.backgroundColor).padding(horizontal = 8.dp)) {
                            Text(
                                "ASCII",
                                style = TextStyle(
                                    color = HexanaTheme.textColor,
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

                                    Box(
                                        modifier = Modifier.width(half).background(HexanaTheme.backgroundColor)
                                            .padding(horizontal = 8.dp)
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(spacing),
                                        ) {
                                            val start = rowIndex * bytesPerRow
                                            val end = min(start + bytesPerRow, buf.size)
                                            for (idx in start until end) {
                                                Text(
                                                    (buf[idx].toInt() and 0xFF).asHexString(),
                                                    style = TextStyle(
                                                        color = HexanaTheme.hexTextColor,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                )
                                            }
                                        }
                                    }
                                    Box(modifier = Modifier.width(separator).background(Color.Gray))
                                    Box(
                                        modifier = Modifier.width(half).background(HexanaTheme.backgroundColor)
                                            .padding(horizontal = 8.dp)
                                    ) {
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
                                                        color = HexanaTheme.textColor,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Show scrollbar only when content overflows the viewport
                        val showScrollbar by remember(listState) {
                            derivedStateOf {
                                val layout = listState.layoutInfo
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
                                adapter = rememberScrollbarAdapter(scrollState = listState)
                            )
                        }
                    }
                }
            }

            val tabs = remember { mutableStateListOf<Tab>() }

            if (tabs.isNotEmpty()) {
                HorizontalDraggableDivider(
                    onDrag = { deltaY ->
                        if (totalHeightPx > 0) {
                            val deltaRatio = deltaY / totalHeightPx
                            topFraction = (topFraction + deltaRatio).coerceIn(0.1f, 0.9f)
                        }
                    }
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f - topFraction)
                        .background(HexanaTheme.backgroundColor),
                    contentAlignment = Alignment.TopStart
                ) {
                    TabsPanel(tabs)
                }
            }
        }
    }
}

@Composable
private fun TabsPanel(tabs: List<Tab>) {
    var selectedTab by remember { mutableStateOf<Tab>(Tab.Imports) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().background(HexanaTheme.backgroundColor)) {
            tabs.forEach { tab ->
                val selected = tab == selectedTab
                val fg = if (selected) HexanaTheme.textColor else HexanaTheme.textColor.copy(alpha = 0.8f)
                val underline = if (selected) HexanaTheme.textColor else Color.Transparent

                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Min)
                        .padding(horizontal = 8.dp)
                        .clickable { selectedTab = tab }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(tab.title, style = TextStyle(color = fg))
                    Spacer(Modifier.height(4.dp))
                    Divider(
                        orientation = org.jetbrains.jewel.ui.Orientation.Horizontal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(underline)
                    )
                }
            }
        }

        Divider(orientation = org.jetbrains.jewel.ui.Orientation.Horizontal)

        // Tab content
        Box(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            when (selectedTab) {
                Tab.Imports -> Text("Preview content", style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace))
                Tab.Exports -> Text("Inspector content", style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace))
            }
        }
    }

}


@Composable
private fun HorizontalDraggableDivider(onDrag: (deltaY: Float) -> Unit) {
    val dragHitAreaThickness = 8.dp

    Divider(
        orientation = org.jetbrains.jewel.ui.Orientation.Horizontal,
        modifier = Modifier
            .fillMaxWidth().height(dragHitAreaThickness / 2).background(Color.Gray)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(dragHitAreaThickness)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .background(Color.Transparent)
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    onDrag(delta)
                },
                startDragImmediately = true
            )

    )
}

private fun Int.asHexString(padStart: Int = 2) = toString(16).padStart(padStart, '0').uppercase()

private sealed interface Tab {
    val title: String
    data object Imports : Tab { override val title = "Imports" }
    data object Exports : Tab { override val title = "Exports" }
}