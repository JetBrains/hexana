package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.encodeHexanaJson
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactAttributeResult
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactModuleResult
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactOmittedResult
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactRecordResult
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactSignatureResult
import org.jetbrains.hexana.mcp.model.artifacts.QueryArtifactsResult
import org.jetbrains.hexana.mcp.model.elements.ElementSegmentResult
import org.jetbrains.hexana.mcp.model.exports.ExportResult
import org.jetbrains.hexana.mcp.model.functions.FunctionCallGraphResult
import org.jetbrains.hexana.mcp.model.functions.FunctionSignatureResult
import org.jetbrains.hexana.mcp.model.globals.GlobalResult
import org.jetbrains.hexana.mcp.model.imports.ImportFunctionResult
import org.jetbrains.hexana.mcp.model.memory.MemoryResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmMemType
import org.jetbrains.hexana.wasm.sections.WasmTableExternalType

private const val DEFAULT_LIMIT = 25
private const val MAX_LIMIT = 100
private const val DEFAULT_MAX_BYTES = 24_000
private const val MIN_MAX_BYTES = 2_000
private const val MAX_MAX_BYTES = 60_000

private val SUPPORTED_OPERATIONS = setOf(
    "overview",
    "find",
    "diff",
    "trace",
    "triage",
    "public_surface",
    "memory_contract",
    "dispatch_table_diff",
    "host_call_diff"
)
private val SUPPORTED_DETAILS = setOf("summary", "evidence")
private val DEFAULT_ENTITY_TYPES = setOf("export", "import", "memory", "global", "function")
private val DIFF_ENTITY_TYPES = setOf("export", "import", "memory", "global", "function", "element")
private val TRIAGE_ENTITY_TYPES = setOf("export", "import", "memory", "global", "element")
private val SUPPORTED_TRACE_RELATIONS = setOf("direct_call", "direct_imported_call", "indirect_call", "memory_write")
private val DEFAULT_TRACE_RELATIONS = setOf("direct_call", "indirect_call", "memory_write")
private val TRIAGE_ENTITY_PRIORITY = mapOf(
    "memory" to 0,
    "import" to 1,
    "export" to 2,
    "element" to 3,
    "global" to 4,
    "function" to 5
)
private val TRIAGE_STATUS_PRIORITY = mapOf(
    "changed" to 0,
    "added" to 1,
    "removed" to 2
)

fun WasmBinaryProvider.readArtifactQuery(
    baseline: WasmBinaryProvider? = null,
    operation: String = "overview",
    entityTypes: List<String> = emptyList(),
    query: List<String> = emptyList(),
    startName: String? = null,
    startFuncidx: Int? = null,
    relations: List<String> = emptyList(),
    limit: Int = DEFAULT_LIMIT,
    detail: String = "summary",
    maxBytes: Int = DEFAULT_MAX_BYTES
): QueryArtifactsResult {
    val normalizedOperation = operation.trim().lowercase().ifBlank { "overview" }
    val normalizedDetail = detail.trim().lowercase().ifBlank { "summary" }
    val boundedLimit = limit.coerceIn(1, MAX_LIMIT)
    val boundedMaxBytes = maxBytes.coerceIn(MIN_MAX_BYTES, MAX_MAX_BYTES)
    val warnings = mutableListOf<String>()

    if (normalizedOperation !in SUPPORTED_OPERATIONS) {
        warnings += "Unsupported operation `$operation`; returned overview instead."
    }
    if (normalizedDetail !in SUPPORTED_DETAILS) {
        warnings += "Unsupported detail `$detail`; returned summary detail instead."
    }
    if (limit != boundedLimit) {
        warnings += "`limit` was clamped to $boundedLimit."
    }
    if (maxBytes != boundedMaxBytes) {
        warnings += "`maxBytes` was clamped to $boundedMaxBytes."
    }

    val effectiveOperation = normalizedOperation.takeIf { it in SUPPORTED_OPERATIONS } ?: "overview"
    val effectiveDetail = normalizedDetail.takeIf { it in SUPPORTED_DETAILS } ?: "summary"
    val records = when (effectiveOperation) {
        "overview" -> emptyList()
        "find" -> findRecords(
            entityTypes = entityTypes.normalizedEntityTypes(DEFAULT_ENTITY_TYPES),
            terms = query.normalizedTerms(),
            detail = effectiveDetail
        )
        "diff" -> {
            if (baseline == null) {
                warnings += "`diff` requires `baselinePath`; returned current module records instead."
                findRecords(
                    entityTypes = entityTypes.normalizedEntityTypes(DIFF_ENTITY_TYPES),
                    terms = query.normalizedTerms(),
                    detail = effectiveDetail
                )
            } else {
                diffRecords(
                    baseline = baseline,
                    entityTypes = entityTypes.normalizedEntityTypes(DIFF_ENTITY_TYPES),
                    terms = query.normalizedTerms(),
                    detail = effectiveDetail
                )
            }
        }
        "trace" -> traceRecords(
            startName = startName?.trim()?.takeIf { it.isNotEmpty() },
            startFuncidx = startFuncidx,
            terms = query.normalizedTerms(),
            relations = relations.normalizedRelations(),
            detail = effectiveDetail,
            limit = boundedLimit
        )
        "triage" -> triageRecords(
            baseline = baseline,
            entityTypes = entityTypes.normalizedEntityTypes(TRIAGE_ENTITY_TYPES),
            terms = query.normalizedTerms(),
            detail = effectiveDetail
        )
        "public_surface" -> publicSurfaceRecords(
            terms = query.normalizedTerms(),
            detail = effectiveDetail
        )
        "memory_contract" -> memoryContractRecords(
            baseline = baseline,
            terms = query.normalizedTerms(),
            detail = effectiveDetail
        )
        "dispatch_table_diff" -> {
            if (baseline == null) {
                warnings += "`dispatch_table_diff` requires `baselinePath`; returned current element segments instead."
                findRecords(
                    entityTypes = setOf("element"),
                    terms = query.normalizedTerms(),
                    detail = effectiveDetail
                )
            } else {
                dispatchTableDiffRecords(
                    baseline = baseline,
                    terms = query.normalizedTerms(),
                    detail = effectiveDetail
                )
            }
        }
        "host_call_diff" -> hostCallDiffRecords(
            baseline = baseline,
            startName = startName?.trim()?.takeIf { it.isNotEmpty() },
            startFuncidx = startFuncidx,
            terms = query.normalizedTerms(),
            detail = effectiveDetail,
            limit = boundedLimit
        )
        else -> emptyList()
    }

    val modules = buildList {
        add(toArtifactModuleResult("current"))
        baseline?.let { add(it.toArtifactModuleResult("baseline")) }
    }
    val budgetedRecords = budgetRecords(
        operation = effectiveOperation,
        detail = effectiveDetail,
        modules = modules,
        records = records,
        limit = boundedLimit,
        maxBytes = boundedMaxBytes,
        warnings = warnings
    )

    return QueryArtifactsResult(
        operation = effectiveOperation,
        detail = effectiveDetail,
        modules = modules,
        records = budgetedRecords.records,
        omitted = budgetedRecords.omitted,
        warnings = warnings
    )
}

private fun WasmBinaryProvider.findRecords(
    entityTypes: Set<String>,
    terms: List<String>,
    detail: String
): List<ArtifactRecordResult> {
    return entities(entityTypes, source = "current", detail = detail)
        .filter { it.matches(terms) }
        .map { it.record }
}

