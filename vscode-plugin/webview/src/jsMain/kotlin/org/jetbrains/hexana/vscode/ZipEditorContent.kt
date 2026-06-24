package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.attributes.selected
import org.jetbrains.compose.web.css.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.shared.formatSizeLong
import org.jetbrains.hexana.zip.ZipEntry
import org.jetbrains.hexana.zip.ZipParser

/**
 * Editor content for a ZIP / JAR archive. Shows a hex viewer on top and a
 * sortable, searchable entry list below — mirroring the IntelliJ plugin's
 * [JarEditorView][org.jetbrains.hexana.ui.JarEditorView].
 *
 * For JARs (archives containing `.class` files) only class entries are shown;
 * for plain ZIP files all non-directory entries are listed. Clicking a row
 * sends the entry metadata to the extension host which decompresses and opens
 * the entry in a child editor tab.
 */
@Composable
internal fun ZipEditorContent(
    fileName: String,
    buffer: CommonByteBuffer,
) {
    val selection = remember { HexSelectionState() }
    var panelHeight by remember { mutableStateOf(DEFAULT_ZIP_PANEL_HEIGHT) }

    val entries = remember(buffer) {
        try {
            ZipParser.parse(buffer)
        } catch (_: Throwable) {
            null
        }
    }

    Div({
        classes("hexana-editor")
        style {
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
            width(100.percent)
            height(100.percent)
            property("overflow", "hidden")
        }
    }) {
        EditorToolbar(
            fileName = fileName,
            size = buffer.size,
            provider = org.jetbrains.hexana.providers.GenericBinaryProvider(buffer),
            onSizeClick = null,
        )
        HexViewerPanel(bytes = buffer, selection = selection)
        SelectionStatusBar(selection = selection, totalBytes = buffer.size)
        ResizeDivider { deltaY ->
            panelHeight = (panelHeight - deltaY).coerceAtLeast(MIN_ZIP_PANEL_HEIGHT)
        }
        ZipEntriesPanel(
            entries = entries,
            heightPx = panelHeight,
        )
    }
}

private const val MIN_ZIP_PANEL_HEIGHT = 80.0
private const val DEFAULT_ZIP_PANEL_HEIGHT = 250.0

@Composable
private fun ZipEntriesPanel(
    entries: List<ZipEntry>?,
    heightPx: Double,
) {
    Div({
        style {
            height(heightPx.px)
            property("flex-shrink", "0")
            property("overflow", "auto")
            display(DisplayStyle.Flex)
            property("flex-direction", "column")
        }
    }) {
        if (entries == null) {
            Div({
                classes("empty-hint")
                style { padding(12.px) }
            }) {
                Text("Failed to parse archive entries.")
            }
            return@Div
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
            return@Div
        }

        val headers = listOf("Name", "Compressed", "Uncompressed")
        val aligns = listOf("left", "right", "right")

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
        ) {
            for (entry in displayEntries) {
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
}

