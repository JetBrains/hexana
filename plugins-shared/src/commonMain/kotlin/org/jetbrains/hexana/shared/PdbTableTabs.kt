package org.jetbrains.hexana.shared

import org.jetbrains.hexana.pdb.PdbDbiStream
import org.jetbrains.hexana.pdb.PdbFile
import org.jetbrains.hexana.pdb.PdbModule
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.PDB_STREAMS_TAB
import org.jetbrains.hexana.providers.PDB_SUMMARY_TAB
import org.jetbrains.hexana.providers.PORTABLE_PDB_SUMMARY_TAB
import org.jetbrains.hexana.providers.PdbBinaryProvider
import org.jetbrains.hexana.providers.PortablePdbBinaryProvider
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.providers.pe.clr.ClrPortablePdb
import org.jetbrains.hexana.providers.pe.clr.clrTableDisplayName

/**
 * Table tabs for `.pdb` files. A Windows PDB gets its MSF streams and the DBI view of modules,
 * source files and section contributions; a Portable PDB its `#Pdb` header, documents and the
 * metadata streams and tables the assembly tabs use too.
 */
class PdbTableTabsProvider : TableTabsProvider {

    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> = when (provider) {
        is PdbBinaryProvider -> WINDOWS_TABS.mapNotNull { singleTab(it, provider, TableTabsContext()) }
        is PortablePdbBinaryProvider -> PORTABLE_TABS.mapNotNull { singleTab(it, provider, TableTabsContext()) }
        else -> emptyList()
    }

    /** The names follow from the provider type, so hosts need not parse to list them. */
    override fun availableTabNames(provider: BinaryProvider, context: TableTabsContext): Pair<List<String>, List<String>>? = when (provider) {
        is PdbBinaryProvider -> WINDOWS_TABS to emptyList()
        is PortablePdbBinaryProvider -> PORTABLE_TABS to emptyList()
        else -> null
    }

    override fun singleTab(name: String, provider: BinaryProvider, context: TableTabsContext): TableTabDescriptor? = when (provider) {
        is PdbBinaryProvider -> provider.parsedPdb?.let { pdb ->
            when (name) {
                PDB_SUMMARY_TAB -> pdbSummaryTab(pdb, provider.bytes.size)
                PDB_STREAMS_TAB -> pdbStreamsTab(pdb)
                MODULES_TAB -> pdb.dbi?.let(::pdbModulesTab)
                SOURCE_FILES_TAB -> pdb.dbi?.let(::pdbSourceFilesTab)
                CONTRIBUTIONS_TAB -> pdb.dbi?.let(::pdbSectionContributionsTab)
                else -> null
            }
        } ?: if (name == PDB_SUMMARY_TAB) emptyTab(PDB_SUMMARY_TAB, "(not a readable MSF 7.00 file)") else null
        is PortablePdbBinaryProvider -> {
            val metadata = provider.metadata
            when (name) {
                PORTABLE_PDB_SUMMARY_TAB -> portablePdbSummaryTab(metadata)
                DOCUMENTS_TAB -> metadata.portablePdb?.let(::portablePdbDocumentsTab)
                PDB_STREAMS_TAB -> clrStreamsTab(metadata)
                TABLES_TAB -> clrTablesTab(metadata)
                else -> null
            }
        }
        else -> null
    }
}

private const val MODULES_TAB = "Modules"
private const val SOURCE_FILES_TAB = "Source Files"
private const val CONTRIBUTIONS_TAB = "Section Contributions"
private const val DOCUMENTS_TAB = "Documents"
private const val TABLES_TAB = "Tables"
private val WINDOWS_TABS = listOf(PDB_SUMMARY_TAB, PDB_STREAMS_TAB, MODULES_TAB, SOURCE_FILES_TAB, CONTRIBUTIONS_TAB)
private val PORTABLE_TABS = listOf(PORTABLE_PDB_SUMMARY_TAB, DOCUMENTS_TAB, PDB_STREAMS_TAB, TABLES_TAB)

