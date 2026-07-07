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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import kotlinx.coroutines.launch
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.borderBottom
import org.jetbrains.jewel.ui.component.*
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.awt.datatransfer.StringSelection
import kotlin.math.floor
import kotlin.math.log10

class TableCellValue<T>(val value: T, val representation: String = value.toString(), val tooltip: String? = null)
class TableColumn(
    val title: String,
    val modifier: Modifier = Modifier,
    val width: Dp? = null,
    val weight: Float? = null,
    val dataProvider: (index: Int) -> TableCellValue<*>
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun Table(
    columns: List<TableColumn>,
    dataCount: Int,
    onRowContextMenu: (id: Int) -> List<ContextMenuItem> = { emptyList() },
    actionColumn: (@Composable (id: Int) -> Unit)? = null,
    actionColumnWidth: Dp = 0.dp,
    defaultSortColumn: Int = -1,
    defaultSortAscending: Boolean = true,
    showIdColumn: Boolean = true,
    onSortChanged: ((columnIndex: Int, ascending: Boolean) -> Unit)? = null,
    onRowSelect: ((id: Int) -> Unit)? = null,
    rowColor: (id: Int) -> Color? = { null },
    onRow: (id: Int) -> Unit = {},
    alwaysShowSearch: Boolean = false,
    searchPlaceholder: String? = null,
    searchBarLeading: (@Composable () -> Unit)? = null,
    onTableFocusChanged: ((Boolean) -> Unit)? = null,
    onSearchChanged: ((String) -> Unit)? = null,
) {
    // Cache cell values lazily per row to avoid computing all cells upfront.
    // Only rows that are actually displayed, filtered, or sorted will be evaluated.
    val cellCache = remember(columns, dataCount) {
        Array<Array<TableCellValue<*>>?>(dataCount) { null }
    }
    val getCellValues = remember(columns, dataCount) {
        { index: Int ->
            cellCache[index] ?: columns.map { column -> column.dataProvider(index) }
                .toTypedArray()
                .also { cellCache[index] = it }
        }
    }

    var sortAsc: Boolean by remember(defaultSortAscending) { mutableStateOf(defaultSortAscending) }
    var sortIndex: Int by remember(defaultSortColumn) { mutableStateOf(defaultSortColumn) }

    var selectedOriginalIndex: Int? by remember { mutableStateOf(null) }
    var hoveredOriginalIndex: Int? by remember { mutableStateOf(null) }

    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var isSearchVisible by remember { mutableStateOf(alwaysShowSearch) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
        }
    }

    val lazyListState = rememberLazyListState()

    LaunchedEffect(searchQuery.text) {
        selectedOriginalIndex = null
        lazyListState.scrollToItem(0)
        onSearchChanged?.invoke(searchQuery.text)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    val scope = rememberCoroutineScope()

    val viewData = remember(columns, dataCount, sortIndex, sortAsc, searchQuery) {
        val indices = (0 until dataCount).toMutableList()

        // Filter — when onSearchChanged is provided, the caller handles filtering externally
        // (e.g. tree tables filter in flattenTree to include matching nested nodes with parents).
        if (onSearchChanged != null) {
            // no-op: external filter
        } else if (searchQuery.text.isNotEmpty()) {
            indices.retainAll { index ->
                getCellValues(index).any { it.representation.contains(searchQuery.text, ignoreCase = true) }
            }
        }

        // Sort — when onSortChanged is provided, the caller handles sorting externally
        // (e.g. tree tables sort in flattenTree to preserve parent-child structure).
        if (onSortChanged != null) {
            // no-op: external sort
        } else if (sortIndex == -1) {
            if (!sortAsc) indices.reverse()
        } else {
            val comparator = Comparator<Int> { a, b ->
                val valueA = getCellValues(a)[sortIndex].value
                val valueB = getCellValues(b)[sortIndex].value
                when {
                    valueA is Int && valueB is Int -> valueA.compareTo(valueB)
                    valueA is Long && valueB is Long -> valueA.compareTo(valueB)
                    valueA is String && valueB is String -> valueA.compareTo(valueB)
                    else -> 0
                }
            }
            indices.sortWith(if (sortAsc) comparator else comparator.reversed())
        }

        indices
    }

    val textMeasurer = rememberTextMeasurer()
    val idColumnWidth = if (showIdColumn) maxOf(
        textMeasurer.measureWidthMonospace(dataCount, 4),
        textMeasurer.measureWidthMonospace("ID ▾"),
    ) else 0.dp


    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                if (event.key == Key.C && (event.isCtrlPressed || event.isMetaPressed)) {
                    selectedOriginalIndex?.let { id ->
                        val rowData = selectedOriginalIndex?.let { getCellValues(it) }
                        if (rowData != null) {
                            val rowContent = buildString {
                                append(id)
                                rowData.forEach {
                                    append("\t")
                                    append(it.representation)
                                }
                            }
                            CopyPasteManager.getInstance().setContents(StringSelection(rowContent))
                        }
                    }
                    return@onPreviewKeyEvent true
                }

                if (event.key == Key.F && (event.isCtrlPressed || event.isMetaPressed)) {
                    isSearchVisible = true
                    return@onPreviewKeyEvent true
                }

                if (event.key == Key.Escape) {
                    if (isSearchVisible && !alwaysShowSearch) {
                        isSearchVisible = false
                        searchQuery = TextFieldValue("")
                        focusRequester.requestFocus()
                        return@onPreviewKeyEvent true
                    } else if (alwaysShowSearch && searchQuery.text.isNotEmpty()) {
                        searchQuery = TextFieldValue("")
                        focusRequester.requestFocus()
                        return@onPreviewKeyEvent true
                    }
                }

                if (event.key == Key.Enter) {
                    if (isSearchVisible) {
                        focusRequester.requestFocus()
                        return@onPreviewKeyEvent true
                    }
                }

                val currentIndex = viewData.indexOf(selectedOriginalIndex ?: -1)
                val newIndex = when (event.key) {
                    Key.DirectionUp -> {
                        (currentIndex - 1).coerceAtLeast(0)
                    }
                    Key.DirectionDown -> {
                        if (currentIndex == -1) 0
                        else (currentIndex + 1).coerceAtMost(viewData.size - 1)
                    }
                    else -> null
                }

                if (newIndex != null && newIndex != currentIndex && viewData.isNotEmpty()) {
                    val newId = viewData[newIndex]
                    selectedOriginalIndex = newId
                    hoveredOriginalIndex = null
                    if (onRowSelect != null) {
                        onRowSelect(newId)
                    } else {
                        onRow(newId)
                    }
                    scope.launch {
                        lazyListState.animateScrollToItem(newIndex)
                    }
                }

                if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                    selectedOriginalIndex?.let { id ->
                        onRow(id)
                    }
                }

                if (event.key == Key.DirectionUp || event.key == Key.DirectionDown
                    || event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                    return@onPreviewKeyEvent true
                }

                if (!isSearchVisible && event.utf16CodePoint != 0 && !event.isCtrlPressed && !event.isMetaPressed && !event.isAltPressed) {
                    val char = event.utf16CodePoint.toChar()
                    if (!char.isISOControl()) {
                        isSearchVisible = true
                        searchQuery = TextFieldValue(char.toString(), selection = TextRange(1))
                        return@onPreviewKeyEvent true
                    }
                }
            }
            false
        }
        .focusRequester(focusRequester)
        .onFocusChanged { focusState -> onTableFocusChanged?.invoke(focusState.hasFocus) }
        .focusable()
        .clickable(interactionSource = null, indication = null) {
            focusRequester.requestFocus()
        }
    ) {
        if (isSearchVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                searchBarLeading?.invoke()
                TextField(
                    value = searchQuery,
                    onValueChange = {
                        searchQuery = it
                        if (it.text.isEmpty() && !alwaysShowSearch) {
                            isSearchVisible = false
                            focusRequester.requestFocus()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(searchFocusRequester),
                    placeholder = { Text(searchPlaceholder ?: "Search...") },
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
                            IconButton(onClick = {
                                searchQuery = TextFieldValue("")
                                if (!alwaysShowSearch) {
                                    isSearchVisible = false
                                }
                                focusRequester.requestFocus()
                            }) {
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
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            if (actionColumnWidth > 0.dp) {
                Spacer(modifier = Modifier.width(actionColumnWidth + 8.dp))
            }

            Row(
                modifier = Modifier.weight(1f)
            ) {
                val selectionColor = when {
                    sortIndex == -1 -> HexanaTheme.textColor
                    else -> Color(238, 237, 237, 80)
                }

                val sortSelectorText = when {
                    sortIndex == -1 && sortAsc -> "▴"
                    else -> "▾"
                }

                if (showIdColumn) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                            .width(idColumnWidth)
                            .clickable {
                                val newAsc = if (sortIndex == -1) !sortAsc else sortAsc
                                sortAsc = newAsc
                                sortIndex = -1
                                onSortChanged?.invoke(-1, newAsc)
                            }
                    ) {
                        Row {
                            Text(
                                "ID",
                                style = TextStyle(
                                    color = HexanaTheme.textColor,
                                    fontFamily = FontFamily.Monospace
                                )
                            )

                            Text(
                                sortSelectorText,
                                modifier = Modifier.padding(start = 4.dp),
                                style = TextStyle(
                                    color = selectionColor,
                                    fontFamily = FontFamily.Monospace,
                                )
                            )
                        }
                    }
                }

                columns.forEachIndexed { columnIndex, column ->
                    Box(
                        modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .borderBottom(Color.Gray, 1.dp, verticalPadding = 4.dp)
                        .clickable {
                            val newAsc = if (sortIndex == columnIndex) !sortAsc else sortAsc
                            sortAsc = newAsc
                            sortIndex = columnIndex
                            onSortChanged?.invoke(columnIndex, newAsc)
                        }
                        .then(column.widthModifier())
                        .then(weightModifier(column))
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
                                column.title,
                                style = TextStyle(
                                    color = HexanaTheme.textColor,
                                    fontFamily = FontFamily.Monospace
                                )
                            )

                            Text(
                                sortSelectorText,
                                modifier = Modifier.padding(start = 4.dp),
                                style = TextStyle(
                                    color = selectionColor,
                                    fontFamily = FontFamily.Monospace,
                                )
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (viewData.isEmpty()) {
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Text(
                        if (searchQuery.text.isEmpty()) "No data available" else "No items match the filter",
                        style = TextStyle(
                            color = HexanaTheme.textColor.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                    .onPointerEvent(PointerEventType.Exit) { hoveredOriginalIndex = null }) {
                items(viewData.size) { rowIndex ->
                    val id = viewData[rowIndex]
                    val data = getCellValues(id)

                    val rowBackgroundColor = if (hoveredOriginalIndex == id) {
                        Color(201, 255, 255, 100).compositeOver(HexanaTheme.hexCellHoverActiveColor)
                    } else if (selectedOriginalIndex == id) {
                        HexanaTheme.hexCellHoverActiveColor
                    } else {
                        rowColor(id) ?: Color.Unspecified
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
                                focusRequester.requestFocus()
                                onRow(id)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (actionColumnWidth > 0.dp) {
                            Box(
                                modifier = Modifier
                                    .width(actionColumnWidth)
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (actionColumn != null) actionColumn(id)
                            }
                        }

                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (showIdColumn) {
                                ContextMenuArea(items = { onRowContextMenu(id) }) {
                                    Box(modifier = Modifier.padding(horizontal = 4.dp).width(idColumnWidth)) {
                                        Text(
                                            id.toString(),
                                            style = TextStyle(color = HexanaTheme.textColor, fontFamily = FontFamily.Monospace),
                                            lineHeight = TextUnit(1f, TextUnitType.Em)
                                        )
                                    }
                                }
                            }

                            columns.forEachIndexed { index, column ->
                                val tableCellValue = data[index]
                                val text = tableCellValue.representation
                                val tooltip = tableCellValue.tooltip

                                Box(modifier = Modifier.then(weightModifier(column))) {
                                    ContextMenuArea(items = { onRowContextMenu(id) }) {
                                        Box(modifier = Modifier.padding(horizontal = 4.dp).then(column.widthModifier()).then(column.modifier)) {
                                            var isOverflowed by remember { mutableStateOf(false) }
                                            val textComponent = @Composable {
                                                Text(
                                                    text,
                                                    style = TextStyle(
                                                        color = HexanaTheme.textColor,
                                                        fontFamily = FontFamily.Monospace
                                                    ),
                                                    lineHeight = TextUnit(1f, TextUnitType.Em),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    onTextLayout = { layoutResult ->
                                                        isOverflowed = layoutResult.hasVisualOverflow
                                                    },
                                                )
                                            }

                                            val effectiveTooltip = tooltip ?: text.takeIf { isOverflowed }
                                            if (effectiveTooltip != null) {
                                                Tooltip(tooltip = { Text(effectiveTooltip) }) {
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
                }
            }

            VerticalScrollbar(lazyListState, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }

}

private fun TableColumn.widthModifier(): Modifier {
    val width = width ?: return Modifier
    return Modifier.width(width)
}

private fun RowScope.weightModifier(column: TableColumn): Modifier {
    val weight = column.weight ?: return Modifier
    return Modifier.weight(weight)
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
