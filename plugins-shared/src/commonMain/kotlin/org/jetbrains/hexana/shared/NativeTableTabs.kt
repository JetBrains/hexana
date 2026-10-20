package org.jetbrains.hexana.shared

import org.jetbrains.hexana.parsing.common.NativeBinaryEndian
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.CoffBinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.FatMachOBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.NativeDebugInfoSummary
import org.jetbrains.hexana.providers.macho.MachOParser

/**
 * Pre-resolved data for the debug tabs that requires filesystem access.
 * Callers should build this outside the rendering path via [scan] and pass
 * it through [TableTabsContext] so that tab descriptor construction never
 * touches the filesystem.
 */
class NativeDebugTabData(
    val debugContext: NativeDebugContext,
    val debugInfoRows: List<Pair<String, String>>?,
) {
    companion object {
        /**
         * Scans the [provider] for debug companions and source-location
         * summary rows using the given [fs] and [projectBasePath].
         * Call this outside the rendering path.
         */
        fun scan(
            provider: NativeBinaryProvider,
            fs: FileSystem? = null,
            projectBasePath: String? = null,
        ): NativeDebugTabData {
            val context = NativeDebugCompanionScanner.scan(provider, fs)
            val debugInfoRows = run {
                val slp = provider.nativeSourceLocationProvider ?: return@run null
                val summary = slp.summary
                if (summary.lineRowCount == 0) return@run null
                buildDebugInfoRows(summary, provider.sourcePath, projectBasePath, fs)
            }
            return NativeDebugTabData(context, debugInfoRows)
        }
    }
}

class NativeTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> =
        tableTabs(provider, TableTabsContext())

    override fun groupedTabs(provider: BinaryProvider): List<GroupedTableTabDescriptor> = when (provider) {
        is PeBinaryProvider -> listOfNotNull(clrMetadataGroup(provider))
        else -> emptyList()
    }

    override fun tableTabs(provider: BinaryProvider, context: TableTabsContext): List<TableTabDescriptor> = when (provider) {
        is ElfBinaryProvider -> elfTabs(provider) + debugTabs(provider, context.nativeDebugData)
        is MachOBinaryProvider -> machOTabs(provider) + debugTabs(provider, context.nativeDebugData)
        is FatMachOBinaryProvider -> fatMachOTabs(provider)
        is PeBinaryProvider -> peTabs(provider) + listOf(debugContextTab(provider, context.nativeDebugData))
        is CoffBinaryProvider -> coffTabs(provider)
        else -> emptyList()
    }

    private fun resolveDebugData(provider: NativeBinaryProvider, debugData: NativeDebugTabData?): NativeDebugTabData {
        if (debugData != null) return debugData
        // Fallback: compute without filesystem access. Callers that need
        // FS-aware companion scanning should pass data through TableTabsContext.
        val context = NativeDebugCompanionScanner.scan(provider, fs = null)
        val debugInfoRows = run {
            val sourceLocationProvider = provider.nativeSourceLocationProvider ?: return@run null
            val summary = sourceLocationProvider.summary
            if (summary.lineRowCount == 0) return@run null
            buildDebugInfoRows(summary, provider.sourcePath, projectBasePath = null, fs = null)
        }
        return NativeDebugTabData(context, debugInfoRows)
    }

    private fun debugTabs(provider: NativeBinaryProvider, debugData: NativeDebugTabData?): List<TableTabDescriptor> {
        val data = resolveDebugData(provider, debugData)
        return listOf(
            debugContextTab(data),
            debugInfoTab(provider, data),
        )
    }

    private fun debugContextTab(provider: NativeBinaryProvider, debugData: NativeDebugTabData?): TableTabDescriptor {
        val data = resolveDebugData(provider, debugData)
        return debugContextTab(data)
    }

    private fun debugContextTab(data: NativeDebugTabData): TableTabDescriptor {
        val rows = data.debugContext.companions
        return TableTabDescriptor(
            name = "Debug Context",
            columns = listOf(
                col("Kind", ColumnWidth.Fill(0.22f)) { CellValue(rows[it].kind.label) },
                col("Status", ColumnWidth.Fill(0.16f)) { CellValue(rows[it].status.label) },
                col("Name", ColumnWidth.Fill(0.30f)) { CellValue(rows[it].name) },
                col("Location", ColumnWidth.Fill(0.45f)) { CellValue(rows[it].location ?: "") },
                col("Notes", ColumnWidth.Fill()) { CellValue(rows[it].description) },
            ),
            rowCount = rows.size,
            rowSelectionProvider = { index ->
                rows.getOrNull(index)?.range?.let { range -> RowSelectionData(range.offset, range.size) }
            },
            prologueText = buildDebugContextPrologueText(data.debugContext),
            emptyMessage = "No debug companion information is available.",
            priority = TabPriority.Low,
        )
    }

    private fun debugInfoTab(provider: NativeBinaryProvider, data: NativeDebugTabData): TableTabDescriptor {
        val sourceLocationProvider = provider.nativeSourceLocationProvider
            ?: return TableTabDescriptor(
                name = "Debug Info",
                columns = emptyList(),
                rowCount = 0,
                emptyMessage = "Debug info: unavailable. No supported native line-table data was found.",
                priority = TabPriority.Low,
            )
        val summary = sourceLocationProvider.summary
        if (summary.lineRowCount == 0) {
            return TableTabDescriptor(
                name = "Debug Info",
                columns = emptyList(),
                rowCount = 0,
                emptyMessage = "Debug info: present, but Hexana did not resolve any source line rows.",
                priority = TabPriority.Low,
            )
        }
        val rows = data.debugInfoRows ?: return TableTabDescriptor(
            name = "Debug Info",
            columns = emptyList(),
            rowCount = 0,
            emptyMessage = "Debug info: present, but Hexana did not resolve any source line rows.",
            priority = TabPriority.Low,
        )
        return TableTabDescriptor(
            name = "Debug Info",
            columns = listOf(
                col("Field", ColumnWidth.Fill(0.24f)) { CellValue(rows[it].first) },
                col("Value", ColumnWidth.Fill()) { CellValue(rows[it].second) },
            ),
            rowCount = rows.size,
            priority = TabPriority.Low,
        )
    }
}

