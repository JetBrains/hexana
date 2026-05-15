package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.zip.ZipEntry
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/**
 * Editor view for a JAR / ZIP archive. Vertical split: a [HexViewer] over the
 * raw archive bytes on top, a sortable + searchable list of class entries on
 * the bottom. Clicking a row hands the (decompressed) bytes off to
 * [onOpenEntry] — the IDE-side wiring opens those bytes in a child
 * [ClassFileEditorView].
 */
@Composable
internal fun JarEditorView(
    entries: List<ZipEntry>,
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    hexFocusRequester: FocusRequester,
    onOpenEntry: (ZipEntry) -> Unit,
) {
    VerticalSplitPanel(
        state = state,
        northPanel = {
            Box(modifier = Modifier.fillMaxSize()) {
                HexViewer(
                    binaryProvider = binaryProvider,
                    state = state,
                    focusRequester = hexFocusRequester,
                )
            }
        },
        southPanel = {
            JarClassesPanel(entries.filter { it.isClass }, onOpenEntry)
        },
    )
}

@Composable
private fun JarClassesPanel(
    classEntries: List<ZipEntry>,
    onOpenEntry: (ZipEntry) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sortColumn by rememberSaveable { mutableStateOf(SortColumn.Name) }
    var sortAsc by rememberSaveable { mutableStateOf(true) }

    val displayed by remember(classEntries, query, sortColumn, sortAsc) {
        derivedStateOf {
            val filtered = if (query.isBlank()) {
                classEntries
            } else {
                classEntries.filter { it.name.contains(query, ignoreCase = true) }
            }
            val sorted = when (sortColumn) {
                SortColumn.Name -> filtered.sortedBy { it.name.lowercase() }
                SortColumn.CompressedSize -> filtered.sortedBy { it.compressedSize }
                SortColumn.UncompressedSize -> filtered.sortedBy { it.uncompressedSize }
            }
            if (sortAsc) sorted else sorted.asReversed()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SearchBar(query = query, onQueryChange = { query = it })
        ColumnHeaderRow(
            sortColumn = sortColumn,
            sortAsc = sortAsc,
            onSort = { column ->
                if (sortColumn == column) {
                    sortAsc = !sortAsc
                } else {
                    sortColumn = column
                    sortAsc = true
                }
            },
        )
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())

        val listState = rememberLazyListState()
        Box(modifier = Modifier.fillMaxSize()) {
            if (displayed.isEmpty()) {
                Text(
                    if (classEntries.isEmpty()) "(no class entries)" else "(no matches for \"$query\")",
                    style = RowStyle,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(state = listState) {
                    items(
                        count = displayed.size,
                        key = { idx -> displayed[idx].localHeaderOffset },
                    ) { idx ->
                        ClassEntryRow(displayed[idx], onOpenEntry)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    val outline = if (JewelTheme.isDark) Color(0xFF393B3D) else Color(0xFFC9CCD6)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = HexanaTheme.textColor,
            ),
            cursorBrush = SolidColor(HexanaTheme.textColor),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, outline, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        "Search classes…",
                        style = RowStyle.copy(color = HexanaTheme.hexTextColor),
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun ColumnHeaderRow(
    sortColumn: SortColumn,
    sortAsc: Boolean,
    onSort: (SortColumn) -> Unit,
) {
    val bg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFEBECF0)
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = HexanaTheme.textColor,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderCell(
            "Name", weight = 5f, style = style,
            indicator = sortIndicator(sortColumn, SortColumn.Name, sortAsc),
            onClick = { onSort(SortColumn.Name) },
        )
        HeaderCell(
            "Compressed", weight = 1.2f, style = style,
            indicator = sortIndicator(sortColumn, SortColumn.CompressedSize, sortAsc),
            onClick = { onSort(SortColumn.CompressedSize) },
        )
        HeaderCell(
            "Uncompressed", weight = 1.2f, style = style,
            indicator = sortIndicator(sortColumn, SortColumn.UncompressedSize, sortAsc),
            onClick = { onSort(SortColumn.UncompressedSize) },
        )
    }
}

@Composable
private fun RowScope.HeaderCell(
    label: String,
    weight: Float,
    style: TextStyle,
    indicator: String,
    onClick: () -> Unit,
) {
    Text(
        "$label $indicator",
        style = style,
        modifier = Modifier
            .weight(weight)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun ClassEntryRow(entry: ZipEntry, onOpen: (ZipEntry) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry) }
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(entry.name, style = RowStyle, modifier = Modifier.weight(5f))
        Text(formatBytes(entry.compressedSize), style = RowStyle, modifier = Modifier.weight(1.2f))
        Text(formatBytes(entry.uncompressedSize), style = RowStyle, modifier = Modifier.weight(1.2f))
    }
}

private enum class SortColumn { Name, CompressedSize, UncompressedSize }

private fun sortIndicator(active: SortColumn, column: SortColumn, asc: Boolean): String =
    if (active != column) " " else if (asc) "▲" else "▼"

private val RowStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${formatDecimal(bytes / 1024.0, 1)} KB"
    bytes < 1024L * 1024 * 1024 -> "${formatDecimal(bytes / (1024.0 * 1024), 1)} MB"
    else -> "${formatDecimal(bytes / (1024.0 * 1024 * 1024), 1)} GB"
}

/** One-decimal byte-size formatter — avoids `String.format`. */
private fun formatDecimal(value: Double, decimals: Int): String {
    val factor = if (decimals == 1) 10.0 else 1.0
    val rounded = kotlin.math.round(value * factor) / factor
    return rounded.toString()
}
