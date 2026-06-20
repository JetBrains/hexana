package org.jetbrains.hexana.mcp.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.mcp.GeneralFunctionEntry
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.renderedMcpType
import org.jetbrains.hexana.mcp.model.functions.AuditLocalStackBudgetResult
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetBudgetResult
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetEvidenceResult
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetFunctionResult
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetMinimalFixResult
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetModuleIdentity
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetRecommendedBudgetResult
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetRecommendedEvidenceResult
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetRecommendedMinimalFixResult
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetRecommendedReportResult
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetRecommendedViolationResult
import org.jetbrains.hexana.mcp.model.functions.LocalStackBudgetViolationResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry

private val localStackBudgetJson = Json {
    ignoreUnknownKeys = true
}

fun WasmBinaryProvider.auditLocalStackBudget(
    policyText: String? = null,
    logText: String? = null,
    checkedExports: List<String> = emptyList(),
    maxLocals: Int? = null,
    countParams: Boolean = false,
    pathLabel: String? = null,
    policyPathLabel: String? = null,
    logPathLabel: String? = null
): AuditLocalStackBudgetResult {
    requireNotNull(wasmModule)
    val policy = policyText?.let(::parseLocalStackBudgetPolicy)
    val logEvidence = logText?.let(::parseLocalStackBudgetLogEvidence)
    val effectiveCheckedExports = (
        checkedExports.takeIf { it.isNotEmpty() }
            ?: policy?.checkedExports?.takeIf { it.isNotEmpty() }
            ?: exportedLocalFunctionNames()
        ).distinct().sorted()
    val effectiveMaxLocals = maxLocals ?: policy?.maxLocals
    val effectiveCountParams = maxLocals?.let { countParams } ?: policy?.countParams ?: countParams

    val functionEntriesByIndex = listFunctionEntries().associateBy { it.funcidx }
    val exportsByName = functionExportsByName()
    val selectedFuncidxs = effectiveCheckedExports.mapNotNull { exportsByName[it]?.index?.toInt() }
    val localsByFuncidx = readFunctionLocals(selectedFuncidxs)
        .associate { locals ->
            locals.funcidx to locals.locals
                .groupingBy { it.renderedMcpType() }
                .fold(0) { acc, local -> acc + local.count }
                .toSortedMap()
        }
    val warnings = mutableListOf<String>()
    val functions = effectiveCheckedExports.mapNotNull { exportName ->
        val export = exportsByName[exportName]
        if (export == null) {
            warnings += "Checked export `$exportName` was not found as a function export."
            return@mapNotNull null
        }
        val entry = functionEntriesByIndex[export.index.toInt()]
        if (entry == null) {
            warnings += "Checked export `$exportName` points to missing funcidx ${export.index}."
            return@mapNotNull null
        }
        entry.toLocalStackBudgetFunction(
            exportName = exportName,
            localsByType = localsByFuncidx[entry.funcidx].orEmpty(),
            maxLocals = effectiveMaxLocals,
            countParams = effectiveCountParams
        )
    }.sortedBy { it.export }

    val violations = if (effectiveMaxLocals == null) {
        emptyList()
    } else {
        functions
            .filter { it.localCount > effectiveMaxLocals }
            .map {
                LocalStackBudgetViolationResult(
                    export = it.export,
                    funcidx = it.funcidx,
                    localCount = it.localCount,
                    localsByType = it.localsByType,
                    excess = it.localCount - effectiveMaxLocals
                )
            }
    }
    val violationExports = violations.map { it.export }.sorted()
    val cleanExports = if (effectiveMaxLocals == null) {
        emptyList()
    } else {
        functions.map { it.export }.filter { it !in violationExports }.sorted()
    }
    val evidenceCheckedExports = logEvidence?.checkedExports?.takeIf { it.isNotEmpty() } ?: effectiveCheckedExports
    if (logEvidence != null && logEvidence.checkedExports.isNotEmpty() && logEvidence.checkedExports.sorted() != effectiveCheckedExports) {
        warnings += "`logPath` checked export batch differs from the requested or policy checked exports."
    }

    val minimalFix = LocalStackBudgetMinimalFixResult(splitOrReduceExports = violationExports)
    val evidence = LocalStackBudgetEvidenceResult(
        logEventId = logEvidence?.eventId,
        checkedExports = evidenceCheckedExports.sorted()
    )
    val recommendedReport = LocalStackBudgetRecommendedReportResult(
        runtimeId = policy?.runtimeId,
        module = pathLabel,
        budget = LocalStackBudgetRecommendedBudgetResult(
            maxLocals = effectiveMaxLocals,
            countParams = effectiveCountParams
        ),
        violations = violations.map {
            LocalStackBudgetRecommendedViolationResult(
                export = it.export,
                funcidx = it.funcidx,
                localCount = it.localCount,
                localsByType = it.localsByType,
                excess = it.excess
            )
        },
        cleanExports = cleanExports,
        evidence = LocalStackBudgetRecommendedEvidenceResult(
            logEventId = evidence.logEventId,
            checkedExports = evidence.checkedExports
        ),
        minimalFix = LocalStackBudgetRecommendedMinimalFixResult(
            splitOrReduceExports = minimalFix.splitOrReduceExports
        )
    )

    return AuditLocalStackBudgetResult(
        module = LocalStackBudgetModuleIdentity(
            path = pathLabel,
            policyPath = policyPathLabel,
            logPath = logPathLabel
        ),
        runtimeId = policy?.runtimeId,
        budget = LocalStackBudgetBudgetResult(
            maxLocals = effectiveMaxLocals,
            countParams = effectiveCountParams
        ),
        checkedExports = effectiveCheckedExports,
        functions = functions,
        violations = violations,
        cleanExports = cleanExports,
        evidence = evidence,
        minimalFix = minimalFix,
        recommendedReport = recommendedReport,
        warnings = warnings
    )
}

