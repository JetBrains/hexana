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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import org.jetbrains.hexana.shared.formatSizeLong
import org.jetbrains.hexana.zip.ZipEntry
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticalScrollbar

/**
 * Builds the panel list for a JAR / ZIP archive editor. The single "Entries"
 * panel contains a sortable + searchable entry list. For JARs (archives
 * containing `.class` files) only class entries are shown by default; for
 * plain ZIP files all non-directory entries are listed. Clicking a row hands
 * the (decompressed) bytes off to [onOpenEntry].
 */
internal fun jarFilePanels(
    entries: List<ZipEntry>,
    onOpenEntry: (ZipEntry) -> Unit,
): List<org.jetbrains.hexana.ui.panel.Panel> = listOf(
    org.jetbrains.hexana.ui.panel.Panel("Entries") {
        JarEntriesContent(entries, onOpenEntry)
    },
)

@Composable
private fun JarEntriesContent(
    entries: List<ZipEntry>,
    onOpenEntry: (ZipEntry) -> Unit,
) {
    val classEntries = entries.filter { it.isClass }
    val hasNonClassEntries = entries.any { !it.isDirectory && !it.isClass }
    if (classEntries.isNotEmpty()) {
        var showAll by rememberSaveable { mutableStateOf(false) }
        val displayedEntries = if (showAll) {
            entries.filter { !it.isDirectory }
        } else {
            classEntries
        }
        ZipEntriesPanel(
            allEntries = displayedEntries,
            onOpenEntry = onOpenEntry,
            searchPlaceholder = if (showAll) "Search entries\u2026" else "Search classes\u2026",
            emptyMessage = "(no class entries)",
            entryFilter = if (hasNonClassEntries) {
                EntryFilter(showAll = showAll, onShowAllChange = { showAll = it })
            } else {
                null
            },
        )
    } else {
        ZipEntriesPanel(
            allEntries = entries.filter { !it.isDirectory },
            onOpenEntry = onOpenEntry,
            searchPlaceholder = "Search entries\u2026",
            emptyMessage = "(no entries)",
        )
    }
}

private class EntryFilter(
    val showAll: Boolean,
    val onShowAllChange: (Boolean) -> Unit,
)

@Composable
private fun ZipEntriesPanel(
    allEntries: List<ZipEntry>,
    onOpenEntry: (ZipEntry) -> Unit,
    searchPlaceholder: String,
    emptyMessage: String,
    entryFilter: EntryFilter? = null,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var sortColumn by rememberSaveable { mutableStateOf(SortColumn.Name) }
    var sortAsc by rememberSaveable { mutableStateOf(true) }

    val displayed by remember(allEntries, query, sortColumn, sortAsc) {
        derivedStateOf {
            val filtered = if (query.isBlank()) {
                allEntries
            } else {
                allEntries.filter { it.name.contains(query, ignoreCase = true) }
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
        SearchBarRow(
            query = query,
            onQueryChange = { query = it },
            placeholder = searchPlaceholder,
            entryFilter = entryFilter,
        )
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
                    if (allEntries.isEmpty()) emptyMessage else "(no matches for \"$query\")",
                    style = RowStyle,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(state = listState) {
                    items(
                        count = displayed.size,
                        key = { idx -> displayed[idx].localHeaderOffset },
                    ) { idx ->
                        ZipEntryRow(displayed[idx], onOpenEntry)
                    }
                }
                VerticalScrollbar(listState, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

@Composable
private fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String = "Search classes\u2026",
    entryFilter: EntryFilter? = null,
) {
    val outline = if (JewelTheme.isDark) Color(0xFF393B3D) else Color(0xFFC9CCD6)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (entryFilter != null) {
            EntryFilterComboBox(entryFilter, outline)
        }
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
                .weight(1f)
                .border(1.dp, outline, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = RowStyle.copy(color = HexanaTheme.hexTextColor),
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun EntryFilterComboBox(filter: EntryFilter, outline: Color) {
    val label = if (filter.showAll) "All entries" else "Classes"
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            "$label ▾",
            style = RowStyle.copy(color = HexanaTheme.textColor),
            modifier = Modifier
                .border(1.dp, outline, RoundedCornerShape(3.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .widthIn(min = 90.dp),
        )
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                onDismissRequest = { expanded = false },
            ) {
                val popupBg = if (JewelTheme.isDark) Color(0xFF2B2D30) else Color(0xFFFFFFFF)
                Column(
                    modifier = Modifier
                        .width(130.dp)
                        .background(popupBg, RoundedCornerShape(4.dp))
                        .border(1.dp, outline, RoundedCornerShape(4.dp)),
                ) {
                    PopupItem("Classes", selected = !filter.showAll) {
                        filter.onShowAllChange(false)
                        expanded = false
                    }
                    PopupItem("All entries", selected = filter.showAll) {
                        filter.onShowAllChange(true)
                        expanded = false
                    }
                }
            }
        }
    }
}

@Composable
private fun PopupItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) {
        if (JewelTheme.isDark) Color(0xFF2E436E) else Color(0xFFD4E2FF)
    } else {
        Color.Transparent
    }
    Text(
        text,
        style = RowStyle.copy(color = HexanaTheme.textColor),
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
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
private fun ZipEntryRow(entry: ZipEntry, onOpen: (ZipEntry) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry) }
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(entry.name, style = RowStyle, modifier = Modifier.weight(5f))
        Text(formatSizeLong(entry.compressedSize), style = RowStyle, modifier = Modifier.weight(1.2f))
        Text(formatSizeLong(entry.uncompressedSize), style = RowStyle, modifier = Modifier.weight(1.2f))
    }
}

private enum class SortColumn { Name, CompressedSize, UncompressedSize }

private fun sortIndicator(active: SortColumn, column: SortColumn, asc: Boolean): String =
    if (active != column) " " else if (asc) "▲" else "▼"

private val RowStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
