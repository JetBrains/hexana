package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import org.jetbrains.hexana.mcp.model.binaries.BinaryOmittedResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryRecordAttributeResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryRecordLocationResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryRecordResult
import org.jetbrains.hexana.mcp.model.binaries.BinaryWarningResult
import org.jetbrains.hexana.mcp.model.binaries.QueryBinaryResult
import org.jetbrains.hexana.mcp.toModuleIdentity
import org.jetbrains.hexana.providers.ArchiveBinaryProvider
import org.jetbrains.hexana.providers.CoffBinaryProvider
import org.jetbrains.hexana.providers.ElfBinaryProvider
import org.jetbrains.hexana.providers.FatMachOBinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.MachOBinaryProvider
import org.jetbrains.hexana.providers.NativeBinaryProvider
import org.jetbrains.hexana.providers.NativeFunctionRecord
import org.jetbrains.hexana.providers.NativeImageInfo
import org.jetbrains.hexana.providers.PeBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.nativeOrGenericBinaryProvider
import org.jetbrains.hexana.providers.elf.ElfSectionHeader
import org.jetbrains.hexana.providers.elf.ElfSymbol
import org.jetbrains.hexana.providers.macho.MachOFatArch
import org.jetbrains.hexana.providers.macho.MachOLoadCommand
import org.jetbrains.hexana.providers.macho.MachOSection
import org.jetbrains.hexana.providers.macho.MachOSymbol
import org.jetbrains.hexana.providers.pe.PeCoffSymbol
import org.jetbrains.hexana.providers.pe.PeDebugEntry
import org.jetbrains.hexana.providers.pe.PeImportLibrary
import org.jetbrains.hexana.providers.pe.PeSection
import org.jetbrains.hexana.wasm.components.WasmComponentExport
import org.jetbrains.hexana.wasm.components.WasmComponentImport
import org.jetbrains.hexana.wasm.sections.WasmExternalType
import org.jetbrains.hexana.wasm.sections.WasmExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmGlobalType
import org.jetbrains.hexana.wasm.sections.WasmImportRecord
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmMemoryExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmSection
import org.jetbrains.hexana.wasm.sections.WasmTableExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType
import org.jetbrains.hexana.wasm.sections.WasmTagExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType
import org.jetbrains.hexana.wasm.sections.WasmUnsupportedExportRecordEntry

private const val DEFAULT_OPERATION = "overview"
private const val DEFAULT_DETAIL = "summary"

fun org.jetbrains.hexana.providers.BinaryProvider.readBinaryQuery(
    operation: String = DEFAULT_OPERATION,
    entityTypes: List<String> = emptyList(),
    query: List<String> = emptyList(),
    offset: Int = 0,
    limit: Int = HexanaAnalysisLimits.DEFAULT_QUERY_LIMIT,
    detail: String = DEFAULT_DETAIL,
    maxBytes: Int = HexanaAnalysisLimits.DEFAULT_QUERY_OUTPUT_BYTES,
    maxOutputBytesLimit: Int = HexanaAnalysisLimits.MAX_OUTPUT_BYTES,
    pathLabel: String? = null,
): QueryBinaryResult {
    if (offset < 0) {
        throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "`offset` must be non-negative")
    }

    val warnings = mutableListOf<BinaryWarningResult>()
    val normalizedOperation = normalizeOperation(operation)
    val normalizedDetail = detail.lowercase().ifBlank { DEFAULT_DETAIL }
    if (normalizedDetail !in setOf("summary", "evidence")) {
        throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "`detail` must be `summary` or `evidence`"
        )
    }

    val boundedLimit = limit.coerceIn(1, HexanaAnalysisLimits.MAX_QUERY_LIMIT)
    val configuredMaxBytes = maxOutputBytesLimit
        .coerceAtLeast(1)
        .coerceAtMost(HexanaAnalysisLimits.MAX_OUTPUT_BYTES)
    val boundedMaxBytes = maxBytes.coerceIn(1, configuredMaxBytes)
    if (boundedLimit != limit) {
        warnings += BinaryWarningResult("clamped_limit", "`limit` was clamped to $boundedLimit.")
    }
    if (boundedMaxBytes != maxBytes) {
        warnings += BinaryWarningResult("clamped_max_bytes", "`maxBytes` was clamped to $boundedMaxBytes.")
    }

    val effectiveOperation = if (isSupportedOperation(normalizedOperation)) {
        normalizedOperation
    } else {
        warnings += BinaryWarningResult(
            "unsupported_operation",
            "Unsupported `operation=$normalizedOperation`; returned `overview` instead."
        )
        "overview"
    }
    val operationRecords = recordsForOperation(effectiveOperation, normalizedDetail, warnings)
    val entityFilter = normalizeEntityTypes(entityTypes, warnings)
    val entityFilterSpecified = entityTypes.any { it.isNotBlank() }
    val entityRecords = if (effectiveOperation == "overview") {
        operationRecords
    } else {
        operationRecords.filterByEntityTypes(entityFilter, entityFilterSpecified)
    }
    if (operationRecords.isNotEmpty() && entityFilterSpecified && entityRecords.isEmpty()) {
        warnings += BinaryWarningResult(
            "no_matching_entity_types",
            "No records matched `entityTypes=${entityTypes.joinToString(",") { it.trim() }}`."
        )
    }
    val terms = query.map { it.trim() }.filter { it.isNotEmpty() }
    val filteredRecords = entityRecords.filterByTerms(terms)
    val pageRecords = filteredRecords.drop(offset).take(boundedLimit).withDetailLevel(normalizedDetail)
    val budgetedRecords = budgetRecords(pageRecords, boundedMaxBytes)
    if (budgetedRecords.size < pageRecords.size) {
        warnings += BinaryWarningResult(
            "max_bytes_truncated",
            "Result truncated to fit `maxBytes=$boundedMaxBytes`; refine `operation`, `query`, or use `detail=summary`."
        )
    }

    val omitted = buildList {
        if (offset > 0) {
            add(BinaryOmittedResult("records", minOf(offset, filteredRecords.size), "before requested `offset`"))
        }
        val afterPage = filteredRecords.size - offset - budgetedRecords.size
        if (afterPage > 0) {
            val reason = if (budgetedRecords.size < pageRecords.size) {
                "outside `limit`/`maxBytes` bounds"
            } else {
                "outside `limit` bounds"
            }
            add(BinaryOmittedResult("records", afterPage, reason))
        }
    }

    val nextOffset = (offset + budgetedRecords.size).takeIf { it < filteredRecords.size }
    return QueryBinaryResult(
        module = toModuleIdentity(pathLabel),
        operation = effectiveOperation,
        total = filteredRecords.size,
        offset = offset,
        returned = budgetedRecords.size,
        nextOffset = nextOffset,
        records = budgetedRecords,
        omitted = omitted,
        warnings = warnings,
    )
}