private fun WasmBinaryProvider.functionExportsByName(): Map<String, WasmFunctionExportRecordEntry> {
    return exports
        .filterIsInstance<WasmFunctionExportRecordEntry>()
        .associateBy { it.name }
}

private fun WasmBinaryProvider.exportedLocalFunctionNames(): List<String> {
    val localFunctionIndices = listFunctionEntries()
        .filter { it.import == null }
        .map { it.funcidx }
        .toSet()
    return exports
        .filterIsInstance<WasmFunctionExportRecordEntry>()
        .filter { it.index.toInt() in localFunctionIndices }
        .map { it.name }
}

private fun GeneralFunctionEntry.toLocalStackBudgetFunction(
    exportName: String,
    localsByType: Map<String, Int>,
    maxLocals: Int?,
    countParams: Boolean
): LocalStackBudgetFunctionResult {
    val declaredLocalCount = localsByType.values.sum()
    val paramCount = signature?.params?.size ?: 0
    val localCount = declaredLocalCount + if (countParams) paramCount else 0
    return LocalStackBudgetFunctionResult(
        export = exportName,
        funcidx = funcidx,
        origin = if (import == null) "local" else "import",
        declaredLocalCount = declaredLocalCount,
        paramCount = paramCount,
        localCount = localCount,
        localsByType = localsByType,
        exceedsBudget = maxLocals?.let { localCount > it },
        excess = maxLocals?.let { (localCount - it).coerceAtLeast(0) }
    )
}

private data class LocalStackBudgetPolicy(
    val runtimeId: String?,
    val maxLocals: Int?,
    val countParams: Boolean,
    val checkedExports: List<String>
)

private data class LocalStackBudgetLogEvidence(
    val eventId: String?,
    val checkedExports: List<String>
)

private fun parseLocalStackBudgetPolicy(text: String): LocalStackBudgetPolicy {
    val root = parseJsonObject(text, "local stack budget policy")
    val runtimeId = root.stringOrNull("runtime_id") ?: root.stringOrNull("runtimeId")
    val maxLocals = root.intOrNull("max_locals") ?: root.intOrNull("maxLocals")
    val countParams = root.booleanOrNull("count_params") ?: root.booleanOrNull("countParams") ?: false
    val checkedExports = root.stringListOrEmpty("checked_exports").ifEmpty {
        root.stringListOrEmpty("checkedExports")
    }.distinct().sorted()
    return LocalStackBudgetPolicy(
        runtimeId = runtimeId,
        maxLocals = maxLocals,
        countParams = countParams,
        checkedExports = checkedExports
    )
}

private fun parseLocalStackBudgetLogEvidence(text: String): LocalStackBudgetLogEvidence {
    val checkedLine = text.lineSequence().firstOrNull { "checked_exports=" in it }
    val rejectionLine = text.lineSequence().firstOrNull { "reason=batch-local-budget" in it }
    val eventId = (checkedLine ?: rejectionLine)?.let(EVENT_ID_REGEX::find)?.groupValues?.getOrNull(1)
    val checkedExports = checkedLine
        ?.substringAfter("checked_exports=", missingDelimiterValue = "")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
        .distinct()
        .sorted()
    return LocalStackBudgetLogEvidence(eventId = eventId, checkedExports = checkedExports)
}

private val EVENT_ID_REGEX = Regex("""\bevent=([^\s]+)""")

private fun parseJsonObject(text: String, label: String) = try {
    localStackBudgetJson.parseToJsonElement(text).jsonObject
} catch (error: Exception) {
    throw HexanaAnalysisException(
        HexanaAnalysisErrorCode.INVALID_INPUT,
        "Failed to parse $label JSON",
        error
    )
}

private fun Map<String, JsonElement>.stringOrNull(name: String): String? {
    val value = this[name] ?: return null
    if (value == JsonNull) return null
    return value.jsonPrimitive.contentOrNull
}

private fun Map<String, JsonElement>.intOrNull(name: String): Int? {
    val value = this[name] ?: return null
    if (value == JsonNull) return null
    return value.jsonPrimitive.intOrNull
}

private fun Map<String, JsonElement>.booleanOrNull(name: String): Boolean? {
    val value = this[name] ?: return null
    if (value == JsonNull) return null
    return value.jsonPrimitive.booleanOrNull
}

private fun Map<String, JsonElement>.stringListOrEmpty(name: String): List<String> {
    val value = this[name] ?: return emptyList()
    if (value == JsonNull) return emptyList()
    return value.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
}
