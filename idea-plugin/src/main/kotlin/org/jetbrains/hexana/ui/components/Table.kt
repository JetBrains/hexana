package org.jetbrains.hexana.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalViewConfiguration
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

/** @param background tint for this cell alone, drawn over the row's own background */
class TableCellValue<T>(
    val value: T,
    val representation: String = value.toString(),
    val tooltip: String? = null,
    val background: Color? = null,
)
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
    onRowDoubleClick: ((id: Int) -> Unit)? = null,
    alwaysShowSearch: Boolean = false,
    searchPlaceholder: String? = null,
    searchBarLeading: (@Composable () -> Unit)? = null,
    onTableFocusChanged: ((Boolean) -> Unit)? = null,
    onSearchChanged: ((String) -> Unit)? = null,
    externalSelectedIndex: Int? = null,
    /**
     * Each change other than to `0` clears the search field, e.g. so an outside request can
     * select a row the current search hides.
     */
    searchResetRequest: Int = 0,
    /**
     * When set, the table sizes itself to at most this many rows instead of
     * filling the available height.
     *
     * A table normally owns its pane and takes all the room, which needs a
     * bounded parent height. Pass this to place several tables one after another
     * in a scrolling layout — a script tab interleaving text and tables — where
     * the parent height is unbounded. Rows stay virtualized either way.
     */
    maxVisibleRows: Int? = null,
    /**
     * Whether the table grabs keyboard focus when it first appears.
     *
     * Turn this off when several tables share a pane; otherwise each one requests
     * focus as it composes and they take it from each other while scrolling.
     */
    requestInitialFocus: Boolean = true,
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
    // Plain holder rather than state: nothing renders it, so writing it must not recompose.
    val lastRowClick = remember { LastRowClick() }
    val doubleClickTimeoutMillis = LocalViewConfiguration.current.doubleTapTimeoutMillis

    var searchQuery by remember { mutableStateOf(TextFieldValue("")) }
    var isSearchVisible by remember { mutableStateOf(alwaysShowSearch) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
        }
    }

    val lazyListState = rememberLazyListState()

    LaunchedEffect(searchResetRequest) {
        if (searchResetRequest != 0) searchQuery = TextFieldValue("")
    }

    LaunchedEffect(searchQuery.text) {
        selectedOriginalIndex = null
        lazyListState.scrollToItem(0)
        onSearchChanged?.invoke(searchQuery.text)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(requestInitialFocus) {
        if (requestInitialFocus) {
            focusRequester.requestFocus()
        }
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
                val cellA = getCellValues(a)[sortIndex]
                val cellB = getCellValues(b)[sortIndex]
                val valueA = cellA.value
                val valueB = cellB.value
                when {
                    valueA is Int && valueB is Int -> valueA.compareTo(valueB)
                    valueA is Long && valueB is Long -> valueA.compareTo(valueB)
                    valueA is String && valueB is String -> valueA.compareTo(valueB)
                    // Mixed or unhandled types: order by what the user sees rather
                    // than reporting "equal", which would leave those rows in an
                    // arbitrary order. A column of mostly numbers with a "n/a" in
                    // it still sorts predictably.
                    else -> cellA.representation.compareTo(cellB.representation)
                }
            }
            indices.sortWith(if (sortAsc) comparator else comparator.reversed())
        }

        indices
    }

    LaunchedEffect(externalSelectedIndex) {
        val id = externalSelectedIndex ?: return@LaunchedEffect
        // A real selection, as if the row had been picked with the keyboard:
        // highlighted, focused so the arrow keys carry on from it, and reported
        // through onRowSelect. It also scrolls when the row is already the
        // selected one, since the user may have scrolled away from it.
        selectedOriginalIndex = id
        hoveredOriginalIndex = null
        focusRequester.requestFocus()
        onRowSelect?.invoke(id)
        val viewIndex = viewData.indexOf(id)
        if (viewIndex >= 0) {
            lazyListState.animateScrollToItem(viewIndex)
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val idColumnWidth = if (showIdColumn) maxOf(
        textMeasurer.measureWidthMonospace(dataCount, 4),
        textMeasurer.measureWidthMonospace("ID ▾"),
    ) else 0.dp
    val minContentWidth = minTableContentWidth(columns, textMeasurer, idColumnWidth, actionColumnWidth)
    val horizontalScroll = rememberScrollState()


    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 8.dp)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                if (event.key == Key.C && (event.isCtrlPressed || event.isMetaPressed)) {
                    selectedOriginalIndex?.let { id ->
                        // The ID leads only when it is shown; otherwise it is an internal index.
                        val cells = getCellValues(id).map { it.representation }
                        val rowContent = (if (showIdColumn) listOf(id.toString()) + cells else cells).joinToString("\t")
                        CopyPasteManager.getInstance().setContents(StringSelection(rowContent))
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

        // Columns never shrink below what their headers and fixed widths need: in a narrow pane
        // (tabs docked beside the hex view) the header and rows scroll sideways together instead
        // of squeezing columns until titles wrap letter by letter and cells overlap.
        BoxWithConstraints(
            modifier = if (maxVisibleRows == null) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(),
        ) {
        val contentWidth = if (constraints.hasBoundedWidth) maxOf(maxWidth, minContentWidth) else minContentWidth
        val scrollsHorizontally = constraints.hasBoundedWidth && contentWidth > maxWidth
        Column(
            modifier = Modifier
                .horizontalScroll(horizontalScroll)
                .width(contentWidth)
                .then(if (maxVisibleRows == null) Modifier.fillMaxHeight() else Modifier),
        ) {
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
                                ),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
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

        val rowHeight = rememberTableRowHeight()
        // The horizontal scrollbar overlays the bottom edge; keep the last row clear of it.
        val scrollbarGutter = if (scrollsHorizontally) HORIZONTAL_SCROLLBAR_GUTTER else 0.dp
        val bodyModifier = if (maxVisibleRows == null) {
            Modifier.weight(1f).padding(bottom = scrollbarGutter)
        } else {
            val visibleRows = maxOf(minOf(viewData.size, maxVisibleRows), 1)
            Modifier.height(rowHeight * visibleRows + BODY_VERTICAL_PADDING * 2 + scrollbarGutter).padding(bottom = scrollbarGutter)
        }

        Box(modifier = bodyModifier) {
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
                modifier = Modifier.fillMaxWidth().padding(vertical = BODY_VERTICAL_PADDING)
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
                            .padding(horizontal = 4.dp, vertical = ROW_VERTICAL_PADDING)
                            .onPointerEvent(PointerEventType.Enter) {
                                hoveredOriginalIndex = id
                            }
                            .clickable {
                                selectedOriginalIndex = id
                                focusRequester.requestFocus()
                                onRow(id)
                                // Detected by hand rather than with combinedClickable, which
                                // would hold back every single click until the double-click
                                // timeout expired.
                                val now = System.currentTimeMillis()
                                if (onRowDoubleClick != null && lastRowClick.id == id &&
                                    now - lastRowClick.at <= doubleClickTimeoutMillis
                                ) {
                                    // Reset, so a third click starts a new pair.
                                    lastRowClick.id = null
                                    onRowDoubleClick(id)
                                } else {
                                    lastRowClick.id = id
                                    lastRowClick.at = now
                                }
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
                                val cellBackground = tableCellValue.background

                                Box(
                                    modifier = Modifier.then(weightModifier(column)).then(
                                        if (cellBackground != null) {
                                            Modifier.background(cellBackground, RoundedCornerShape(3.dp))
                                        } else {
                                            Modifier
                                        },
                                    ),
                                ) {
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

        }
        }
        // Outside the scrolled content, so they stay on the visible edges.
        VerticalScrollbar(lazyListState, modifier = Modifier.align(Alignment.CenterEnd))
        if (scrollsHorizontally) {
            HorizontalScrollbar(horizontalScroll, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        }
    }

}

/**
 * The narrowest the table may get before it scrolls horizontally: every fixed-width column at
 * its width, and weighted columns wide enough that each still shows its title (and at least
 * [MIN_WEIGHTED_COLUMN_CHARS] characters) after the space is split by weight. Includes the
 * per-cell and per-row padding the layout adds.
 */
@Composable
private fun minTableContentWidth(
    columns: List<TableColumn>,
    textMeasurer: TextMeasurer,
    idColumnWidth: Dp,
    actionColumnWidth: Dp,
): Dp {
    val minWeighted = textMeasurer.measureWidthMonospace("x".repeat(MIN_WEIGHTED_COLUMN_CHARS))
    val totalWeight = columns.sumOf { (if (it.width == null) it.weight ?: 0f else 0f).toDouble() }.toFloat()
    var fixed = ROW_HORIZONTAL_PADDING * 2
    if (actionColumnWidth > 0.dp) fixed += actionColumnWidth + CELL_HORIZONTAL_PADDING * 2
    if (idColumnWidth > 0.dp) fixed += idColumnWidth + CELL_HORIZONTAL_PADDING * 2
    var weightedShare = 0.dp
    for (column in columns) {
        fixed += CELL_HORIZONTAL_PADDING * 2
        val width = column.width
        val weight = column.weight
        val titleWidth = textMeasurer.measureWidthMonospace(column.title + " ▾")
        when {
            width != null -> fixed += width
            weight != null && weight > 0f && totalWeight > 0f ->
                weightedShare = maxOf(weightedShare, maxOf(titleWidth, minWeighted) * (totalWeight / weight))
            else -> fixed += titleWidth
        }
    }
    return fixed + weightedShare
}

/** Room reserved below the rows for the overlaying horizontal scrollbar, as in `RowDetailPanel`. */
private val HORIZONTAL_SCROLLBAR_GUTTER = 10.dp

/** Narrowest a weighted (fill) column may get, in monospace characters. */
private const val MIN_WEIGHTED_COLUMN_CHARS = 12

/** Horizontal padding of each header and data cell, per side. */
private val CELL_HORIZONTAL_PADDING = 4.dp

/** Horizontal padding of each data row, per side. */
private val ROW_HORIZONTAL_PADDING = 4.dp

/** The row and time of a table's last click, for double-click detection. */
private class LastRowClick(var id: Int? = null, var at: Long = 0L)

/**
 * Height of one data row.
 *
 * Rows are uniform — every cell is a single monospace line — so a row count
 * converts exactly to a height. Callers that need to decide how many rows a table
 * may show use this rather than re-deriving it, so the two cannot drift.
 */
@Composable
internal fun rememberTableRowHeight(): Dp {
    val textMeasurer = rememberTextMeasurer()
    return with(LocalDensity.current) {
        textMeasurer.measureMonospaceInt("x").size.height.toDp()
    } + ROW_VERTICAL_PADDING * 2
}

/** Chrome around the row list: the body's own vertical padding. */
internal val TABLE_BODY_CHROME_HEIGHT: Dp get() = BODY_VERTICAL_PADDING * 2

/** Vertical padding on each data row; part of the row-height calculation. */
private val ROW_VERTICAL_PADDING = 2.dp

/** Vertical padding around the row list; part of the body-height calculation. */
private val BODY_VERTICAL_PADDING = 8.dp

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
