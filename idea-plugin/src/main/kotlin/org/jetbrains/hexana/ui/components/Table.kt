package org.jetbrains.hexana.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import kotlinx.coroutines.launch
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.borderBottom
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import kotlin.math.floor
import kotlin.math.log10

class TableCellValue<T>(val value: T, val representation: String = value.toString(), val tooltip: String? = null)
class TableColumn(
    val title: String,
    val modifier: Modifier = Modifier,
    val dataProvider: (index: Int) -> TableCellValue<*>
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Table(
    columns: List<TableColumn>,
    dataCount: Int,
    onRowContextMenu: (id: Int) -> List<ContextMenuItem> = { emptyList() },
    onRow: (id: Int) -> Unit = {},
) {

    var sortAsc: Boolean by remember { mutableStateOf(true) }
    var sortIndex: Int by remember { mutableStateOf(-1) }

    var selectedOriginalIndex: Int? by remember { mutableStateOf(null) }
    var hoveredOriginalIndex: Int? by remember { mutableStateOf(null) }

    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    val searchFocusRequester = remember { FocusRequester() }

    val lazyListState = rememberLazyListState()

    LaunchedEffect(searchQuery.text) {
        selectedOriginalIndex = null
        lazyListState.scrollToItem(0)
    }

    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val viewData = remember(columns, dataCount, sortIndex, sortAsc, searchQuery) {
        val base = (0 until dataCount).map { index ->
            index to columns.map { column -> column.dataProvider(index) }
        }

        val filtered = if (searchQuery.text.isEmpty()) {
            base
        } else {
            base.filter { (_, values) ->
                values.any { it.representation.contains(searchQuery.text, ignoreCase = true) }
            }
        }

        if (sortIndex == -1) {
            if (sortAsc) {
                filtered.sortedBy { (id, _) -> id }
            } else {
                filtered.sortedByDescending { (id, _) -> id }
            }
        } else {
            if (sortAsc) {
                filtered.sortedWith { a, b ->
                    val valueA = a.second[sortIndex].value
                    val valueB = b.second[sortIndex].value
                    when {
                        valueA is Int && valueB is Int -> valueA.compareTo(valueB)
                        valueA is String && valueB is String -> valueA.compareTo(valueB)
                        else -> 0
                    }
                }
            } else {
                filtered.sortedWith { a, b ->
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


    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                if (event.key == Key.F && (event.isCtrlPressed || event.isMetaPressed)) {
                    searchFocusRequester.requestFocus()
                    return@onPreviewKeyEvent true
                }

                val currentIndex = viewData.indexOfFirst { it.first == selectedOriginalIndex }
                val newIndex = when (event.key) {
                    Key.DirectionUp -> (currentIndex - 1).coerceAtLeast(0)
                    Key.DirectionDown -> {
                        if (currentIndex == -1) 0
                        else (currentIndex + 1).coerceAtMost(viewData.size - 1)
                    }
                    else -> null
                }

                if (newIndex != null && newIndex != currentIndex && viewData.isNotEmpty()) {
                    val (newId, _) = viewData[newIndex]
                    selectedOriginalIndex = newId
                    onRow(newId)
                    scope.launch {
                        lazyListState.animateScrollToItem(newIndex)
                    }
                    return@onPreviewKeyEvent true
                }
            }
            false
        }
        .focusRequester(focusRequester)
        .focusable()
        .clickable(interactionSource = null, indication = null) {
            focusRequester.requestFocus()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(searchFocusRequester),
                placeholder = { Text("Search...") },
                leadingIcon = {
                    Icon(
                        AllIconsKeys.Actions.Search,
                        contentDescription = null,
                        iconClass = AllIcons::class.java,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailingIcon = if (searchQuery.text.isNotEmpty()) {
                    {
                        IconButton(onClick = { searchQuery = TextFieldValue("") }) {
                            Icon(
                                AllIconsKeys.Actions.Close,
                                contentDescription = "Clear search",
                                iconClass = AllIcons::class.java,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                } else null
            )
        }

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

            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                    .widthIn(min = idColumnMinWidth)
                    .clickable {
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
                Box(
                    modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                    .clickable {
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

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .onPointerEvent(PointerEventType.Exit) { hoveredOriginalIndex = null }) {
                items(viewData.size) { rowIndex ->
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
                            .clickable {
                                selectedOriginalIndex = id
                                onRow(id)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ContextMenuArea(items = { onRowContextMenu(id) }) {
                            Box(modifier = Modifier.padding(horizontal = 4.dp).widthIn(min = idColumnMinWidth)) {
                                Text(
                                    id.toString(),
                                    style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
                                    lineHeight = TextUnit(1f, TextUnitType.Em)
                                )
                            }
                        }

                        columns.forEachIndexed { index, column ->
                            val tableCellValue = data[index]
                            val text = tableCellValue.representation
                            val tooltip = tableCellValue.tooltip

                            ContextMenuArea(items = { onRowContextMenu(id) }) {
                                Box(modifier = Modifier.padding(horizontal = 4.dp).then(column.modifier)) {
                                    val textComponent = @Composable {
                                        Text(
                                            text,
                                            style = TextStyle(
                                                color = HexanaTheme.textColor,
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            lineHeight = TextUnit(1f, TextUnitType.Em),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    if (tooltip != null) {
                                        Tooltip(tooltip = { Text(tooltip) }) {
                                            textComponent()
                                        }
                                    } else {
                                        textComponent()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            VerticalScrollbar(lazyListState, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }

}


private fun TextMeasurer.measureMonospaceInt(str: String): TextLayoutResult {
    return measure("x".repeat(str.length), style = TextStyle(fontFamily = FontFamily.Monospace))
}

private fun TextMeasurer.measureMonospaceInt(num: Int, minLen: Int = 0): TextLayoutResult {
    val maxLen = maxOf(floor(log10(num.toDouble())).toInt() + 1, minLen)
    return measureMonospaceInt("x".repeat(maxLen))
}

@Composable
internal fun TextMeasurer.measureWidthMonospace(str: String): Dp {
    val localDensity = LocalDensity.current
    return with(localDensity) {
        measureMonospaceInt(str).size.width.toDp()
    }
}

@Composable
internal fun TextMeasurer.measureWidthMonospace(num: Int, minLen: Int = 0): Dp {
    val localDensity = LocalDensity.current
    return with(localDensity) {
        measureMonospaceInt(num, minLen).size.width.toDp()
    }
}