private fun String.nonBlankOrNull(): String? = trim().takeIf { it.isNotEmpty() }

private fun normalizeOperation(operation: String): String =
    when (val normalized = operation.lowercase().trim().replace('-', '_')) {
        "", "overview", "summary" -> "overview"
        "all" -> "all"
        "find", "search" -> "find"
        "function" -> "functions"
        "import" -> "imports"
        "export" -> "exports"
        "section" -> "sections"
        "symbol" -> "symbols"
        "string" -> "strings"
        "segment" -> "segments"
        "relocation" -> "relocations"
        "debug_info", "debugs" -> "debug"
        "nativeimage", "native_images", "nativeimage_info", "native_image_info" -> "native_image"
        "load_command", "loadcommands" -> "load_commands"
        "archive_member", "member", "members" -> "archive_members"
        else -> normalized
    }

private fun isSupportedOperation(operation: String): Boolean =
    operation in setOf(
        "overview",
        "all",
        "find",
        "functions",
        "imports",
        "exports",
        "sections",
        "symbols",
        "strings",
        "segments",
        "relocations",
        "load_commands",
        "archive_members",
        "debug",
        "native_image",
    )

private fun org.jetbrains.hexana.providers.BinaryProvider.recordsForOperation(
    operation: String,
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> {
    val available = when (operation) {
        "overview" -> overviewRecords(warnings)
        "all" -> allQueryableRecords(detail, warnings)
        "find" -> allQueryableRecords(detail, warnings)
        "functions" -> functionRecords(detail, warnings)
        "imports" -> importRecords(detail, warnings)
        "exports" -> exportRecords(detail, warnings)
        "sections" -> sectionRecords(detail, warnings)
        "symbols" -> symbolRecords(detail, warnings)
        "strings" -> stringRecords()
        "segments" -> segmentRecords(detail, warnings)
        "relocations" -> relocationRecords(warnings)
        "load_commands" -> loadCommandRecords(detail, warnings)
        "archive_members" -> archiveMemberRecords(detail, warnings)
        "debug" -> debugRecords(detail, warnings)
        "native_image" -> nativeImageRecords(detail, warnings)
        else -> emptyList()
    }
    if (available.isEmpty() && operation != "overview") {
        warnings += BinaryWarningResult("no_records", "No `$operation` records are available for this binary format.")
    }
    return available
}

private fun org.jetbrains.hexana.providers.BinaryProvider.overviewRecords(
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> {
    val summary = readBinarySummary()
    warnings += summary.warnings
    val attrs = buildList {
        addAttr("format", summary.module.format)
        addAttr("displayFormat", summary.module.displayFormat)
        addAttr("byteLength", summary.module.byteLength)
        summary.module.architecture?.let { addAttr("architecture", it) }
        summary.module.endian?.let { addAttr("endian", it) }
        summary.module.wordSize?.let { addAttr("wordSize", it) }
        with(summary.counts) {
            functions?.let { addAttr("functions", it) }
            types?.let { addAttr("types", it) }
            memories?.let { addAttr("memories", it) }
            globals?.let { addAttr("globals", it) }
            tables?.let { addAttr("tables", it) }
            symbols?.let { addAttr("symbols", it) }
            imports?.let { addAttr("imports", it) }
            exports?.let { addAttr("exports", it) }
            sections?.let { addAttr("sections", it) }
            segments?.let { addAttr("segments", it) }
            loadCommands?.let { addAttr("loadCommands", it) }
            architectures?.let { addAttr("architectures", it) }
            coreModules?.let { addAttr("coreModules", it) }
            nestedComponents?.let { addAttr("nestedComponents", it) }
            relocations?.let { addAttr("relocations", it) }
            strings?.let { addAttr("strings", it) }
            archiveMembers?.let { addAttr("archiveMembers", it) }
        }
    }
    return listOf(
        BinaryRecordResult(
            kind = "overview",
            summary = "${summary.module.displayFormat} binary, ${summary.module.byteLength} bytes",
            attributes = attrs,
        )
    )
}

private fun org.jetbrains.hexana.providers.BinaryProvider.allQueryableRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    functionRecords(detail, warnings) +
        importRecords(detail, warnings) +
        exportRecords(detail, warnings) +
        sectionRecords(detail, warnings) +
        symbolRecords(detail, warnings) +
        stringRecords() +
        segmentRecords(detail, warnings) +
        relocationRecords(warnings) +
        loadCommandRecords(detail, warnings) +
        archiveMemberRecords(detail, warnings) +
        debugRecords(detail, warnings) +
        nativeImageRecords(detail, warnings)

private fun org.jetbrains.hexana.providers.BinaryProvider.functionRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    when (this) {
        is WasmBinaryProvider -> wasmFunctionRecords(detail)
        is NativeBinaryProvider -> nativeFunctionRecords(detail, warnings)
        else -> emptyList()
    }

private fun WasmBinaryProvider.wasmFunctionRecords(detail: String): List<BinaryRecordResult> {
    val importedFunctions = imports.filter { it.type is WasmTypeIndexExternalType }
    val localFunctions = functions
    return importedFunctions.mapIndexed { index, record ->
        val typeidx = (record.type as WasmTypeIndexExternalType).index
        BinaryRecordResult(
            kind = "function",
            name = "${record.prefix}.${record.name}",
            summary = "imported function[$index] `${record.prefix}.${record.name}` type[$typeidx]",
            location = BinaryRecordLocationResult(index = index, fileOffset = record.offset.toLong(), size = record.size.toLong()),
            attributes = listOf(
                attr("source", "import"),
                attr("module", record.prefix),
                attr("import", record.name),
                attr("typeidx", typeidx),
            ),
            evidence = if (detail == "evidence") listOf("import record offset=${record.offset} size=${record.size}") else emptyList(),
        )
    } + localFunctions.mapIndexed { codeIndex, record ->
        val globalIndex = importedFunctions.size + codeIndex
        BinaryRecordResult(
            kind = "function",
            name = nameSection?.functionSubsection?.namemap?.get(globalIndex),
            summary = "local function[$globalIndex] type[$record]",
            location = codeOffsets.getOrNull(codeIndex)?.let {
                BinaryRecordLocationResult(index = globalIndex, fileOffset = it.offset.toLong(), size = it.size.toLong())
            },
            attributes = listOf(
                attr("source", "code"),
                attr("typeidx", record),
            ),
            evidence = if (detail == "evidence") {
                listOfNotNull(
                    "code index=$codeIndex",
                    codeOffsets.getOrNull(codeIndex)?.let { "code offset=${it.offset} size=${it.size}" },
                )
            } else {
                emptyList()
            },
        )
    }
}

private fun NativeBinaryProvider.nativeFunctionRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> {
    val functions = nativeFunctions ?: run {
        warnings += BinaryWarningResult("partial_parse", "Could not discover native functions.")
        return emptyList()
    }
    return functions.map { it.toRecord(detail) }
}

private fun NativeFunctionRecord.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "function",
        name = demangledName ?: name,
        summary = buildString {
            append(if (imported) "imported " else "defined ")
            append("function")
            append(name.nonBlankOrNull()?.let { " `$it`" }.orEmpty())
            append(" at ")
            append(hex(virtualAddress))
        },
        location = BinaryRecordLocationResult(
            index = symbolIndex,
            fileOffset = fileOffset,
            virtualAddress = virtualAddress.toLongOrNull(),
            size = size?.toLongOrNull(),
            section = sectionName,
        ),
        attributes = buildList {
            addAttr("source", source.name)
            addAttr("imported", imported)
            addAttr("thunkLike", thunkLike)
            addAttr("rangeConfidence", rangeConfidence.name)
            addAttr("sectionIndex", sectionIndex)
            if (demangledName != null && demangledName != name) addAttr("mangledName", name)
        },
        evidence = if (detail == "evidence") {
            listOf("symbolIndex=$symbolIndex", "recordOffset=${recordRange.start}", "recordSize=${recordRange.length}")
        } else {
            emptyList()
        },
    )

private fun org.jetbrains.hexana.providers.BinaryProvider.importRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    when (this) {
        is WasmBinaryProvider -> imports.mapIndexed { index, record -> record.toRecord(index, detail) }
        is WasmComponentBinaryProvider -> imports.mapIndexed { index, record -> record.toRecord(index) }
        is PeBinaryProvider -> peImportRecords(detail, warnings)
        is MachOBinaryProvider -> dylibImportRecords(detail, warnings)
        else -> emptyList()
    }

private fun WasmImportRecord.toRecord(index: Int, detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "import",
        name = "$prefix.$name",
        summary = "import[$index] `$prefix.$name` ${type.kindName()}",
        location = BinaryRecordLocationResult(index = index, fileOffset = offset.toLong(), size = size.toLong()),
        attributes = listOf(attr("module", prefix), attr("name", name), attr("kind", type.kindName())),
        evidence = if (detail == "evidence") listOf("record offset=$offset size=$size") else emptyList(),
    )

private fun WasmComponentImport.toRecord(index: Int): BinaryRecordResult =
    BinaryRecordResult(
        kind = "import",
        name = importName,
        summary = "component import[$index] `$importName`",
        location = BinaryRecordLocationResult(index = index, fileOffset = offset.toLong(), size = size.toLong()),
        attributes = listOfNotNull(version?.let { attr("version", it) }),
    )

private fun PeBinaryProvider.peImportRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> {
    val libraries = imports ?: run {
        warnings += BinaryWarningResult("partial_parse", "Could not parse PE imports.")
        return emptyList()
    }
    return libraries.flatMap { library -> library.toRecords(detail) }
}

private fun PeImportLibrary.toRecords(detail: String): List<BinaryRecordResult> =
    entries.map { entry ->
        val name = entry.name ?: entry.ordinal?.let { "#$it" }
        BinaryRecordResult(
            kind = "import",
            name = listOfNotNull(this.name, name).joinToString("!"),
            summary = "PE import `${this.name}!${name ?: "<unnamed>"}`",
            location = BinaryRecordLocationResult(
                index = entry.index,
                fileOffset = entry.thunkRange.start,
                virtualAddress = entry.thunkRva.toLong(),
                size = entry.thunkRange.length,
            ),
            attributes = buildList {
                addAttr("library", this@toRecords.name)
                entry.hint?.let { addAttr("hint", it) }
                entry.ordinal?.let { addAttr("ordinal", it) }
            },
            evidence = if (detail == "evidence") {
                listOf("descriptorOffset=${descriptorRange.start}", "thunkOffset=${entry.thunkRange.start}")
            } else {
                emptyList()
            },
        )
    }

private fun MachOBinaryProvider.dylibImportRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> {
    val libraries = dylibs ?: run {
        warnings += BinaryWarningResult("partial_parse", "Could not parse Mach-O dylib load commands.")
        return emptyList()
    }
    return libraries.map {
        BinaryRecordResult(
            kind = "import",
            name = it.name,
            summary = "Mach-O ${it.kind.name.lowercase()} dylib `${it.name}`",
            location = BinaryRecordLocationResult(index = it.index, fileOffset = it.commandRange.start, size = it.commandRange.length),
            attributes = listOf(attr("kind", it.kind.name), attr("type", hex(it.type))),
            evidence = if (detail == "evidence") listOf("commandOffset=${it.commandRange.start}") else emptyList(),
        )
    }
}

private fun org.jetbrains.hexana.providers.BinaryProvider.exportRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    when (this) {
        is WasmBinaryProvider -> exports.mapIndexed { index, record -> record.toRecord(index, detail) }
        is WasmComponentBinaryProvider -> exports.mapIndexed { index, record -> record.toRecord(index) }
        is PeBinaryProvider -> peExportRecords(detail, warnings)
        else -> emptyList()
    }

private fun WasmExportRecordEntry.toRecord(index: Int, detail: String): BinaryRecordResult {
    val externIndex = externIndex()
    val kind = externType.kindName()
    return BinaryRecordResult(
        kind = "export",
        name = name,
        summary = "export[$index] `$name` is $kind[$externIndex]",
        location = BinaryRecordLocationResult(index = index, fileOffset = offset.toLong(), size = size.toLong()),
        attributes = listOf(attr("kind", kind), attr("externIndex", externIndex)),
        evidence = if (detail == "evidence") listOf("record offset=$offset size=$size") else emptyList(),
    )
}

private fun WasmComponentExport.toRecord(index: Int): BinaryRecordResult =
    BinaryRecordResult(
        kind = "export",
        name = exportName,
        summary = "component export[$index] `$exportName`",
        location = BinaryRecordLocationResult(index = index, fileOffset = offset.toLong(), size = size.toLong()),
        attributes = listOfNotNull(version?.let { attr("version", it) }),
    )

private fun PeBinaryProvider.peExportRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> {
    val exportDirectory = exports ?: return emptyList()
    return exportDirectory.entries.map { entry ->
        BinaryRecordResult(
            kind = "export",
            name = entry.name,
            summary = "PE export ${entry.name?.let { "`$it` " }.orEmpty()}ordinal ${entry.ordinal}",
            location = BinaryRecordLocationResult(
                index = entry.index,
                fileOffset = entry.addressTableRange.start,
                virtualAddress = entry.rva.toLong(),
                size = entry.addressTableRange.length,
            ),
            attributes = buildList {
                addAttr("ordinal", entry.ordinal)
                entry.forwarder?.let { addAttr("forwarder", it) }
            },
            evidence = if (detail == "evidence") listOf("directoryOffset=${exportDirectory.directoryRange.start}") else emptyList(),
        )
    }.also {
        if (it.isEmpty()) warnings += BinaryWarningResult("no_records", "PE export directory has no export entries.")
    }
}

private fun org.jetbrains.hexana.providers.BinaryProvider.sectionRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    when (this) {
        is WasmBinaryProvider -> wasmModule?.sections.orEmpty().mapIndexed { index, section -> section.toRecord(index, detail) }
        is WasmComponentBinaryProvider -> wasmModule?.sections.orEmpty().mapIndexed { index, section -> section.toRecord(index, detail) }
        is ElfBinaryProvider -> sections?.map { it.toRecord(detail) } ?: warnMissing(warnings, "Could not parse ELF sections.")
        is MachOBinaryProvider -> sections?.map { it.toRecord(detail) } ?: warnMissing(warnings, "Could not parse Mach-O sections.")
        is PeBinaryProvider -> sections?.map { it.toRecord(detail) } ?: warnMissing(warnings, "Could not parse PE sections.")
        is CoffBinaryProvider -> sections?.map { it.toRecord(detail) } ?: warnMissing(warnings, "Could not parse COFF sections.")
        else -> emptyList()
    }

private fun WasmSection.toRecord(index: Int, detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "section",
        name = name,
        summary = "Wasm section[$index] `$name`",
        location = BinaryRecordLocationResult(index = index, fileOffset = sectionOffset.toLong(), size = sectionSize.toLong()),
        attributes = listOf(attr("contentOffset", contentOffset), attr("contentSize", contentSize)),
        evidence = if (detail == "evidence") listOf("sectionRange=$sectionRange", "contentRange=$contentRange") else emptyList(),
    )

private fun ElfSectionHeader.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "section",
        name = name,
        summary = "ELF section[$index] `${name.ifBlank { "<unnamed>" }}`",
        location = BinaryRecordLocationResult(index = index, fileOffset = fileOffset.toLongOrNull(), virtualAddress = address.toLongOrNull(), size = size.toLongOrNull(), section = name),
        attributes = listOf(attr("type", hex(type)), attr("flags", hex(flags)), attr("alignment", addressAlignment)),
        evidence = if (detail == "evidence") listOf("headerOffset=${headerRange.start}", "headerSize=${headerRange.length}") else emptyList(),
    )