private fun WasmBinaryProvider.diffRecords(
    baseline: WasmBinaryProvider,
    entityTypes: Set<String>,
    terms: List<String>,
    detail: String
): List<ArtifactRecordResult> {
    return diffEntityRecords(
        current = entities(entityTypes, source = "current", detail = detail),
        previous = baseline.entities(entityTypes, source = "baseline", detail = detail),
        terms = terms,
        triage = false
    )
}

private fun WasmBinaryProvider.triageRecords(
    baseline: WasmBinaryProvider?,
    entityTypes: Set<String>,
    terms: List<String>,
    detail: String
): List<ArtifactRecordResult> {
    val current = entities(entityTypes, source = "current", detail = detail)
    if (baseline == null) {
        val records = current
            .filter { it.matches(terms) }
            .map { it.record }
            .sortedWith(compareBy<ArtifactRecordResult> { TRIAGE_ENTITY_PRIORITY[it.entityType] ?: Int.MAX_VALUE }
                .thenBy { it.name ?: it.id })
        return listOf(currentSurfaceFinding(records)) + records
    }

    val changes = diffEntityRecords(
        current = current,
        previous = baseline.entities(entityTypes, source = "baseline", detail = detail),
        terms = terms,
        triage = true
    ).sortedWith(
        compareBy<ArtifactRecordResult> { TRIAGE_STATUS_PRIORITY[it.status] ?: Int.MAX_VALUE }
            .thenBy { TRIAGE_ENTITY_PRIORITY[it.entityType] ?: Int.MAX_VALUE }
            .thenBy { it.name ?: it.id }
    )
    return listOf(diffTriageFinding(changes, terms)) + changes
}

private fun diffEntityRecords(
    current: List<EntitySnapshot>,
    previous: List<EntitySnapshot>,
    terms: List<String>,
    triage: Boolean
): List<ArtifactRecordResult> {
    val currentByKey = current.associateBy { it.key }
    val previousByKey = previous.associateBy { it.key }
    val keys = (currentByKey.keys + previousByKey.keys).sorted()

    return keys.mapNotNull { key ->
        val currentEntity = currentByKey[key]
        val previousEntity = previousByKey[key]
        val status = when {
            currentEntity != null && previousEntity == null -> "added"
            currentEntity == null && previousEntity != null -> "removed"
            currentEntity != null && previousEntity != null && currentEntity.comparisonKey != previousEntity.comparisonKey -> "changed"
            else -> "unchanged"
        }
        if (status == "unchanged") return@mapNotNull null
        if (!matchesAny(terms, previousEntity, currentEntity)) return@mapNotNull null

        val selected = currentEntity ?: previousEntity ?: return@mapNotNull null
        selected.record.copy(
            status = status,
            source = when (status) {
                "removed" -> "baseline"
                else -> "current"
            },
            summary = if (triage) {
                triageSummary(status, selected, previousEntity, currentEntity)
            } else {
                when (status) {
                    "added" -> "Added ${selected.record.summary}"
                    "removed" -> "Removed ${selected.record.summary}"
                    else -> "Changed ${selected.record.summary}"
                }
            },
            attributes = if (triage) {
                triageAttributes(previousEntity, currentEntity, selected.record.attributes)
            } else {
                diffAttributes(previousEntity, currentEntity, selected.record.attributes)
            }
        )
    }
}

private fun WasmBinaryProvider.publicSurfaceRecords(
    terms: List<String>,
    detail: String
): List<ArtifactRecordResult> {
    val snapshots = entities(setOf("export"), source = "current", detail = detail)
    val records = snapshots.filterEntitySnapshotsByTermsOrAll(terms)
        .map { it.record }
        .sortedWith(
            compareBy<ArtifactRecordResult> { publicSurfaceKindPriority(it.kind) }
                .thenBy { it.name ?: it.id }
        )
    return listOf(publicSurfaceFinding(records)) + records
}

private fun WasmBinaryProvider.memoryContractRecords(
    baseline: WasmBinaryProvider?,
    terms: List<String>,
    detail: String
): List<ArtifactRecordResult> {
    val current = entities(setOf("memory"), source = "current", detail = detail)
    if (baseline == null) {
        val records = current.filterEntitySnapshotsByTermsOrAll(terms).map { it.record }
        return listOf(memoryContractFinding(records, baseline = false)) + records
    }

    val exactChanges = diffEntityRecords(
        current = current,
        previous = baseline.entities(setOf("memory"), source = "baseline", detail = detail),
        terms = terms,
        triage = true
    )
    val changes = (exactChanges.takeIf { it.isNotEmpty() || terms.isEmpty() }
        ?: diffEntityRecords(
            current = current,
            previous = baseline.entities(setOf("memory"), source = "baseline", detail = detail),
            terms = emptyList(),
            triage = true
        )).sortedBy { it.index ?: Int.MAX_VALUE }
    return listOf(memoryContractFinding(changes, baseline = true)) + changes
}

private fun WasmBinaryProvider.dispatchTableDiffRecords(
    baseline: WasmBinaryProvider,
    terms: List<String>,
    detail: String
): List<ArtifactRecordResult> {
    val previousElements = baseline.readElementSegments().segments.associateBy { it.elementidx }
    val currentElements = readElementSegments().segments.associateBy { it.elementidx }
    val previousFunctions = baseline.listFunctionEntries().associateBy { it.funcidx }
    val currentFunctions = listFunctionEntries().associateBy { it.funcidx }

    val records = (previousElements.keys + currentElements.keys).sorted().flatMap { elementidx ->
        val previous = previousElements[elementidx]
        val current = currentElements[elementidx]
        dispatchSlotDiffRecords(
            elementidx = elementidx,
            previous = previous,
            current = current,
            previousFunctions = previousFunctions,
            currentFunctions = currentFunctions,
            detail = detail
        )
    }.filterRecordsByTermsOrAll(terms)

    return listOf(dispatchTableDiffFinding(records, terms)) + records
}

private fun WasmBinaryProvider.hostCallDiffRecords(
    baseline: WasmBinaryProvider?,
    startName: String?,
    startFuncidx: Int?,
    terms: List<String>,
    detail: String,
    limit: Int
): List<ArtifactRecordResult> {
    val currentRootIndices = resolveStartFunctions(startName, startFuncidx, terms, limit)
    if (currentRootIndices.isEmpty()) {
        return listOf(
            ArtifactRecordResult(
                id = "host-call-diff:roots",
                entityType = "finding",
                status = "current",
                source = "current",
                summary = "No host-call roots matched; pass `startName`, `startFuncidx`, or a function/export query term",
                attributes = buildList {
                    startName?.let { add("startName", it) }
                    startFuncidx?.let { add("startFuncidx", it.toString()) }
                    if (terms.isNotEmpty()) add("query", terms.joinToString(","))
                }.toAttributes()
            )
        )
    }

    val currentCalls = hostCallSnapshots(currentRootIndices, source = "current", detail = detail)
    if (baseline == null) {
        val records = currentCalls.map { it.record }.filterRecordsByTermsOrAll(terms)
        return listOf(hostCallFinding(records, baseline = false, terms = terms)) + records
    }

    val previousRootIndices = baseline.resolveStartFunctions(startName, startFuncidx, terms, limit)
    val previousCalls = baseline.hostCallSnapshots(previousRootIndices, source = "baseline", detail = detail)
    val records = diffHostCalls(previousCalls, currentCalls)
        .filterRecordsByTermsOrAll(terms)
        .sortedWith(
            compareBy<ArtifactRecordResult> { TRIAGE_STATUS_PRIORITY[it.status] ?: Int.MAX_VALUE }
                .thenBy { it.name ?: it.id }
        )
    return listOf(hostCallFinding(records, baseline = true, terms = terms)) + records
}

