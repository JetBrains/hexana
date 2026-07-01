package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider

class StringsTabProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> =
        stringsTab(provider)?.let(::listOf).orEmpty()
}

private fun stringsTab(provider: BinaryProvider): TableTabDescriptor? {
    val strings = provider.strings.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Strings",
        columns = listOf(
            col("Offset") { CellValue(hex(strings[it].offset)) },
            col("Size") { CellValue(strings[it].size) },
            col("Value", ColumnWidth.Fill()) { CellValue(strings[it].value) },
        ),
        rowCount = strings.size,
        rowSelectionProvider = { strings.getOrNull(it)?.let { string -> RowSelectionData(string.offset, string.size) } },
    )
}
