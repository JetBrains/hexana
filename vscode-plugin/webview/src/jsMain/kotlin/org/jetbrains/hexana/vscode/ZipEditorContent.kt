package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.shared.formatSizeLong
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

    if (displayEntries.isEmpty()) {
        Div({
            classes("empty-hint")
            style { padding(12.px) }
        }) {
            Text(if (classEntries.isNotEmpty()) "(no class entries)" else "(no entries)")
        }
        return
    }

    val headers = listOf("Name", "Compressed", "Uncompressed")
    val aligns = listOf("left", "right", "right")

    // Render rows incrementally in batches to avoid blocking the browser
    // main thread when the archive has tens of thousands of entries.
    val initialBatch = 100
    val batchSize = 300
    var renderedCount by remember(displayEntries) { mutableStateOf(minOf(initialBatch, displayEntries.size)) }

    LaunchedEffect(displayEntries, renderedCount) {
        if (renderedCount < displayEntries.size) {
            delay(1)
            renderedCount = minOf(renderedCount + batchSize, displayEntries.size)
        }
    }

    val isLoading = renderedCount < displayEntries.size

    SearchableTable(
        headers = headers,
        columnAligns = aligns,
        defaultSortColumn = 0,
        defaultSortDirection = SortDirection.ASC,
        alwaysShowSearch = true,
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
        footer = if (isLoading) {
            {
                Div({
                    classes("loading-hint")
                    style {
                        property("text-align", "center")
                        property("padding", "8px 0")
                        property("opacity", "0.7")
                        property("font-size", "12px")
                    }
                }) {
                    Text("Loading entries $renderedCount / ${displayEntries.size}\u2026")
                }
            }
        } else null,
    ) {
        for (i in 0 until renderedCount) {
            val entry = displayEntries[i]
            PanelTableRow(
                onClick = {
                    postOpenZipEntryToHost(
                        name = entry.name,
                        dataOffset = entry.dataOffset,
                        compressedSize = entry.compressedSize,
                        uncompressedSize = entry.uncompressedSize,
                        compressionMethod = entry.compressionMethod,
                    )
                },
            ) {
                Td({ classes("cell") }) { Text(entry.name) }
                Td({ classes("cell-right") }) { Text(formatSizeLong(entry.compressedSize)) }
                Td({ classes("cell-right") }) { Text(formatSizeLong(entry.uncompressedSize)) }
            }
        }
    }
}

