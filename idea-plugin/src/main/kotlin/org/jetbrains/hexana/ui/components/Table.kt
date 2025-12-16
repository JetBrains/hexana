package org.jetbrains.hexana.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            columns.forEach { column ->
                Box(modifier = Modifier.padding(horizontal = 4.dp).borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp).then(column.modifier)) {
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

        LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            items(dataCount) { rowIndex ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    columns.forEachIndexed { index, column ->
                        Box(modifier = Modifier.padding(horizontal = 4.dp).then(column.modifier)) {
                            Text(
                                column.dataProvider(rowIndex),
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