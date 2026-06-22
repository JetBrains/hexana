package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider

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

/** Reference measure string for name columns. */
const val UNIVERSAL_NAME_MEASURE = "sdgdsgsd_DSgsdgdsg_DS_gsdgsdGS_GSD_GSD_GDSG__sgdsg_SDG_SDGsdgs_S_G"

/** Reference measure string for qualified name columns. */
const val UNIVERSAL_QUALIFIED_NAME_MEASURE = "component:subtractor11111/environent@2.9.0"