private fun coffTabs(provider: CoffBinaryProvider): List<TableTabDescriptor> = buildList {
    coffSymbolsTab(provider)?.let { add(it) }
}

private fun coffSymbolsTab(provider: CoffBinaryProvider): TableTabDescriptor? {
    val symbols = provider.coffSymbols?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Symbols",
        columns = listOf(
            col("Index") { CellValue(symbols[it].index) },
            col("Name", ColumnWidth.Fill(0.36f)) { CellValue(symbols[it].name.ifBlank { "<unnamed>" }) },
            col("Section") { CellValue(symbols[it].sectionNumber) },
            col("Value") { CellValue(hex(symbols[it].value)) },
            col("Storage") { CellValue(storageClassName(symbols[it].storageClass)) },
            col("Aux", ColumnWidth.Fill()) { CellValue(symbols[it].auxiliaryCount) },
        ),
        rowCount = symbols.size,
        rowSelectionProvider = { symbols.getOrNull(it)?.let { symbol -> selection(symbol.recordRange) } },
    )
}

private fun peTabs(provider: PeBinaryProvider): List<TableTabDescriptor> = buildList {
    peHeadersTab(provider)?.let { add(it) }
    addAll(clrTabs(provider))
    peSectionsTab(provider)?.let { add(it) }
    peImportsTab(provider)?.let { add(it) }
    peExportsTab(provider)?.let { add(it) }
    peRelocationsTab(provider)?.let { add(it) }
    peDebugTab(provider)?.let { add(it) }
    peSymbolsTab(provider)?.let { add(it) }
}

private fun peHeadersTab(provider: PeBinaryProvider): TableTabDescriptor? {
    val dosHeader = provider.dosHeader ?: return null
    val coffHeader = provider.coffHeader ?: return null
    return keyValueTab("Headers", peHeaderRows(dosHeader, coffHeader, provider.optionalHeader), labelWidth = ColumnWidth.Fill(0.38f))
}

private fun peSectionsTab(provider: PeBinaryProvider): TableTabDescriptor? {
    val sections = provider.sections?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Sections",
        columns = listOf(
            col("Index") { CellValue(sections[it].index) },
            col("Name", ColumnWidth.Fill(0.24f)) { CellValue(sections[it].name.ifBlank { "<unnamed>" }) },
            col("RVA") { CellValue(hex(sections[it].virtualAddress)) },
            col("VSize") { CellValue(hex(sections[it].virtualSize)) },
            col("Raw Offset") { CellValue(hex(sections[it].rawDataPointer)) },
            col("Raw Size") { CellValue(hex(sections[it].rawDataSize)) },
            col("Relocs") { CellValue(sections[it].relocationCount) },
            col("Flags", ColumnWidth.Fill()) { CellValue(hex(sections[it].characteristics)) },
        ),
        rowCount = sections.size,
        rowSelectionProvider = { sections.getOrNull(it)?.let { section -> selection(section.contentRange ?: section.headerRange) } },
    )
}