private fun WasmBinaryProvider.traceRecords(
    startName: String?,
    startFuncidx: Int?,
    terms: List<String>,
    relations: Set<String>,
    detail: String,
    limit: Int
): List<ArtifactRecordResult> {
    val startIndices = resolveStartFunctions(startName, startFuncidx, terms, limit)
    if (startIndices.isEmpty()) return emptyList()

    return readFunctionCallGraph(startIndices).functions.flatMap { function ->
        buildList {
            add(function.toTraceFunctionRecord(detail))
            if ("direct_call" in relations) {
                addAll(function.directCalls.map { call ->
                    ArtifactRecordResult(
                        id = "call:${function.funcidx}:${call.targetFuncidx}:${call.offset ?: "unknown"}",
                        entityType = "call",
                        status = "current",
                        source = "current",
                        relation = "direct_call",
                        targetId = "function:${call.targetFuncidx}",
                        name = call.target?.exportedAs?.firstOrNull()
                            ?: call.target?.importInfo?.name
                            ?: call.targetFuncidx.toString(),
                        kind = call.op,
                        index = call.targetFuncidx,
                        signature = call.target?.signature?.toArtifactSignature(),
                        summary = "function[${function.funcidx}] ${call.op} function[${call.targetFuncidx}]",
                        attributes = buildList {
                            call.offset?.let { add("offset", it.toString()) }
                            call.target?.importInfo?.let { import ->
                                add("targetImport", "${import.module}.${import.name}")
                            }
                            if (detail == "evidence") {
                                add("text", call.text)
                                add("fromFuncidx", function.funcidx.toString())
                            }
                        }.toAttributes()
                    )
                })
            }
            if ("direct_imported_call" in relations) {
                addAll(function.directImportedCalls.map { call ->
                    ArtifactRecordResult(
                        id = "imported-call:${function.funcidx}:${call.targetFuncidx}:${call.offset ?: "unknown"}",
                        entityType = "call",
                        status = "current",
                        source = "current",
                        relation = "direct_imported_call",
                        targetId = "function:${call.targetFuncidx}",
                        name = call.target?.importInfo?.name,
                        kind = call.op,
                        index = call.targetFuncidx,
                        signature = call.target?.signature?.toArtifactSignature(),
                        summary = "function[${function.funcidx}] calls import ${call.target?.importInfo?.module}.${call.target?.importInfo?.name}",
                        attributes = buildList {
                            call.offset?.let { add("offset", it.toString()) }
                            call.target?.importInfo?.let { import ->
                                add("targetImport", "${import.module}.${import.name}")
                            }
                            if (detail == "evidence") {
                                add("text", call.text)
                            }
                        }.toAttributes()
                    )
                })
            }
            if ("indirect_call" in relations) {
                addAll(function.indirectCalls.map { call ->
                    ArtifactRecordResult(
                        id = "indirect-call:${function.funcidx}:${call.typeidx}:${call.tableidx}:${call.offset ?: "unknown"}",
                        entityType = "call",
                        status = "current",
                        source = "current",
                        relation = "indirect_call",
                        kind = call.op,
                        summary = "function[${function.funcidx}] ${call.op} type[${call.typeidx}] table[${call.tableidx}]",
                        attributes = buildList {
                            add("typeidx", call.typeidx.toString())
                            add("tableidx", call.tableidx.toString())
                            call.offset?.let { add("offset", it.toString()) }
                            if (detail == "evidence") add("text", call.text)
                        }.toAttributes()
                    )
                })
            }
            if ("memory_write" in relations) {
                addAll(function.directMemoryWrites.map { write ->
                    ArtifactRecordResult(
                        id = "memory-write:${function.funcidx}:${write.offset ?: write.text.hashCode()}",
                        entityType = "instruction",
                        status = "current",
                        source = "current",
                        relation = "memory_write",
                        kind = write.op,
                        summary = "function[${function.funcidx}] writes memory with ${write.op}",
                        attributes = buildList {
                            write.offset?.let { add("offset", it.toString()) }
                            write.memoryOffset?.let { add("memoryOffset", it.toString()) }
                            write.alignment?.let { add("alignment", it.toString()) }
                            if (detail == "evidence") add("text", write.text)
                        }.toAttributes()
                    )
                })
            }
        }
    }
}

private fun WasmBinaryProvider.resolveStartFunctions(
    startName: String?,
    startFuncidx: Int?,
    terms: List<String>,
    limit: Int
): List<Int> {
    startFuncidx?.let { return listOf(it) }

    val lookups = buildList {
        startName?.let(::add)
        addAll(terms)
    }.distinct()
    if (lookups.isEmpty()) return emptyList()

    return lookups.asSequence()
        .flatMap { term -> readFunctionsByName(term, limit).functions.asSequence() }
        .map { it.funcidx }
        .distinct()
        .take(limit.coerceAtMost(20))
        .toList()
}

private fun WasmBinaryProvider.entities(
    requestedTypes: Set<String>,
    source: String,
    detail: String
): List<EntitySnapshot> {
    val functions = listFunctionEntries()
    val functionsByIndex = functions.associateBy { it.funcidx }
    val exports = readExports().exports
    val memoriesByIndex = readMemory().memories.associateBy { it.memoryIdx }
    val globalsByIndex = readGlobals().globals.associateBy { it.globalIdx }
    val importedMemoriesByIndex = importedMemoriesByIndex()
    val importedTablesByIndex = importedTablesByIndex()
    val exportedMemoryNames = exports
        .filter { it.kind.kindName() == "memory" }
        .groupBy(
            keySelector = { it.externIndex.toInt() },
            valueTransform = { it.name }
        )

    return buildList {
        if ("export" in requestedTypes) {
            addAll(exports.map {
                it.toExportEntity(
                    source = source,
                    functionsByIndex = functionsByIndex,
                    memoriesByIndex = memoriesByIndex,
                    globalsByIndex = globalsByIndex,
                    importedMemoriesByIndex = importedMemoriesByIndex,
                    importedTablesByIndex = importedTablesByIndex,
                    detail = detail
                )
            })
        }
        if ("import" in requestedTypes) {
            addAll(readImportFunctions().imports.map { it.toImportEntity(source) })
        }
        if ("memory" in requestedTypes) {
            addAll(readMemory().memories.map { it.toMemoryEntity(source, exportedMemoryNames, importedMemoriesByIndex, detail) })
        }
        if ("global" in requestedTypes) {
            addAll(readGlobals().globals.map { it.toGlobalEntity(source, detail) })
        }
        if ("function" in requestedTypes) {
            addAll(functions.map { it.toFunctionEntity(source, detail) })
        }
        if ("element" in requestedTypes) {
            addAll(readElementSegments().segments.map { it.toElementEntity(source, detail) })
        }
    }
}

private fun WasmBinaryProvider.toArtifactModuleResult(source: String): ArtifactModuleResult {
    val summary = readModuleSummary()
    return ArtifactModuleResult(
        source = source,
        functions = summary.functions,
        imports = summary.imports.functions + summary.imports.memories + summary.imports.tables + summary.imports.globals,
        exports = summary.exports,
        memories = summary.memories,
        globals = summary.globals,
        tables = summary.tables,
        types = summary.types,
        moduleSize = summary.moduleSize
    )
}

