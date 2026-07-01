package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ClassFileBinaryProvider

class ClassFileTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> = when (provider) {
        is ClassFileBinaryProvider -> constantPoolTab(provider)?.let(::listOf).orEmpty()
        else -> emptyList()
    }
}

private fun constantPoolTab(provider: ClassFileBinaryProvider): TableTabDescriptor? {
    val entries = provider.constantPoolEntries.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Constant Pool",
        columns = listOf(
            col("Index") { CellValue(entries[it].index) },
            col("Tag", ColumnWidth.Fill(0.22f)) { CellValue(entries[it].tag) },
            col("Value", ColumnWidth.Fill()) { CellValue(entries[it].description) },
        ),
        rowCount = entries.size,
    )
}
