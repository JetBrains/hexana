package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.MonosResult
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageReason
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageResult

/**
 * [TableTabsProvider] for core Wasm binaries.
 *
 * Produces table-based tabs: Data, Exports, Imports, Functions, Custom.
 */
class WasmTableTabsProvider : TableTabsProvider {
    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is WasmBinaryProvider) return emptyList()
        if (!provider.canProvideWasmModule()) return emptyList()
        return buildList {
            dataTab(provider)?.let { add(it) }
            exportsTab(provider)?.let { add(it) }
            importsTab(provider)?.let { add(it) }
            functionsTab(provider)?.let { add(it) }
            customTab(provider)?.let { add(it) }
        }
    }
}

private fun dataTab(provider: WasmBinaryProvider): TableTabDescriptor? {
    val dataRecords = provider.data
    if (dataRecords.isEmpty()) return null

    return TableTabDescriptor(
        name = "Data",
        columns = listOf(
            ColumnDescriptor(
                name = "Type",
                widthBehavior = ColumnWidth.MeasuredMin(dataSegmentTypeLabel(1u)),
            ) { index ->
                CellValue(dataRecords[index].type, dataSegmentTypeLabel(dataRecords[index].type))
            },
            ColumnDescriptor(
                name = "Size",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                CellValue(dataRecords[index].recordLength)
            },
        ),
        rowCount = dataRecords.size,
        rowSelectionProvider = { index ->
            val record = dataRecords.getOrNull(index)
            record?.let { RowSelectionData(it.offset, it.recordLength) }
        },
    )
}

private fun exportsTab(provider: WasmBinaryProvider): TableTabDescriptor? {
    val exports = provider.exports
    if (exports.isEmpty()) return null

    val signatureCache = exports.map { export ->
        if (export is org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry) {
            resolveFunctionSignature(provider, export)?.let { sig ->
                formatSignature(sig)
            } ?: ""
        } else ""
    }

    return TableTabDescriptor(
        name = "Exports",
        columns = listOf(
            ColumnDescriptor(
                name = "Kind",
                widthBehavior = ColumnWidth.Measured("function"),
            ) { index ->
                CellValue(exportKindLabel(exports[index]))
            },
            ColumnDescriptor(
                name = "Name",
                widthBehavior = ColumnWidth.Fill(0.5f),
            ) { index ->
                val name = exports[index].name
                CellValue(name, tooltip = name.takeIf { it.isNotEmpty() })
            },
            ColumnDescriptor(
                name = "Signature",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                val sig = signatureCache[index]
                CellValue(sig, tooltip = sig.takeIf { it.isNotEmpty() })
            },
        ),
        rowCount = exports.size,
        defaultSortColumn = TableTabDescriptor.ID_COLUMN,
        rowSelectionProvider = { index ->
            val record = exports.getOrNull(index)
            record?.let { RowSelectionData(it.offset, it.size) }
        },
    )
}

private fun importsTab(provider: WasmBinaryProvider): TableTabDescriptor? {
    val imports = provider.imports
    if (imports.isEmpty()) return null

    return TableTabDescriptor(
        name = "Imports",
        columns = listOf(
            ColumnDescriptor(
                name = "Data",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                val record = imports[index]
                val qualifiedName =
                    if (record.prefix.isNotEmpty()) "${record.prefix}.${record.name}" else record.name
                CellValue(record.name, tooltip = qualifiedName.takeIf { it.isNotEmpty() })
            },
        ),
        rowCount = imports.size,
        defaultSortColumn = TableTabDescriptor.ID_COLUMN,
        rowSelectionProvider = { index ->
            val record = imports.getOrNull(index)
            record?.let { RowSelectionData(it.offset, it.size) }
        },
    )
}

private fun functionsTab(provider: WasmBinaryProvider): TableTabDescriptor? {
    val functions = provider.functions
    if (functions.isEmpty()) return null

    val nameSection = provider.nameSection
    val functionNamesMap = nameSection?.functionSubsection?.namemap
    val typeNames = nameSection?.typesSection?.namemap

    // The name section's function subsection is keyed by the GLOBAL function index (imported
    // functions occupy [0, importFuncCount), defined functions follow), whereas this table's row
    // index is 0-based over defined functions only. Convert before indexing the name map.
    val importFuncCount = provider.importFuncCount

    return TableTabDescriptor(
        name = "Functions",
        columns = buildList {
            if (functionNamesMap != null) {
                add(ColumnDescriptor(
                    name = "Name",
                    widthBehavior = ColumnWidth.Measured(UNIVERSAL_NAME_MEASURE),
                ) { index ->
                    val nameResolved = resolveFunctionName(functionNamesMap, importFuncCount + index)
                    CellValue(
                        nameResolved,
                        tooltip = nameResolved.takeIf { it.length > UNIVERSAL_NAME_MEASURE.length },
                    )
                })
            }
            add(ColumnDescriptor(
                name = "Signature",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                val signatureDescription = functions[index].getDescription(index, typeNames)
                CellValue(signatureDescription, tooltip = signatureDescription)
            })
        },
        rowCount = functions.size,
        rowSelectionProvider = { index ->
            val record = functions.getOrNull(index)
            record?.let { RowSelectionData(it.offset, it.size) }
        },
    )
}