private fun ExportResult.toExportEntity(
    source: String,
    functionsByIndex: Map<Int, GeneralFunctionEntry>,
    memoriesByIndex: Map<Int, MemoryResult>,
    globalsByIndex: Map<Int, GlobalResult>,
    importedMemoriesByIndex: Map<Int, ImportName>,
    importedTablesByIndex: Map<Int, ImportName>,
    detail: String
): EntitySnapshot {
    val kindName = kind.kindName()
    val function = functionsByIndex[externIndex.toInt()].takeIf { kindName == "function" }
    val memory = memoriesByIndex[externIndex.toInt()].takeIf { kindName == "memory" }
    val global = globalsByIndex[externIndex.toInt()].takeIf { kindName == "global" }
    val importedMemory = importedMemoriesByIndex[externIndex.toInt()].takeIf { kindName == "memory" }
    val importedTable = importedTablesByIndex[externIndex.toInt()].takeIf { kindName == "table" }
    val signature = function?.signature?.toArtifactSignature()
    val attributes = buildList {
        add("externIndex", externIndex.toString())
        add("kindTag", kind.toString())
        function?.let { entry ->
            entry.typeidx?.let { add("typeidx", it.toString()) }
            add("origin", entry.origin())
            add("definedIn", entry.definedIn)
            entry.import?.let { add("import", "${it.module}.${it.name}") }
            if (detail == "evidence") {
                entry.codeOffset?.let { add("codeOffset", it.toString()) }
                entry.instructionCount?.let { add("instructionCount", it.toString()) }
            }
        }
        memory?.let { mem ->
            add("memidx", mem.memoryIdx.toString())
            add("origin", mem.origin())
            importedMemory?.let { import ->
                add("import", import.render())
                add("importModule", import.module)
                add("importName", import.name)
            }
            add("memoryMin", mem.limit.min.toString())
            add("memoryMax", mem.limit.max?.toString() ?: "none")
            add("memoryHasMax", mem.limit.hasMax.toString())
            add("memoryAddressType", mem.limit.addressType)
        }
        global?.let { glob ->
            add("globalidx", glob.globalIdx.toString())
            add("origin", glob.origin())
            glob.prefix?.let { prefix -> glob.name?.let { name -> add("import", "$prefix.$name") } }
            add("type", glob.type)
            add("mutable", glob.mutability.toString())
            glob.expression?.let { add("initExpr", it) }
        }
        if (kindName == "table") {
            add("tableidx", externIndex.toString())
            add("origin", if (importedTable != null) "import" else "local")
            importedTable?.let { import ->
                add("import", import.render())
                add("importModule", import.module)
                add("importName", import.name)
            }
        }
        if (detail == "evidence") {
            add("idKey", "export:$kindName:$name")
        }
    }.toAttributes()
    return EntitySnapshot(
        key = "export:$kindName:$name",
        comparisonKey = buildList {
            add(kindName)
            add(signature?.compact().orEmpty())
            add(memory?.limit?.range.orEmpty())
            global?.let { add("${it.type}:${it.mutability}:${it.expression.orEmpty()}") }
            if (detail == "evidence") add("externIndex=$externIndex")
        }.joinToString("|"),
        record = ArtifactRecordResult(
            id = "export:$kindName:$name",
            entityType = "export",
            status = "current",
            source = source,
            name = name,
            kind = kindName,
            index = externIndex.toInt(),
            signature = signature,
            summary = "export `$name` is $kindName[$externIndex]",
            attributes = attributes
        )
    )
}

private fun ImportFunctionResult.toImportEntity(source: String): EntitySnapshot {
    val signature = signature?.let { ArtifactSignatureResult(params = it.params, results = it.ret) }
    return EntitySnapshot(
        key = "import:function:$module.$name",
        comparisonKey = signature?.compact().orEmpty(),
        record = ArtifactRecordResult(
            id = "import:function:$module.$name",
            entityType = "import",
            status = "current",
            source = source,
            name = name,
            kind = "function",
            index = funcidx,
            signature = signature,
            summary = "import `$module.$name` is function[$funcidx]",
            attributes = listOf(
                "module" to module,
                "funcidx" to funcidx.toString()
            ).toAttributes()
        )
    )
}

private fun MemoryResult.toMemoryEntity(
    source: String,
    exportedNames: Map<Int, List<String>>,
    importedMemoriesByIndex: Map<Int, ImportName>,
    detail: String
): EntitySnapshot {
    val exports = exportedNames[memoryIdx].orEmpty()
    val importedMemory = importedMemoriesByIndex[memoryIdx]
    val attributes = buildList {
        add("definedIn", definedIn)
        add("origin", origin())
        importedMemory?.let { import ->
            add("import", import.render())
            add("importModule", import.module)
            add("importName", import.name)
        }
        add("addressType", limit.addressType)
        add("min", limit.min.toString())
        add("max", limit.max?.toString() ?: "none")
        add("hasMax", limit.hasMax.toString())
        add("range", limit.range)
        if (exports.isNotEmpty()) add("exportedAs", exports.joinToString(","))
        if (detail == "evidence") add("flag", limit.flag)
    }.toAttributes()
    return EntitySnapshot(
        key = "memory:$memoryIdx",
        comparisonKey = attributes.joinToString("|") { "${it.name}=${it.value}" },
        record = ArtifactRecordResult(
            id = "memory:$memoryIdx",
            entityType = "memory",
            status = "current",
            source = source,
            name = exports.firstOrNull() ?: importedMemory?.name,
            kind = definedIn,
            index = memoryIdx,
            summary = "memory[$memoryIdx] ${limit.range} pages from $definedIn",
            attributes = attributes
        )
    )
}

private fun GlobalResult.toGlobalEntity(source: String, detail: String): EntitySnapshot {
    val attributes = buildList {
        name?.let { add("name", it) }
        prefix?.let { add("module", it) }
        add("type", type)
        add("mutable", mutability.toString())
        add("definedIn", definedIn)
        add("origin", origin())
        expression?.let { add("expression", it) }
    }.toAttributes()
    return EntitySnapshot(
        key = "global:$globalIdx",
        comparisonKey = attributes.joinToString("|") { "${it.name}=${it.value}" },
        record = ArtifactRecordResult(
            id = "global:$globalIdx",
            entityType = "global",
            status = "current",
            source = source,
            name = name ?: prefix,
            kind = definedIn,
            index = globalIdx,
            summary = "global[$globalIdx] $type mutable=$mutability from $definedIn",
            attributes = attributes
        )
    )
}

private fun GeneralFunctionEntry.toFunctionEntity(source: String, detail: String): EntitySnapshot {
    val displayName = exportedAs.firstOrNull() ?: import?.name ?: "function[$funcidx]"
    val signature = signature?.toArtifactSignature()
    val attributes = buildList {
        import?.let { add("import", "${it.module}.${it.name}") }
        if (exportedAs.isNotEmpty()) add("exportedAs", exportedAs.joinToString(","))
        typeidx?.let { add("typeidx", it.toString()) }
        add("definedIn", definedIn)
        if (detail == "evidence") {
            codeOffset?.let { add("codeOffset", it.toString()) }
            codeSize?.let { add("codeSize", it.toString()) }
            instructionCount?.let { add("instructionCount", it.toString()) }
            add("funcidx", funcidx.toString())
        }
    }.toAttributes()
    val stableName = exportedAs.firstOrNull()?.let { "export:$it" }
        ?: import?.let { "import:${it.module}.${it.name}" }
        ?: "funcidx:$funcidx"
    val comparisonKey = buildList {
        add(signature?.compact().orEmpty())
        add(if (import != null) "import" else "local")
        import?.let { add("${it.module}.${it.name}") }
        if (exportedAs.isNotEmpty()) add(exportedAs.joinToString(","))
        typeidx?.let { add("typeidx=$it") }
        add("definedIn=$definedIn")
        if (detail == "evidence") {
            codeSize?.let { add("codeSize=$it") }
            instructionCount?.let { add("instructionCount=$it") }
        }
    }.joinToString("|")
    return EntitySnapshot(
        key = "function:$stableName",
        comparisonKey = comparisonKey,
        record = ArtifactRecordResult(
            id = "function:$funcidx",
            entityType = "function",
            status = "current",
            source = source,
            name = displayName,
            kind = if (import != null) "import" else "local",
            index = funcidx,
            signature = signature,
            summary = "function[$funcidx] `$displayName` from $definedIn",
            attributes = attributes
        )
    )
}