private fun MachOSection.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "section",
        name = qualifiedName(),
        summary = "Mach-O section[$index] `${qualifiedName()}`",
        location = BinaryRecordLocationResult(index = index, fileOffset = fileOffset.toLongOrNull(), virtualAddress = address.toLongOrNull(), size = size.toLongOrNull(), section = qualifiedName()),
        attributes = listOf(attr("segment", segmentName), attr("flags", hex(flags)), attr("relocationCount", relocationCount)),
        evidence = if (detail == "evidence") listOf("headerOffset=${headerRange.start}", "headerSize=${headerRange.length}") else emptyList(),
    )

private fun PeSection.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "section",
        name = name,
        summary = "PE/COFF section[$index] `${name.ifBlank { "<unnamed>" }}`",
        location = BinaryRecordLocationResult(index = index, fileOffset = rawDataPointer.toLong(), virtualAddress = virtualAddress.toLong(), size = rawDataSize.toLong(), section = name),
        attributes = listOf(attr("virtualSize", virtualSize), attr("characteristics", hex(characteristics)), attr("relocationCount", relocationCount)),
        evidence = if (detail == "evidence") listOf("headerOffset=${headerRange.start}", "headerSize=${headerRange.length}") else emptyList(),
    )

private fun org.jetbrains.hexana.providers.BinaryProvider.symbolRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    when (this) {
        is ElfBinaryProvider -> symbols?.map { it.toRecord(detail) } ?: warnMissing(warnings, "Could not parse ELF symbols.")
        is MachOBinaryProvider -> symbols?.map { it.toRecord(detail) } ?: warnMissing(warnings, "Could not parse Mach-O symbols.")
        is PeBinaryProvider -> coffSymbols?.map { it.toRecord(detail) } ?: emptyList()
        is CoffBinaryProvider -> coffSymbols?.map { it.toRecord(detail) } ?: warnMissing(warnings, "Could not parse COFF symbols.")
        is ArchiveBinaryProvider -> archiveNestedSymbolRecords(detail, warnings)
        else -> emptyList()
    }