private fun emptyTab(name: String, message: String) =
    TableTabDescriptor(name = name, columns = emptyList(), rowCount = 0, emptyMessage = message)

// region Windows PDB

private fun pdbSummaryTab(pdb: PdbFile, fileSize: Int): TableTabDescriptor {
    val superBlock = pdb.superBlock.range
    val infoRange = pdb.stream(1)?.fileRange(0, pdb.stream(1)!!.size)
    val dbiRange = pdb.stream(3)?.fileRange(0, 64)
    val rows = buildList {
        add(HeaderRow("Format", "Windows PDB (MSF 7.00)", superBlock))
        add(HeaderRow("Block Size", pdb.superBlock.blockSize.toString(), superBlock))
        add(HeaderRow("Blocks", "${pdb.superBlock.blockCount} (${pdb.superBlock.blockCount.toLong() * pdb.superBlock.blockSize} bytes, file has $fileSize)", superBlock))
        add(HeaderRow("Streams", pdb.streams.size.toString(), pdb.blockMapRange))
        pdb.info?.let { info ->
            add(HeaderRow("PDB Version", pdbVersionName(info.version), infoRange))
            info.guid?.let { add(HeaderRow("GUID", it, infoRange)) }
            add(HeaderRow("Age", info.age.toString(), infoRange))
            add(HeaderRow("Signature", "${hex(info.signature)} (${info.signature})", infoRange))
            if (info.features.isNotEmpty()) add(HeaderRow("Features", info.features.joinToString { it.label }, infoRange))
        }
        pdb.dbi?.let { dbi ->
            add(HeaderRow("DBI Version", dbiVersionName(dbi.version), dbiRange))
            add(HeaderRow("DBI Age", dbi.age.toString(), dbiRange))
            add(HeaderRow("Machine", machineName(dbi.machine), dbiRange))
            add(HeaderRow("Toolchain", "${dbi.buildMajor}.${dbi.buildMinor}" + (if (dbi.pdbDllVersion != 0) " (mspdb ${dbi.pdbDllVersion}.${dbi.pdbDllRebuild})" else ""), dbiRange))
            add(HeaderRow("Flags", dbiFlags(dbi), dbiRange))
            add(HeaderRow("Modules", dbi.modules.size.toString(), dbi.substreams.firstOrNull()?.fileRange))
            add(HeaderRow("Source Files", dbi.modules.flatMap { it.sourceFiles }.distinct().size.toString(), null))
        }
        pdb.tpi?.let { add(HeaderRow("Type Records (TPI)", "${it.recordCount} (${it.typeRecordBytes} bytes)", pdb.stream(2)?.fileRange(0, 56))) }
        pdb.ipi?.let { add(HeaderRow("Id Records (IPI)", "${it.recordCount} (${it.typeRecordBytes} bytes)", pdb.stream(4)?.fileRange(0, 56))) }
        pdb.info?.namedStreams?.forEach { (name, index) -> add(HeaderRow("Named Stream", "$name → stream $index", null)) }
        pdb.diagnostics.forEach { add(HeaderRow("Warning", it.message, it.range)) }
    }
    return keyValueTab(PDB_SUMMARY_TAB, rows, labelWidth = ColumnWidth.Fill(0.3f))
}

private fun pdbStreamsTab(pdb: PdbFile): TableTabDescriptor {
    val streams = pdb.streams
    if (streams.isEmpty()) return emptyTab(PDB_STREAMS_TAB, "(no readable stream directory)")
    return TableTabDescriptor(
        name = PDB_STREAMS_TAB,
        columns = listOf(
            col("Index") { CellValue(streams[it].index) },
            col("Contents", ColumnWidth.Fill(0.4f)) { CellValue(pdb.streamRoles[streams[it].index] ?: "") },
            col("Size") { CellValue(streams[it].size.toLong(), if (streams[it].isNil) "nil" else streams[it].size.toString()) },
            col("Blocks") { CellValue(streams[it].blocks.size) },
            col("File Offset", ColumnWidth.Fill()) {
                val offset = streams[it].fileOffset(0)
                CellValue(offset?.toLong() ?: -1L, offset?.let(::hex) ?: "")
            },
        ),
        rowCount = streams.size,
        rowSelectionProvider = { index -> streams.getOrNull(index)?.let { it.fileRange(0, it.size) }?.let(::selection) },
        showIdColumn = false,
    )
}