private fun peImportsTab(provider: PeBinaryProvider): TableTabDescriptor? {
    val libraries = provider.imports?.takeIf { it.isNotEmpty() } ?: return null
    val rows = peImportRows(libraries)
    return TableTabDescriptor(
        name = "Imports",
        columns = listOf(
            col("Library", ColumnWidth.Fill(0.26f)) { CellValue(rows[it].library.name.ifBlank { "<unnamed>" }) },
            col("Import", ColumnWidth.Fill(0.34f)) { CellValue(rows[it].entry?.displayName() ?: "<descriptor>") },
            col("Hint") { CellValue(rows[it].entry?.hint?.toString() ?: "") },
            col("ILT RVA") { CellValue(hex(rows[it].library.importLookupTableRva)) },
            col("IAT RVA", ColumnWidth.Fill()) { CellValue(hex(rows[it].library.importAddressTableRva)) },
        ),
        rowCount = rows.size,
        rowSelectionProvider = { index ->
            val row = rows.getOrNull(index) ?: return@TableTabDescriptor null
            selection(row.entry?.hintNameRange ?: row.entry?.thunkRange ?: row.library.nameRange ?: row.library.descriptorRange)
        },
    )
}

private fun peExportsTab(provider: PeBinaryProvider): TableTabDescriptor? {
    val exports = provider.exports?.takeIf { it.entries.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Exports",
        columns = listOf(
            col("Ordinal") { CellValue(exports.entries[it].ordinal.toString()) },
            col("Name", ColumnWidth.Fill(0.36f)) { CellValue(exports.entries[it].name ?: "<ordinal-only>") },
            col("RVA") { CellValue(hex(exports.entries[it].rva)) },
            col("Forwarder", ColumnWidth.Fill()) { CellValue(exports.entries[it].forwarder ?: "") },
        ),
        rowCount = exports.entries.size,
        rowSelectionProvider = { exports.entries.getOrNull(it)?.let { entry -> selection(entry.nameRange ?: entry.addressTableRange) } },
    )
}

private fun peRelocationsTab(provider: PeBinaryProvider): TableTabDescriptor? {
    val relocations = provider.baseRelocations?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Relocations",
        columns = listOf(
            col("Block") { CellValue(relocations[it].blockIndex) },
            col("Entry") { CellValue(relocations[it].entryIndex) },
            col("Type", ColumnWidth.Fill(0.28f)) { CellValue(relocationTypeName(relocations[it].type)) },
            col("Page RVA") { CellValue(hex(relocations[it].pageRva)) },
            col("Target RVA", ColumnWidth.Fill()) { CellValue(hex(relocations[it].targetRva)) },
        ),
        rowCount = relocations.size,
        rowSelectionProvider = { relocations.getOrNull(it)?.let { relocation -> selection(relocation.recordRange) } },
    )
}

private fun peDebugTab(provider: PeBinaryProvider): TableTabDescriptor? {
    val entries = provider.debugEntries?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Debug",
        columns = listOf(
            col("Index") { CellValue(entries[it].index) },
            col("Type") { CellValue(debugTypeName(entries[it].type)) },
            col("Size") { CellValue(entries[it].sizeOfData.toString()) },
            col("PDB", ColumnWidth.Fill(0.42f)) { CellValue(entries[it].codeView?.pdbPath ?: "") },
            col("Signature") { CellValue(entries[it].codeView?.signature ?: "") },
            col("Age", ColumnWidth.Fill()) { CellValue(entries[it].codeView?.age?.toString() ?: "") },
        ),
        rowCount = entries.size,
        rowSelectionProvider = { entries.getOrNull(it)?.let { entry -> selection(entry.dataRange ?: entry.recordRange) } },
    )
}