private fun ArchiveBinaryProvider.archiveNestedSymbolRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> {
    val archive = archiveFile ?: run {
        warnings += BinaryWarningResult("partial_parse", "Could not parse archive members.")
        return emptyList()
    }
    return archive.members.filterNot { it.isBookkeeping }.flatMap { member ->
        val classification = classifyMember(member)
        if (!classification.openable) return@flatMap emptyList()

        val memberBytes = memberBytes(member) ?: return@flatMap emptyList()
        val memberProvider = nativeOrGenericBinaryProvider(memberBytes, "${sourcePath ?: "<archive>"}::${member.name}")
        memberProvider.symbolRecords(detail, warnings).map { record ->
            record.copy(
                summary = "archive member `${member.name}` ${record.summary}",
                attributes = buildList {
                    add(attr("archiveMember", member.name))
                    add(attr("archiveMemberKind", classification.kind.name.lowercase()))
                    addAll(record.attributes)
                },
                evidence = if (detail == "evidence") {
                    listOf("archiveMember=${member.name}", "memberContentOffset=${member.contentRange.start}") + record.evidence
                } else {
                    record.evidence
                },
            )
        }
    }
}

private fun ElfSymbol.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "symbol",
        name = name,
        summary = "ELF symbol[$index] `${name.ifBlank { "<unnamed>" }}`",
        location = BinaryRecordLocationResult(index = index, fileOffset = recordRange.start, virtualAddress = value.toLongOrNull(), size = size.toLongOrNull()),
        attributes = listOf(attr("binding", binding), attr("type", type), attr("sectionIndex", sectionIndex)),
        evidence = if (detail == "evidence") listOf("recordOffset=${recordRange.start}", "recordSize=${recordRange.length}") else emptyList(),
    )

