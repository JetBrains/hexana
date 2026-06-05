package org.jetbrains.hexana.mcp.query

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.hexana.mcp.DataEntry
import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.ParameterSignature
import org.jetbrains.hexana.mcp.encodeHexanaJson
import org.jetbrains.hexana.mcp.listDataSectionInternal
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactAttributeResult
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactModuleResult
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactOmittedResult
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactRecordResult
import org.jetbrains.hexana.mcp.model.artifacts.ArtifactSignatureResult
import org.jetbrains.hexana.mcp.model.artifacts.QueryArtifactsResult
import org.jetbrains.hexana.mcp.model.elements.ElementSegmentResult
import org.jetbrains.hexana.mcp.model.exports.ExportResult
import org.jetbrains.hexana.mcp.model.functions.FunctionCallGraphResult
import org.jetbrains.hexana.mcp.model.functions.FunctionDirectCallResult
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
private const val SUMMARY_FACT_JSON_LIMIT = 64

private val SUPPORTED_OPERATIONS = setOf(
    "overview",
    "find",
    "diff",
    "trace",
    "triage",
    "api_diff",
    "public_surface",
    "abi_surface",
    "abi_binding_regeneration",
    "host_adapter_repair",
    "import_surface",
    "memory_contract",
    "global_state_policy",
    "data_payload_audit",
    "local_budget",
    "entrypoint_effects",
    "crash_frames",
    "dispatch_table_diff",
    "host_call_diff"
)
private val SUPPORTED_DETAILS = setOf("summary", "evidence")
private val DEFAULT_ENTITY_TYPES = setOf("export", "import", "memory", "global", "function")
private val DIFF_ENTITY_TYPES = setOf("export", "import", "memory", "global", "function", "element")
private val TRIAGE_ENTITY_TYPES = setOf("export", "import", "memory", "global", "element")
private val SUPPORTED_TRACE_RELATIONS = setOf("direct_call", "direct_imported_call", "indirect_call", "memory_write")
private val DEFAULT_TRACE_RELATIONS = setOf("direct_call", "indirect_call", "memory_write")
private val ENTRYPOINT_EFFECT_RELATIONS = setOf("direct_imported_call", "memory_write")
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
private val API_DIFF_STATUS_PRIORITY = mapOf(
    "added" to 0,
    "removed" to 1,
    "changed" to 2
)