private fun peSymbolsTab(provider: PeBinaryProvider): TableTabDescriptor? {
    val symbols = provider.coffSymbols?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Symbols",
        columns = listOf(
            col("Index") { CellValue(symbols[it].index) },
            col("Name", ColumnWidth.Fill(0.36f)) { CellValue(symbols[it].name.ifBlank { "<unnamed>" }) },
            col("Section") { CellValue(symbols[it].sectionNumber) },
            col("Value") { CellValue(hex(symbols[it].value)) },
            col("Storage") { CellValue(storageClassName(symbols[it].storageClass)) },
            col("Aux", ColumnWidth.Fill()) { CellValue(symbols[it].auxiliaryCount) },
        ),
        rowCount = symbols.size,
        rowSelectionProvider = { symbols.getOrNull(it)?.let { symbol -> selection(symbol.recordRange) } },
    )
}

private fun fatMachOTabs(provider: FatMachOBinaryProvider): List<TableTabDescriptor> = buildList {
    machOUniversalTab(provider)?.let { add(it) }
}

private fun machOUniversalTab(provider: FatMachOBinaryProvider): TableTabDescriptor? {
    val architectures = provider.architectures?.takeIf { it.isNotEmpty() } ?: return null
    // Precompute each slice's endianness once by peeking the slice's Mach-O
    // header. A universal binary can in principle mix endianness across
    // slices, so we record it per row instead of inheriting from the fat
    // header.
    val sliceEndians: List<NativeBinaryEndian> = architectures.map { arch ->
        val range = arch.sliceRange ?: return@map NativeBinaryEndian.Unknown
        val slice = provider.bytes.slice(range.offset, range.size)
        MachOParser.parseHeader(slice)?.endian ?: NativeBinaryEndian.Unknown
    }
    return TableTabDescriptor(
        name = "Universal",
        columns = listOf(
            col("Index") { CellValue(architectures[it].index) },
            col("Offset") { CellValue(hex(architectures[it].offset)) },
            col("Size") { CellValue(hex(architectures[it].size)) },
            col("Architecture", ColumnWidth.Fill(0.5f)) {
                val a = architectures[it]
                CellValue(machOCpuArchName(a.cpuType, a.cpuSubtype))
            },
            col("Endianness", ColumnWidth.Fill()) { CellValue(endianLabel(sliceEndians[it])) },
        ),
        rowCount = architectures.size,
        rowSelectionProvider = { architectures.getOrNull(it)?.let { arch -> selection(arch.sliceRange ?: arch.archRange) } },
    )
}

private fun machOTabs(provider: MachOBinaryProvider): List<TableTabDescriptor> = buildList {
    machOHeadersTab(provider)?.let { add(it) }
    machOLoadCommandsTab(provider)?.let { add(it) }
    machOSegmentsTab(provider)?.let { add(it) }
    machOSectionsTab(provider)?.let { add(it) }
    machOSymbolsTab(provider)?.let { add(it) }
    machODynamicSymbolsTab(provider)?.let { add(it) }
    machODylibsTab(provider)?.let { add(it) }
    machORelocationsTab(provider)?.let { add(it) }
}

private fun machOHeadersTab(provider: MachOBinaryProvider): TableTabDescriptor? {
    val header = provider.header ?: return null
    return keyValueTab("Headers", machOHeaderRows(header, provider.uuid?.value))
}

private fun machOLoadCommandsTab(provider: MachOBinaryProvider): TableTabDescriptor? {
    val commands = provider.loadCommands?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Load Commands",
        columns = listOf(
            col("Index") { CellValue(commands[it].index) },
            col("Type") { CellValue(loadCommandName(commands[it].type)) },
            col("Raw") { CellValue(hex(commands[it].type)) },
            col("Size", ColumnWidth.Fill()) { CellValue(commands[it].size.toString()) },
        ),
        rowCount = commands.size,
        rowSelectionProvider = { commands.getOrNull(it)?.let { command -> selection(command.range) } },
    )
}

private fun machOSegmentsTab(provider: MachOBinaryProvider): TableTabDescriptor? {
    val segments = provider.segments?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Segments",
        columns = listOf(
            col("Index") { CellValue(segments[it].index) },
            col("Name") { CellValue(segments[it].name.ifBlank { "<unnamed>" }) },
            col("VM Address") { CellValue(hex(segments[it].virtualAddress)) },
            col("VM Size") { CellValue(hex(segments[it].virtualSize)) },
            col("File Offset") { CellValue(hex(segments[it].fileOffset)) },
            col("File Size") { CellValue(hex(segments[it].fileSize)) },
            col("Sections", ColumnWidth.Fill()) { CellValue(segments[it].sectionCount.toString()) },
        ),
        rowCount = segments.size,
        rowSelectionProvider = { segments.getOrNull(it)?.let { segment -> selection(segment.fileRange ?: segment.commandRange) } },
    )
}