private fun customTab(provider: WasmBinaryProvider): TableTabDescriptor? {
    val customSections = provider.wasmModule?.customSections ?: emptyList()
    if (customSections.isEmpty()) return null

    val sampleName = customSections.maxByOrNull { it.name.length }?.name ?: ""

    val payloads = customSections.map { section ->
        val bytes = provider.readContent(section, 64)
        bytesToAsciiPreview(bytes)
    }

    return TableTabDescriptor(
        name = "Custom",
        columns = listOf(
            ColumnDescriptor(
                name = "Name",
                widthBehavior = ColumnWidth.MeasuredMin(sampleName),
            ) { index ->
                CellValue(customSections[index].name)
            },
            ColumnDescriptor(
                name = "Payload",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                CellValue(payloads[index])
            },
        ),
        rowCount = customSections.size,
        rowSelectionProvider = { index ->
            val section = customSections.getOrNull(index)
            section?.let { RowSelectionData(it.sectionOffset, it.contentSize) }
        },
    )
}

/**
 * [TableTabsProvider] for Wasm profiler tabs (monomorphization analysis)
 * and garbage analysis.
 */
class WasmProfilerTabsProvider : TableTabsProvider {
    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> {
        if (provider !is WasmBinaryProvider) return emptyList()
        if (!provider.canProvideWasmModule()) return emptyList()
        return listOfNotNull(monosTab(provider))
    }

    override fun groupedTabs(provider: BinaryProvider): List<GroupedTableTabDescriptor> {
        if (provider !is WasmBinaryProvider) return emptyList()
        if (!provider.canProvideWasmModule()) return emptyList()
        val garbage = provider.garbageAnalysis ?: return emptyList()
        return listOf(garbageGroupedTab(provider, garbage))
    }
}

private fun monosTab(provider: WasmBinaryProvider): TableTabDescriptor? {
    val monos: MonosResult? = provider.monosAnalysis
    val groups = monos?.groups ?: emptyList()

    if (groups.isEmpty()) return null

    val importFuncCount = provider.importFuncCount
    val codeOffsets = provider.codeOffsets

    return TableTabDescriptor(
        name = "Monos",
        columns = listOf(
            ColumnDescriptor(
                name = "Base Name",
                widthBehavior = ColumnWidth.Measured(UNIVERSAL_NAME_MEASURE),
            ) { index ->
                val g = groups[index]
                CellValue(
                    g.baseName,
                    tooltip = g.baseName.takeIf { it.length > UNIVERSAL_NAME_MEASURE.length },
                )
            },
            ColumnDescriptor(
                name = "Count",
                widthBehavior = ColumnWidth.Measured("Count"),
            ) { index ->
                CellValue(groups[index].count)
            },
            ColumnDescriptor(
                name = "Total Size",
                widthBehavior = ColumnWidth.Measured("00000.0 KB"),
            ) { index ->
                val g = groups[index]
                CellValue(g.totalSize, formatSize(g.totalSize))
            },
            ColumnDescriptor(
                name = "Avg Size",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                val g = groups[index]
                CellValue(g.avgSize, formatSize(g.avgSize))
            },
        ),
        rowCount = groups.size,
        rowSelectionProvider = { rowIndex ->
            val group = groups[rowIndex]
            val firstFuncIdx = group.funcIndices.firstOrNull()
            if (firstFuncIdx != null) {
                val codeIdx = firstFuncIdx - importFuncCount
                codeOffsets.getOrNull(codeIdx)?.let { entry ->
                    RowSelectionData(entry.offset, entry.size)
                }
            } else null
        },
        emptyMessage = "No monomorphizations detected",
    )
}

/** Reference measure string for name columns. */
const val UNIVERSAL_NAME_MEASURE = "sdgdsgsd_DSgsdgdsg_DS_gsdgsdGS_GSD_GSD_GDSG__sgdsg_SDG_SDGsdgs_S_G"

/** Reference measure string for qualified name columns. */
const val UNIVERSAL_QUALIFIED_NAME_MEASURE = "component:subtractor11111/environent@2.9.0"

// ---------------------------------------------------------------------------
// Garbage analysis tabs (grouped under a single "Garbage" outer tab)
// ---------------------------------------------------------------------------

private fun garbageGroupedTab(
    provider: WasmBinaryProvider,
    garbage: GarbageResult,
): GroupedTableTabDescriptor {
    val innerTabs = buildList {
        garbageFunctionsTab(provider, garbage)?.let { add(it) }
        garbageTypesTab(provider, garbage)?.let { add(it) }
        garbageDataTab(garbage)?.let { add(it) }
    }
    return GroupedTableTabDescriptor(
        name = "Garbage",
        innerTabs = innerTabs,
    )
}

