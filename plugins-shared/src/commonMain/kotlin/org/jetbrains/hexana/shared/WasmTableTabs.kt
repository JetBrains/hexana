package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.analysis.MonosResult
import org.jetbrains.hexana.wasm.analysis.exceptions.ThrowingFunction
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageReason
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageResult
import org.jetbrains.hexana.wasm.sections.ParseProblem
import org.jetbrains.hexana.wasm.sections.ParseProblemKind

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
            tagsTab(provider)?.let { add(it) }
            exceptionSurfaceTab(provider)?.let { add(it) }
            customTab(provider)?.let { add(it) }
            problemsTab(provider)?.let { add(it) }
        }
    }

    override fun availableTabNames(provider: BinaryProvider, context: TableTabsContext): Pair<List<String>, List<String>>? {
        if (provider !is WasmBinaryProvider) return Pair(emptyList(), emptyList())
        val module = provider.wasmModule ?: return Pair(emptyList(), emptyList())
        val names = buildList {
            if (module.dataSection != null) add("Data")
            if (module.exportSection != null) add("Exports")
            if (module.importSection != null) add("Imports")
            if (module.codeSection != null) add("Functions")
            // Tag-based EH present: Tags decodes the tag section; Exceptions is
            // the panic-surface analysis (singleTab returns null and the
            // placeholder is removed when the surface is empty).
            if (module.exceptionSection != null) {
                add("Tags")
                add("Exceptions")
            }
            if (module.customSections.isNotEmpty()) add("Custom")
            // parseProblems is lazy; avoid forcing it here — advertise the tab
            // only when there are code-section or type-section anomalies that we
            // can detect cheaply without iterating all opcode records.
            if (module.codeSection != null || module.typesSection != null) add("Problems")
        }
        return Pair(names, emptyList())
    }

    override fun singleTab(name: String, provider: BinaryProvider, context: TableTabsContext): TableTabDescriptor? {
        if (provider !is WasmBinaryProvider) return null
        if (!provider.canProvideWasmModule()) return null
        return when (name) {
            "Data" -> dataTab(provider)
            "Exports" -> exportsTab(provider)
            "Imports" -> importsTab(provider)
            "Functions" -> functionsTab(provider)
            "Tags" -> tagsTab(provider)
            "Exceptions" -> exceptionSurfaceTab(provider)
            "Custom" -> customTab(provider)
            "Problems" -> problemsTab(provider)
            else -> null
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
    // Use codeOffsets (lightweight byte-position scan) instead of provider.functions
    // which triggers expensive parseTypes() for all type definitions.
    val codeOffsets = provider.codeOffsets
    if (codeOffsets.isEmpty()) return null

    // Check whether function names exist without triggering the (potentially huge) parse.
    val hasFunctionNames = provider.nameSection?.functionSubsection != null

    // Defer expensive property accesses to cell render time so the tab descriptor
    // is created instantly even for modules with 100k+ functions.
    val lazyImportFuncCount by lazy { provider.importFuncCount }
    val lazyFunctions by lazy { provider.functions }

    return TableTabDescriptor(
        name = "Functions",
        columns = buildList {
            if (hasFunctionNames) {
                add(ColumnDescriptor(
                    name = "Name",
                    widthBehavior = ColumnWidth.Measured(UNIVERSAL_NAME_MEASURE),
                ) { index ->
                    val functionNamesMap = provider.nameSection?.functionSubsection?.namemap
                    val nameResolved = if (functionNamesMap != null) {
                        resolveFunctionName(functionNamesMap, lazyImportFuncCount + index)
                    } else "func $index"
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
                val typeNames = provider.nameSection?.typesSection?.namemap
                val signatureDescription = lazyFunctions.getOrNull(index)?.getDescription(index, typeNames)
                    ?: "func $index"
                CellValue(signatureDescription, tooltip = signatureDescription)
            })
        },
        rowCount = codeOffsets.size,
        rowSelectionProvider = { index ->
            val entry = codeOffsets.getOrNull(index)
            entry?.let { RowSelectionData(it.offset, it.size) }
        },
    )
}

private fun tagsTab(provider: WasmBinaryProvider): TableTabDescriptor? {
    // Decoded exception tags, like the Types tab decodes type records: one row
    // per tag (imported first) with its origin, attribute, and payload signature.
    val tags = provider.exceptionTags?.entries.orEmpty()
    if (tags.isEmpty()) return null

    return TableTabDescriptor(
        name = "Tags",
        columns = listOf(
            ColumnDescriptor(name = "Tag", widthBehavior = ColumnWidth.Measured("tag 000")) { index ->
                CellValue(tags[index].globalIndex, "tag ${tags[index].globalIndex}")
            },
            ColumnDescriptor(name = "Origin", widthBehavior = ColumnWidth.Measured("imported")) { index ->
                CellValue(if (tags[index].imported) "imported" else "defined")
            },
            ColumnDescriptor(name = "Attribute", widthBehavior = ColumnWidth.Measured("exception")) { index ->
                CellValue(if (tags[index].attribute == 0) "exception" else "attr ${tags[index].attribute}")
            },
            ColumnDescriptor(name = "Payload", widthBehavior = ColumnWidth.Fill()) { index ->
                val p = "${tags[index].payloadText}  (type ${tags[index].typeIdx})"
                CellValue(p, tooltip = p)
            },
        ),
        rowCount = tags.size,
        defaultSortColumn = TableTabDescriptor.ID_COLUMN,
    )
}

private fun exceptionSurfaceTab(provider: WasmBinaryProvider): TableTabDescriptor? {
    // The module's panic surface: exported entry points whose exceptions escape
    // to the host. Empty (all recovered) = no tab. v1 shows for tag-based EH
    // modules; throw_ref-only host-rethrow modules typically recover panics.
    val surface = provider.exceptionPropagation?.uncaughtAtEntry().orEmpty()
    if (surface.isEmpty()) return null
    val tags = provider.exceptionTags
    val importFuncCount = provider.importFuncCount
    val codeOffsets = provider.codeOffsets

    fun uncaughtText(entry: ThrowingFunction): String {
        val parts = entry.thrown.tags.sorted().map { t ->
            tags?.payloadText(t)?.let { "tag $t $it" } ?: "tag $t"
        }
        val all = if (entry.thrown.dynamic) parts + "dynamic (host/throw_ref)" else parts
        return all.joinToString(", ")
    }

    return TableTabDescriptor(
        name = "Exceptions",
        columns = listOf(
            ColumnDescriptor(
                name = "Exported entry",
                widthBehavior = ColumnWidth.Fill(0.4f),
            ) { index ->
                val e = surface[index]
                val label = e.name ?: "func ${e.funcIdx}"
                CellValue(label, tooltip = label)
            },
            ColumnDescriptor(
                name = "Escapes uncaught",
                widthBehavior = ColumnWidth.Fill(),
            ) { index ->
                val text = uncaughtText(surface[index])
                CellValue(text, tooltip = text)
            },
        ),
        rowCount = surface.size,
        rowSelectionProvider = { index ->
            val e = surface.getOrNull(index) ?: return@TableTabDescriptor null
            val codeIdx = e.funcIdx - importFuncCount
            codeOffsets.getOrNull(codeIdx)?.let { RowSelectionData(it.offset, it.size) }
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

private fun problemsTab(provider: WasmBinaryProvider): TableTabDescriptor? {
    // parseProblems is lazy — this forces it. The work is bounded (capped at 1000)
    // and runs on a background thread (singleTab is always called off-EDT).
    return problemsTabDescriptor(provider.parseProblems)
}

/** Problems tab shared by core-module and component providers. */
internal fun problemsTabDescriptor(problems: List<ParseProblem>): TableTabDescriptor? {
    if (problems.isEmpty()) return null

    return TableTabDescriptor(
        name = "Problems",
        columns = listOf(
            col("Kind", ColumnWidth.MeasuredMin("Unknown opcode")) { index ->
                CellValue(problems[index].kind.label())
            },
            col("Location", ColumnWidth.MeasuredMin("func 9999")) { index ->
                val p = problems[index]
                val loc = when {
                    p.funcIdx != null -> "func ${p.funcIdx}"
                    p.typeIdx != null -> "type ${p.typeIdx}"
                    else -> p.sectionName
                }
                CellValue(loc)
            },
            col("Offset", ColumnWidth.MeasuredMin("0x00000000")) { index ->
                val v = hex(problems[index].byteOffset)
                CellValue(problems[index].byteOffset.toLong(), v)
            },
            col("Size", ColumnWidth.MeasuredMin("0x0000")) { index ->
                val v = hex(problems[index].byteSize)
                CellValue(problems[index].byteSize.toLong(), v)
            },
            col("Message", ColumnWidth.Fill()) { index ->
                val msg = problems[index].message
                CellValue(msg, tooltip = msg.takeIf { it.length > 40 })
            },
        ),
        rowCount = problems.size,
        rowSelectionProvider = { index ->
            val p = problems.getOrNull(index) ?: return@TableTabDescriptor null
            RowSelectionData(p.byteOffset, p.byteSize)
        },
        // rowClickAction is left null here; the IntelliJ enricher replaces it
        // for rows with a funcIdx to navigate into the WAT view.
    )
}

private fun ParseProblemKind.label(): String = when (this) {
    ParseProblemKind.LOCALS_PARSE_FAILURE -> "Locals parse failure"
    ParseProblemKind.UNKNOWN_OPCODE -> "Unknown opcode"
    ParseProblemKind.CODE_ENTRY_CORRUPT -> "Code entry corrupt"
    ParseProblemKind.UNKNOWN_TYPE_BYTE -> "Unknown type byte"
    ParseProblemKind.INIT_EXPR_PARSE_FAILURE -> "Init expr failure"
    ParseProblemKind.UNKNOWN_CORE_INSTANCE_TAG -> "Unknown instance tag"
    ParseProblemKind.CORE_INSTANCE_ENTRY_CORRUPT -> "Instance entry corrupt"
    ParseProblemKind.SECTION_TRUNCATED -> "Section truncated"
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
        return listOfNotNull(garbageGroupedTab(provider))
    }

    override fun availableTabNames(provider: BinaryProvider, context: TableTabsContext): Pair<List<String>, List<String>>? {
        if (provider !is WasmBinaryProvider) return Pair(emptyList(), emptyList())
        if (!provider.canProvideWasmModule()) return Pair(emptyList(), emptyList())
        // Both Monos and Garbage require nameSection + codeOffsets which are expensive;
        // assume they will be available if the module has a code section and name section.
        val module = provider.wasmModule ?: return Pair(emptyList(), emptyList())
        val hasCode = module.codeSection != null
        val hasNames = module.getCustomSectionByName("name") != null
        val ordinary = if (hasCode && hasNames) listOf("Monos") else emptyList()
        val grouped = if (hasCode) listOf("Garbage") else emptyList()
        return Pair(ordinary, grouped)
    }

    override fun singleTab(name: String, provider: BinaryProvider, context: TableTabsContext): TableTabDescriptor? {
        if (provider !is WasmBinaryProvider) return null
        if (!provider.canProvideWasmModule()) return null
        return when (name) {
            "Monos" -> monosTab(provider)
            else -> null
        }
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

/**
 * Returns a grouped garbage tab with non-empty subtabs, or `null` when
 * no subtab has data. The rendering layer handles the single-subtab case
 * by promoting it to a plain tab automatically.
 */
private fun garbageGroupedTab(
    provider: WasmBinaryProvider,
): GroupedTableTabDescriptor? {
    val garbage = provider.garbageAnalysis ?: return null
    val innerTabs = buildList {
        garbageFunctionsTab(provider, garbage)?.let { add(it) }
        garbageTypesTab(provider, garbage)?.let { add(it) }
        garbageDataTab(garbage)?.let { add(it) }
    }.filter { it.rowCount > 0 }
    if (innerTabs.isEmpty()) return null
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