private fun ElementSegmentResult.toElementEntity(source: String, detail: String): EntitySnapshot {
    val elementValues = elements.orEmpty()
    val offset = offsetExpr.orEmpty()
    val table = tableIdx?.toString() ?: if (mode == "active") "0" else "none"
    val tableSummary = if (table == "none") "" else " table[$table]"
    val attributes = buildList {
        add("kindTag", kind.toString())
        add("mode", mode)
        add("tableIdx", table)
        if (offset.isNotEmpty()) add("offsetExpr", offset.compactList(maxItems = 8))
        add("elementType", elementType)
        add("elementCount", elementValues.size.toString())
        add("elements", elementValues.compactList(maxItems = if (detail == "evidence") 80 else 40))
    }.toAttributes()
    return EntitySnapshot(
        key = "element:$elementidx",
        comparisonKey = buildList {
            add("kind=$kind")
            add("mode=$mode")
            add("tableIdx=$table")
            add("offset=${offset.joinToString(" ")}")
            add("elementType=$elementType")
            add("elements=${elementValues.joinToString(",")}")
        }.joinToString("|"),
        record = ArtifactRecordResult(
            id = "element:$elementidx",
            entityType = "element",
            status = "current",
            source = source,
            kind = mode,
            index = elementidx,
            summary = "element[$elementidx] $mode$tableSummary ${elementType} elements ${elementValues.compactList(maxItems = 12)}",
            attributes = attributes
        )
    )
}

private fun dispatchSlotDiffRecords(
    elementidx: Int,
    previous: ElementSegmentResult?,
    current: ElementSegmentResult?,
    previousFunctions: Map<Int, GeneralFunctionEntry>,
    currentFunctions: Map<Int, GeneralFunctionEntry>,
    detail: String
): List<ArtifactRecordResult> {
    val previousElements = previous?.elements.orEmpty()
    val currentElements = current?.elements.orEmpty()
    val maxSize = maxOf(previousElements.size, currentElements.size)
    val offset = current?.offsetExpr ?: previous?.offsetExpr ?: emptyList()
    val tableIdx = current?.tableIdx ?: previous?.tableIdx ?: 0
    val baseSlot = offset.activeElementBaseSlot()

    return (0 until maxSize).mapNotNull { position ->
        val previousTarget = previousElements.getOrNull(position)
        val currentTarget = currentElements.getOrNull(position)
        if (previousTarget == currentTarget) return@mapNotNull null

        val previousFuncidx = previousTarget?.toIntOrNull()
        val currentFuncidx = currentTarget?.toIntOrNull()
        val previousFunction = previousFuncidx?.let(previousFunctions::get)
        val currentFunction = currentFuncidx?.let(currentFunctions::get)
        val status = when {
            previousTarget == null -> "added"
            currentTarget == null -> "removed"
            else -> "changed"
        }
        val slot = baseSlot?.plus(position) ?: position
        val previousName = previousFunction?.exportedAs?.firstOrNull() ?: previousTarget ?: "<missing>"
        val currentName = currentFunction?.exportedAs?.firstOrNull() ?: currentTarget ?: "<missing>"

        ArtifactRecordResult(
            id = "dispatch-slot:$elementidx:$slot",
            entityType = "dispatch_slot",
            status = status,
            source = if (status == "removed") "baseline" else "current",
            name = currentName.takeUnless { it == "<missing>" } ?: previousName,
            kind = "table_slot",
            index = slot,
            targetId = currentFuncidx?.let { "function:$it" },
            signature = currentFunction?.signature?.toArtifactSignature() ?: previousFunction?.signature?.toArtifactSignature(),
            summary = "table[$tableIdx] slot[$slot] target ${status} from function[${previousTarget ?: "<missing>"}] $previousName to function[${currentTarget ?: "<missing>"}] $currentName",
            attributes = buildList {
                add("elementidx", elementidx.toString())
                add("elementPosition", position.toString())
                add("slot", slot.toString())
                add("tableIdx", tableIdx.toString())
                if (offset.isNotEmpty()) add("offsetExpr", offset.compactList(maxItems = 8))
                previousTarget?.let { add("previousFuncidx", it) }
                currentTarget?.let { add("currentFuncidx", it) }
                previousFunction?.let { function ->
                    add("previousOrigin", function.origin())
                    if (function.exportedAs.isNotEmpty()) add("previousExportedAs", function.exportedAs.joinToString(","))
                    function.import?.let { add("previousImport", "${it.module}.${it.name}") }
                    function.signature?.toArtifactSignature()?.compact()?.let { add("previousSignature", it) }
                }
                currentFunction?.let { function ->
                    add("currentOrigin", function.origin())
                    if (function.exportedAs.isNotEmpty()) add("currentExportedAs", function.exportedAs.joinToString(","))
                    function.import?.let { add("currentImport", "${it.module}.${it.name}") }
                    function.signature?.toArtifactSignature()?.compact()?.let { add("currentSignature", it) }
                }
                add("previousElements", previousElements.compactList(maxItems = if (detail == "evidence") 80 else 24))
                add("currentElements", currentElements.compactList(maxItems = if (detail == "evidence") 80 else 24))
            }.toAttributes()
        )
    }
}

private fun WasmBinaryProvider.hostCallSnapshots(
    rootIndices: List<Int>,
    source: String,
    detail: String
): List<HostCallSnapshot> {
    return readFunctionCallGraph(rootIndices).functions.flatMap { function ->
        function.directImportedCalls.map { call ->
            val import = call.target?.importInfo
            val hostName = import?.let { "${it.module}.${it.name}" } ?: "function:${call.targetFuncidx}"
            val rootName = function.exportedAs.firstOrNull() ?: "function:${function.funcidx}"
            val record = ArtifactRecordResult(
                id = "host-call:${rootName}:${hostName}",
                entityType = "host_call",
                status = "current",
                source = source,
                name = hostName,
                kind = "direct_imported_call",
                index = call.targetFuncidx,
                relation = "direct_imported_call",
                targetId = "function:${call.targetFuncidx}",
                signature = call.target?.signature?.toArtifactSignature(),
                summary = "`$rootName` directly calls host import `$hostName`",
                attributes = buildList {
                    add("rootFuncidx", function.funcidx.toString())
                    add("rootName", rootName)
                    if (function.exportedAs.isNotEmpty()) add("rootExportedAs", function.exportedAs.joinToString(","))
                    function.signature?.toArtifactSignature()?.compact()?.let { add("rootSignature", it) }
                    import?.let {
                        add("import", "${it.module}.${it.name}")
                        add("importModule", it.module)
                        add("importName", it.name)
                    }
                    add("targetFuncidx", call.targetFuncidx.toString())
                    call.offset?.let { add("offset", it.toString()) }
                    if (detail == "evidence") add("text", call.text)
                }.toAttributes()
            )
            HostCallSnapshot(
                key = "${rootName}:${hostName}",
                rootName = rootName,
                hostName = hostName,
                record = record
            )
        }
    }
}