private fun MachOSymbol.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "symbol",
        name = name,
        summary = "Mach-O symbol[$index] `${name.ifBlank { "<unnamed>" }}`",
        location = BinaryRecordLocationResult(index = index, fileOffset = recordRange.start, virtualAddress = value.toLongOrNull(), size = recordRange.length),
        attributes = listOf(attr("type", type), attr("sectionIndex", sectionIndex), attr("description", description)),
        evidence = if (detail == "evidence") listOf("recordOffset=${recordRange.start}", "recordSize=${recordRange.length}") else emptyList(),
    )

private fun PeCoffSymbol.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "symbol",
        name = name,
        summary = "COFF symbol[$index] `${name.ifBlank { "<unnamed>" }}`",
        location = BinaryRecordLocationResult(index = index, fileOffset = recordRange.start, virtualAddress = value.toLong(), size = recordRange.length),
        attributes = listOf(attr("sectionNumber", sectionNumber), attr("type", type), attr("storageClass", storageClass)),
        evidence = if (detail == "evidence") listOf("recordOffset=${recordRange.start}", "recordSize=${recordRange.length}") else emptyList(),
    )

private fun org.jetbrains.hexana.providers.BinaryProvider.stringRecords(): List<BinaryRecordResult> =
    strings.mapIndexed { index, record ->
        BinaryRecordResult(
            kind = "string",
            name = record.value.take(80),
            summary = "string[$index] `${record.value.take(80)}`",
            location = BinaryRecordLocationResult(index = index, fileOffset = record.offset.toLong(), size = record.size.toLong()),
        )
    }

private fun org.jetbrains.hexana.providers.BinaryProvider.segmentRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    when (this) {
        is ElfBinaryProvider -> programHeaders?.mapIndexed { index, header ->
            BinaryRecordResult(
                kind = "segment",
                summary = "ELF program header[$index] type=${hex(header.type)}",
                location = BinaryRecordLocationResult(index = index, fileOffset = header.fileOffset.toLongOrNull(), virtualAddress = header.virtualAddress.toLongOrNull(), size = header.fileSize.toLongOrNull()),
                attributes = listOf(attr("flags", hex(header.flags)), attr("memorySize", header.memorySize), attr("alignment", header.alignment)),
                evidence = if (detail == "evidence") listOf("headerOffset=${header.headerRange.start}") else emptyList(),
            )
        } ?: warnMissing(warnings, "Could not parse ELF program headers.")
        is MachOBinaryProvider -> segments?.map {
            BinaryRecordResult(
                kind = "segment",
                name = it.name,
                summary = "Mach-O segment[${it.index}] `${it.name.ifBlank { "<unnamed>" }}`",
                location = BinaryRecordLocationResult(index = it.index, fileOffset = it.fileOffset.toLongOrNull(), virtualAddress = it.virtualAddress.toLongOrNull(), size = it.fileSize.toLongOrNull()),
                attributes = listOf(attr("virtualSize", it.virtualSize), attr("sectionCount", it.sectionCount), attr("flags", hex(it.flags))),
                evidence = if (detail == "evidence") listOf("commandOffset=${it.commandRange.start}") else emptyList(),
            )
        } ?: warnMissing(warnings, "Could not parse Mach-O segments.")
        is FatMachOBinaryProvider -> architectures?.map { it.toRecord(detail) }
            ?: warnMissing(warnings, "Could not parse Mach-O universal slices.")
        else -> emptyList()
    }