fun WasmBinaryProvider.readArtifactQuery(
    baseline: WasmBinaryProvider? = null,
    bindingText: String? = null,
    configText: String? = null,
    operation: String = "overview",
    entityTypes: List<String> = emptyList(),
    query: List<String> = emptyList(),
    startName: String? = null,
    startFuncidx: Int? = null,
    relations: List<String> = emptyList(),
    limit: Int = DEFAULT_LIMIT,
    detail: String = "summary",
    maxBytes: Int = DEFAULT_MAX_BYTES,
    pathLabel: String? = null,
    baselinePathLabel: String? = null,
    bindingPathLabel: String? = null,
    configPathLabel: String? = null
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
        "api_diff" -> {
            if (baseline == null) {
                warnings += "`api_diff` requires `baselinePath`; returned current public exports instead."
                publicSurfaceRecords(
                    terms = query.normalizedTerms(),
                    detail = effectiveDetail
                )
            } else {
                apiDiffRecords(
                    baseline = baseline,
                    terms = query.normalizedTerms()
                )
            }
        }
        "public_surface" -> publicSurfaceRecords(
            terms = query.normalizedTerms(),
            detail = effectiveDetail
        )
        "abi_surface" -> abiSurfaceRecords(
            terms = query.normalizedTerms()
        )
        "abi_binding_regeneration" -> abiBindingRegenerationRecords(
            terms = query.normalizedTerms(),
            bindingText = bindingText,
            pathLabel = pathLabel,
            bindingPathLabel = bindingPathLabel,
            warnings = warnings
        )
        "host_adapter_repair" -> hostAdapterRepairRecords(
            terms = query.normalizedTerms(),
            configText = configText,
            pathLabel = pathLabel,
            configPathLabel = configPathLabel,
            warnings = warnings
        )
        "import_surface" -> importSurfaceRecords(
            terms = query.normalizedTerms()
        )
        "memory_contract" -> memoryContractRecords(
            baseline = baseline,
            terms = query.normalizedTerms(),
            detail = effectiveDetail
        )
        "global_state_policy" -> globalStatePolicyRecords(
            baseline = baseline,
            terms = query.normalizedTerms()
        )
        "data_payload_audit" -> dataPayloadAuditRecords(
            terms = query.trimmedTerms(),
            detail = effectiveDetail
        )
        "local_budget" -> localBudgetRecords(
            terms = query.normalizedTerms(),
            detail = effectiveDetail
        )
        "entrypoint_effects" -> entrypointEffectsRecords(
            terms = query.normalizedTerms(),
            effects = relations.normalizedEntrypointEffects(),
            detail = effectiveDetail
        )
        "crash_frames" -> crashFrameRecords(
            startName = startName?.trim()?.takeIf { it.isNotEmpty() },
            startFuncidx = startFuncidx,
            terms = query.normalizedTerms(),
            limit = boundedLimit
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
        add(toArtifactModuleResult("current", pathLabel))
        baseline?.let { add(it.toArtifactModuleResult("baseline", baselinePathLabel)) }
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

private val hostAdapterConfigJson = Json {
    ignoreUnknownKeys = true
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

private fun WasmBinaryProvider.abiSurfaceRecords(
    terms: List<String>
): List<ArtifactRecordResult> {
    val functions = listFunctionEntries()
    val functionsByIndex = functions.associateBy { it.funcidx }
    val importFunctions = functions.filter { it.import != null }
    val importRecords = importFunctions.map { it.toAbiSurfaceImportRecord() }
    val exportFunctions = readExports().exports
        .filter { it.kind.kindName() == "function" }
        .mapNotNull { export ->
            val function = functionsByIndex[export.externIndex.toInt()] ?: return@mapNotNull null
            ExportedFunction(export.name, function)
        }
    val exportRecords = exportFunctions.map { it.function.toAbiSurfaceExportRecord(it.name) }
    val records = (importRecords + exportRecords)
        .filterRecordsByTermsOrAll(terms)
        .sortedWith(
            compareBy<ArtifactRecordResult> { abiSurfaceKindPriority(it.kind) }
                .thenBy { it.name ?: it.id }
        )
    return listOf(
        abiSurfaceFinding(
            records = records,
            allImports = importRecords,
            allExports = exportRecords,
            importFacts = importFunctions.map { it.toFunctionImportFact() },
            exportFacts = exportFunctions.map { it.function.toFunctionExportFact(it.name) }
        )
    ) + records
}

private fun WasmBinaryProvider.abiBindingRegenerationRecords(
    terms: List<String>,
    bindingText: String?,
    pathLabel: String?,
    bindingPathLabel: String?,
    warnings: MutableList<String>
): List<ArtifactRecordResult> {
    val functions = listFunctionEntries()
    val functionsByIndex = functions.associateBy { it.funcidx }
    val currentImports = functions
        .filter { it.import != null }
        .associate { function ->
            val import = requireNotNull(function.import)
            "${import.module}.${import.name}" to function.signature.toArtifactFunctionSignature()
        }
        .toSortedMap()
    val currentExports = readExports().exports
        .filter { it.kind.kindName() == "function" }
        .mapNotNull { export ->
            val function = functionsByIndex[export.externIndex.toInt()] ?: return@mapNotNull null
            export.name to function.signature.toArtifactFunctionSignature()
        }
        .toMap()
        .toSortedMap()
    val binding = bindingText?.let { parseAbiBindingFile(it, warnings) }
    if (bindingText == null) {
        warnings += "`abi_binding_regeneration` can compare stale bindings when `bindingPath` points at a common Python binding file with MODULE, IMPORTS, and EXPORTS literals."
    }
    val replacement = AbiReplacementBindings(
        module = binding?.module ?: pathLabel.orEmpty(),
        imports = currentImports.toMap(),
        exports = currentExports.toMap()
    )
    val mismatches = binding?.let {
        abiBindingMismatches(
            currentImports = currentImports,
            currentExports = currentExports,
            staleImports = it.imports,
            staleExports = it.exports
        )
    }.orEmpty()
    val records = mismatches
        .filter { terms.isEmpty() || terms.any { term -> it.symbol.lowercase().contains(term) } }
        .map { it.toAbiBindingMismatchRecord() }

    return listOf(
        ArtifactRecordResult(
            id = "abi-binding-regeneration:summary",
            entityType = "finding",
            status = "current",
            source = "current",
            kind = "abi_binding_regeneration",
            summary = "ABI binding regeneration has ${currentImports.size} current imports, ${currentExports.size} current exports, and ${mismatches.size} stale binding mismatches",
            attributes = buildList {
                add("direction", "`expected` is the current Wasm signature or null when a stale binding should be removed; `actual` is the stale binding signature or null when a binding is missing.")
                pathLabel?.let { add("module", it) }
                bindingPathLabel?.let { add("bindingPath", it) }
                binding?.module?.let { add("staleModule", it) }
                add("currentImports", currentImports.size.toString())
                add("currentExports", currentExports.size.toString())
                add("staleImports", binding?.imports?.size?.toString() ?: "0")
                add("staleExports", binding?.exports?.size?.toString() ?: "0")
                add("mismatches", mismatches.size.toString())
                add("currentImportsJson", encodeHexanaJson(currentImports.toMap()))
                add("currentExportsJson", encodeHexanaJson(currentExports.toMap()))
                binding?.let {
                    add("staleImportsJson", encodeHexanaJson(it.imports))
                    add("staleExportsJson", encodeHexanaJson(it.exports))
                    add("bindingMismatchesJson", encodeHexanaJson(mismatches))
                }
                add("replacementBindingsJson", encodeHexanaJson(replacement))
            }.toAttributes()
        )
    ) + records
}

private fun WasmBinaryProvider.importSurfaceRecords(
    terms: List<String>
): List<ArtifactRecordResult> {
    val functions = listFunctionEntries()
    val functionsByIndex = functions.associateBy { it.funcidx }
    val importFunctions = functions.filter { it.import != null }
    val importRecords = importFunctions
        .map {
            val record = it.toAbiSurfaceImportRecord()
            record.copy(id = record.id.replace("abi-surface:", "import-surface:"))
        }
    val exportFacts = readExports().exports
        .filter { it.kind.kindName() == "function" }
        .mapNotNull { export ->
            val function = functionsByIndex[export.externIndex.toInt()] ?: return@mapNotNull null
            function.toFunctionExportFact(export.name)
        }
    val records = importRecords
        .filterRecordsByTermsOrAll(terms)
        .sortedWith(
            compareBy<ArtifactRecordResult> { it.attrValue("module").orEmpty() }
                .thenBy { it.name ?: it.id }
        )
    return listOf(
        importSurfaceFinding(
            records = records,
            allImports = importRecords,
            terms = terms,
            importFacts = importFunctions.map { it.toFunctionImportFact() },
            exportFacts = exportFacts
        )
    ) + records
}

private fun WasmBinaryProvider.hostAdapterRepairRecords(
    terms: List<String>,
    configText: String?,
    pathLabel: String?,
    configPathLabel: String?,
    warnings: MutableList<String>
): List<ArtifactRecordResult> {
    val functions = listFunctionEntries()
    val functionsByIndex = functions.associateBy { it.funcidx }
    val importFacts = functions
        .filter { it.import != null }
        .map { it.toHostAdapterRequiredImport() }
    val entrypointCandidates = readExports().exports
        .filter { it.kind.kindName() == "function" }
        .mapNotNull { export ->
            val function = functionsByIndex[export.externIndex.toInt()] ?: return@mapNotNull null
            if (function.import != null) return@mapNotNull null
            HostAdapterEntrypointFact(
                export = export.name,
                params = function.signature?.params.orEmpty(),
                results = function.signature?.ret.orEmpty()
            )
        }
        .sortedBy { it.export }
    val config = configText?.let { parseHostAdapterConfig(it, warnings) }
    if (configText == null) {
        warnings += "`host_adapter_repair` can compare stale adapter JSON when `configPath` points at the active host adapter config."
    }
    val requiredEntrypoint = selectHostAdapterEntrypoint(
        candidates = entrypointCandidates,
        terms = terms,
        configured = config?.entrypoint?.export
    )
    val mismatches = hostAdapterConfigMismatches(
        requiredImports = importFacts,
        requiredEntrypoint = requiredEntrypoint,
        config = config
    )
    val repairConfig = buildHostAdapterRepairConfig(
        module = pathLabel ?: config?.module.orEmpty(),
        imports = importFacts,
        entrypoint = requiredEntrypoint
    )
    val mismatchRecords = mismatches.map { mismatch ->
        ArtifactRecordResult(
            id = "host-adapter-repair:mismatch:${mismatch.field}",
            entityType = "config_mismatch",
            status = "changed",
            source = "current",
            name = mismatch.field,
            kind = "host_adapter_config_mismatch",
            summary = "Host adapter config `${mismatch.field}` differs from the current Wasm ABI.",
            attributes = buildList {
                add("field", mismatch.field)
                add("expectedJson", encodeHexanaJson(mismatch.expected))
                add("actualJson", encodeHexanaJson(mismatch.actual))
            }.toAttributes()
        )
    }
    return listOf(
        ArtifactRecordResult(
            id = "host-adapter-repair:summary",
            entityType = "finding",
            status = "current",
            source = "current",
            kind = "host_adapter_repair",
            summary = "Host adapter repair has ${importFacts.size} required imports, ${entrypointCandidates.size} local entrypoint candidates, and ${mismatches.size} config mismatches",
            attributes = buildList {
                add("repairSchema", "Minimal repair_config contains only `module`, `imports`, and `entrypoint`; stale fields such as `profile`, `rollback_entrypoint`, `legacy_profiles`, and `notes` are not part of the repair output.")
                pathLabel?.let { add("module", it) }
                configPathLabel?.let { add("configPath", it) }
                add("requiredImports", importFacts.size.toString())
                add("entrypointCandidates", entrypointCandidates.size.toString())
                requiredEntrypoint?.let { add("requiredEntrypoint", it.export) }
                config?.ignoredTopLevelFields
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add("ignoredConfigFields", it.joinToString(",")) }
                addJsonFacts("requiredImportsJson", importFacts)
                add("requiredEntrypointJson", encodeHexanaJson(requiredEntrypoint))
                add("configMismatchesJson", encodeHexanaJson(mismatches))
                add("repairConfigJson", encodeHexanaJson(repairConfig))
            }.toAttributes()
        )
    ) + mismatchRecords
}

private fun WasmBinaryProvider.apiDiffRecords(
    baseline: WasmBinaryProvider,
    terms: List<String>
): List<ArtifactRecordResult> {
    val currentExports = apiExportSnapshots(source = "current")
    val previousExports = baseline.apiExportSnapshots(source = "baseline")
    val currentByKey = currentExports.associateBy { it.key }
    val previousByKey = previousExports.associateBy { it.key }

    val records = (currentByKey.keys + previousByKey.keys).sorted().mapNotNull { key ->
        val currentExport = currentByKey[key]
        val previousExport = previousByKey[key]
        val status = when {
            currentExport != null && previousExport == null -> "added"
            currentExport == null && previousExport != null -> "removed"
            currentExport != null && previousExport != null && currentExport.comparisonKey != previousExport.comparisonKey -> "changed"
            else -> "unchanged"
        }
        if (status == "unchanged") return@mapNotNull null
        val selected = currentExport ?: previousExport ?: return@mapNotNull null
        val record = selected.record.copy(
            status = status,
            source = if (status == "removed") "baseline" else "current",
            summary = when (status) {
                "added" -> "Added public ${selected.kind} export `${selected.name}` ${selected.signature?.compact().orEmpty()}".trim()
                "removed" -> "Removed public ${selected.kind} export `${selected.name}` ${selected.signature?.compact().orEmpty()}".trim()
                else -> "Changed public ${selected.kind} export `${selected.name}` signature from ${previousExport?.signature?.compact() ?: "<missing>"} to ${currentExport?.signature?.compact() ?: "<missing>"}"
            },
            attributes = buildList {
                previousExport?.signature?.let { signature ->
                    add("previousParams", signature.params.compactList(maxItems = 12))
                    add("previousResults", signature.results.compactList(maxItems = 12))
                }
                currentExport?.signature?.let { signature ->
                    add("currentParams", signature.params.compactList(maxItems = 12))
                    add("currentResults", signature.results.compactList(maxItems = 12))
                }
                previousExport?.record?.index?.let { add("previousIndex", it.toString()) }
                currentExport?.record?.index?.let { add("currentIndex", it.toString()) }
                previousExport?.record?.attrValue("typeidx")?.let { add("previousTypeidx", it) }
                currentExport?.record?.attrValue("typeidx")?.let { add("currentTypeidx", it) }
                previousExport?.record?.attrValue("funcidx")?.let { add("previousFuncidx", it) }
                currentExport?.record?.attrValue("funcidx")?.let { add("currentFuncidx", it) }
            }.toAttributes()
        )
        record.takeIf { it.matches(terms) }
    }.sortedWith(
        compareBy<ArtifactRecordResult> { API_DIFF_STATUS_PRIORITY[it.status] ?: Int.MAX_VALUE }
            .thenBy { it.name ?: it.id }
    )

    val abiRiskRecords = referenceTypeImportRecords(terms)

    return listOf(apiDiffFinding(records, abiRiskRecords)) + records + abiRiskRecords
}

private fun WasmBinaryProvider.apiExportSnapshots(source: String): List<ApiExportSnapshot> {
    return entities(setOf("export"), source = source, detail = "summary")
        .mapNotNull { snapshot ->
            val record = snapshot.record
            val name = record.name ?: return@mapNotNull null
            val kind = record.kind ?: return@mapNotNull null
            ApiExportSnapshot(
                key = "$kind:$name",
                name = name,
                kind = kind,
                signature = record.signature,
                comparisonKey = buildList {
                    add(kind)
                    record.signature?.let { add(it.compact()) }
                    record.attrValue("memoryMin")?.let { add("min=$it") }
                    record.attrValue("memoryMax")?.let { add("max=$it") }
                    record.attrValue("type")?.let { add("type=$it") }
                    record.attrValue("mutable")?.let { add("mutable=$it") }
                }.joinToString("|"),
                record = record.copy(
                    attributes = buildList {
                        when (kind) {
                            "function" -> {
                                record.attrValue("origin")?.let { add("origin", it) }
                                record.attrValue("import")?.let { add("import", it) }
                                record.attrValue("typeidx")?.let { add("typeidx", it) }
                                record.attrValue("funcidx")?.let { add("funcidx", it) }
                            }
                            "memory" -> {
                                record.attrValue("origin")?.let { add("origin", it) }
                                record.attrValue("memoryMin")?.let { add("min", it) }
                                record.attrValue("memoryMax")?.let { add("max", it) }
                            }
                            "global" -> {
                                record.attrValue("origin")?.let { add("origin", it) }
                                record.attrValue("type")?.let { add("type", it) }
                                record.attrValue("mutable")?.let { add("mutable", it) }
                            }
                            "table" -> {
                                record.attrValue("origin")?.let { add("origin", it) }
                                record.attrValue("import")?.let { add("import", it) }
                            }
                        }
                    }.toAttributes()
                )
            )
        }
}

private fun WasmBinaryProvider.referenceTypeImportRecords(terms: List<String>): List<ArtifactRecordResult> {
    return listFunctionEntries()
        .asSequence()
        .filter { it.import != null }
        .filter { it.signature?.containsReferenceType() == true }
        .mapNotNull { function ->
            val import = function.import ?: return@mapNotNull null
            val signature = function.signature?.toArtifactSignature()
            val locationDisplay = "import ${import.module}.${import.name}"
            ArtifactRecordResult(
                id = "abi-reference-import:${import.module}.${import.name}",
                entityType = "import",
                status = "current",
                source = "current",
                name = import.name,
                kind = "reference_type_import",
                index = function.funcidx,
                locationKind = "import",
                module = import.module,
                typeidx = function.typeidx,
                locationDisplay = locationDisplay,
                signature = signature,
                summary = "Current import `${import.module}.${import.name}` uses reference-type ABI ${signature?.compact().orEmpty()}".trim(),
                attributes = buildList {
                    add("module", import.module)
                    add("name", import.name)
                    add("import", "${import.module}.${import.name}")
                    add("where", locationDisplay)
                    add("locationKind", "import")
                    add("locationDisplay", locationDisplay)
                    add("feature", "reference-types")
                    add("funcidx", function.funcidx.toString())
                    function.typeidx?.let { add("typeidx", it.toString()) }
                    function.signature?.let { signature ->
                        add("params", signature.params.normalizedReferenceTypes().compactList(maxItems = 12))
                        add("results", signature.ret.normalizedReferenceTypes().compactList(maxItems = 12))
                    }
                }.toAttributes()
            )
        }
        .toList()
        .filterRecordsByTermsOrAll(terms)
        .sortedWith(
            compareBy<ArtifactRecordResult> { it.attrValue("typeidx")?.toIntOrNull() ?: Int.MAX_VALUE }
                .thenBy { it.attrValue("import") ?: it.name ?: it.id }
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
        .map { it.toPublicSurfaceRecord() }
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
    return listOf(
        hostCallFinding(
            records = records,
            baseline = true,
            terms = terms,
            previousCalls = previousCalls,
            currentCalls = currentCalls
        )
    ) + records
}

private fun WasmBinaryProvider.globalStatePolicyRecords(
    baseline: WasmBinaryProvider?,
    terms: List<String>
): List<ArtifactRecordResult> {
    val globals = readGlobals().globals
    val currentGlobalExports = globalExportSnapshots(source = "current")
    val baselineGlobalExportNames = baseline?.globalExportSnapshots(source = "baseline").orEmpty()
        .map { it.export }
        .toSet()

    val mutableImportedGlobals = globals
        .filter { it.origin == "import" && it.mutability }
        .map { it.toMutableImportedGlobalPolicyRecord() }

    val newPublicGlobals = currentGlobalExports
        .filter { baseline == null || it.export !in baselineGlobalExportNames }
        .map { it.toNewPublicGlobalPolicyRecord() }
        .sortedBy { it.name ?: it.id }

    val allowedPublicGlobals = currentGlobalExports
        .filter { it.export in baselineGlobalExportNames }
        .sortedBy { it.export }

    val records = (mutableImportedGlobals + newPublicGlobals)
        .filterRecordsByTermsOrAll(terms)
    return listOf(globalStatePolicyFinding(records, allowedPublicGlobals)) + records
}

private fun WasmBinaryProvider.globalExportSnapshots(source: String): List<GlobalExportSnapshot> {
    val globalsByIndex = readGlobals().globals.associateBy { it.globalIdx }
    return readExports().exports
        .asSequence()
        .filter { it.kind.kindName() == "global" }
        .mapNotNull { export ->
            val globalidx = export.externIndex.toInt()
            val global = globalsByIndex[globalidx] ?: return@mapNotNull null
            GlobalExportSnapshot(
                source = source,
                export = export.name,
                global = global
            )
        }
        .toList()
}

private fun WasmBinaryProvider.dataPayloadAuditRecords(
    terms: List<String>,
    detail: String
): List<ArtifactRecordResult> {
    val wasmModule = requireNotNull(wasmModule)
    val segments = bytes.listDataSectionInternal(wasmModule)
    if (terms.isEmpty()) {
        return listOf(dataPayloadAuditNeedsQueryFinding(segments))
    }
    val records = segments.map { it.toDataPayloadRecord(terms, detail) }
    return listOf(dataPayloadAuditFinding(records, terms)) + records
}

private fun WasmBinaryProvider.localBudgetRecords(
    terms: List<String>,
    detail: String
): List<ArtifactRecordResult> {
    val exports = exportedFunctions()
    val selectedExports = if (terms.isEmpty()) {
        exports
    } else {
        exports.filter { export ->
            terms.any { term ->
                term in export.name.lowercase() ||
                    term == export.function.funcidx.toString() ||
                    export.function.signature?.let { signature ->
                        term in signature.toArtifactSignature().compact().lowercase()
                    } == true
            }
        }.ifEmpty { exports }
    }
    val graphsByIndex = if (selectedExports.isEmpty()) {
        emptyMap()
    } else {
        readFunctionCallGraph(selectedExports.map { it.function.funcidx }).functions.associateBy { it.funcidx }
    }
    val records = selectedExports.map { export ->
        graphsByIndex[export.function.funcidx].toLocalBudgetRecord(
            exportName = export.name,
            fallback = export.function,
            detail = detail
        )
    }
    return listOf(localBudgetFinding(records, terms)) + records
}

private fun WasmBinaryProvider.entrypointEffectsRecords(
    terms: List<String>,
    effects: Set<String>,
    detail: String
): List<ArtifactRecordResult> {
    val includeMemoryWrites = "memory_write" in effects
    val includeHostCalls = "direct_imported_call" in effects
    val exports = exportedFunctions()
        .filter { it.function.import == null }
    if (exports.isEmpty()) {
        return listOf(entrypointEffectsFinding(emptyList(), emptyList(), totalLocalExports = 0, effects = effects, terms = terms))
    }

    val localGraphsByIndex = readReachableLocalFunctionGraphs(exports.map { it.function.funcidx })
    val graphsByIndex = exports
        .mapNotNull { export -> localGraphsByIndex[export.function.funcidx]?.let { export.function.funcidx to it } }
        .toMap()
    val policyEntries = if (includeHostCalls) {
        exports.mapNotNull { export ->
            graphsByIndex[export.function.funcidx]?.toEntrypointPolicyEntry(export.name)
        }.sortedBy { it.export }
    } else {
        emptyList()
    }
    val transitivelyHostCallingExports = if (includeHostCalls) {
        exports.mapNotNull { export ->
            val graph = graphsByIndex[export.function.funcidx] ?: return@mapNotNull null
            export.name.takeIf {
                graph.directImportedCalls.isEmpty() && graph.reachesImportedCall(localGraphsByIndex)
            }
        }.sorted()
    } else {
        emptyList()
    }
    val allRecords = exports.mapNotNull { export ->
        graphsByIndex[export.function.funcidx]?.toEntrypointEffectsRecord(
            exportName = export.name,
            includeMemoryWrites = includeMemoryWrites,
            includeHostCalls = includeHostCalls,
            detail = detail
        )
    }
    val records = allRecords
        .filterRecordsByTermsOrAll(terms)
        .sortedBy { it.name ?: it.id }
    return listOf(
        entrypointEffectsFinding(
            allRecords = allRecords,
            returnedRecords = records,
            totalLocalExports = exports.size,
            effects = effects,
            terms = terms,
            policyEntries = policyEntries,
            transitivelyHostCallingExports = transitivelyHostCallingExports
        )
    ) + records
}

private fun WasmBinaryProvider.readReachableLocalFunctionGraphs(rootIndices: List<Int>): Map<Int, FunctionCallGraphResult> {
    val localIndices = listFunctionEntries()
        .asSequence()
        .filter { it.import == null }
        .map { it.funcidx }
        .toSet()
    val graphsByIndex = mutableMapOf<Int, FunctionCallGraphResult>()
    val pending = ArrayDeque(rootIndices.filter { it in localIndices }.distinct())

    while (pending.isNotEmpty()) {
        val batch = buildList {
            while (pending.isNotEmpty()) {
                val funcidx = pending.removeFirst()
                if (funcidx !in graphsByIndex && funcidx in localIndices) add(funcidx)
            }
        }
        if (batch.isEmpty()) continue

        for (graph in readFunctionCallGraph(batch).functions) {
            graphsByIndex[graph.funcidx] = graph
            graph.directCalls
                .map { it.targetFuncidx }
                .filter { it in localIndices && it !in graphsByIndex }
                .forEach { pending.addLast(it) }
        }
    }

    return graphsByIndex
}

private fun WasmBinaryProvider.crashFrameRecords(
    startName: String?,
    startFuncidx: Int?,
    terms: List<String>,
    limit: Int
): List<ArtifactRecordResult> {
    val startIndices = resolveStartFunctions(startName, startFuncidx, terms, limit)
    if (startIndices.isEmpty()) {
        return listOf(
            ArtifactRecordResult(
                id = "crash-frames:roots",
                entityType = "finding",
                status = "current",
                source = "current",
                summary = "No crash frame functions matched; pass `startFuncidx`, `startName`, or `query` funcidx/name values",
                attributes = buildList {
                    startName?.let { add("startName", it) }
                    startFuncidx?.let { add("startFuncidx", it.toString()) }
                    if (terms.isNotEmpty()) add("query", terms.joinToString(","))
                }.toAttributes()
            )
        )
    }

    val graph = readFunctionCallGraph(startIndices).functions
    val frameRecords = graph.map { it.toCrashFrameRecord() }
    val suspiciousCalls = graph.flatMap { function ->
        function.directImportedCalls.map { call ->
            val targetImport = call.target?.importInfo?.let { "${it.module}.${it.name}" }
            val callerName = function.exportedAs.firstOrNull() ?: "function:${function.funcidx}"
            ArtifactRecordResult(
                id = "crash-import-call:${function.funcidx}:${call.targetFuncidx}:${call.offset ?: "unknown"}",
                entityType = "host_call",
                status = "current",
                source = "current",
                relation = "direct_imported_call",
                targetId = "function:${call.targetFuncidx}",
                name = targetImport ?: "function:${call.targetFuncidx}",
                kind = "direct_imported_call",
                index = call.targetFuncidx,
                signature = call.target?.signature?.toArtifactSignature(),
                summary = "crash frame function[${function.funcidx}] `$callerName` directly calls `${targetImport ?: "function:${call.targetFuncidx}"}`",
                attributes = buildList {
                    add("callerFuncidx", function.funcidx.toString())
                    add("callerExport", callerName)
                    add("targetFuncidx", call.targetFuncidx.toString())
                    targetImport?.let { add("targetImport", it) }
                    add("instruction", call.text)
                    call.offset?.let { add("offset", it.toString()) }
                }.toAttributes()
            )
        }
    }

    return listOf(crashFrameFinding(frameRecords, suspiciousCalls)) + frameRecords + suspiciousCalls
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
    val directIndices = buildList {
        startFuncidx?.let(::add)
        addAll(terms.mapNotNull { it.toIntOrNull() })
    }
    val lookups = buildList {
        startName?.let(::add)
        addAll(terms.filter { it.toIntOrNull() == null })
    }.distinct()
    if (lookups.isEmpty()) return directIndices.distinct().take(limit.coerceAtMost(20))

    return (directIndices.asSequence() + lookups.asSequence()
        .flatMap { term -> readFunctionsByName(term, limit).functions.asSequence() }
        .map { it.funcidx })
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

private fun WasmBinaryProvider.exportedFunctions(): List<ExportedFunction> {
    val functionsByIndex = listFunctionEntries().associateBy { it.funcidx }
    return readExports().exports
        .filter { it.kind.kindName() == "function" }
        .mapNotNull { export ->
            val function = functionsByIndex[export.externIndex.toInt()] ?: return@mapNotNull null
            ExportedFunction(export.name, function)
        }
        .sortedBy { it.name }
}

private fun WasmBinaryProvider.toArtifactModuleResult(source: String, path: String?): ArtifactModuleResult {
    val summary = readModuleSummary()
    return ArtifactModuleResult(
        source = source,
        path = path,
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
            glob.importModule?.let { module -> glob.importName?.let { name -> add("import", "$module.$name") } }
            glob.importModule?.let { add("importModule", it) }
            glob.importName?.let { add("importName", it) }
            add("type", glob.type)
            add("mutable", glob.mutability.toString())
            glob.expression?.let { add("initExpr", it) }
            if (glob.exportedAs.isNotEmpty()) add("exportedAs", glob.exportedAs.joinToString(","))
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
        add("globalidx", globalIdx.toString())
        name?.let { add("name", it) }
        prefix?.let { add("module", it) }
        importModule?.let { add("importModule", it) }
        importName?.let { add("importName", it) }
        add("type", type)
        add("mutable", mutability.toString())
        add("definedIn", definedIn)
        add("origin", origin)
        expression?.let { add("expression", it) }
        if (exportedAs.isNotEmpty()) add("exportedAs", exportedAs.joinToString(","))
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

private fun ArtifactRecordResult.toPublicSurfaceRecord(): ArtifactRecordResult {
    val compactAttributes = buildList {
        when (kind) {
            "function" -> {
                index?.let { add("funcidx", it.toString()) }
                attrValue("origin")?.let { add("origin", it) }
                attrValue("import")?.let { add("import", it) }
                signature?.let { signature ->
                    add("params", signature.params.compactList(maxItems = 12))
                    add("results", signature.results.compactList(maxItems = 12))
                }
            }
            "memory" -> {
                index?.let { add("memidx", it.toString()) }
                attrValue("origin")?.let { add("origin", it) }
                attrValue("import")?.let { add("import", it) }
                attrValue("memoryMin")?.let { add("min", it) }
                attrValue("memoryMax")?.let { add("max", it) }
                attrValue("memoryAddressType")?.let { add("addressType", it) }
            }
            "global" -> {
                index?.let { add("globalidx", it.toString()) }
                attrValue("origin")?.let { add("origin", it) }
                attrValue("type")?.let { add("type", it) }
                attrValue("mutable")?.let { add("mutable", it) }
                attrValue("initExpr")?.let { add("initExpr", it) }
            }
            "table" -> {
                index?.let { add("tableidx", it.toString()) }
                attrValue("origin")?.let { add("origin", it) }
                attrValue("import")?.let { add("import", it) }
            }
        }
    }.toAttributes()
    return copy(attributes = compactAttributes)
}

private fun GeneralFunctionEntry.toAbiSurfaceImportRecord(): ArtifactRecordResult {
    val import = requireNotNull(import)
    val signature = signature?.toArtifactSignature()
    return ArtifactRecordResult(
        id = "abi-surface:import:${import.module}.${import.name}",
        entityType = "import",
        status = "current",
        source = "current",
        name = import.name,
        kind = "function_import",
        index = funcidx,
        signature = signature,
        summary = "function import `${import.module}.${import.name}` funcidx[$funcidx] ${signature?.compact().orEmpty()}".trim(),
        attributes = buildList {
            add("module", import.module)
            add("name", import.name)
            add("import", "${import.module}.${import.name}")
            add("funcidx", funcidx.toString())
            typeidx?.let { add("typeidx", it.toString()) }
            signature?.let {
                add("params", it.params.compactList(maxItems = 12))
                add("results", it.results.compactList(maxItems = 12))
            }
        }.toAttributes()
    )
}

private fun GeneralFunctionEntry.toAbiSurfaceExportRecord(exportName: String): ArtifactRecordResult {
    val signature = signature?.toArtifactSignature()
    return ArtifactRecordResult(
        id = "abi-surface:export:$exportName",
        entityType = "export",
        status = "current",
        source = "current",
        name = exportName,
        kind = "function_export",
        index = funcidx,
        signature = signature,
        summary = "function export `$exportName` funcidx[$funcidx] ${signature?.compact().orEmpty()}".trim(),
        attributes = buildList {
            add("export", exportName)
            add("funcidx", funcidx.toString())
            add("origin", origin())
            import?.let { add("import", "${it.module}.${it.name}") }
            typeidx?.let { add("typeidx", it.toString()) }
            signature?.let {
                add("params", it.params.compactList(maxItems = 12))
                add("results", it.results.compactList(maxItems = 12))
            }
        }.toAttributes()
    )
}

private fun GeneralFunctionEntry.toFunctionImportFact(): FunctionImportFact {
    val import = requireNotNull(import)
    return FunctionImportFact(
        module = import.module,
        name = import.name,
        qualifiedName = "${import.module}.${import.name}",
        funcidx = funcidx,
        typeidx = typeidx,
        signature = signature.toArtifactFunctionSignature()
    )
}

private fun GeneralFunctionEntry.toHostAdapterRequiredImport(): HostAdapterRequiredImport {
    val import = requireNotNull(import)
    return HostAdapterRequiredImport(
        name = "${import.module}.${import.name}",
        params = signature?.params.orEmpty(),
        results = signature?.ret.orEmpty()
    )
}

private fun GeneralFunctionEntry.toFunctionExportFact(exportName: String): FunctionExportFact {
    return FunctionExportFact(
        export = exportName,
        funcidx = funcidx,
        origin = origin(),
        typeidx = typeidx,
        signature = signature.toArtifactFunctionSignature()
    )
}

private fun abiSurfaceFinding(
    records: List<ArtifactRecordResult>,
    allImports: List<ArtifactRecordResult>,
    allExports: List<ArtifactRecordResult>,
    importFacts: List<FunctionImportFact>,
    exportFacts: List<FunctionExportFact>
): ArtifactRecordResult {
    val returnedImports = records.filter { it.kind == "function_import" }
    val returnedExports = records.filter { it.kind == "function_export" }
    val returnedImportNames = returnedImports.mapNotNull { it.attrValue("import") }.toSet()
    val returnedExportNames = returnedExports.mapNotNull { it.name }.toSet()
    val returnedImportFacts = importFacts.filter { it.qualifiedName in returnedImportNames }
    val returnedExportFacts = exportFacts.filter { it.export in returnedExportNames }
    return ArtifactRecordResult(
        id = "abi-surface:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "ABI surface has ${allImports.size} function imports and ${allExports.size} function exports",
        attributes = buildList {
            add("functionImports", allImports.size.toString())
            add("functionExports", allExports.size.toString())
            if (returnedImports.isNotEmpty()) {
                add("imports", returnedImports.mapNotNull { it.attrValue("import") }.distinct().sorted().joinToString(","))
                addJsonFacts("functionImportsJson", returnedImportFacts)
            }
            if (returnedExports.isNotEmpty()) {
                add("exports", returnedExports.mapNotNull { it.name }.distinct().sorted().joinToString(","))
                add("entrypoints", returnedExports.mapNotNull { it.name }.distinct().sorted().joinToString(","))
                addJsonFacts("functionExportsJson", returnedExportFacts)
                addJsonFacts("exportedEntrypointsJson", returnedExportFacts.filter { it.origin == "local" })
            }
        }.toAttributes()
    )
}

private fun importSurfaceFinding(
    records: List<ArtifactRecordResult>,
    allImports: List<ArtifactRecordResult>,
    terms: List<String>,
    importFacts: List<FunctionImportFact>,
    exportFacts: List<FunctionExportFact>
): ArtifactRecordResult {
    val returnedImportNames = records.mapNotNull { it.attrValue("import") }.toSet()
    val returnedImportFacts = importFacts.filter { it.qualifiedName in returnedImportNames }
    return ArtifactRecordResult(
        id = "import-surface:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Import surface has ${allImports.size} function imports",
        attributes = buildList {
            add("functionImports", allImports.size.toString())
            if (records.isNotEmpty()) {
                add("imports", records.mapNotNull { it.attrValue("import") }.distinct().sorted().joinToString(","))
                addJsonFacts("functionImportsJson", returnedImportFacts)
                records.mapNotNull { it.attrValue("typeidx")?.toIntOrNull() }
                    .distinct()
                    .sorted()
                    .takeIf { it.isNotEmpty() }
                    ?.let { add("targetedTypeIndices", it.joinToString(",")) }
            }
            if (exportFacts.isNotEmpty()) {
                addJsonFacts("functionExportsJson", exportFacts)
                addJsonFacts("exportedEntrypointsJson", exportFacts.filter { it.origin == "local" })
            }
            if (terms.isNotEmpty()) add("query", terms.joinToString(","))
        }.toAttributes()
    )
}

private fun apiDiffFinding(
    records: List<ArtifactRecordResult>,
    abiRiskRecords: List<ArtifactRecordResult>
): ArtifactRecordResult {
    val added = records.count { it.status == "added" }
    val removed = records.count { it.status == "removed" }
    val changed = records.count { it.status == "changed" }
    return ArtifactRecordResult(
        id = "api-diff:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Public API diff found $added added, $removed removed, $changed changed exports, and ${abiRiskRecords.size} reference-type imports",
        attributes = buildList {
            add("added", added.toString())
            add("removed", removed.toString())
            add("changed", changed.toString())
            add("referenceTypeImports", abiRiskRecords.size.toString())
            val focus = records.take(10).mapNotNull { it.name }
            if (focus.isNotEmpty()) add("focus", focus.joinToString(","))
            val targetedTypeIndices = (records + abiRiskRecords)
                .flatMap { record ->
                    listOfNotNull(
                        record.attrValue("previousTypeidx"),
                        record.attrValue("currentTypeidx"),
                        record.attrValue("typeidx")
                    )
                }
                .mapNotNull { it.toIntOrNull() }
                .distinct()
                .sorted()
            if (targetedTypeIndices.isNotEmpty()) add("targetedTypeIndices", targetedTypeIndices.joinToString(","))
            val referenceImports = abiRiskRecords.mapNotNull { it.attrValue("import") }.distinct().sorted()
            if (referenceImports.isNotEmpty()) add("referenceImports", referenceImports.joinToString(","))
        }.toAttributes()
    )
}

private fun globalStatePolicyFinding(
    records: List<ArtifactRecordResult>,
    allowedPublicGlobals: List<GlobalExportSnapshot>
): ArtifactRecordResult {
    val mutableImports = records.count { it.kind == "mutable_imported_global" }
    val newPublicGlobals = records.count { it.kind == "new_public_global" }
    val mutableImportFacts = records
        .filter { it.kind == "mutable_imported_global" }
        .mapNotNull { it.toGlobalPolicyFact() }
        .sortedWith(compareBy<GlobalPolicyFact> { it.globalidx }.thenBy { it.export.orEmpty() }.thenBy { it.importNameQualified.orEmpty() })
    val newPublicGlobalFacts = records
        .filter { it.kind == "new_public_global" }
        .mapNotNull { it.toGlobalPolicyFact() }
        .sortedWith(compareBy<GlobalPolicyFact> { it.export.orEmpty() }.thenBy { it.globalidx })
    val allowedPublicGlobalFacts = allowedPublicGlobals.map { it.toGlobalPolicyFact() }
    return ArtifactRecordResult(
        id = "global-policy:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Global policy found $mutableImports mutable imported globals and $newPublicGlobals new public globals",
        attributes = buildList {
            add("mutableImportedGlobals", mutableImports.toString())
            add("newPublicGlobals", newPublicGlobals.toString())
            if (allowedPublicGlobals.isNotEmpty()) add("allowedPublicGlobals", allowedPublicGlobals.joinToString(",") { it.export })
            if (mutableImportFacts.isNotEmpty()) addJsonFacts("mutableImportedGlobalsJson", mutableImportFacts)
            if (newPublicGlobalFacts.isNotEmpty()) addJsonFacts("newPublicGlobalsJson", newPublicGlobalFacts)
            if (allowedPublicGlobalFacts.isNotEmpty()) addJsonFacts("allowedPublicGlobalsJson", allowedPublicGlobalFacts)
            val targetedIndices = records.mapNotNull { it.index }.distinct().sorted()
            if (targetedIndices.isNotEmpty()) add("targetedGlobalIndices", targetedIndices.joinToString(","))
        }.toAttributes()
    )
}

private fun GlobalResult.toMutableImportedGlobalPolicyRecord(): ArtifactRecordResult {
    val importModule = importModule ?: prefix
    val importName = importName ?: name
    val import = listOfNotNull(importModule, importName).joinToString(".")
    return ArtifactRecordResult(
        id = "global-policy:mutable-import:$globalIdx",
        entityType = "global",
        status = "current",
        source = "current",
        name = importName,
        kind = "mutable_imported_global",
        index = globalIdx,
        summary = "Mutable imported global `$import` violates host global-state policy",
        attributes = buildList {
            add("globalidx", globalIdx.toString())
            if (import.isNotEmpty()) add("import", import)
            importModule?.let { add("importModule", it) }
            importName?.let { add("importName", it) }
            add("origin", origin)
            add("type", type)
            add("mutable", mutability.toString())
            if (exportedAs.isNotEmpty()) add("exportedAs", exportedAs.joinToString(","))
        }.toAttributes()
    )
}

private fun GlobalExportSnapshot.toNewPublicGlobalPolicyRecord(): ArtifactRecordResult {
    return ArtifactRecordResult(
        id = "global-policy:new-export:$export",
        entityType = "global",
        status = "added",
        source = "current",
        name = export,
        kind = "new_public_global",
        index = global.globalIdx,
        summary = "New exported global `$export` must be reported to the host global-state policy",
        attributes = buildList {
            add("export", export)
            add("globalidx", global.globalIdx.toString())
            add("origin", global.origin)
            global.importModule?.let { module -> global.importName?.let { name -> add("import", "$module.$name") } }
            global.importModule?.let { add("importModule", it) }
            global.importName?.let { add("importName", it) }
            add("type", global.type)
            add("mutable", global.mutability.toString())
            global.expression?.let { add("initExpr", it) }
        }.toAttributes()
    )
}

private fun ArtifactRecordResult.toGlobalPolicyFact(): GlobalPolicyFact? {
    val globalidx = index ?: attrValue("globalidx")?.toIntOrNull() ?: return null
    return GlobalPolicyFact(
        export = attrValue("export"),
        importNameQualified = attrValue("import"),
        globalidx = globalidx,
        type = attrValue("type").orEmpty(),
        mutable = attrValue("mutable")?.toBooleanStrictOrNull() ?: false,
        initExpr = attrValue("initExpr"),
        origin = attrValue("origin").orEmpty(),
        importModule = attrValue("importModule"),
        importName = attrValue("importName")
    )
}

private fun GlobalExportSnapshot.toGlobalPolicyFact(): GlobalPolicyFact {
    return GlobalPolicyFact(
        export = export,
        importNameQualified = global.importModule?.let { module -> global.importName?.let { name -> "$module.$name" } },
        globalidx = global.globalIdx,
        type = global.type,
        mutable = global.mutability,
        initExpr = global.expression,
        origin = global.origin,
        importModule = global.importModule,
        importName = global.importName
    )
}

private fun DataEntry.toDataPayloadRecord(
    terms: List<String>,
    detail: String
): ArtifactRecordResult {
    val matchedTerms = matchedPayloadTerms(terms)
    val mode = if (kind == 1) "passive" else "active"
    val memory = memoryIndex?.toString() ?: if (mode == "active") "0" else null
    val preview = utf8PayloadPreview(maxChars = if (detail == "evidence") 320 else 160)
    val matchText = matchedTerms.joinToString(",")
    return ArtifactRecordResult(
        id = "data-segment:$index",
        entityType = "data_segment",
        status = "current",
        source = "current",
        name = "data[$index]",
        kind = mode,
        index = index,
        summary = if (matchedTerms.isEmpty()) {
            "data segment[$index] $mode ${data.size} bytes has no query-term match"
        } else {
            "data segment[$index] $mode ${data.size} bytes contains raw payload term(s): $matchText"
        },
        attributes = buildList {
            add("segmentIndex", index.toString())
            add("mode", mode)
            memory?.let { add("memory", it) }
            expression?.let { add("offsetExpr", it) }
            add("byteLength", data.size.toString())
            add("clean", matchedTerms.isEmpty().toString())
            if (matchedTerms.isNotEmpty()) {
                add("matchedTerms", matchText)
                add("rawBytesConfirmed", "true")
            }
            if (preview.isNotBlank()) add("utf8Preview", preview)
            if (detail == "evidence") {
                add("hexPreview", data.take(48).joinToString(separator = " ") { it.toHexString() })
            }
        }.toAttributes()
    )
}

private fun dataPayloadAuditFinding(
    records: List<ArtifactRecordResult>,
    terms: List<String>
): ArtifactRecordResult {
    val matched = records.filter { it.attrValue("matchedTerms") != null }
    val clean = records.filter { it.attrValue("matchedTerms") == null }
    val active = records.count { it.kind == "active" }
    val passive = records.count { it.kind == "passive" }
    return ArtifactRecordResult(
        id = "data-payload-audit:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Data payload audit scanned ${records.size} segments and matched ${matched.size} segments",
        attributes = buildList {
            add("scannedSegments", records.size.toString())
            add("activeSegments", active.toString())
            add("passiveSegments", passive.toString())
            add("matchedSegments", matched.mapNotNull { it.index }.sorted().joinToString(","))
            add("cleanSegments", clean.mapNotNull { it.index }.sorted().joinToString(","))
            if (terms.isNotEmpty()) add("queryTerms", terms.joinToString(","))
            val matchedTerms = matched.flatMap { it.attrValue("matchedTerms").orEmpty().split(",") }
                .filter { it.isNotBlank() }
                .distinct()
            if (matchedTerms.isNotEmpty()) add("matchedTerms", matchedTerms.joinToString(","))
        }.toAttributes()
    )
}

private fun dataPayloadAuditNeedsQueryFinding(
    segments: List<DataEntry>
): ArtifactRecordResult {
    val active = segments.count { it.kind != 1 }
    val passive = segments.count { it.kind == 1 }
    return ArtifactRecordResult(
        id = "data-payload-audit:needs-query",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Data payload audit needs forbidden payload strings in `query`; read the policy file first and call again with exact terms",
        attributes = buildList {
            add("queryRequired", "true")
            add("scannedSegments", segments.size.toString())
            add("activeSegments", active.toString())
            add("passiveSegments", passive.toString())
        }.toAttributes()
    )
}

private fun FunctionCallGraphResult?.toLocalBudgetRecord(
    exportName: String,
    fallback: GeneralFunctionEntry,
    detail: String
): ArtifactRecordResult {
    val signature = this?.signature?.toArtifactSignature() ?: fallback.signature?.toArtifactSignature()
    val localsByType = this?.locals.orEmpty()
        .groupingBy { it.type }
        .fold(0) { acc, local -> acc + local.count }
        .toSortedMap()
    val localCount = localsByType.values.sum()
    return ArtifactRecordResult(
        id = "local-budget:export:$exportName",
        entityType = "function",
        status = "current",
        source = "current",
        name = exportName,
        kind = "local_profile",
        index = fallback.funcidx,
        signature = signature,
        summary = "export `$exportName` funcidx[${fallback.funcidx}] declares $localCount locals",
        attributes = buildList {
            add("export", exportName)
            add("funcidx", fallback.funcidx.toString())
            fallback.typeidx?.let { add("typeidx", it.toString()) }
            signature?.let {
                add("params", it.params.compactList(maxItems = 12))
                add("results", it.results.compactList(maxItems = 12))
            }
            add("localCount", localCount.toString())
            if (localsByType.isNotEmpty()) {
                add("localsByType", localsByType.entries.joinToString(",") { "${it.key}=${it.value}" })
                add("locals", localsByType.entries.joinToString(",") { "${it.value}:${it.key}" })
            } else {
                add("localsByType", "[]")
                add("locals", "[]")
            }
            if (detail == "evidence") {
                this@toLocalBudgetRecord?.codeOffset?.let { add("codeOffset", it.toString()) }
                this@toLocalBudgetRecord?.codeSize?.let { add("codeSize", it.toString()) }
                this@toLocalBudgetRecord?.instructionCount?.let { add("instructionCount", it.toString()) }
            }
        }.toAttributes()
    )
}

private fun FunctionCallGraphResult.toEntrypointEffectsRecord(
    exportName: String,
    includeMemoryWrites: Boolean,
    includeHostCalls: Boolean,
    detail: String
): ArtifactRecordResult? {
    val memoryWrites = if (includeMemoryWrites) directMemoryWrites else emptyList()
    val directHostCalls = if (includeHostCalls) directImportedCalls else emptyList()
    val calledImports = directHostCalls.toPolicyCalledImports()
    if (memoryWrites.isEmpty() && calledImports.isEmpty()) return null

    val signature = signature?.toArtifactSignature()
    val calledImportNames = calledImports.map { "${it.module}.${it.name}" }
    val policyEntry = toEntrypointPolicyEntry(exportName)
    val effectSummary = buildList {
        if (memoryWrites.isNotEmpty()) add("${memoryWrites.size} direct memory write(s)")
        if (calledImports.isNotEmpty()) add("direct host import call(s) to ${calledImportNames.joinToString(",")}")
    }.joinToString(" and ")
    return ArtifactRecordResult(
        id = "entrypoint-effects:export:$exportName",
        entityType = "entrypoint",
        status = "current",
        source = "current",
        name = exportName,
        kind = "entrypoint_effects",
        index = funcidx,
        signature = signature,
        summary = "export `$exportName` funcidx[$funcidx] has $effectSummary",
        attributes = buildList {
            add("export", exportName)
            add("funcidx", funcidx.toString())
            typeidx?.let { add("typeidx", it.toString()) }
            add("origin", "local")
            signature?.let {
                add("params", it.params.compactList(maxItems = 12))
                add("results", it.results.compactList(maxItems = 12))
            }
            add("directMemoryWrites", memoryWrites.size.toString())
            if (memoryWrites.isNotEmpty()) {
                add("memoryWriteOps", memoryWrites.map { it.op }.distinct().sorted().joinToString(","))
            }
            add("directHostCalls", directHostCalls.size.toString())
            if (calledImports.isNotEmpty()) {
                add("calledImports", calledImportNames.joinToString(","))
                add(
                    "calledImportSignatures",
                    calledImports.joinToString(";") { import ->
                        "${import.module}.${import.name}:${import.signature.compact()}"
                    }
                )
                add("calledImportsJson", encodeHexanaJson(calledImports))
                policyEntry?.let { add("directHostPolicyEntryJson", encodeHexanaJson(it)) }
            }
            if (detail == "evidence") {
                if (memoryWrites.isNotEmpty()) {
                    add("memoryWriteInstructions", memoryWrites.joinToString(";") { it.text.take(120) })
                }
                if (directHostCalls.isNotEmpty()) {
                    add("directHostCallInstructions", directHostCalls.joinToString(";") { it.text.take(120) })
                }
            }
        }.toAttributes()
    )
}

private fun FunctionCallGraphResult.toEntrypointPolicyEntry(exportName: String): EntrypointPolicyEntry? {
    val calledImports = directImportedCalls.toPolicyCalledImports()
    if (calledImports.isEmpty()) return null
    return EntrypointPolicyEntry(
        export = exportName,
        funcidx = funcidx,
        signature = EntrypointSignature(
            params = signature?.params.orEmpty(),
            results = signature?.ret.orEmpty()
        ),
        calledImports = calledImports
    )
}

private fun List<FunctionDirectCallResult>.toPolicyCalledImports(): List<EntrypointPolicyCalledImport> {
    return mapNotNull { call ->
        val import = call.target?.importInfo ?: return@mapNotNull null
        EntrypointPolicyCalledImport(
            module = import.module,
            name = import.name,
            signature = EntrypointImportSignature(
                params = call.target.signature?.params.orEmpty(),
                result = call.target.signature?.ret.orEmpty()
            )
        )
    }
        .distinctBy { "${it.module}.${it.name}" }
        .sortedWith(compareBy<EntrypointPolicyCalledImport> { it.module }.thenBy { it.name })
}

private fun FunctionCallGraphResult.reachesImportedCall(
    localGraphsByIndex: Map<Int, FunctionCallGraphResult>
): Boolean {
    val visited = mutableSetOf(funcidx)
    val queue = ArrayDeque<Int>()
    directCalls
        .map { it.targetFuncidx }
        .filter { localGraphsByIndex.containsKey(it) }
        .forEach { queue.addLast(it) }

    while (queue.isNotEmpty()) {
        val targetFuncidx = queue.removeFirst()
        if (!visited.add(targetFuncidx)) continue
        val targetGraph = localGraphsByIndex[targetFuncidx] ?: continue
        if (targetGraph.directImportedCalls.isNotEmpty()) return true
        targetGraph.directCalls
            .map { it.targetFuncidx }
            .filter { localGraphsByIndex.containsKey(it) }
            .forEach { queue.addLast(it) }
    }
    return false
}

private fun localBudgetFinding(
    records: List<ArtifactRecordResult>,
    terms: List<String>
): ArtifactRecordResult {
    val maxLocalCount = records.mapNotNull { it.attrValue("localCount")?.toIntOrNull() }.maxOrNull() ?: 0
    val maxLocalExports = records
        .filter { it.attrValue("localCount")?.toIntOrNull() == maxLocalCount }
        .mapNotNull { it.name }
        .sorted()
    val zeroLocalExports = records
        .filter { it.attrValue("localCount") == "0" }
        .mapNotNull { it.name }
        .sorted()
    return ArtifactRecordResult(
        id = "local-budget:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Local budget scan returned ${records.size} exported functions; max declared locals=$maxLocalCount",
        attributes = buildList {
            add("checkedExports", records.mapNotNull { it.name }.sorted().joinToString(","))
            add("functionExports", records.size.toString())
            add("maxLocalCount", maxLocalCount.toString())
            if (maxLocalExports.isNotEmpty()) add("maxLocalExports", maxLocalExports.joinToString(","))
            if (zeroLocalExports.isNotEmpty()) add("zeroLocalExports", zeroLocalExports.joinToString(","))
            if (terms.isNotEmpty()) add("query", terms.joinToString(","))
            val localCounts = records
                .mapNotNull { record -> record.name?.let { name -> record.attrValue("localCount")?.let { "$name=$it" } } }
                .sorted()
            if (localCounts.isNotEmpty()) add("localCounts", localCounts.joinToString(","))
        }.toAttributes()
    )
}

private fun entrypointEffectsFinding(
    allRecords: List<ArtifactRecordResult>,
    returnedRecords: List<ArtifactRecordResult>,
    totalLocalExports: Int,
    effects: Set<String>,
    terms: List<String>,
    policyEntries: List<EntrypointPolicyEntry> = emptyList(),
    transitivelyHostCallingExports: List<String> = emptyList()
): ArtifactRecordResult {
    val memoryWritingExports = allRecords.filter { it.attrValue("directMemoryWrites")?.toIntOrNull().orZero() > 0 }
    val hostCallingExports = allRecords.filter { it.attrValue("directHostCalls")?.toIntOrNull().orZero() > 0 }
    return ArtifactRecordResult(
        id = "entrypoint-effects:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Entrypoint effect scan found ${allRecords.size} effectful exported functions across $totalLocalExports local function exports",
        attributes = buildList {
            add("effects", effects.sorted().joinToString(","))
            add("localFunctionExports", totalLocalExports.toString())
            add("effectfulExports", allRecords.size.toString())
            add("memoryWritingExports", memoryWritingExports.size.toString())
            add("hostCallingExports", hostCallingExports.size.toString())
            add("cleanExportCount", (totalLocalExports - allRecords.size).coerceAtLeast(0).toString())
            if (memoryWritingExports.isNotEmpty()) {
                add("memoryWritingExportNames", memoryWritingExports.mapNotNull { it.name }.sorted().joinToString(","))
            }
            if (hostCallingExports.isNotEmpty()) {
                add("hostCallingExportNames", hostCallingExports.mapNotNull { it.name }.sorted().joinToString(","))
                add(
                    "calledImports",
                    hostCallingExports.flatMap { it.attrValue("calledImports").orEmpty().split(",") }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                        .joinToString(",")
                )
            }
            if ("direct_imported_call" in effects) {
                add("directHostEntryPointsJson", encodeHexanaJson(policyEntries))
                add("excludedTransitiveExportsJson", encodeHexanaJson(transitivelyHostCallingExports))
            }
            if (transitivelyHostCallingExports.isNotEmpty()) {
                add("transitiveHostCallingExportNames", transitivelyHostCallingExports.joinToString(","))
            }
            if (returnedRecords.isNotEmpty()) {
                add("returnedExports", returnedRecords.mapNotNull { it.name }.sorted().joinToString(","))
            }
            if (terms.isNotEmpty()) add("query", terms.joinToString(","))
        }.toAttributes()
    )
}

private fun FunctionCallGraphResult.toCrashFrameRecord(): ArtifactRecordResult {
    return ArtifactRecordResult(
        id = "crash-frame:function:$funcidx",
        entityType = "crash_frame",
        status = "current",
        source = "current",
        name = exportedAs.firstOrNull() ?: importInfo?.name ?: "function:$funcidx",
        kind = kind,
        index = funcidx,
        signature = signature?.toArtifactSignature(),
        summary = "crash frame function[$funcidx] ${exportedAs.firstOrNull() ?: importInfo?.name ?: ""}".trim(),
        attributes = buildList {
            add("origin", if (importInfo != null) "import" else "local")
            importInfo?.let { add("import", "${it.module}.${it.name}") }
            if (exportedAs.isNotEmpty()) add("exportedAs", exportedAs.joinToString(","))
            if (locals.isNotEmpty()) add("locals", locals.joinToString(",") { "${it.count}:${it.type}" })
            typeidx?.let { add("typeidx", it.toString()) }
            codeOffset?.let { add("codeOffset", it.toString()) }
            codeSize?.let { add("codeSize", it.toString()) }
            instructionCount?.let { add("instructionCount", it.toString()) }
        }.toAttributes()
    )
}

private fun crashFrameFinding(
    frames: List<ArtifactRecordResult>,
    suspiciousCalls: List<ArtifactRecordResult>
): ArtifactRecordResult {
    return ArtifactRecordResult(
        id = "crash-frames:summary",
        entityType = "finding",
        status = "current",
        source = "current",
        summary = "Crash frame query resolved ${frames.size} functions and ${suspiciousCalls.size} direct imported calls",
        attributes = buildList {
            if (frames.isNotEmpty()) add("funcidx", frames.mapNotNull { it.index }.joinToString(","))
            if (suspiciousCalls.isNotEmpty()) {
                add("directImportedCalls", suspiciousCalls.mapNotNull { it.attrValue("targetImport") ?: it.name }.distinct().joinToString(","))
            }
        }.toAttributes()
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
    terms: List<String>,
    previousCalls: List<HostCallSnapshot> = emptyList(),
    currentCalls: List<HostCallSnapshot> = emptyList()
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
            if (previousCalls.isNotEmpty()) add("oldDirectHostCalls", previousCalls.hostCallNames().joinToString(","))
            if (currentCalls.isNotEmpty()) add("currentDirectHostCalls", currentCalls.hostCallNames().joinToString(","))
            if (currentCalls.isNotEmpty()) add("entrypoints", currentCalls.map { it.rootName }.distinct().sorted().joinToString(","))
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

private fun List<HostCallSnapshot>.hostCallNames(): List<String> =
    map { it.hostName }.distinct().sorted()

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

private fun Int?.orZero(): Int = this ?: 0

private fun publicSurfaceKindPriority(kind: String?): Int = when (kind) {
    "function" -> 0
    "memory" -> 1
    "global" -> 2
    "table" -> 3
    else -> 4
}

private fun abiSurfaceKindPriority(kind: String?): Int = when (kind) {
    "function_import" -> 0
    "function_export" -> 1
    else -> 2
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

private fun List<String>.trimmedTerms(): List<String> =
    map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

private fun List<String>.normalizedEntityTypes(defaults: Set<String>): Set<String> {
    val normalized = map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    return normalized.ifEmpty { defaults }
}

private fun List<String>.normalizedRelations(): Set<String> {
    val normalized = map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    return normalized.ifEmpty { DEFAULT_TRACE_RELATIONS }.intersect(SUPPORTED_TRACE_RELATIONS)
}

private fun List<String>.normalizedEntrypointEffects(): Set<String> {
    val normalized = mapNotNull { raw ->
        when (raw.trim().lowercase()) {
            "" -> null
            "direct_imported_call", "host_call", "host_calls", "import_call", "imported_call" -> "direct_imported_call"
            "memory_write", "memory", "write", "writes" -> "memory_write"
            else -> raw.trim().lowercase()
        }
    }.toSet()
    return normalized.ifEmpty { ENTRYPOINT_EFFECT_RELATIONS }
        .intersect(ENTRYPOINT_EFFECT_RELATIONS)
        .ifEmpty { ENTRYPOINT_EFFECT_RELATIONS }
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

private fun ParameterSignature?.toArtifactFunctionSignature(): ArtifactFunctionSignature =
    ArtifactFunctionSignature(
        params = this?.params.orEmpty(),
        results = this?.ret.orEmpty()
    )

private fun ArtifactSignatureResult.compact(): String =
    "(${params.joinToString(",")}) -> (${results.joinToString(",")})"

private fun EntrypointImportSignature.compact(): String =
    "(${params.joinToString(",")}) -> (${result.joinToString(",")})"

private fun ParameterSignature.containsReferenceType(): Boolean =
    (params + ret).any { it.isReferenceTypeName() }

private fun List<String>.normalizedReferenceTypes(): List<String> =
    map { it.normalizeReferenceTypeName() }

private fun String.isReferenceTypeName(): Boolean =
    normalizeReferenceTypeName() in setOf("externref", "funcref") || trim().startsWith("ref ")

private fun String.normalizeReferenceTypeName(): String {
    val value = trim()
    return when (value) {
        "externref" -> "externref"
        "funcref" -> "funcref"
        "ref extern" -> "externref"
        "ref null extern" -> "externref"
        "ref func" -> "funcref"
        "ref null func" -> "funcref"
        else -> value
    }
}

private fun DataEntry.matchedPayloadTerms(terms: List<String>): List<String> {
    if (terms.isEmpty()) return emptyList()
    val text = String(data, Charsets.UTF_8)
    val lowerText = text.lowercase()
    return terms.filter { term ->
        val lowerTerm = term.lowercase()
        lowerTerm in lowerText || data.containsBytes(term.encodeToByteArray())
    }
}

private fun DataEntry.utf8PayloadPreview(maxChars: Int): String =
    String(data, Charsets.UTF_8)
        .replace(Regex("""[\p{Cntrl}&&[^\r\n\t]]"""), " ")
        .take(maxChars)

private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (needle.size > size) return false
    for (start in 0..(size - needle.size)) {
        var matched = true
        for (index in needle.indices) {
            if (this[start + index] != needle[index]) {
                matched = false
                break
            }
        }
        if (matched) return true
    }
    return false
}

private fun List<Pair<String, String>>.toAttributes(): List<ArtifactAttributeResult> =
    map { (name, value) -> ArtifactAttributeResult(name, value) }

private fun MutableList<Pair<String, String>>.add(name: String, value: String) {
    add(name to value)
}

private inline fun <reified T> MutableList<Pair<String, String>>.addJsonFacts(name: String, facts: List<T>) {
    val visibleFacts = facts.take(SUMMARY_FACT_JSON_LIMIT)
    add(name, encodeHexanaJson(visibleFacts))
    add("${name}Count", facts.size.toString())
    if (visibleFacts.size < facts.size) {
        add("${name}Truncated", "true")
    }
}

private fun parseAbiBindingFile(text: String, warnings: MutableList<String>): ParsedAbiBinding {
    val module = Regex("""(?m)^\s*MODULE\s*=\s*(['"])(.*?)\1""").find(text)?.groupValues?.getOrNull(2)
    val imports = parseAbiBindingSignatureMap(text, "IMPORTS", warnings)
    val exports = parseAbiBindingSignatureMap(text, "EXPORTS", warnings)
    if (module == null) warnings += "`bindingPath` did not contain a parseable MODULE string."
    if (imports == null) warnings += "`bindingPath` did not contain a parseable IMPORTS signature map."
    if (exports == null) warnings += "`bindingPath` did not contain a parseable EXPORTS signature map."
    return ParsedAbiBinding(
        module = module,
        imports = imports.orEmpty().toSortedMap(),
        exports = exports.orEmpty().toSortedMap()
    )
}

private fun parseAbiBindingSignatureMap(
    text: String,
    variableName: String,
    warnings: MutableList<String>
): Map<String, ArtifactFunctionSignature>? {
    val body = extractPythonDictLiteral(text, variableName) ?: return null
    val entryRegex = Regex(
        """(['"])(.*?)\1\s*:\s*\{\s*(['"])params\3\s*:\s*\[(.*?)\]\s*,\s*(['"])results\5\s*:\s*\[(.*?)\]\s*\}""",
        setOf(RegexOption.DOT_MATCHES_ALL)
    )
    val entries = entryRegex.findAll(body).associate { match ->
        val name = match.groupValues[2]
        val params = parsePythonStringList(match.groupValues[4])
        val results = parsePythonStringList(match.groupValues[6])
        name to ArtifactFunctionSignature(params = params, results = results)
    }
    if (entries.isEmpty() && Regex("""\S""").containsMatchIn(body)) {
        warnings += "`$variableName` exists but no {'params': [...], 'results': [...]} entries were parsed."
    }
    return entries
}

private fun extractPythonDictLiteral(text: String, variableName: String): String? {
    val assignment = Regex("""(?m)^\s*$variableName\s*=""").find(text) ?: return null
    val start = text.indexOf('{', assignment.range.last + 1)
    if (start < 0) return null
    var depth = 0
    var quote: Char? = null
    var escaped = false
    for (index in start until text.length) {
        val ch = text[index]
        if (quote != null) {
            if (escaped) {
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == quote) {
                quote = null
            }
            continue
        }
        when (ch) {
            '\'', '"' -> quote = ch
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    return text.substring(start + 1, index)
                }
            }
        }
    }
    return null
}

private fun parsePythonStringList(body: String): List<String> =
    Regex("""(['"])(.*?)\1""").findAll(body).map { it.groupValues[2] }.toList()

private fun abiBindingMismatches(
    currentImports: Map<String, ArtifactFunctionSignature>,
    currentExports: Map<String, ArtifactFunctionSignature>,
    staleImports: Map<String, ArtifactFunctionSignature>,
    staleExports: Map<String, ArtifactFunctionSignature>
): List<AbiBindingMismatch> {
    val exportMismatches = (currentExports.keys + staleExports.keys).sorted().mapNotNull { name ->
        val expected = currentExports[name]
        val actual = staleExports[name]
        if (expected == actual) null else AbiBindingMismatch("EXPORTS.$name", expected, actual)
    }
    val importMismatches = (currentImports.keys + staleImports.keys).sorted().mapNotNull { name ->
        val expected = currentImports[name]
        val actual = staleImports[name]
        if (expected == actual) null else AbiBindingMismatch("IMPORTS.$name", expected, actual)
    }
    return exportMismatches + importMismatches
}

private fun AbiBindingMismatch.toAbiBindingMismatchRecord(): ArtifactRecordResult {
    val status = when {
        expected == null -> "removed"
        actual == null -> "added"
        else -> "changed"
    }
    return ArtifactRecordResult(
        id = "abi-binding-regeneration:mismatch:$symbol",
        entityType = "binding",
        status = status,
        source = "current",
        name = symbol,
        kind = "abi_binding_mismatch",
        signature = expected.toArtifactSignature(),
        summary = "Binding `$symbol` $status: expected current Wasm ${expected?.compact().orEmpty().ifBlank { "null" }}, actual stale ${actual?.compact().orEmpty().ifBlank { "null" }}",
        attributes = buildList {
            add("symbol", symbol)
            add("expectedMeaning", "current Wasm signature, or null when stale binding should be removed")
            add("actualMeaning", "stale binding signature, or null when binding is missing")
            add("expectedSignatureJson", encodeHexanaJson(expected))
            add("actualSignatureJson", encodeHexanaJson(actual))
            expected?.let {
                add("expectedParams", it.params.compactList(maxItems = 12))
                add("expectedResults", it.results.compactList(maxItems = 12))
            }
            actual?.let {
                add("actualParams", it.params.compactList(maxItems = 12))
                add("actualResults", it.results.compactList(maxItems = 12))
            }
        }.toAttributes()
    )
}

private fun parseHostAdapterConfig(text: String, warnings: MutableList<String>): ParsedHostAdapterConfig? {
    val root = runCatching {
        hostAdapterConfigJson.parseToJsonElement(text).jsonObject
    }.getOrElse { error ->
        warnings += "`configPath` did not contain parseable JSON: ${error.message ?: error::class.simpleName.orEmpty()}."
        return null
    }
    val knownFields = setOf("module", "imports", "entrypoint")
    val module = root["module"]?.jsonPrimitive?.content
    val imports = root["imports"]?.jsonObject?.mapValues { (_, value) ->
        parseHostAdapterSignature(value.jsonObject)
    }.orEmpty()
    val entrypoint = root["entrypoint"]?.jsonObject?.let { entrypoint ->
        HostAdapterEntrypointFact(
            export = entrypoint["export"]?.jsonPrimitive?.content.orEmpty(),
            params = entrypoint["params"].stringList(),
            results = entrypoint["results"].stringList()
        )
    }
    return ParsedHostAdapterConfig(
        module = module,
        imports = imports,
        entrypoint = entrypoint,
        ignoredTopLevelFields = root.keys.filter { it !in knownFields }.sorted()
    )
}

private fun parseHostAdapterSignature(obj: JsonObject): ArtifactFunctionSignature =
    ArtifactFunctionSignature(
        params = obj["params"].stringList(),
        results = obj["results"].stringList()
    )

private fun JsonElement?.stringList(): List<String> {
    val array = this as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return array.mapNotNull { element ->
        element.jsonPrimitive.content
    }
}

private fun selectHostAdapterEntrypoint(
    candidates: List<HostAdapterEntrypointFact>,
    terms: List<String>,
    configured: String?
): HostAdapterEntrypointFact? {
    if (terms.isNotEmpty()) {
        candidates.firstOrNull { candidate ->
            terms.any { term -> candidate.export.lowercase().contains(term) }
        }?.let { return it }
    }
    configured?.let { configuredName ->
        candidates.firstOrNull { it.export == configuredName }?.let { return it }
    }
    return candidates.singleOrNull() ?: candidates.firstOrNull()
}

private fun hostAdapterConfigMismatches(
    requiredImports: List<HostAdapterRequiredImport>,
    requiredEntrypoint: HostAdapterEntrypointFact?,
    config: ParsedHostAdapterConfig?
): List<HostAdapterConfigMismatch> {
    if (config == null) return emptyList()
    val mismatches = mutableListOf<HostAdapterConfigMismatch>()
    requiredEntrypoint?.let { expected ->
        val actual = config.entrypoint
        mismatches.addIfDifferent("entrypoint.export", JsonPrimitive(expected.export), actual?.export.toJsonElement())
        mismatches.addIfDifferent("entrypoint.params", expected.params.toJsonArray(), actual?.params.toJsonArrayOrNull())
        mismatches.addIfDifferent("entrypoint.results", expected.results.toJsonArray(), actual?.results.toJsonArrayOrNull())
    }
    requiredImports.forEach { expected ->
        val actual = config.imports[expected.name]
        mismatches.addIfDifferent("imports.${expected.name}.params", expected.params.toJsonArray(), actual?.params.toJsonArrayOrNull())
        mismatches.addIfDifferent("imports.${expected.name}.results", expected.results.toJsonArray(), actual?.results.toJsonArrayOrNull())
    }
    return mismatches
}

private fun MutableList<HostAdapterConfigMismatch>.addIfDifferent(
    field: String,
    expected: JsonElement,
    actual: JsonElement?
) {
    val actualOrNull = actual ?: JsonNull
    if (expected != actualOrNull) {
        add(HostAdapterConfigMismatch(field = field, expected = expected, actual = actualOrNull))
    }
}

private fun String?.toJsonElement(): JsonElement =
    this?.let { JsonPrimitive(it) } ?: JsonNull

private fun List<String>?.toJsonArrayOrNull(): JsonElement =
    this?.toJsonArray() ?: JsonNull

private fun List<String>.toJsonArray(): JsonElement =
    kotlinx.serialization.json.buildJsonArray {
        forEach { add(JsonPrimitive(it)) }
    }

private fun buildHostAdapterRepairConfig(
    module: String,
    imports: List<HostAdapterRequiredImport>,
    entrypoint: HostAdapterEntrypointFact?
): JsonObject = buildJsonObject {
    put("module", module)
    putJsonObject("imports") {
        imports.forEach { import ->
            putJsonObject(import.name) {
                putJsonArray("params") {
                    import.params.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("results") {
                    import.results.forEach { add(JsonPrimitive(it)) }
                }
            }
        }
    }
    entrypoint?.let {
        putJsonObject("entrypoint") {
            put("export", it.export)
            putJsonArray("params") {
                it.params.forEach { param -> add(JsonPrimitive(param)) }
            }
            putJsonArray("results") {
                it.results.forEach { result -> add(JsonPrimitive(result)) }
            }
        }
    }
}

private fun ArtifactFunctionSignature?.toArtifactSignature(): ArtifactSignatureResult? =
    this?.let { ArtifactSignatureResult(params = it.params, results = it.results) }

private fun ArtifactFunctionSignature.compact(): String =
    "(${params.joinToString(",")}) -> (${results.joinToString(",")})"

private data class EntitySnapshot(
    val key: String,
    val comparisonKey: String,
    val record: ArtifactRecordResult
)

private data class ApiExportSnapshot(
    val key: String,
    val name: String,
    val kind: String,
    val signature: ArtifactSignatureResult?,
    val comparisonKey: String,
    val record: ArtifactRecordResult
)

private data class GlobalExportSnapshot(
    val source: String,
    val export: String,
    val global: GlobalResult
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

@Serializable
private data class FunctionImportFact(
    val module: String,
    val name: String,
    @SerialName("import")
    val qualifiedName: String,
    val funcidx: Int,
    val typeidx: Int?,
    val signature: ArtifactFunctionSignature
)

@Serializable
private data class FunctionExportFact(
    val export: String,
    val funcidx: Int,
    val origin: String,
    val typeidx: Int?,
    val signature: ArtifactFunctionSignature
)

@Serializable
private data class ArtifactFunctionSignature(
    val params: List<String>,
    val results: List<String>
)

@Serializable
private data class HostAdapterRequiredImport(
    val name: String,
    val params: List<String>,
    val results: List<String>
)

@Serializable
private data class HostAdapterEntrypointFact(
    val export: String,
    val params: List<String>,
    val results: List<String>
)

@Serializable
private data class HostAdapterConfigMismatch(
    val field: String,
    val expected: JsonElement,
    val actual: JsonElement
)

private data class ParsedHostAdapterConfig(
    val module: String?,
    val imports: Map<String, ArtifactFunctionSignature>,
    val entrypoint: HostAdapterEntrypointFact?,
    val ignoredTopLevelFields: List<String>
)

private data class ParsedAbiBinding(
    val module: String?,
    val imports: Map<String, ArtifactFunctionSignature>,
    val exports: Map<String, ArtifactFunctionSignature>
)

@Serializable
private data class AbiBindingMismatch(
    val symbol: String,
    val expected: ArtifactFunctionSignature?,
    val actual: ArtifactFunctionSignature?
)

@Serializable
private data class AbiReplacementBindings(
    @SerialName("MODULE")
    val module: String,
    @SerialName("IMPORTS")
    val imports: Map<String, ArtifactFunctionSignature>,
    @SerialName("EXPORTS")
    val exports: Map<String, ArtifactFunctionSignature>
)

@Serializable
private data class GlobalPolicyFact(
    val export: String? = null,
    @SerialName("import")
    val importNameQualified: String? = null,
    val globalidx: Int,
    val type: String,
    val mutable: Boolean,
    val initExpr: String? = null,
    val origin: String,
    val importModule: String? = null,
    val importName: String? = null
)

@Serializable
private data class EntrypointPolicyEntry(
    val export: String,
    val funcidx: Int,
    val signature: EntrypointSignature,
    @SerialName("called_imports")
    val calledImports: List<EntrypointPolicyCalledImport>
)

@Serializable
private data class EntrypointSignature(
    val params: List<String>,
    val results: List<String>
)

@Serializable
private data class EntrypointPolicyCalledImport(
    val module: String,
    val name: String,
    val signature: EntrypointImportSignature
)

@Serializable
private data class EntrypointImportSignature(
    val params: List<String>,
    @SerialName("return")
    val result: List<String>
)

private data class ExportedFunction(
    val name: String,
    val function: GeneralFunctionEntry
)

private data class BudgetedRecords(
    val records: List<ArtifactRecordResult>,
    val omitted: ArtifactOmittedResult?
)