private fun diffHostCalls(
    previous: List<HostCallSnapshot>,
    current: List<HostCallSnapshot>
): List<ArtifactRecordResult> {
    val previousByKey = previous.associateBy { it.key }
    val currentByKey = current.associateBy { it.key }
    return (previousByKey.keys + currentByKey.keys).sorted().mapNotNull { key ->
        val previousCall = previousByKey[key]
        val currentCall = currentByKey[key]
        val status = when {
            previousCall == null && currentCall != null -> "added"
            previousCall != null && currentCall == null -> "removed"
            else -> "unchanged"
        }
        if (status == "unchanged") return@mapNotNull null
        val selected = currentCall ?: previousCall ?: return@mapNotNull null
        selected.record.copy(
            status = status,
            source = if (status == "removed") "baseline" else "current",
            summary = when (status) {
                "added" -> "Added direct host call `${selected.hostName}` from `${selected.rootName}`"
                else -> "Removed direct host call `${selected.hostName}` from `${selected.rootName}`"
            },
            attributes = buildList {
                add("rootName", selected.rootName)
                add("hostImport", selected.hostName)
                previousCall?.record?.attrValue("offset")?.let { add("previousOffset", it) }
                currentCall?.record?.attrValue("offset")?.let { add("currentOffset", it) }
            }.toAttributes().plus(selected.record.attributes).distinctBy { it.name }
        )
    }
}

private fun budgetRecords(
    operation: String,
    detail: String,
    modules: List<ArtifactModuleResult>,
    records: List<ArtifactRecordResult>,
    limit: Int,
    maxBytes: Int,
    warnings: MutableList<String>
): BudgetedRecords {
    val limitedRecords = records.take(limit)
    val accepted = mutableListOf<ArtifactRecordResult>()
    var limitedByBytes = false

    for (record in limitedRecords) {
        val candidate = accepted + record
        if (estimatedQueryBytes(operation, detail, modules, candidate, records.size - candidate.size, limit, maxBytes, warnings) <= maxBytes) {
            accepted += record
            continue
        }

        val compactRecord = record.compactForBudget()
        if (accepted.isEmpty() && estimatedQueryBytes(operation, detail, modules, listOf(compactRecord), records.size - 1, limit, maxBytes, warnings) <= maxBytes) {
            accepted += compactRecord
        }
        limitedByBytes = true
        break
    }

    if (limitedRecords.size < records.size) {
        // The `omitted` field carries the user-facing count; no warning is needed for a normal `limit` truncation.
    } else if (accepted.size < limitedRecords.size) {
        limitedByBytes = true
    }
    if (limitedByBytes) {
        warnings += "Result truncated to fit `maxBytes=$maxBytes`; refine `entityTypes`, `query`, `start*`, or use `detail=summary`."
    }

    var finalRecords = accepted.toList()
    while (
        finalRecords.isNotEmpty() &&
        estimatedQueryBytes(operation, detail, modules, finalRecords, records.size - finalRecords.size, limit, maxBytes, warnings) > maxBytes
    ) {
        finalRecords = finalRecords.dropLast(1)
    }

    val omittedCount = records.size - finalRecords.size
    val omitted = if (omittedCount > 0) {
        ArtifactOmittedResult(
            count = omittedCount,
            reason = buildOmittedReason(
                limitedByCount = records.size > limit,
                limitedByBytes = limitedByBytes || finalRecords.size < limitedRecords.size,
                limit = limit,
                maxBytes = maxBytes
            )
        )
    } else {
        null
    }
    return BudgetedRecords(finalRecords, omitted)
}

private fun estimatedQueryBytes(
    operation: String,
    detail: String,
    modules: List<ArtifactModuleResult>,
    records: List<ArtifactRecordResult>,
    omittedCount: Int,
    limit: Int,
    maxBytes: Int,
    warnings: List<String>
): Int {
    val omitted = if (omittedCount > 0) {
        ArtifactOmittedResult(
            count = omittedCount,
            reason = buildOmittedReason(
                limitedByCount = omittedCount > 0,
                limitedByBytes = true,
                limit = limit,
                maxBytes = maxBytes
            )
        )
    } else {
        null
    }
    return encodeHexanaJson(
        QueryArtifactsResult(
            operation = operation,
            detail = detail,
            modules = modules,
            records = records,
            omitted = omitted,
            warnings = warnings
        )
    ).encodeToByteArray().size
}

private fun buildOmittedReason(
    limitedByCount: Boolean,
    limitedByBytes: Boolean,
    limit: Int,
    maxBytes: Int
): String {
    val constraints = buildList {
        if (limitedByCount) add("`limit=$limit`")
        if (limitedByBytes) add("`maxBytes=$maxBytes`")
    }.ifEmpty { listOf("query bounds") }
    return "Result set exceeded ${constraints.joinToString(" and ")}; refine `entityTypes`, `query`, or `start*`."
}

private fun ArtifactRecordResult.compactForBudget(): ArtifactRecordResult =
    copy(
        summary = summary.take(240),
        signature = null,
        attributes = emptyList()
    )

private fun currentSurfaceFinding(records: List<ArtifactRecordResult>): ArtifactRecordResult {
    val exportRecords = records.filter { it.entityType == "export" }
    val attributes = buildList {
        add("exports", exportRecords.size.toString())
        add("functionExports", exportRecords.count { it.kind == "function" }.toString())
        add("tableExports", exportRecords.count { it.kind == "table" }.toString())
        add("memoryExports", exportRecords.count { it.kind == "memory" }.toString())
        add("globalExports", exportRecords.count { it.kind == "global" }.toString())
        add("imports", records.count { it.entityType == "import" }.toString())
        add("memories", records.count { it.entityType == "memory" }.toString())
        add("globals", records.count { it.entityType == "global" }.toString())
        add("elementSegments", records.count { it.entityType == "element" }.toString())
    }.toAttributes()
    return ArtifactRecordResult(
        id = "triage:surface",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Current module surface has ${exportRecords.size} exports and ${records.count { it.entityType == "element" }} element segments",
        attributes = attributes
    )
}

private fun publicSurfaceFinding(records: List<ArtifactRecordResult>): ArtifactRecordResult {
    val functionExports = records.filter { it.kind == "function" }
    val memoryExports = records.filter { it.kind == "memory" }
    val globalExports = records.filter { it.kind == "global" }
    val tableExports = records.filter { it.kind == "table" }
    val attributes = buildList {
        add("exports", records.size.toString())
        add("functionExports", functionExports.size.toString())
        add("memoryExports", memoryExports.size.toString())
        add("globalExports", globalExports.size.toString())
        add("tableExports", tableExports.size.toString())
        add("importedFunctionExports", functionExports.count { it.attrValue("origin") == "import" }.toString())
        add("localFunctionExports", functionExports.count { it.attrValue("origin") == "local" }.toString())
        val reexportedImports = functionExports
            .filter { it.attrValue("origin") == "import" }
            .mapNotNull { export -> export.name?.let { name -> export.attrValue("import")?.let { "$name=$it" } } }
        if (reexportedImports.isNotEmpty()) add("reexportedImports", reexportedImports.joinToString(","))
    }.toAttributes()
    return ArtifactRecordResult(
        id = "public-surface:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Public surface has ${records.size} exports: ${functionExports.size} functions, ${memoryExports.size} memories, ${globalExports.size} globals, ${tableExports.size} tables",
        attributes = attributes
    )
}