private fun pdbModulesTab(dbi: PdbDbiStream): TableTabDescriptor {
    val modules = dbi.modules
    if (modules.isEmpty()) return emptyTab(MODULES_TAB, "(no modules)")
    return TableTabDescriptor(
        name = MODULES_TAB,
        columns = listOf(
            col("Index") { CellValue(modules[it].index) },
            col("Module", ColumnWidth.Fill(0.32f)) { CellValue(modules[it].moduleName) },
            col("Object File", ColumnWidth.Fill(0.32f)) { CellValue(modules[it].objectFileName.takeIf { name -> name != modules[it].moduleName }.orEmpty()) },
            col("Stream") { CellValue(modules[it].symbolStream ?: -1, modules[it].symbolStream?.toString().orEmpty()) },
            col("Symbols") { CellValue(modules[it].symbolByteSize.toLong()) },
            col("Lines (C13)") { CellValue(modules[it].c13ByteSize.toLong()) },
            col("Files") { CellValue(modules[it].sourceFiles.size) },
            col("Contribution", ColumnWidth.Fill()) { CellValue(contributionLabel(modules[it])) },
        ),
        rowCount = modules.size,
        rowSelectionProvider = { modules.getOrNull(it)?.fileRange?.let(::selection) },
        alwaysShowSearch = modules.size > 20,
        searchPlaceholder = "Search modules",
        showIdColumn = false,
    )
}

private fun pdbSourceFilesTab(dbi: PdbDbiStream): TableTabDescriptor {
    val rows = dbi.modules.flatMap { module -> module.sourceFiles.map { module to it } }
    if (rows.isEmpty()) return emptyTab(SOURCE_FILES_TAB, "(no source files)")
    return TableTabDescriptor(
        name = SOURCE_FILES_TAB,
        columns = listOf(
            col("File", ColumnWidth.Fill(0.6f)) { CellValue(rows[it].second) },
            col("Module", ColumnWidth.Fill()) { CellValue(rows[it].first.moduleName) },
        ),
        rowCount = rows.size,
        rowSelectionProvider = { rows.getOrNull(it)?.first?.fileRange?.let(::selection) },
        alwaysShowSearch = true,
        searchPlaceholder = "Search source files",
        showIdColumn = false,
    )
}

private fun pdbSectionContributionsTab(dbi: PdbDbiStream): TableTabDescriptor {
    val contributions = dbi.sectionContributions
    if (contributions.isEmpty()) return emptyTab(CONTRIBUTIONS_TAB, "(no section contributions)")
    return TableTabDescriptor(
        name = CONTRIBUTIONS_TAB,
        columns = listOf(
            col("Section") { CellValue(contributions[it].section) },
            col("Offset") { CellValue(contributions[it].offset.toLong(), hex(contributions[it].offset)) },
            col("Size") { CellValue(contributions[it].size.toLong(), hex(contributions[it].size)) },
            col("Characteristics") { CellValue(contributions[it].characteristics.toLong(), hex(contributions[it].characteristics)) },
            col("Module", ColumnWidth.Fill()) {
                val module = dbi.modules.getOrNull(contributions[it].moduleIndex)
                CellValue(module?.moduleName ?: "#${contributions[it].moduleIndex}")
            },
        ),
        rowCount = contributions.size,
        rowSelectionProvider = { contributions.getOrNull(it)?.fileRange?.let(::selection) },
        showIdColumn = false,
    )
}

private fun contributionLabel(module: PdbModule): String {
    val contribution = module.contribution
    if (contribution.section == 0 || contribution.section == 0xFFFF) return ""
    return "${contribution.section.toString(16).uppercase().padStart(4, '0')}:${contribution.offset.toUInt().toString(16).uppercase().padStart(8, '0')} (${contribution.size} bytes)"
}

