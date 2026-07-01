package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.DexBinaryProvider

class DexTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> = when (provider) {
        is DexBinaryProvider -> dexStringsTab(provider)?.let(::listOf).orEmpty()
        else -> emptyList()
    }
}

private fun dexStringsTab(provider: DexBinaryProvider): TableTabDescriptor? {
    val entries = provider.dexStrings.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Strings",
        columns = listOf(
            col("Index") { CellValue(entries[it].index) },
            col("Value", ColumnWidth.Fill()) {
                val raw = entries[it].value
                val escaped = escapeControlChars(raw)
                CellValue(escaped, tooltip = if (escaped != raw) raw else null)
            },
        ),
        rowCount = entries.size,
    )
}
