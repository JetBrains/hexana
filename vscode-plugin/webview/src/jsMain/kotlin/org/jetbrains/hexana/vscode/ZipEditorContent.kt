package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.shared.ZipEntryRow
import org.jetbrains.hexana.shared.zipEntriesDescriptor
import org.jetbrains.hexana.zip.ZipEntry
import org.jetbrains.hexana.zip.ZipParser

/**
 * Builds the extra [PanelTab] entries for a ZIP / JAR archive.
 *
 * For JARs (archives containing `.class` files) only class entries are shown;
 * for plain ZIP files all non-directory entries are listed. Clicking a row
 * sends the entry metadata to the extension host which decompresses and opens
 * the entry in a child editor tab.
 */
internal fun zipExtraTabs(buffer: CommonByteBuffer): List<PanelTab> {
    val entries = try {
        ZipParser.parse(buffer)
    } catch (_: Throwable) {
        null
    }
    return listOf(
        PanelTab("Entries") { _ ->
            ZipEntriesContent(entries = entries)
        }
    )
}
@Composable
private fun ZipEntriesContent(
    entries: List<ZipEntry>?,
) {
    if (entries == null) {
        Div({
            classes("empty-hint")
            style { padding(12.px) }
        }) {
            Text("Failed to parse archive entries.")
        }
        return
    }

    val classEntries = entries.filter { it.isClass }
    val hasNonClassEntries = entries.any { !it.isDirectory && !it.isClass }
    var showAll by remember { mutableStateOf(false) }

    val displayEntries = if (classEntries.isNotEmpty()) {
        if (showAll) entries.filter { !it.isDirectory } else classEntries
    } else {
        entries.filter { !it.isDirectory }
    }

    val descriptor = zipEntriesDescriptor(
        entries = displayEntries.map { ZipEntryRow(it.name, it.compressedSize, it.uncompressedSize) },
        onRowClick = { index ->
            val entry = displayEntries[index]
            postOpenZipEntryToHost(
                name = entry.name,
                dataOffset = entry.dataOffset,
                compressedSize = entry.compressedSize,
                uncompressedSize = entry.uncompressedSize,
                compressionMethod = entry.compressionMethod,
            )
        },
        searchPlaceholder = if (classEntries.isNotEmpty()) {
            if (showAll) "Search entries\u2026" else "Search classes\u2026"
        } else {
            "Search entries\u2026"
        },
        emptyMessage = if (classEntries.isNotEmpty()) "(no class entries)" else "(no entries)",
    )

    val selection = remember { HexSelectionState() }

    TableTabContent(
        descriptor = descriptor,
        selection = selection,
        searchBarLeading = if (classEntries.isNotEmpty() && hasNonClassEntries) {
            {
                Select({
                    classes("hexana-entry-filter-select")
                    onChange { event ->
                        showAll = event.value == "all"
                    }
                }) {
                    Option("classes", { if (!showAll) selected() }) { Text("Classes") }
                    Option("all", { if (showAll) selected() }) { Text("All entries") }
                }
            }
        } else {
            null
        },
    )
}