private fun machOSectionsTab(provider: MachOBinaryProvider): TableTabDescriptor? {
    val sections = provider.sections?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Sections",
        columns = listOf(
            col("Index") { CellValue(sections[it].index) },
            col("Segment") { CellValue(sections[it].segmentName.ifBlank { "<unnamed>" }) },
            col("Name", ColumnWidth.Fill(0.25f)) { CellValue(sections[it].name.ifBlank { "<unnamed>" }) },
            col("Address") { CellValue(hex(sections[it].address)) },
            col("Offset") { CellValue(hex(sections[it].fileOffset)) },
            col("Size") { CellValue(hex(sections[it].size)) },
            col("Flags", ColumnWidth.Fill()) { CellValue(hex(sections[it].flags)) },
        ),
        rowCount = sections.size,
        rowSelectionProvider = { sections.getOrNull(it)?.let { section -> selection(section.contentRange ?: section.headerRange) } },
    )
}

private fun machOSymbolsTab(provider: MachOBinaryProvider): TableTabDescriptor? {
    val symbols = provider.symbols?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Symbols",
        columns = listOf(
            col("Index") { CellValue(symbols[it].index) },
            col("Name", ColumnWidth.Fill(0.35f)) { CellValue(symbols[it].name.ifBlank { "<unnamed>" }) },
            col("Kind") { CellValue(symbolKind(symbols[it].type)) },
            col("Flags") { CellValue(symbolFlags(symbols[it].type)) },
            col("Section") { CellValue(symbols[it].sectionIndex) },
            col("Value") { CellValue(hex(symbols[it].value)) },
            col("Desc", ColumnWidth.Fill()) { CellValue(hex(symbols[it].description)) },
        ),
        rowCount = symbols.size,
        rowSelectionProvider = { symbols.getOrNull(it)?.let { symbol -> selection(symbol.recordRange) } },
    )
}

private fun machODynamicSymbolsTab(provider: MachOBinaryProvider): TableTabDescriptor? {
    val dysymtab = provider.dysymtab ?: return null
    return keyValueTab(
        "Dynamic Symbols",
        listOf(
            HeaderRow("Local Symbols", "${dysymtab.localSymbolIndex} + ${dysymtab.localSymbolCount}", dysymtab.commandRange),
            HeaderRow("External Defined", "${dysymtab.externalDefinedSymbolIndex} + ${dysymtab.externalDefinedSymbolCount}", dysymtab.commandRange),
            HeaderRow("Undefined", "${dysymtab.undefinedSymbolIndex} + ${dysymtab.undefinedSymbolCount}", dysymtab.commandRange),
            HeaderRow("TOC", "${hex(dysymtab.tableOfContentsOffset)} + ${dysymtab.tableOfContentsCount}", dysymtab.commandRange),
            HeaderRow("Module Table", "${hex(dysymtab.moduleTableOffset)} + ${dysymtab.moduleTableCount}", dysymtab.commandRange),
            HeaderRow("External References", "${hex(dysymtab.externalReferenceSymbolTableOffset)} + ${dysymtab.externalReferenceSymbolTableCount}", dysymtab.commandRange),
            HeaderRow("Indirect Symbols", "${hex(dysymtab.indirectSymbolTableOffset)} + ${dysymtab.indirectSymbolTableCount}", dysymtab.commandRange),
            HeaderRow("External Relocations", "${hex(dysymtab.externalRelocationOffset)} + ${dysymtab.externalRelocationCount}", dysymtab.commandRange),
            HeaderRow("Local Relocations", "${hex(dysymtab.localRelocationOffset)} + ${dysymtab.localRelocationCount}", dysymtab.commandRange),
        ),
        labelWidth = ColumnWidth.Fill(0.45f),
    )
}

private fun machODylibsTab(provider: MachOBinaryProvider): TableTabDescriptor? {
    val dylibs = provider.dylibs?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Dylibs",
        columns = listOf(
            col("Index") { CellValue(dylibs[it].index) },
            col("Kind") { CellValue(dylibKindLabel(dylibs[it].kind)) },
            col("Name", ColumnWidth.Fill(0.55f)) { CellValue(dylibs[it].name.ifBlank { "<unnamed>" }) },
            col("Current") { CellValue(version(dylibs[it].currentVersion)) },
            col("Compat", ColumnWidth.Fill()) { CellValue(version(dylibs[it].compatibilityVersion)) },
        ),
        rowCount = dylibs.size,
        rowSelectionProvider = { dylibs.getOrNull(it)?.let { dylib -> selection(dylib.nameRange ?: dylib.commandRange) } },
    )
}