private fun memoryContractFinding(
    records: List<ArtifactRecordResult>,
    baseline: Boolean
): ArtifactRecordResult {
    val changed = records.count { it.status == "changed" }
    val imported = records.count { it.attrValue("origin") == "import" }
    return ArtifactRecordResult(
        id = "memory-contract:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = if (baseline) {
            "Memory contract diff found $changed changed memories"
        } else {
            "Memory contract found ${records.size} current memories"
        },
        attributes = buildList {
            add("memories", records.size.toString())
            add("changed", changed.toString())
            add("imported", imported.toString())
            records.firstOrNull()?.attrValue("import")?.let { add("firstImport", it) }
            records.firstOrNull()?.attrValue("exportedAs")?.let { add("firstExportedAs", it) }
        }.toAttributes()
    )
}

private fun dispatchTableDiffFinding(
    records: List<ArtifactRecordResult>,
    terms: List<String>
): ArtifactRecordResult {
    return ArtifactRecordResult(
        id = "dispatch-table:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Dispatch table diff found ${records.size} changed table slots",
        attributes = buildList {
            add("changedSlots", records.count { it.status == "changed" }.toString())
            add("addedSlots", records.count { it.status == "added" }.toString())
            add("removedSlots", records.count { it.status == "removed" }.toString())
            if (terms.isNotEmpty()) add("query", terms.joinToString(","))
            if (records.isNotEmpty()) add("focus", records.take(5).joinToString("; ") { it.summary })
        }.toAttributes()
    )
}

private fun hostCallFinding(
    records: List<ArtifactRecordResult>,
    baseline: Boolean,
    terms: List<String>
): ArtifactRecordResult {
    val added = records.filter { it.status == "added" }
    val removed = records.filter { it.status == "removed" }
    return ArtifactRecordResult(
        id = "host-call:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = if (baseline) {
            "Host-call diff found ${added.size} added and ${removed.size} removed direct host calls"
        } else {
            "Host-call scan found ${records.size} direct host calls"
        },
        attributes = buildList {
            add("added", added.size.toString())
            add("removed", removed.size.toString())
            if (added.isNotEmpty()) add("newDirectHostCalls", added.mapNotNull { it.attrValue("hostImport") ?: it.name }.distinct().joinToString(","))
            if (removed.isNotEmpty()) add("removedDirectHostCalls", removed.mapNotNull { it.attrValue("hostImport") ?: it.name }.distinct().joinToString(","))
            if (terms.isNotEmpty()) add("query", terms.joinToString(","))
        }.toAttributes()
    )
}

private fun diffTriageFinding(
    changes: List<ArtifactRecordResult>,
    terms: List<String>
): ArtifactRecordResult {
    val statusCounts = changes.groupingBy { it.status }.eachCount()
    val entityStatusCounts = changes.groupingBy { "${it.entityType}.${it.status}" }.eachCount()
    val attributes = buildList {
        add("changed", (statusCounts["changed"] ?: 0).toString())
        add("added", (statusCounts["added"] ?: 0).toString())
        add("removed", (statusCounts["removed"] ?: 0).toString())
        entityStatusCounts.toSortedMap().forEach { (name, count) -> add(name, count.toString()) }
        if (terms.isNotEmpty()) add("query", terms.joinToString(","))
        if (changes.isNotEmpty()) add("focus", changes.take(5).joinToString("; ") { it.summary })
    }.toAttributes()
    val summary = if (changes.isEmpty()) {
        if (terms.isEmpty()) {
            "Triage found no public artifact changes"
        } else {
            "Triage found no public artifact changes matching ${terms.joinToString(",")}"
        }
    } else {
        "Triage found ${statusCounts["changed"] ?: 0} changed, ${statusCounts["added"] ?: 0} added, and ${statusCounts["removed"] ?: 0} removed records"
    }
    return ArtifactRecordResult(
        id = "triage:diff",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = summary,
        attributes = attributes
    )
}

private fun triageSummary(
    status: String,
    selected: EntitySnapshot,
    previous: EntitySnapshot?,
    current: EntitySnapshot?
): String {
    if (status != "changed") {
        return when (status) {
            "added" -> "Added ${selected.record.summary}"
            "removed" -> "Removed ${selected.record.summary}"
            else -> selected.record.summary
        }
    }

    val previousSignature = previous?.record?.signature?.compact()
    val currentSignature = current?.record?.signature?.compact()
    if (previousSignature != currentSignature && (previousSignature != null || currentSignature != null)) {
        return "Changed ${selected.record.entityType} ${selected.record.displayLabel()} signature from ${previousSignature ?: "<missing>"} to ${currentSignature ?: "<missing>"}"
    }

    if (selected.record.entityType == "element") {
        val previousElements = previous?.record?.attrValue("elements") ?: "<missing>"
        val currentElements = current?.record?.attrValue("elements") ?: "<missing>"
        return "Changed element ${selected.record.displayLabel()} elements from $previousElements to $currentElements"
    }

    if (selected.record.entityType == "memory") {
        val previousRange = previous?.record?.attrValue("range") ?: previous?.record?.summary ?: "<missing>"
        val currentRange = current?.record?.attrValue("range") ?: current?.record?.summary ?: "<missing>"
        return "Changed memory ${selected.record.displayLabel()} limits from $previousRange to $currentRange"
    }

    return "Changed ${selected.record.summary}"
}

private fun triageAttributes(
    previous: EntitySnapshot?,
    current: EntitySnapshot?,
    fallback: List<ArtifactAttributeResult>
): List<ArtifactAttributeResult> {
    val leading = buildList {
        previous?.record?.summary?.let { add("previousSummary", it) }
        current?.record?.summary?.let { add("currentSummary", it) }

        val previousSignature = previous?.record?.signature?.compact()
        val currentSignature = current?.record?.signature?.compact()
        if (previousSignature != currentSignature && (previousSignature != null || currentSignature != null)) {
            previousSignature?.let { add("previousSignature", it) }
            currentSignature?.let { add("currentSignature", it) }
        }

        if (previous?.record?.entityType == "element" || current?.record?.entityType == "element") {
            addIfChanged("previousElements", previous?.record?.attrValue("elements"), "currentElements", current?.record?.attrValue("elements"))
            addIfChanged("previousTableIdx", previous?.record?.attrValue("tableIdx"), "currentTableIdx", current?.record?.attrValue("tableIdx"))
            addIfChanged("previousOffsetExpr", previous?.record?.attrValue("offsetExpr"), "currentOffsetExpr", current?.record?.attrValue("offsetExpr"))
        }
        if (previous?.record?.entityType == "memory" || current?.record?.entityType == "memory") {
            addIfChanged("previousMin", previous?.record?.attrValue("min"), "currentMin", current?.record?.attrValue("min"))
            addIfChanged("previousMax", previous?.record?.attrValue("max"), "currentMax", current?.record?.attrValue("max"))
            addIfChanged("previousHasMax", previous?.record?.attrValue("hasMax"), "currentHasMax", current?.record?.attrValue("hasMax"))
            current?.record?.attrValue("import")?.let { add("import", it) }
            current?.record?.attrValue("exportedAs")?.let { add("exportedAs", it) }
            current?.record?.attrValue("origin")?.let { add("origin", it) }
        }
    }.toAttributes()

    return (leading + diffAttributes(previous, current, fallback)).distinctBy { it.name }
}

private fun MutableList<Pair<String, String>>.addIfChanged(
    previousName: String,
    previousValue: String?,
    currentName: String,
    currentValue: String?
) {
    if (previousValue == currentValue) return
    previousValue?.let { add(previousName, it) }
    currentValue?.let { add(currentName, it) }
}

