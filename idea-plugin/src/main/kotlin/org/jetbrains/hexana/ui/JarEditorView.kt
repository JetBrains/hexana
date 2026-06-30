package org.jetbrains.hexana.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import org.jetbrains.hexana.shared.ZipEntryRow
import org.jetbrains.hexana.shared.zipEntriesDescriptor
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.ui.panel.Panel
import org.jetbrains.hexana.ui.panel.toColumnLayout
import org.jetbrains.hexana.zip.ZipEntry
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

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
): List<Panel> = listOf(
    Panel("Entries") {
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
        ZipEntriesTable(
            entries = displayedEntries,
            onOpenEntry = onOpenEntry,
            searchPlaceholder = if (showAll) "Search entries\u2026" else "Search classes\u2026",
            emptyMessage = "(no class entries)",
            searchBarLeading = if (hasNonClassEntries) {
                { EntryFilterComboBox(showAll = showAll, onShowAllChange = { showAll = it }) }
            } else {
                null
            },
        )
    } else {
        ZipEntriesTable(
            entries = entries.filter { !it.isDirectory },
            onOpenEntry = onOpenEntry,
            searchPlaceholder = "Search entries\u2026",
            emptyMessage = "(no entries)",
        )
    }
}

@Composable
private fun ZipEntriesTable(
    entries: List<ZipEntry>,
    onOpenEntry: (ZipEntry) -> Unit,
    searchPlaceholder: String,
    emptyMessage: String,
    searchBarLeading: (@Composable () -> Unit)? = null,
) {
    val descriptor = zipEntriesDescriptor(
        entries = entries,
        onOpenEntry = onOpenEntry,
        searchPlaceholder = searchPlaceholder,
        emptyMessage = emptyMessage,
    )

    val textMeasurer = rememberTextMeasurer()

    val columnLayouts = descriptor.columns.map { col ->
        col.widthBehavior.toColumnLayout(
            textMeasurer = textMeasurer,
            title = col.name,
            rowCount = descriptor.rowCount,
            cellProvider = col.cellProvider,
        )
    }

    val columns = remember(descriptor, columnLayouts) {
        descriptor.columns.mapIndexed { i, col ->
            TableColumn(
                title = col.name,
                modifier = columnLayouts[i].modifier,
                width = columnLayouts[i].width,
                weight = columnLayouts[i].weight,
            ) { index ->
                val cell = col.cellProvider(index)
                TableCellValue(cell.value, cell.representation, cell.tooltip)
            }
        }
    }

    if (descriptor.rowCount == 0) {
        Column {
            Table(
                columns = columns,
                dataCount = 0,
                showIdColumn = false,
                alwaysShowSearch = true,
                searchPlaceholder = searchPlaceholder,
                searchBarLeading = searchBarLeading,
            )
            Text(emptyMessage, modifier = Modifier.padding(12.dp))
        }
        return
    }

    Table(
        columns = columns,
        dataCount = descriptor.rowCount,
        showIdColumn = false,
        defaultSortColumn = descriptor.defaultSortColumn ?: -1,
        defaultSortAscending = descriptor.defaultSortAscending,
        onRow = { rowIndex -> onOpenEntry(entries[rowIndex]) },
        alwaysShowSearch = true,
        searchPlaceholder = searchPlaceholder,
        searchBarLeading = searchBarLeading,
    )
}

private fun zipEntriesDescriptor(
    entries: List<ZipEntry>,
    onOpenEntry: (ZipEntry) -> Unit,
    searchPlaceholder: String,
    emptyMessage: String,
) = zipEntriesDescriptor(
    entries = entries.map { ZipEntryRow(it.name, it.compressedSize, it.uncompressedSize) },
    onRowClick = { index -> onOpenEntry(entries[index]) },
    searchPlaceholder = searchPlaceholder,
    emptyMessage = emptyMessage,
)

private val RowStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
private fun EntryFilterComboBox(showAll: Boolean, onShowAllChange: (Boolean) -> Unit) {
    val outline = if (JewelTheme.isDark) Color(0xFF393B3D) else Color(0xFFC9CCD6)
    val label = if (showAll) "All entries" else "Classes"
    var expanded by remember { mutableStateOf(false) }
    Box {
        Text(
            "$label \u25BE",
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
                    PopupItem("Classes", selected = !showAll) {
                        onShowAllChange(false)
                        expanded = false
                    }
                    PopupItem("All entries", selected = showAll) {
                        onShowAllChange(true)
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