private fun MachOFatArch.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "segment",
        name = "slice-$index",
        summary = "Mach-O universal slice[$index] cpu=$cpuType",
        location = BinaryRecordLocationResult(index = index, fileOffset = offset.toLongOrNull(), size = size.toLongOrNull()),
        attributes = listOf(attr("cpuType", cpuType), attr("cpuSubtype", cpuSubtype), attr("alignment", alignment)),
        evidence = if (detail == "evidence") listOf("archOffset=${archRange.start}", "archSize=${archRange.length}") else emptyList(),
    )

private fun org.jetbrains.hexana.providers.BinaryProvider.relocationRecords(
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    when (this) {
        is ElfBinaryProvider -> relocations?.mapIndexed { index, relocation ->
            BinaryRecordResult(
                kind = "relocation",
                name = relocation.symbol?.name,
                summary = "ELF relocation[$index] offset=${hex(relocation.offset)}",
                location = BinaryRecordLocationResult(index = index, fileOffset = relocation.recordRange.start, virtualAddress = relocation.offset.toLongOrNull(), size = relocation.recordRange.length),
                attributes = listOf(
                    attr("type", relocation.type),
                    attr("relocationSectionIndex", relocation.relocationSectionIndex),
                    attr("targetSectionIndex", relocation.targetSectionIndex),
                    attr("symbolIndex", relocation.symbolIndex),
                ),
            )
        } ?: warnMissing(warnings, "Could not parse ELF relocations.")
        is MachOBinaryProvider -> relocations?.map { relocation ->
            BinaryRecordResult(
                kind = "relocation",
                name = relocation.symbol?.name,
                summary = "Mach-O relocation[${relocation.index}] `${relocation.sectionName}` address=${hex(relocation.address)}",
                location = BinaryRecordLocationResult(index = relocation.index, fileOffset = relocation.recordRange.start, virtualAddress = relocation.address.toLong(), size = relocation.recordRange.length, section = relocation.sectionName),
                attributes = listOf(attr("type", relocation.type), attr("external", relocation.external), attr("pcrel", relocation.pcrel)),
            )
        } ?: warnMissing(warnings, "Could not parse Mach-O relocations.")
        is PeBinaryProvider -> baseRelocations?.map { relocation ->
            BinaryRecordResult(
                kind = "relocation",
                summary = "PE base relocation block[${relocation.blockIndex}] entry[${relocation.entryIndex}] targetRva=${hex(relocation.targetRva)}",
                location = BinaryRecordLocationResult(index = relocation.entryIndex, fileOffset = relocation.recordRange.start, virtualAddress = relocation.targetRva.toLong(), size = relocation.recordRange.length),
                attributes = listOf(attr("blockIndex", relocation.blockIndex), attr("type", relocation.type), attr("pageRva", hex(relocation.pageRva))),
            )
        } ?: warnMissing(warnings, "Could not parse PE base relocations.")
        else -> emptyList()
    }

private fun org.jetbrains.hexana.providers.BinaryProvider.loadCommandRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    when (this) {
        is MachOBinaryProvider -> loadCommands?.map { it.toRecord(detail) }
            ?: warnMissing(warnings, "Could not parse Mach-O load commands.")
        else -> emptyList()
    }

private fun MachOLoadCommand.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "load_command",
        summary = "Mach-O load command[$index] type=${hex(type)}",
        location = BinaryRecordLocationResult(index = index, fileOffset = range.offset.toLong(), size = range.size.toLong()),
        attributes = listOf(attr("type", hex(type)), attr("size", size)),
        evidence = if (detail == "evidence") listOf("commandOffset=${range.offset}") else emptyList(),
    )

private fun org.jetbrains.hexana.providers.BinaryProvider.archiveMemberRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    when (this) {
        is ArchiveBinaryProvider -> {
            val archive = archiveFile ?: run {
                warnings += BinaryWarningResult("partial_parse", "Could not parse archive members.")
                return emptyList()
            }
            archive.members.filterNot { it.isBookkeeping }.map { member ->
                val classification = classifyMember(member)
                BinaryRecordResult(
                    kind = "archive_member",
                    name = member.name,
                    summary = "archive member[${member.index}] `${member.name}` ${classification.displayName}",
                    location = BinaryRecordLocationResult(index = member.index, fileOffset = member.contentRange.start, size = member.contentRange.length),
                    attributes = listOf(
                        attr("kind", classification.kind.name.lowercase()),
                        attr("openable", classification.openable),
                        attr("mode", member.mode),
                    ),
                    evidence = if (detail == "evidence") listOf("headerOffset=${member.headerRange.start}") else emptyList(),
                )
            }
        }
        else -> emptyList()
    }

