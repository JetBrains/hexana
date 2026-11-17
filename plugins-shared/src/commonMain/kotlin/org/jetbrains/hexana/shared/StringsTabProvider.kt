package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider

class StringsTabProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> =
        stringsTab(provider)?.let(::listOf).orEmpty()
}

private fun stringsTab(provider: BinaryProvider): TableTabDescriptor? {
    val strings = provider.strings.takeIf { it.isNotEmpty() } ?: return null
    var hasOffset = false
    var hasSize = false
    var hasIndex = false
    var hasTag = false
    for (s in strings) {
        if (s.offset != -1) hasOffset = true
        if (s.size != -1) hasSize = true
        if (s.index != -1) hasIndex = true
        if (s.tag != null) hasTag = true
        if (hasOffset && hasSize && hasIndex && hasTag) break
    }
    return TableTabDescriptor(
        name = "Strings",
        columns = listOfNotNull(
            col("Index") { CellValue(strings[it].index) }.takeIf { hasIndex },
            col("Offset") { CellValue(hex(strings[it].offset)) }.takeIf { hasOffset },
            col("Size") { CellValue(strings[it].size) }.takeIf { hasSize },
            col("Tag", ColumnWidth.Fill(0.22f)) { CellValue(strings[it].tag) }.takeIf { hasTag },
            col("Value", ColumnWidth.Fill()) {
                val raw = strings[it].value
                val escaped = escapeControlChars(raw)
                CellValue(escaped, tooltip = if (escaped != raw) raw else null)
            },
        ),
        rowCount = strings.size,
        rowSelectionProvider = { strings.getOrNull(it)?.let { string -> RowSelectionData(string.offset, string.size) } },
        defaultSortColumn = if (hasIndex) 0 else null,
        showIdColumn = !hasIndex,
        alwaysShowSearch = true,
        searchPlaceholder = "Search strings",
        priority = TabPriority.Low,
    )
}
