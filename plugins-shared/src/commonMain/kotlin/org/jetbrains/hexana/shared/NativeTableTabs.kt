package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider

class NativeTableTabsProvider : TableTabsProvider {
    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> = when (provider) {
        is ElfBinaryProvider -> elfTabs(provider)
        is NativeBinaryProvider -> stringsTab(provider)?.let(::listOf).orEmpty()
        else -> emptyList()
    }
}

private fun elfTabs(provider: ElfBinaryProvider): List<TableTabDescriptor> = buildList {
    elfHeadersTab(provider)?.let { add(it) }
    elfSectionsTab(provider)?.let { add(it) }
    elfSegmentsTab(provider)?.let { add(it) }
    elfSymbolsTab(provider)?.let { add(it) }
    elfFunctionsTab(provider)?.let { add(it) }
    elfRelocationsTab(provider)?.let { add(it) }
    elfImportsTab(provider)?.let { add(it) }
    elfExportsTab(provider)?.let { add(it) }
    stringsTab(provider)?.let { add(it) }
}

private fun elfHeadersTab(provider: ElfBinaryProvider): TableTabDescriptor? {
    val header = provider.header ?: return null
    val rows = elfHeaderRows(header, provider.sections)
    return keyValueTab("Headers", rows)
}

private fun elfSectionsTab(provider: ElfBinaryProvider): TableTabDescriptor? {
    val sections = provider.sections?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Sections",
        columns = listOf(
            col("Index") { CellValue(sections[it].index) },
            col("Name", ColumnWidth.Fill(0.35f)) { CellValue(sections[it].name.ifEmpty { "<unnamed>" }) },
            col("Type") { CellValue(hex(sections[it].type)) },
            col("Flags") { CellValue(hex(sections[it].flags)) },
            col("Address") { CellValue(hex(sections[it].address)) },
            col("Offset") { CellValue(hex(sections[it].fileOffset)) },
            col("Size", ColumnWidth.Fill()) { CellValue(sections[it].size.toString()) },
        ),
        rowCount = sections.size,
        rowSelectionProvider = { sections.getOrNull(it)?.let { section -> selection(section.contentRange ?: section.headerRange) } },
    )
}

private fun elfSegmentsTab(provider: ElfBinaryProvider): TableTabDescriptor? {
    val segments = provider.programHeaders?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Segments",
        columns = listOf(
            col("Index") { CellValue(segments[it].index) },
            col("Type") { CellValue(hex(segments[it].type)) },
            col("Flags") { CellValue(hex(segments[it].flags)) },
            col("Offset") { CellValue(hex(segments[it].fileOffset)) },
            col("VAddr") { CellValue(hex(segments[it].virtualAddress)) },
            col("File Size") { CellValue(segments[it].fileSize.toString()) },
            col("Mem Size") { CellValue(segments[it].memorySize.toString()) },
            col("Align", ColumnWidth.Fill()) { CellValue(hex(segments[it].alignment)) },
        ),
        rowCount = segments.size,
        rowSelectionProvider = { segments.getOrNull(it)?.let { segment -> selection(segment.fileRange ?: segment.headerRange) } },
    )
}

private fun elfFunctionsTab(provider: ElfBinaryProvider): TableTabDescriptor? {
    val functions = provider.nativeFunctions?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Functions",
        columns = listOf(
            col("Index") { CellValue(functions[it].symbolIndex) },
            col("Name", ColumnWidth.Fill(0.35f)) { CellValue(functions[it].name) },
            col("Demangled", ColumnWidth.Fill(0.35f)) { CellValue(functions[it].demangledName ?: "") },
            col("Kind") { CellValue(functions[it].kindLabel()) },
            col("Source") { CellValue(functions[it].source.name) },
            col("Section") { CellValue(functions[it].sectionName ?: functions[it].sectionIndex.toString()) },
            col("Address") { CellValue(hex(functions[it].virtualAddress)) },
            col("Offset") { CellValue(functions[it].fileOffset?.let(::hex) ?: "") },
            col("Size") { CellValue(functions[it].size?.toString() ?: "") },
            col("Range", ColumnWidth.Fill()) { CellValue(functions[it].rangeConfidence.name) },
        ),
        rowCount = functions.size,
        rowSelectionProvider = { functions.getOrNull(it)?.selection() },
    )
}