private fun pdbVersionName(version: UInt): String = when (version) {
    19941610u -> "VC2"
    19950623u -> "VC4"
    19950814u -> "VC41"
    19960307u -> "VC50"
    19970604u -> "VC98"
    19990604u -> "VC70 (deprecated)"
    20000404u -> "VC70"
    20030901u -> "VC80"
    20091201u -> "VC110"
    20140508u -> "VC140"
    else -> version.toString()
} + " ($version)"

private fun dbiVersionName(version: UInt): String = when (version) {
    930803u -> "VC41"
    19960307u -> "V50"
    19970606u -> "V60"
    19990903u -> "V70"
    20091201u -> "V110"
    else -> version.toString()
} + " ($version)"

private fun dbiFlags(dbi: PdbDbiStream): String = buildList {
    if (dbi.isIncrementallyLinked) add("incrementally linked")
    if (dbi.isStripped) add("stripped (private symbols removed)")
    if (dbi.hasConflictingTypes) add("conflicting types")
}.ifEmpty { listOf("none") }.joinToString()

// endregion

// region Portable PDB

private fun portablePdbSummaryTab(metadata: ClrMetadata): TableTabDescriptor {
    val pdb = metadata.portablePdb
    val rows = buildList {
        add(HeaderRow("Format", "Portable PDB", metadata.root?.range))
        metadata.root?.let { add(HeaderRow("Metadata Version", it.version, it.range)) }
        if (pdb == null) {
            add(HeaderRow("#Pdb Stream", "missing or malformed", null))
        } else {
            val range = pdb.header.range
            add(HeaderRow("PDB Id GUID", pdb.header.guid, range))
            add(HeaderRow("PDB Id Stamp", hex(pdb.header.stamp), range))
            add(HeaderRow("Entry Point", if (pdb.header.entryPointToken == 0u) "none" else "MethodDef ${hex(pdb.header.entryPointToken)}", range))
            add(HeaderRow("Documents", pdb.documents.size.toString(), null))
            add(HeaderRow("Methods With Debug Info", pdb.methodDebugInformationCount.toString(), null))
            pdb.documents.mapNotNull { it.language }.distinct().takeIf { it.isNotEmpty() }?.let {
                add(HeaderRow("Languages", it.joinToString(), null))
            }
            if (pdb.header.referencedTableRows.isNotEmpty()) {
                add(
                    HeaderRow(
                        "Assembly Tables",
                        pdb.header.referencedTableRows.entries.joinToString { (id, rows) -> "${clrTableDisplayName(id)} $rows" },
                        range,
                    ),
                )
            }
        }
        metadata.diagnostics.forEach { add(HeaderRow("Warning", it.message, it.range)) }
    }
    return keyValueTab(PORTABLE_PDB_SUMMARY_TAB, rows, labelWidth = ColumnWidth.Fill(0.3f))
}

private fun portablePdbDocumentsTab(pdb: ClrPortablePdb): TableTabDescriptor {
    val documents = pdb.documents
    if (documents.isEmpty()) return emptyTab(DOCUMENTS_TAB, "(no documents)")
    return TableTabDescriptor(
        name = DOCUMENTS_TAB,
        columns = listOf(
            col("Row") { CellValue(documents[it].row) },
            col("Name", ColumnWidth.Fill(0.45f)) { CellValue(documents[it].name) },
            col("Language") { CellValue(documents[it].language.orEmpty()) },
            col("Hash Algorithm") { CellValue(documents[it].hashAlgorithm.orEmpty()) },
            col("Hash", ColumnWidth.Fill()) { CellValue(documents[it].hash) },
        ),
        rowCount = documents.size,
        rowSelectionProvider = { documents.getOrNull(it)?.recordRange?.let(::selection) },
        alwaysShowSearch = documents.size > 20,
        searchPlaceholder = "Search documents",
        showIdColumn = false,
    )
}

// endregion