private fun garbageFunctionsTab(
    provider: WasmBinaryProvider,
    garbage: GarbageResult,
): TableTabDescriptor? {
    val entries = garbage.entries
    val importFuncCount = provider.importFuncCount
    val codeOffsets = provider.codeOffsets

    val pct = if (garbage.codeSectionTotalBytes > 0) {
        val raw = garbage.totalWastedBytes * 100.0 / garbage.codeSectionTotalBytes
        val rounded = kotlin.math.round(raw * 10) / 10.0
        "${rounded}%"
    } else "\u2014"

    val prologueText = "${entries.size} unreachable, ${formatSizeLong(garbage.totalWastedBytes)} wasted ($pct of code)"

    return TableTabDescriptor(
        name = "Functions (${entries.size})",
        columns = listOf(
            ColumnDescriptor(
                name = "Index",
                widthBehavior = ColumnWidth.Measured("x Index"),
            ) { index ->
                CellValue(entries[index].funcIndex)
            },
            ColumnDescriptor(
                name = "Name",
                widthBehavior = ColumnWidth.Measured(UNIVERSAL_NAME_MEASURE),
            ) { index ->
                val name = entries[index].name
                CellValue(name, tooltip = name.takeIf { it.length > UNIVERSAL_NAME_MEASURE.length })
            },
            ColumnDescriptor(
                name = "Size",
                widthBehavior = ColumnWidth.Measured("00000.0 KB"),
            ) { index ->
                val e = entries[index]
                if (e.size > 0) CellValue(e.size, formatSize(e.size))
                else CellValue(0, "\u2014")
            },
            ColumnDescriptor(
                name = "Module",
                widthBehavior = ColumnWidth.Measured("component:env"),
            ) { index ->
                CellValue(entries[index].module ?: "", representation = entries[index].module ?: "")
            },
            ColumnDescriptor(
                name = "Reason",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                val reason = when (entries[index].reason) {
                    GarbageReason.NO_CALLERS -> "No callers"
                    GarbageReason.CALLERS_UNREACHABLE -> "Callers unreachable"
                }
                CellValue(reason)
            },
        ),
        rowCount = entries.size,
        rowSelectionProvider = { index ->
            val entry = entries[index]
            if (entry.size > 0) {
                val codeIdx = entry.funcIndex - importFuncCount
                codeOffsets.getOrNull(codeIdx)?.let { offset ->
                    RowSelectionData(offset.offset, offset.size)
                }
            } else null
        },
        prologueText = prologueText,
        emptyMessage = "No unreachable functions detected",
    )
}

private fun garbageTypesTab(
    provider: WasmBinaryProvider,
    garbage: GarbageResult,
): TableTabDescriptor? {
    val entries = garbage.typeEntries

    val types = run {
        val typesSection = provider.wasmModule?.typesSection ?: return@run emptyList()
        with(typesSection) { provider.bytes.parseTypes() }
    }
    val typeNames = provider.nameSection?.typesSection?.namemap

    val prologueText = "${entries.size} unreachable types, ${formatSizeLong(garbage.typeWastedBytes)} wasted"

    return TableTabDescriptor(
        name = "Types (${entries.size})",
        columns = listOf(
            ColumnDescriptor(
                name = "Index",
                widthBehavior = ColumnWidth.MeasuredMin("x Index"),
            ) { index ->
                CellValue(entries[index].typeIndex)
            },
            ColumnDescriptor(
                name = "Size",
                widthBehavior = ColumnWidth.MeasuredMin("x 00000 B"),
            ) { index ->
                CellValue(entries[index].size, "${entries[index].size} B")
            },
            ColumnDescriptor(
                name = "Signature",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                val typeIdx = entries[index].typeIndex
                val desc = types.getOrNull(typeIdx)?.getDescription(typeIdx, typeNames) ?: "type[$typeIdx]"
                CellValue(desc, tooltip = desc)
            },
        ),
        rowCount = entries.size,
        prologueText = prologueText,
        emptyMessage = "No unreachable types detected",
    )
}

private fun garbageDataTab(garbage: GarbageResult): TableTabDescriptor? {
    val entries = garbage.dataSegmentEntries

    val prologueText = "${entries.size} data segments, ${formatSizeLong(garbage.dataSegmentWastedBytes)} potential garbage"

    return TableTabDescriptor(
        name = "Data (${entries.size})",
        columns = listOf(
            ColumnDescriptor(
                name = "Index",
                widthBehavior = ColumnWidth.MeasuredMin("x Index"),
            ) { index ->
                CellValue(entries[index].index)
            },
            ColumnDescriptor(
                name = "Data Size",
                widthBehavior = ColumnWidth.MeasuredMin("x Data Size"),
            ) { index ->
                CellValue(entries[index].dataSize, formatSize(entries[index].dataSize))
            },
            ColumnDescriptor(
                name = "Record Size",
                widthBehavior = ColumnWidth.MeasuredMin("x Record Size"),
            ) { index ->
                CellValue(entries[index].recordSize, formatSize(entries[index].recordSize))
            },
        ),
        rowCount = entries.size,
        prologueText = prologueText,
        emptyMessage = "No data segments",
    )
}