private fun org.jetbrains.hexana.providers.BinaryProvider.debugRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> =
    when (this) {
        is WasmBinaryProvider -> wasmModule?.sections.orEmpty()
            .filter { it.name.isDebugLikeSectionName() }
            .mapIndexed { index, section -> section.toDebugRecord(index, detail) }
        is WasmComponentBinaryProvider -> wasmModule?.sections.orEmpty()
            .filter { it.name.isDebugLikeSectionName() }
            .mapIndexed { index, section -> section.toDebugRecord(index, detail) }
        is ElfBinaryProvider -> sections?.filter { it.name.isDebugLikeSectionName() }?.map { it.toDebugRecord(detail) }
            ?: warnMissing(warnings, "Could not parse ELF sections for debug metadata.")
        is MachOBinaryProvider -> buildList {
            uuid?.let { uuid ->
                add(
                    BinaryRecordResult(
                        kind = "debug",
                        name = "uuid",
                        summary = "Mach-O UUID ${uuid.value}",
                        location = BinaryRecordLocationResult(fileOffset = uuid.uuidRange.start, size = uuid.uuidRange.length),
                        attributes = listOf(attr("uuid", uuid.value), attr("source", "LC_UUID")),
                        evidence = if (detail == "evidence") {
                            listOf("commandOffset=${uuid.commandRange.start}", "uuidOffset=${uuid.uuidRange.start}")
                        } else {
                            emptyList()
                        },
                    )
                )
            }
            val debugSections = sections?.filter { it.name.isDebugLikeSectionName() } ?: run {
                warnings += BinaryWarningResult("partial_parse", "Could not parse Mach-O sections for debug metadata.")
                emptyList()
            }
            addAll(debugSections.map { it.toDebugRecord(detail) })
        }
        is PeBinaryProvider -> peDebugRecords(detail, warnings)
        else -> emptyList()
    }

private fun WasmSection.toDebugRecord(index: Int, detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "debug",
        name = name,
        summary = "Wasm debug/custom section[$index] `$name`",
        location = BinaryRecordLocationResult(index = index, fileOffset = sectionOffset.toLong(), size = sectionSize.toLong()),
        attributes = listOf(attr("section", name), attr("contentSize", contentSize)),
        evidence = if (detail == "evidence") listOf("sectionRange=$sectionRange", "contentRange=$contentRange") else emptyList(),
    )

private fun ElfSectionHeader.toDebugRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "debug",
        name = name,
        summary = "ELF debug section[$index] `${name.ifBlank { "<unnamed>" }}`",
        location = BinaryRecordLocationResult(index = index, fileOffset = fileOffset.toLongOrNull(), virtualAddress = address.toLongOrNull(), size = size.toLongOrNull(), section = name),
        attributes = listOf(attr("type", hex(type)), attr("flags", hex(flags))),
        evidence = if (detail == "evidence") listOf("headerOffset=${headerRange.start}", "headerSize=${headerRange.length}") else emptyList(),
    )

private fun MachOSection.toDebugRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "debug",
        name = qualifiedName(),
        summary = "Mach-O debug section[${index}] `${qualifiedName()}`",
        location = BinaryRecordLocationResult(index = index, fileOffset = fileOffset.toLongOrNull(), virtualAddress = address.toLongOrNull(), size = size.toLongOrNull(), section = qualifiedName()),
        attributes = listOf(attr("segment", segmentName), attr("flags", hex(flags))),
        evidence = if (detail == "evidence") listOf("headerOffset=${headerRange.start}", "headerSize=${headerRange.length}") else emptyList(),
    )

private fun PeBinaryProvider.peDebugRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> {
    val records = debugEntries ?: run {
        warnings += BinaryWarningResult("partial_parse", "Could not parse PE debug directory.")
        return emptyList()
    }
    return records.map { it.toRecord(detail) }
}

private fun PeDebugEntry.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "debug",
        name = codeView?.pdbPath ?: codeView?.signature,
        summary = buildString {
            append("PE debug entry[$index] type=${hex(type)}")
            codeView?.pdbPath?.let { append(" pdb=`").append(it).append('`') }
        },
        location = BinaryRecordLocationResult(index = index, fileOffset = recordRange.start, virtualAddress = addressOfRawData.toLong(), size = recordRange.length),
        attributes = buildList {
            addAttr("type", hex(type))
            addAttr("timeDateStamp", hex(timeDateStamp))
            addAttr("sizeOfData", sizeOfData)
            codeView?.let {
                addAttr("codeViewSignature", it.signature)
                it.guid?.let { guid -> addAttr("guid", guid) }
                it.age?.let { age -> addAttr("age", age) }
                it.pdbPath?.let { path -> addAttr("pdbPath", path) }
            }
        },
        evidence = if (detail == "evidence") {
            listOfNotNull(
                "recordOffset=${recordRange.start}",
                dataRange?.let { "dataOffset=${it.offset} dataSize=${it.size}" },
            )
        } else {
            emptyList()
        },
    )

private fun org.jetbrains.hexana.providers.BinaryProvider.nativeImageRecords(
    detail: String,
    warnings: MutableList<BinaryWarningResult>,
): List<BinaryRecordResult> {
    if (this !is NativeBinaryProvider) return emptyList()
    val info = nativeImageInfo ?: return emptyList()
    if (!info.isNativeImage) return emptyList()
    return listOf(info.toRecord(detail))
}

private fun NativeImageInfo.toRecord(detail: String): BinaryRecordResult =
    BinaryRecordResult(
        kind = "native_image",
        name = version,
        summary = buildString {
            append("GraalVM Native Image")
            version?.let { append(" `").append(it).append('`') }
            platform?.let { append(" for ").append(it) }
        },
        location = sbom?.let { BinaryRecordLocationResult(fileOffset = it.fileOffset, size = it.length) },
        attributes = buildList {
            addAttr("confidence", confidence.name.lowercase())
            addAttr("edition", edition.name.lowercase())
            version?.let { addAttr("version", it) }
            platform?.let { addAttr("platform", it) }
            addAttr("svmSectionCount", svmSections.size)
            sbom?.let {
                addAttr("sbomOffset", it.fileOffset)
                addAttr("sbomLength", it.length)
                addAttr("sbomGzip", it.isGzip)
            }
        },
        evidence = if (detail == "evidence") {
            buildList {
                if (svmSections.isNotEmpty()) add("svmSections=${svmSections.joinToString(",")}")
                sbom?.let { add("sbomOffset=${it.fileOffset} sbomLength=${it.length}") }
            }
        } else {
            emptyList()
        },
    )

