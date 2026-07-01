package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.zip.ZipParser

/**
 * Lightweight data holder for a single ZIP/JAR entry, carrying only the
 * fields needed by the shared table descriptor. Platform code maps its
 * richer entry type into this before calling [zipEntriesDescriptor].
 */
class ZipEntryRow(
    val name: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
)

/**
 * Builds a [TableTabDescriptor] for a list of ZIP/JAR entries.
 *
 * The descriptor is platform-agnostic and can be rendered by both the
 * IntelliJ and VS Code UI layers.
 *
 * @param entries the entry rows to display
 * @param onRowClick optional callback invoked when a row is clicked
 * @param searchPlaceholder placeholder text for the search bar
 * @param emptyMessage message shown when [entries] is empty
 */
fun zipEntriesDescriptor(
    entries: List<ZipEntryRow>,
    onRowClick: ((index: Int) -> Unit)? = null,
    searchPlaceholder: String = "Search entries\u2026",
    emptyMessage: String = "(no entries)",
): TableTabDescriptor = TableTabDescriptor(
    name = "Entries",
    columns = listOf(
        ColumnDescriptor(
            name = "Name",
            widthBehavior = ColumnWidth.Fill(),
        ) { index ->
            val name = entries[index].name
            CellValue(name, tooltip = name.takeIf { it.isNotEmpty() })
        },
        ColumnDescriptor(
            name = "Compressed",
            widthBehavior = ColumnWidth.Measured("00,000,000 bytes"),
        ) { index ->
            CellValue(entries[index].compressedSize, formatSizeLong(entries[index].compressedSize))
        },
        ColumnDescriptor(
            name = "Uncompressed",
            widthBehavior = ColumnWidth.Measured("00,000,000 bytes"),
        ) { index ->
            CellValue(entries[index].uncompressedSize, formatSizeLong(entries[index].uncompressedSize))
        },
    ),
    rowCount = entries.size,
    defaultSortColumn = 0,
    defaultSortAscending = true,
    rowClickAction = onRowClick,
    alwaysShowSearch = true,
    searchPlaceholder = searchPlaceholder,
    emptyMessage = emptyMessage,
    showIdColumn = false,
)

/**
 * [TableTabsProvider] for ZIP / JAR archives.
 *
 * Detects ZIP files via [isZipFile] magic check, parses the central
 * directory with [ZipParser], and produces a single "Entries" table tab
 * listing all non-directory entries. Platform-specific behaviour (entry
 * opening on click) is added by the platform enricher.
 */
class ZipTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        val buffer = provider.bytes
        if (!isZipFile(buffer)) return emptyList()

        val entries = try {
            ZipParser.parse(buffer.duplicate().position(0))
        } catch (_: Throwable) {
            return emptyList()
        }

        val displayEntries = entries.filter { !it.isDirectory }

        return listOf(
            zipEntriesDescriptor(
                entries = displayEntries.map { ZipEntryRow(it.name, it.compressedSize, it.uncompressedSize) },
            )
        )
    }
}
