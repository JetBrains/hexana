package org.jetbrains.hexana.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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


class TableColumn(val title: String, val modifier: Modifier = Modifier, val dataProvider: (index: Int) -> String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Table(columns: List<TableColumn>, dataCount: Int) {

    var sortAsc: Boolean by remember { mutableStateOf(true) }
    var sortIndex: Int by remember { mutableStateOf(-1) }

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
                base.sortedBy { (_, data) -> data[sortIndex] }
            } else {
                base.sortedByDescending { (_, data) -> data[sortIndex] }
            }
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val widthMonospace = textMeasurer.measureWidthMonospace(dataCount, 2)


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
            .widthIn(min = widthMonospace)
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

        LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            items(dataCount) { rowIndex ->
                val (id, data) = viewData[rowIndex]

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.padding(horizontal = 4.dp).widthIn(min = widthMonospace)) {
                        Text(
                            id.toString(),
                            style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
                            lineHeight = TextUnit(1f, TextUnitType.Em)
                        )
                    }

                    columns.forEachIndexed { index, column ->
                        val text = data[index]
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