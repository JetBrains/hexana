package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ZipBinaryProvider

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
    predefinedFilters: List<PredefinedFilter> = emptyList(),
    extraColumns: List<ColumnDescriptor> = emptyList(),
    defaultSortColumn: Int = 0,
    defaultSortAscending: Boolean = true,
    defaultFilterShowAll: Boolean = false,
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
    ) + extraColumns,
    rowCount = entries.size,
    defaultSortColumn = defaultSortColumn,
    defaultSortAscending = defaultSortAscending,
    rowClickAction = onRowClick,
    alwaysShowSearch = true,
    searchPlaceholder = searchPlaceholder,
    emptyMessage = emptyMessage,
    showIdColumn = false,
    predefinedFilters = predefinedFilters,
    defaultFilterShowAll = defaultFilterShowAll,
)

/**
 * [TableTabsProvider] for ZIP / JAR archives.
 *
 * Produces a single "Entries" table tab listing all non-directory entries
 * when the provider is a [ZipBinaryProvider]. The cached entry list from
 * the provider is reused, avoiding redundant parsing. Platform-specific
 * behaviour (entry opening on click) is added by the platform enricher.
 */
class ZipTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> =
        tableTabs(provider, TableTabsContext())

    override fun tableTabs(provider: BinaryProvider, context: TableTabsContext): List<TableTabDescriptor> {
        if (provider !is ZipBinaryProvider) return emptyList()
        val displayEntries = provider.displayEntries
        if (displayEntries.isEmpty()) return emptyList()

        val rows = displayEntries.map { ZipEntryRow(it.name, it.compressedSize, it.uncompressedSize) }
        val isApk = context.fileName?.endsWith(".apk", ignoreCase = true) == true

        if (isApk) {
            val kindColumn = ColumnDescriptor(
                name = "Kind",
                widthBehavior = ColumnWidth.Measured("Resource"),
            ) { index ->
                val name = rows[index].name
                val (sortOrder, label) = apkEntryKind(name)
                CellValue(sortOrder, label)
            }
            val filters = listOf(
                PredefinedFilter("Classes") { index -> apkEntryKind(rows[index].name).second == "Classes" },
                PredefinedFilter("Native") { index -> apkEntryKind(rows[index].name).second == "Native" },
                PredefinedFilter("Resource") { index -> apkEntryKind(rows[index].name).second == "Resource" },
                PredefinedFilter("Other") { index -> apkEntryKind(rows[index].name).second == "Other" },
            )
            return listOf(
                zipEntriesDescriptor(
                    entries = rows,
                    extraColumns = listOf(kindColumn),
                    defaultSortColumn = 3,
                    defaultSortAscending = true,
                    predefinedFilters = filters,
                    defaultFilterShowAll = true,
                )
            )
        }

        val hasClassFiles = rows.any { it.name.endsWith(".class") }
        val filters = if (hasClassFiles) {
            listOf(PredefinedFilter("Classes") { index -> rows[index].name.endsWith(".class") })
        } else emptyList()

        return listOf(
            zipEntriesDescriptor(
                entries = rows,
                predefinedFilters = filters,
            )
        )
    }
}

/**
 * Returns the APK kind sort order and label for the given entry name.
 */
private fun apkEntryKind(name: String): Pair<Int, String> = when {
    name.startsWith("res/") -> 2 to "Resource"
    name.startsWith("lib/") -> 1 to "Native"
    name.endsWith(".dex") -> 0 to "Classes"
    else -> 3 to "Other"
}