private fun FunctionCallGraphResult.toTraceFunctionRecord(detail: String): ArtifactRecordResult {
    return ArtifactRecordResult(
        id = "function:$funcidx",
        entityType = "function",
        status = "current",
        source = "current",
        name = exportedAs.firstOrNull() ?: importInfo?.name ?: "function[$funcidx]",
        kind = kind,
        index = funcidx,
        signature = signature?.toArtifactSignature(),
        summary = "trace root function[$funcidx] ${exportedAs.firstOrNull() ?: importInfo?.name ?: ""}".trim(),
        attributes = buildList {
            importInfo?.let { add("import", "${it.module}.${it.name}") }
            if (exportedAs.isNotEmpty()) add("exportedAs", exportedAs.joinToString(","))
            typeidx?.let { add("typeidx", it.toString()) }
            codeOffset?.let { add("codeOffset", it.toString()) }
            codeSize?.let { add("codeSize", it.toString()) }
            instructionCount?.let { add("instructionCount", it.toString()) }
            add("directCalls", directCalls.size.toString())
            add("directImportedCalls", directImportedCalls.size.toString())
            add("indirectCalls", indirectCalls.size.toString())
            add("memoryWrites", directMemoryWrites.size.toString())
            if (detail == "evidence") add("locals", locals.joinToString(",") { "${it.count}:${it.type}" })
        }.toAttributes()
    )
}

private fun matchesAny(terms: List<String>, vararg entities: EntitySnapshot?): Boolean =
    terms.isEmpty() || entities.any { it?.matches(terms) == true }

private fun List<EntitySnapshot>.filterEntitySnapshotsByTermsOrAll(terms: List<String>): List<EntitySnapshot> {
    if (terms.isEmpty()) return this
    return filter { it.matches(terms) }.ifEmpty { this }
}

private fun List<ArtifactRecordResult>.filterRecordsByTermsOrAll(terms: List<String>): List<ArtifactRecordResult> {
    if (terms.isEmpty()) return this
    return filter { it.matches(terms) }.ifEmpty { this }
}

private fun ArtifactRecordResult.matches(terms: List<String>): Boolean {
    if (terms.isEmpty()) return true
    val haystack = buildString {
        append(id).append('\n')
        append(entityType).append('\n')
        append(status).append('\n')
        append(summary).append('\n')
        name?.let { append(it).append('\n') }
        kind?.let { append(it).append('\n') }
        signature?.let { append(it.compact()).append('\n') }
        attributes.forEach { append(it.name).append('=').append(it.value).append('\n') }
    }.lowercase()
    return terms.any { it in haystack }
}

private fun ArtifactRecordResult.displayLabel(): String =
    name?.let { "`$it`" } ?: id

private fun ArtifactRecordResult.attrValue(name: String): String? =
    attributes.firstOrNull { it.name == name }?.value

private fun List<String>.compactList(maxItems: Int): String {
    if (isEmpty()) return "[]"
    val visible = take(maxItems)
    val suffix = if (size > maxItems) ",...(${size - maxItems} more)" else ""
    return visible.joinToString(separator = ",", prefix = "[", postfix = "$suffix]")
}

private fun List<String>.activeElementBaseSlot(): Int? {
    val expr = firstOrNull() ?: return null
    return Regex("""i32\.const\s+(-?\d+)""").find(expr)?.groupValues?.getOrNull(1)?.toIntOrNull()
}

private fun publicSurfaceKindPriority(kind: String?): Int = when (kind) {
    "function" -> 0
    "memory" -> 1
    "global" -> 2
    "table" -> 3
    else -> 4
}

private fun GeneralFunctionEntry.origin(): String =
    if (import != null) "import" else "local"

private fun MemoryResult.origin(): String =
    if (definedIn == "import") "import" else "local"

private fun GlobalResult.origin(): String =
    if (definedIn == "import") "import" else "local"

private fun WasmBinaryProvider.importedMemoriesByIndex(): Map<Int, ImportName> {
    val importSection = wasmModule?.importSection ?: return emptyMap()
    return with(importSection) {
        bytes.getImportRecords()
            .filter { it.type is WasmMemType }
            .mapIndexed { index, record -> index to ImportName(record.prefix, record.name) }
            .toMap()
    }
}

private fun WasmBinaryProvider.importedTablesByIndex(): Map<Int, ImportName> {
    val importSection = wasmModule?.importSection ?: return emptyMap()
    return with(importSection) {
        bytes.getImportRecords()
            .filter { it.type is WasmTableExternalType }
            .mapIndexed { index, record -> index to ImportName(record.prefix, record.name) }
            .toMap()
    }
}

private fun List<String>.normalizedTerms(): List<String> =
    map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

private fun List<String>.normalizedEntityTypes(defaults: Set<String>): Set<String> {
    val normalized = map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    return normalized.ifEmpty { defaults }
}

private fun List<String>.normalizedRelations(): Set<String> {
    val normalized = map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    return normalized.ifEmpty { DEFAULT_TRACE_RELATIONS }.intersect(SUPPORTED_TRACE_RELATIONS)
}

private fun EntitySnapshot.matches(terms: List<String>): Boolean {
    if (terms.isEmpty()) return true
    val haystack = buildString {
        append(record.id).append('\n')
        append(record.entityType).append('\n')
        append(record.summary).append('\n')
        record.name?.let { append(it).append('\n') }
        record.kind?.let { append(it).append('\n') }
        record.signature?.let { append(it.compact()).append('\n') }
        record.attributes.forEach {
            append(it.name).append('=').append(it.value).append('\n')
        }
    }.lowercase()
    return terms.any { it in haystack }
}

private fun diffAttributes(
    previous: EntitySnapshot?,
    current: EntitySnapshot?,
    fallback: List<ArtifactAttributeResult>
): List<ArtifactAttributeResult> {
    val before = previous?.record?.attributes.orEmpty().associateBy { it.name }
    val after = current?.record?.attributes.orEmpty().associateBy { it.name }
    val keys = (before.keys + after.keys).sorted()
    val changed = keys.mapNotNull { key ->
        val oldValue = before[key]?.value
        val newValue = after[key]?.value
        if (oldValue == newValue) return@mapNotNull null
        ArtifactAttributeResult(
            name = key,
            value = "${oldValue ?: "<missing>"} -> ${newValue ?: "<missing>"}"
        )
    }
    return changed.ifEmpty { fallback }
}

private fun Byte.kindName(): String = when (toInt()) {
    0 -> "function"
    1 -> "table"
    2 -> "memory"
    3 -> "global"
    4 -> "tag"
    else -> "unknown"
}

private fun ParameterSignature.toArtifactSignature(): ArtifactSignatureResult =
    ArtifactSignatureResult(params = params, results = ret)

private fun FunctionSignatureResult.toArtifactSignature(): ArtifactSignatureResult =
    ArtifactSignatureResult(params = params, results = ret)

private fun ArtifactSignatureResult.compact(): String =
    "(${params.joinToString(",")}) -> (${results.joinToString(",")})"

private fun List<Pair<String, String>>.toAttributes(): List<ArtifactAttributeResult> =
    map { (name, value) -> ArtifactAttributeResult(name, value) }

private fun MutableList<Pair<String, String>>.add(name: String, value: String) {
    add(name to value)
}

private data class EntitySnapshot(
    val key: String,
    val comparisonKey: String,
    val record: ArtifactRecordResult
)

private data class ImportName(
    val module: String,
    val name: String
) {
    fun render(): String = "$module.$name"
}

private data class HostCallSnapshot(
    val key: String,
    val rootName: String,
    val hostName: String,
    val record: ArtifactRecordResult
)

private data class BudgetedRecords(
    val records: List<ArtifactRecordResult>,
    val omitted: ArtifactOmittedResult?
)