private fun String.isDebugLikeSectionName(): Boolean {
    val normalized = trim().lowercase()
    return normalized == "name" ||
        normalized.startsWith(".debug") ||
        normalized.startsWith(".zdebug") ||
        normalized.startsWith("__debug") ||
        normalized.startsWith(".note.gnu.build-id")
}

private fun normalizeEntityTypes(
    rawEntityTypes: List<String>,
    warnings: MutableList<BinaryWarningResult>,
): Set<String> {
    val unknown = mutableListOf<String>()
    val normalized = rawEntityTypes.mapNotNull { raw ->
        when (val value = raw.trim().lowercase().replace('-', '_')) {
            "" -> null
            "function", "functions" -> "function"
            "import", "imports" -> "import"
            "export", "exports" -> "export"
            "section", "sections" -> "section"
            "symbol", "symbols" -> "symbol"
            "string", "strings" -> "string"
            "segment", "segments", "architecture", "architectures", "slice", "slices" -> "segment"
            "relocation", "relocations" -> "relocation"
            "load_command", "load_commands", "loadcommands" -> "load_command"
            "archive_member", "archive_members", "member", "members" -> "archive_member"
            "debug", "debug_info", "debugs" -> "debug"
            "native_image", "native_images", "nativeimage", "native_image_info", "nativeimage_info" -> "native_image"
            "overview", "summary" -> "overview"
            else -> {
                unknown += value
                null
            }
        }
    }.toSet()
    if (unknown.isNotEmpty()) {
        warnings += BinaryWarningResult(
            "unsupported_entity_types",
            "Ignored unsupported `entityTypes`: ${unknown.joinToString(",")}."
        )
    }
    return normalized
}

private fun List<BinaryRecordResult>.filterByEntityTypes(
    entityTypes: Set<String>,
    entityFilterSpecified: Boolean,
): List<BinaryRecordResult> {
    if (!entityFilterSpecified) return this
    return filter { it.kind in entityTypes }
}

private fun List<BinaryRecordResult>.withDetailLevel(detail: String): List<BinaryRecordResult> {
    if (detail == "evidence") return this
    return map {
        if (it.location == null && it.evidence.isEmpty()) {
            it
        } else {
            it.copy(location = null, evidence = emptyList())
        }
    }
}

private fun List<BinaryRecordResult>.filterByTerms(terms: List<String>): List<BinaryRecordResult> {
    if (terms.isEmpty()) return this
    val loweredTerms = terms.map { it.lowercase() }
    return filter { record ->
        val haystack = buildString {
            append(record.kind).append('\n')
            record.name?.let { append(it).append('\n') }
            append(record.summary).append('\n')
            record.location?.let { append(it).append('\n') }
            record.attributes.forEach { append(it.name).append('=').append(it.value).append('\n') }
            record.evidence.forEach { append(it).append('\n') }
        }.lowercase()
        loweredTerms.any { it in haystack }
    }
}

private fun budgetRecords(records: List<BinaryRecordResult>, maxBytes: Int): List<BinaryRecordResult> {
    val accepted = mutableListOf<BinaryRecordResult>()
    var estimated = 600
    for (record in records) {
        val nextEstimate = estimated + record.estimatedBytes()
        if (nextEstimate > maxBytes && accepted.isNotEmpty()) break
        accepted += record
        estimated = nextEstimate
        if (estimated > maxBytes) break
    }
    return accepted
}

private fun BinaryRecordResult.estimatedBytes(): Int {
    val base = 180 + kind.length + summary.length + (name?.length ?: 0)
    val attrs = attributes.sumOf { 32 + it.name.length + it.value.length }
    val evidenceBytes = evidence.sumOf { 16 + it.length }
    return base + attrs + evidenceBytes
}

private fun MutableList<BinaryRecordAttributeResult>.addAttr(name: String, value: Any) {
    add(attr(name, value))
}

private fun attr(name: String, value: Any): BinaryRecordAttributeResult =
    BinaryRecordAttributeResult(name, value.toString())

private fun warnMissing(
    warnings: MutableList<BinaryWarningResult>,
    message: String,
): List<BinaryRecordResult> {
    warnings += BinaryWarningResult("partial_parse", message)
    return emptyList()
}

private fun WasmExternalType.kindName(): String =
    when (this) {
        is WasmTypeIndexExternalType -> "function"
        is WasmTableExternalType -> "table"
        is WasmMemType -> "memory"
        is WasmGlobalType -> "global"
        else -> "extern"
    }

private fun Byte.kindName(): String = when (toInt()) {
    0 -> "function"
    1 -> "table"
    2 -> "memory"
    3 -> "global"
    4 -> "tag"
    else -> "extern:$this"
}

private fun WasmExportRecordEntry.externIndex(): UInt =
    when (this) {
        is WasmFunctionExportRecordEntry -> index
        is WasmTableExportRecordEntry -> index
        is WasmMemoryExportRecordEntry -> index
        is WasmGlobalExportRecordEntry -> index
        is WasmTagExportRecordEntry -> index
        is WasmUnsupportedExportRecordEntry -> index
    }

private fun MachOSection.qualifiedName(): String =
    listOf(segmentName, name).filter { it.isNotBlank() }.joinToString(",").ifBlank { "<unnamed>" }

private fun ULong.toLongOrNull(): Long? =
    takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()

private fun hex(value: UInt): String = "0x${value.toString(16)}"

private fun hex(value: ULong): String = "0x${value.toString(16)}"

private fun hex(value: Int): String = "0x${value.toString(16)}"
