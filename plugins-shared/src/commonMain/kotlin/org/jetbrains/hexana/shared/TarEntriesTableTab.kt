package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.TarBinaryProvider

/**
 * Lightweight data holder for a single TAR entry, carrying only the
 * fields needed by the shared table descriptor.
 */
class TarEntryRow(
    val name: String,
    val size: Long,
)

/**
 * Builds a [TableTabDescriptor] for a list of TAR entries.
 *
 * The descriptor is platform-agnostic and can be rendered by both the
 * IntelliJ and VS Code UI layers.
 */
fun tarEntriesDescriptor(
    entries: List<TarEntryRow>,
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
            name = "Size",
            widthBehavior = ColumnWidth.Measured("00,000,000 bytes"),
        ) { index ->
            CellValue(entries[index].size, formatSizeLong(entries[index].size))
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
 * [TableTabsProvider] for TAR archives.
 *
 * Produces a single "Entries" table tab listing all non-directory entries
 * when the provider is a [TarBinaryProvider]. Platform-specific behaviour
 * (entry opening on click) is added by the platform enricher.
 */
class TarTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> =
        tableTabs(provider, TableTabsContext())

    override fun tableTabs(provider: BinaryProvider, context: TableTabsContext): List<TableTabDescriptor> {
        if (provider !is TarBinaryProvider) return emptyList()
        val displayEntries = provider.displayEntries
        if (displayEntries.isEmpty()) return emptyList()

        val rows = displayEntries.map { TarEntryRow(it.name, it.size) }

        return listOf(
            tarEntriesDescriptor(entries = rows)
        )
    }
}