private fun machORelocationsTab(provider: MachOBinaryProvider): TableTabDescriptor? {
    val relocations = provider.relocations?.takeIf { it.isNotEmpty() } ?: return null
    return TableTabDescriptor(
        name = "Relocations",
        columns = listOf(
            col("Index") { CellValue(relocations[it].index) },
            col("Section", ColumnWidth.Fill(0.22f)) { CellValue(relocations[it].sectionName.ifBlank { relocations[it].sectionIndex.toString() }) },
            col("Address") { CellValue(hex(relocations[it].address)) },
            col("Type") { CellValue(hex(relocations[it].type)) },
            col("Len") { CellValue(relocations[it].length) },
            col("PCRel") { CellValue(if (relocations[it].pcrel) "yes" else "no") },
            col("Target", ColumnWidth.Fill()) { index ->
                val relocation = relocations[index]
                val target = when {
                    relocation.scattered -> relocation.value?.let(::hex) ?: ""
                    relocation.external -> relocation.symbol?.name?.ifBlank { "<unnamed>" } ?: relocation.symbolOrSectionIndex.toString()
                    else -> "section ${relocation.symbolOrSectionIndex}"
                }
                CellValue(target)
            },
        ),
        rowCount = relocations.size,
        rowSelectionProvider = { relocations.getOrNull(it)?.let { relocation -> selection(relocation.recordRange) } },
    )
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

private fun buildDebugInfoRows(
    summary: NativeDebugInfoSummary,
    binaryPath: String?,
    projectBasePath: String?,
    fs: FileSystem?,
): List<Pair<String, String>> = buildList {
    add("Line rows" to summary.lineRowCount.toString())
    add("Source files" to summary.fileCount.toString())
    add("First VA" to (summary.firstAddress?.let { hex(it) } ?: ""))
    add("Last VA" to (summary.lastAddress?.let { hex(it) } ?: ""))
    summary.sampleFiles.forEachIndexed { index, file ->
        val display = resolveNativeSourceDisplay(file, binaryPath, projectBasePath, fs)
        add("File ${index + 1}" to display)
    }
}

/**
 * Resolves a source file path referenced in debug info against the binary's
 * directory and the project base path, returning a display string like
 * "found: /resolved/path" or "missing: original".
 */
private fun resolveNativeSourceDisplay(
    sourceFilePath: String,
    binaryPath: String?,
    projectBasePath: String?,
    fs: FileSystem?,
): String {
    if (fs == null) return "missing: $sourceFilePath"
    val resolved = resolveNativeSourcePath(sourceFilePath, binaryPath, projectBasePath, fs)
    return if (resolved != null && fs.fileExists(resolved)) "found: $resolved" else "missing: $sourceFilePath"
}

/**
 * Tries to locate a source file on the filesystem by checking the raw path,
 * paths relative to the binary directory (and its parent), and relative to
 * the project base path.
 */
fun resolveNativeSourcePath(
    sourceFilePath: String,
    binaryPath: String?,
    projectBasePath: String?,
    fs: FileSystem,
): String? {
    // Check the raw path first (works for absolute paths)
    if (fs.fileExists(sourceFilePath)) return sourceFilePath

    val isAbsolute = sourceFilePath.startsWith("/") || (sourceFilePath.length >= 3 && sourceFilePath[1] == ':')
    if (isAbsolute) return sourceFilePath // absolute but doesn't exist — return as-is for display

    // Try relative to binary directory and its parent
    if (binaryPath != null) {
        val binaryDir = fs.parentDir(binaryPath)
        val resolved = fs.joinPath(binaryDir, sourceFilePath)
        if (fs.fileExists(resolved)) return resolved
        val parentDir = fs.parentDir(binaryDir)
        if (parentDir.isNotEmpty()) {
            val resolvedParent = fs.joinPath(parentDir, sourceFilePath)
            if (fs.fileExists(resolvedParent)) return resolvedParent
        }
    }

    // Try relative to project base path
    if (projectBasePath != null) {
        val resolved = fs.joinPath(projectBasePath, sourceFilePath)
        if (fs.fileExists(resolved)) return resolved
    }

    return null
}
