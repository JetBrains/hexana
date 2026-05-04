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

private const val DEFAULT_LIMIT = 25
private const val MAX_LIMIT = 100
private const val DEFAULT_MAX_BYTES = 24_000
private const val MIN_MAX_BYTES = 2_000
private const val MAX_MAX_BYTES = 60_000

private val SUPPORTED_OPERATIONS = setOf("overview", "find", "diff", "trace", "triage")
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
    val exportedMemoryNames = exports
        .filter { it.kind.kindName() == "memory" }
        .groupBy(
            keySelector = { it.externIndex.toInt() },
            valueTransform = { it.name }
        )

    return buildList {
        if ("export" in requestedTypes) {
            addAll(exports.map { it.toExportEntity(source, functionsByIndex, memoriesByIndex, detail) })
        }
        if ("import" in requestedTypes) {
            addAll(readImportFunctions().imports.map { it.toImportEntity(source) })
        }
        if ("memory" in requestedTypes) {
            addAll(readMemory().memories.map { it.toMemoryEntity(source, exportedMemoryNames, detail) })
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
    detail: String
): EntitySnapshot {
    val kindName = kind.kindName()
    val function = functionsByIndex[externIndex.toInt()].takeIf { kindName == "function" }
    val memory = memoriesByIndex[externIndex.toInt()].takeIf { kindName == "memory" }
    val signature = function?.signature?.toArtifactSignature()
    val attributes = buildList {
        add("externIndex", externIndex.toString())
        add("kindTag", kind.toString())
        function?.let { entry ->
            entry.typeidx?.let { add("typeidx", it.toString()) }
            add("definedIn", entry.definedIn)
            if (detail == "evidence") {
                entry.codeOffset?.let { add("codeOffset", it.toString()) }
                entry.instructionCount?.let { add("instructionCount", it.toString()) }
            }
        }
        memory?.let { mem ->
            add("memoryMin", mem.limit.min.toString())
            add("memoryMax", mem.limit.max?.toString() ?: "none")
            add("memoryAddressType", mem.limit.addressType)
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
    detail: String
): EntitySnapshot {
    val exports = exportedNames[memoryIdx].orEmpty()
    val attributes = buildList {
        add("definedIn", definedIn)
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
            name = exports.firstOrNull(),
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
        if (detail == "evidence") expression?.let { add("expression", it) }
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

private data class BudgetedRecords(
    val records: List<ArtifactRecordResult>,
    val omitted: ArtifactOmittedResult?
)