private fun elfSymbolsTab(provider: ElfBinaryProvider): TableTabDescriptor? {
    val symbols = provider.symbols?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Symbols",
        columns = listOf(
            col("Index") { CellValue(symbols[it].index) },
            col("Name", ColumnWidth.Fill(0.35f)) { CellValue(symbols[it].name.ifEmpty { "<unnamed>" }) },
            col("Bind") { CellValue(symbolBinding(symbols[it].binding)) },
            col("Type") { CellValue(symbolType(symbols[it].type)) },
            col("Section") { CellValue(symbols[it].sectionIndex) },
            col("Value") { CellValue(hex(symbols[it].value)) },
            col("Size", ColumnWidth.Fill()) { CellValue(symbols[it].size.toString()) },
        ),
        rowCount = symbols.size,
        rowSelectionProvider = { symbols.getOrNull(it)?.let { symbol -> selection(symbol.recordRange) } },
    )
}

private fun elfRelocationsTab(provider: ElfBinaryProvider): TableTabDescriptor? {
    val relocations = provider.relocations?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Relocations",
        columns = listOf(
            col("Index") { CellValue(relocations[it].index) },
            col("Section") { CellValue(relocations[it].relocationSectionIndex) },
            col("Target") { CellValue(relocations[it].targetSectionIndex) },
            col("Offset") { CellValue(hex(relocations[it].offset)) },
            col("Type") { CellValue(hex(relocations[it].type)) },
            col("Symbol", ColumnWidth.Fill(0.4f)) { index ->
                val relocation = relocations[index]
                CellValue(relocation.symbol?.name?.ifEmpty { "<unnamed>" } ?: relocation.symbolIndex.toString())
            },
            col("Addend", ColumnWidth.Fill()) { CellValue(relocations[it].addend?.toString() ?: "") },
        ),
        rowCount = relocations.size,
        rowSelectionProvider = { relocations.getOrNull(it)?.let { relocation -> selection(relocation.recordRange) } },
    )
}

private fun elfImportsTab(provider: ElfBinaryProvider): TableTabDescriptor? {
    val imports = provider.dynamicEntries?.filter { it.neededLibrary != null }?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Imports",
        columns = listOf(
            col("Index") { CellValue(imports[it].index) },
            col("Library", ColumnWidth.Fill()) { CellValue(imports[it].neededLibrary ?: "") },
        ),
        rowCount = imports.size,
        rowSelectionProvider = { imports.getOrNull(it)?.let { entry -> selection(entry.recordRange) } },
    )
}

private fun elfExportsTab(provider: ElfBinaryProvider): TableTabDescriptor? {
    val sections = provider.sections ?: return null
    val symbols = provider.symbols ?: return null
    val exports = symbols.filter { symbol ->
        val section = sections.getOrNull(symbol.tableSectionIndex)
        section?.type == SHT_DYNSYM && symbol.sectionIndex != 0 && (symbol.binding == 1 || symbol.binding == 2)
    }.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Exports",
        columns = listOf(
            col("Index") { CellValue(exports[it].index) },
            col("Name", ColumnWidth.Fill(0.35f)) { CellValue(exports[it].name.ifEmpty { "<unnamed>" }) },
            col("Bind") { CellValue(symbolBinding(exports[it].binding)) },
            col("Type") { CellValue(symbolType(exports[it].type)) },
            col("Section") { CellValue(exports[it].sectionIndex) },
            col("Value") { CellValue(hex(exports[it].value)) },
            col("Size", ColumnWidth.Fill()) { CellValue(exports[it].size.toString()) },
        ),
        rowCount = exports.size,
        rowSelectionProvider = { exports.getOrNull(it)?.let { export -> selection(export.recordRange) } },
    )
}

private fun stringsTab(provider: NativeBinaryProvider): TableTabDescriptor? {
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
