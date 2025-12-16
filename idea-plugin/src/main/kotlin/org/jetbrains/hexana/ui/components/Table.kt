package org.jetbrains.hexana.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.onClick
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.borderBottom
import org.jetbrains.jewel.ui.component.Text
import kotlin.math.floor
import kotlin.math.log10

class TableCellValue<T>(val value: T, val representation: String = value.toString())
class TableColumn(val title: String, val modifier: Modifier = Modifier, val dataProvider: (index: Int) -> TableCellValue<*>)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Table(columns: List<TableColumn>, dataCount: Int, onRow: (id: Int) -> Unit = {}) {

    var sortAsc: Boolean by remember { mutableStateOf(true) }
    var sortIndex: Int by remember { mutableStateOf(-1) }

    var selectedOriginalIndex: Int? by remember { mutableStateOf(null) }
    var hoveredOriginalIndex: Int? by remember { mutableStateOf(null) }

    val viewData = remember(columns, dataCount, sortIndex, sortAsc) {
        val base = (0 until dataCount).map { index ->
            index to columns.map { column -> column.dataProvider(index) }
        }

        if (sortIndex == -1) {
            if (sortAsc) {
                base.sortedBy { (id, _) -> id }
            } else {
                base.sortedByDescending { (id, _) -> id }
            }
        } else {
            if (sortAsc) {
                base.sortedWith { a, b ->
                    val valueA = a.second[sortIndex].value
                    val valueB = b.second[sortIndex].value
                    when {
                        valueA is Int && valueB is Int -> valueA.compareTo(valueB)
                        valueA is String && valueB is String -> valueA.compareTo(valueB)
                        else -> 0
                    }
                }
            } else {
                base.sortedWith { a, b ->
                    val valueA = a.second[sortIndex].value
                    val valueB = b.second[sortIndex].value
                    when {
                        valueA is Int && valueB is Int -> valueB.compareTo(valueA)
                        valueA is String && valueB is String -> valueB.compareTo(valueA)
                        else -> 0
                    }
                }
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val idColumnMinWidth = textMeasurer.measureWidthMonospace(dataCount, 4)


    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            val selectionColor = when {
                sortIndex == -1 -> HexanaTheme.textColor
                else -> Color(238, 237, 237, 80)
            }

            val sortSelectorText = when {
                sortIndex == -1 && sortAsc -> "▴"
                else -> "▾"
            }

            Box(modifier = Modifier
            .padding(horizontal = 4.dp)
            .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
            .widthIn(min = idColumnMinWidth)
                .onClick {
                    if (sortIndex == -1) {
                        sortAsc = !sortAsc
                    }

                    sortIndex = -1
                }
            ) {
                Row {
                    Text(
                        sortSelectorText,
                        modifier = Modifier.padding(end = 4.dp),
                        style = TextStyle(
                            color = selectionColor,
                            fontFamily = FontFamily.Monospace,
                        )
                    )

                    Text(
                        "ID",
                        style = TextStyle(
                            color = HexanaTheme.textColor,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            columns.forEachIndexed { columnIndex, column ->
                Box(modifier = Modifier
                .padding(horizontal = 4.dp)
                .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                .onClick {
                    if (sortIndex == columnIndex) {
                        sortAsc = !sortAsc
                    }
                    sortIndex = columnIndex
                }
                .then(column.modifier)) {
                    val selectionColor = when {
                        columnIndex == sortIndex -> HexanaTheme.textColor
                        else -> Color(238, 237, 237, 80)
                    }

                    val sortSelectorText = when {
                        columnIndex == sortIndex && sortAsc -> "▴"
                        else -> "▾"
                    }

                    Row {
                        Text(
                            sortSelectorText,
                            modifier = Modifier.padding(end = 4.dp),
                            style = TextStyle(
                                color = selectionColor,
                                fontFamily = FontFamily.Monospace,
                            )
                        )

                        Text(
                            column.title,
                            style = TextStyle(
                                color = HexanaTheme.textColor,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).onPointerEvent(PointerEventType.Exit) { hoveredOriginalIndex = null }) {
            items(dataCount) { rowIndex ->
                val (id, data) = viewData[rowIndex]

                val rowBackgroundColor = if (hoveredOriginalIndex == id) {
                    Color(201, 255, 255, 100).compositeOver(HexanaTheme.hexCellHoverActiveColor)
                } else if (selectedOriginalIndex == id) {
                    HexanaTheme.hexCellHoverActiveColor
                } else {
                    Color.Unspecified
                }

                Row(
                    modifier = Modifier
                    .fillMaxWidth()
                    .background(rowBackgroundColor)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .onPointerEvent(PointerEventType.Enter) {
                        hoveredOriginalIndex = id
                    }
                    .onClick {
                        selectedOriginalIndex = id
                        onRow(id)
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.padding(horizontal = 4.dp).widthIn(min = idColumnMinWidth)) {
                        Text(
                            id.toString(),
                            style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
                            lineHeight = TextUnit(1f, TextUnitType.Em)
                        )
                    }

                    columns.forEachIndexed { index, column ->
                        val text = data[index].representation
                        Box(modifier = Modifier.padding(horizontal = 4.dp).then(column.modifier)) {
                            Text(
                                text,
                                style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
                                lineHeight = TextUnit(1f, TextUnitType.Em)
                            )
                        }
                    }
                }
            }
        }
    }

}


private fun TextMeasurer.measureMonospaceInt(str: String): TextLayoutResult {
    return measure("x".repeat(str.length), style = TextStyle(fontFamily = FontFamily.Monospace))
}

private  fun TextMeasurer.measureMonospaceInt(num: Int, minLen: Int = 0): TextLayoutResult {
    val maxLen = maxOf(floor(log10(num.toDouble())).toInt() + 1, minLen)
    return measureMonospaceInt("x".repeat(maxLen))
}

@Composable
internal fun TextMeasurer.measureWidthMonospace(str: String): Dp {
    val localDensity = LocalDensity.current
    return with (localDensity) {
        measureMonospaceInt(str).size.width.toDp()
    }
}

@Composable
internal fun TextMeasurer.measureWidthMonospace(num: Int, minLen: Int = 0): Dp {
    val localDensity = LocalDensity.current
    return with (localDensity) {
        measureMonospaceInt(num, minLen).size.width.toDp()
    }
}