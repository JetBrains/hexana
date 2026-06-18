package org.jetbrains.hexana.mcp.query

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.model.exports.ExportResult
import org.jetbrains.hexana.mcp.model.globals.AuditGlobalStatePolicyResult
import org.jetbrains.hexana.mcp.model.globals.GlobalResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyAllowedGlobalsSummaryResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyDetailResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyModuleIdentity
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyEvidenceResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyGlobalResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyRecommendedReportResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyReportGlobalResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyReportViolationResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyRequestLimitResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyUnchangedGlobalsSummaryResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyViolationResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

private const val DEFAULT_GLOBAL_STATE_POLICY_DETAIL = "compact"
private const val COMPACT_PAYLOAD_BUDGET_ESTIMATED_TOKENS = 12_000

private val globalStatePolicyJson = Json {
    ignoreUnknownKeys = true
}

fun WasmBinaryProvider.auditGlobalStatePolicy(
    baseline: WasmBinaryProvider,
    policyText: String,
    logText: String? = null,
    pathLabel: String? = null,
    baselinePathLabel: String? = null,
    policyPathLabel: String? = null,
    logPathLabel: String? = null,
    detail: String = DEFAULT_GLOBAL_STATE_POLICY_DETAIL
): AuditGlobalStatePolicyResult {
    val policy = parseGlobalStatePolicy(policyText)
    val detailMode = GlobalStatePolicyDetailMode.parse(detail)
    val currentExports = globalExports()
    val baselineExportNames = baseline.globalExports().map { it.export }.toSet()
    val currentGlobals = readGlobals().globals

    val newPublicGlobals = currentExports
        .filter { it.export !in baselineExportNames }
        .map { it.toPolicyGlobal(status = "added", allowedBy = policy.allowedBy(it)) }
        .sortedByIdentity()

    val allowedPublicGlobals = currentExports
        .mapNotNull { export ->
            when {
                export.export in baselineExportNames -> export.toPolicyGlobal(status = "baseline_public", allowedBy = "baseline")
                else -> policy.allowedBy(export)?.let { export.toPolicyGlobal(status = "policy_allowed", allowedBy = it) }
            }
        }
        .sortedByIdentity()

    val mutableImportedGlobals = currentGlobals
        .filter { it.origin == "import" && it.mutability }
        .sortedWith(compareBy<GlobalResult> { it.globalIdx }.thenBy { it.importModule.orEmpty() }.thenBy { it.importName.orEmpty() })

    val requestLimits = currentExports
        .filter { policy.isRequestLimitExport(it.export) }
        .map { export -> export.toRequestLimit(policy) }
        .sortedWith(compareBy<GlobalStatePolicyRequestLimitResult> { it.globalidx }.thenBy { it.export })

    val violations = buildList {
        if (policy.forbidMutableImportedGlobals) {
            mutableImportedGlobals.forEach { global ->
                add(
                    GlobalStatePolicyViolationResult(
                        kind = "mutable_imported_global",
                        globalidx = global.globalIdx,
                        importNameQualified = global.importNameQualified(),
                        field = "mutability",
                        observed = "mutable",
                        policy = "immutable_imports",
                        message = "Mutable imported global `${global.importNameQualified()}` violates the global-state policy."
                    )
                )
            }
        }

        newPublicGlobals
            .filter { policy.enforceAllowedPublicGlobals && !it.allowed }
            .forEach { global ->
                add(
                    GlobalStatePolicyViolationResult(
                        kind = "forbidden_public_global",
                        globalidx = global.globalidx,
                        export = global.export,
                        field = "export",
                        observed = global.export,
                        policy = "allowed_public_globals",
                        message = "New public global `${global.export}` is not allowed by the global-state policy."
                    )
                )
            }

        requestLimits
            .filter { it.violation != null }
            .forEach { limit ->
                add(
                    GlobalStatePolicyViolationResult(
                        kind = limit.violation!!,
                        globalidx = limit.globalidx,
                        export = limit.export,
                        field = "request_limit",
                        observed = limit.value?.toString(),
                        policy = limit.maxValue?.toString(),
                        message = "Request-limit global `${limit.export}` has value ${limit.value?.toString() ?: "unknown"}; policy maximum is ${limit.maxValue?.toString() ?: "unspecified"}."
                    )
                )
            }
    }.sortedWith(compareBy<GlobalStatePolicyViolationResult> { it.globalidx }.thenBy { it.kind }.thenBy { it.export.orEmpty() })

    val globals = (newPublicGlobals + mutableImportedGlobals.map { it.toPolicyGlobal(status = "targeted_mutable_import") })
        .distinctBy { it.globalidx to (it.export ?: it.importNameQualified).orEmpty() }
        .sortedByIdentity()

    val targetedGlobalIndices = (violations.map { it.globalidx } + globals.map { it.globalidx })
        .distinct()
        .sorted()

    val fullEvidence = (globals + allowedPublicGlobals)
        .distinctBy { it.globalidx to (it.export ?: it.importNameQualified).orEmpty() }
        .map { it.toEvidence() }
        .sortedWith(compareBy<GlobalStatePolicyEvidenceResult> { it.globalidx }.thenBy { it.export.orEmpty() }.thenBy { it.importNameQualified.orEmpty() })
    val evidence = when (detailMode) {
        GlobalStatePolicyDetailMode.COMPACT -> globals.map { it.toEvidence() }
            .sortedWith(compareBy<GlobalStatePolicyEvidenceResult> { it.globalidx }.thenBy { it.export.orEmpty() }.thenBy { it.importNameQualified.orEmpty() })
        GlobalStatePolicyDetailMode.FULL -> fullEvidence
    }
    val allowedPublicGlobalsResult = when (detailMode) {
        GlobalStatePolicyDetailMode.COMPACT -> allowedPublicGlobals
            .filter { it.allowedBy != "baseline" }
            .sortedByIdentity()
        GlobalStatePolicyDetailMode.FULL -> allowedPublicGlobals
    }
    val allowedPublicGlobalNames = allowedPublicGlobals
        .mapNotNull { it.export }
        .distinct()
        .sorted()
    val unchangedPublicGlobalNames = allowedPublicGlobals
        .filter { it.allowedBy == "baseline" }
        .mapNotNull { it.export }
        .distinct()
        .sorted()
    val allowedPublicGlobalSummary = GlobalStatePolicyAllowedGlobalsSummaryResult(
        count = allowedPublicGlobalNames.size,
        names = allowedPublicGlobalNames,
        returnedAsObjects = allowedPublicGlobalsResult.size,
        omittedObjectCount = allowedPublicGlobals.size - allowedPublicGlobalsResult.size
    )
    val unchangedGlobalSummary = GlobalStatePolicyUnchangedGlobalsSummaryResult(
        currentGlobalCount = currentGlobals.size,
        importedGlobalCount = currentGlobals.count { it.origin == "import" },
        localGlobalCount = currentGlobals.count { it.origin == "local" },
        unchangedPublicGlobalCount = unchangedPublicGlobalNames.size,
        unchangedPublicGlobalNames = unchangedPublicGlobalNames
    )
    val logEvidence = parseGlobalStatePolicyLogEvidence(logText, pathLabel)
    val recommendedReport = GlobalStatePolicyRecommendedReportResult(
        policyId = policy.policyId,
        module = pathLabel ?: logEvidence?.module,
        comparedTo = logEvidence?.comparedTo ?: baselinePathLabel,
        logEventId = logEvidence?.eventId,
        violations = violations
            .filter { it.kind == "mutable_imported_global" && it.importNameQualified != null }
            .mapNotNull { violation ->
                val global = currentGlobals.firstOrNull { it.globalIdx == violation.globalidx } ?: return@mapNotNull null
                GlobalStatePolicyReportViolationResult(
                    kind = violation.kind,
                    globalidx = violation.globalidx,
                    importName = violation.importNameQualified,
                    type = global.type,
                    mutable = global.mutability
                )
            }
            .sortedBy { it.globalidx },
        newPublicGlobals = newPublicGlobals
            .mapNotNull { it.toReportGlobal() }
            .sortedWith(compareBy<GlobalStatePolicyReportGlobalResult> { it.export }.thenBy { it.globalidx }),
        allowedPublicGlobals = allowedPublicGlobalNames,
        targetedGlobalIndices = targetedGlobalIndices,
        recommendation = recommendationFor(violations, newPublicGlobals)
    )

    val warnings = buildList {
        if (logText != null && logText.isBlank()) {
            add("Runtime log was empty.")
        }
    }

    return AuditGlobalStatePolicyResult(
        module = GlobalStatePolicyModuleIdentity(
            path = pathLabel,
            baselinePath = baselinePathLabel,
            policyPath = policyPathLabel,
            logPath = logPathLabel
        ),
        policyId = policy.policyId,
        detail = GlobalStatePolicyDetailResult(
            mode = detailMode.wireName,
            compactPayloadBudgetEstimatedTokens = COMPACT_PAYLOAD_BUDGET_ESTIMATED_TOKENS,
            allowedPublicGlobalsReturned = allowedPublicGlobalsResult.size,
            allowedPublicGlobalsTotal = allowedPublicGlobals.size,
            evidenceReturned = evidence.size,
            evidenceTotal = fullEvidence.size
        ),
        globals = globals,
        newPublicGlobals = newPublicGlobals,
        allowedPublicGlobals = allowedPublicGlobalsResult,
        allowedPublicGlobalSummary = allowedPublicGlobalSummary,
        unchangedGlobalSummary = unchangedGlobalSummary,
        requestLimits = requestLimits,
        violations = violations,
        targetedGlobalIndices = targetedGlobalIndices,
        evidence = evidence,
        recommendedReport = recommendedReport,
        warnings = warnings
    )
}

private fun WasmBinaryProvider.globalExports(): List<GlobalExport> {
    val globalsByIndex = readGlobals().globals.associateBy { it.globalIdx }
    return readExports().exports
        .asSequence()
        .filter { it.kind.kindName() == "global" }
        .mapNotNull { export ->
            val globalidx = export.externIndex.toInt()
            val global = globalsByIndex[globalidx] ?: return@mapNotNull null
            GlobalExport(export = export.name, global = global, exportRecord = export)
        }
        .sortedWith(compareBy<GlobalExport> { it.global.globalIdx }.thenBy { it.export })
        .toList()
}

private fun GlobalExport.toPolicyGlobal(status: String, allowedBy: String? = null): GlobalStatePolicyGlobalResult =
    global.toPolicyGlobal(export = export, status = status, allowedBy = allowedBy)

private fun GlobalResult.toPolicyGlobal(
    export: String? = exportedAs.firstOrNull(),
    status: String,
    allowedBy: String? = null
): GlobalStatePolicyGlobalResult =
    GlobalStatePolicyGlobalResult(
        export = export,
        importNameQualified = importNameQualified(),
        globalidx = globalIdx,
        type = type,
        mutable = mutability,
        initExpr = expression,
        origin = origin,
        importModule = importModule,
        importName = importName,
        status = status,
        allowed = allowedBy != null,
        allowedBy = allowedBy
    )

private fun GlobalStatePolicyGlobalResult.toEvidence(): GlobalStatePolicyEvidenceResult =
    GlobalStatePolicyEvidenceResult(
        kind = status,
        globalidx = globalidx,
        export = export,
        importNameQualified = importNameQualified,
        type = type,
        mutable = mutable,
        initExpr = initExpr,
        origin = origin
    )

private fun GlobalStatePolicyGlobalResult.toReportGlobal(): GlobalStatePolicyReportGlobalResult? {
    val export = export ?: return null
    return GlobalStatePolicyReportGlobalResult(
        export = export,
        globalidx = globalidx,
        origin = origin,
        type = type,
        mutable = mutable,
        initExpr = initExpr
    )
}

private fun GlobalExport.toRequestLimit(policy: GlobalStatePolicy): GlobalStatePolicyRequestLimitResult {
    val value = global.expression?.constIntegerValue()
    val maxValue = policy.requestLimitMaxValue
    val violation = when {
        maxValue != null && (value == null || value > maxValue) -> "request_limit_exceeded"
        global.mutability -> "mutable_request_limit"
        else -> null
    }
    return GlobalStatePolicyRequestLimitResult(
        export = export,
        globalidx = global.globalIdx,
        value = value,
        maxValue = maxValue,
        allowed = violation == null,
        violation = violation
    )
}

private fun parseGlobalStatePolicy(text: String): GlobalStatePolicy {
    if (text.isBlank()) {
        throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "Policy file must not be blank")
    }
    val root = try {
        globalStatePolicyJson.parseToJsonElement(text).jsonObject
    } catch (error: Exception) {
        throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "Policy file must be a JSON object",
            error
        )
    }

    val requestLimits = root["requestLimits"]?.asObjectOrNull() ?: root["requestLimit"]?.asObjectOrNull()
    val allowedPublicGlobals = (
        root.allowedGlobalExports("allowedPublicGlobals") +
            root.allowedGlobalExports("allowed_public_globals") +
            root.allowedGlobalExports("allowedGlobals") +
            root.allowedGlobalExports("allowed_globals") +
            requestLimits.allowedGlobalExports("allowedPublicGlobals") +
            requestLimits.allowedGlobalExports("allowed_public_globals") +
            requestLimits.allowedGlobalExports("exports") +
            requestLimits.stringOrNull("export")
        )
        .filterNotNull()
        .toSet()
    val reportNewExportedGlobals = root.booleanOrNull("reportNewExportedGlobals")
        ?: root.booleanOrNull("report_new_exported_globals")
        ?: false
    val explicitAllowedPublicGlobals = root.containsKey("allowedPublicGlobals") ||
        root.containsKey("allowed_public_globals") ||
        root.containsKey("allowedGlobals") ||
        root.containsKey("allowed_globals")

    val requestLimitExports = (
        requestLimits.allowedGlobalExports("exports") +
            requestLimits.allowedGlobalExports("allowedPublicGlobals") +
            requestLimits.stringOrNull("export") +
            root.stringOrNull("requestLimitExport")
        )
        .filterNotNull()
        .toSet()

    return GlobalStatePolicy(
        policyId = root.stringOrNull("policyId") ?: root.stringOrNull("policy_id"),
        allowedPublicGlobals = allowedPublicGlobals,
        requestLimitExports = requestLimitExports,
        requestLimitMaxValue = requestLimits.longOrNull("maxValue")
            ?: requestLimits.longOrNull("max")
            ?: requestLimits.longOrNull("maximum")
            ?: root.longOrNull("maxRequestLimit"),
        forbidMutableImportedGlobals = root.booleanOrNull("forbidMutableImportedGlobals")
            ?: root.booleanOrNull("forbid_mutable_imported_globals")
            ?: root.booleanOrNull("allowMutableImportedGlobals")?.not()
            ?: root.booleanOrNull("allow_mutable_imported_globals")?.not()
            ?: true,
        enforceAllowedPublicGlobals = explicitAllowedPublicGlobals && !reportNewExportedGlobals
    )
}

private fun GlobalStatePolicy.allowedBy(export: GlobalExport): String? =
    when (export.export) {
        in requestLimitExports -> "request_limits"
        in allowedPublicGlobals -> "allowed_public_globals"
        else -> null
    }

private fun GlobalStatePolicy.isRequestLimitExport(export: String): Boolean =
    export in requestLimitExports

private fun String.constIntegerValue(): Long? {
    val parts = trim().split(Regex("\\s+"))
    if (parts.size != 2) return null
    if (parts.first() !in setOf("i32.const", "i64.const")) return null
    return parts[1].toLongOrNull()
}

private fun List<GlobalStatePolicyGlobalResult>.sortedByIdentity(): List<GlobalStatePolicyGlobalResult> =
    sortedWith(compareBy<GlobalStatePolicyGlobalResult> { it.globalidx }.thenBy { it.export.orEmpty() }.thenBy { it.importNameQualified.orEmpty() })

private fun GlobalResult.importNameQualified(): String? =
    importModule?.let { module -> importName?.let { name -> "$module.$name" } }

private fun Byte.kindName(): String = when (toInt()) {
    0 -> "func"
    1 -> "table"
    2 -> "memory"
    3 -> "global"
    4 -> "tag"
    else -> "unknown"
}

private fun JsonObject?.allowedGlobalExports(name: String): List<String> {
    val element = this?.get(name) ?: return emptyList()
    return when (element) {
        is JsonArray -> element.mapNotNull { it.allowedGlobalExportName() }
        else -> listOfNotNull(element.allowedGlobalExportName())
    }
}

private fun JsonElement.allowedGlobalExportName(): String? =
    when (this) {
        is JsonObject -> stringOrNull("export") ?: stringOrNull("name")
        else -> stringOrNull()
    }

private fun JsonObject?.longOrNull(name: String): Long? =
    this?.get(name)?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull

private fun JsonObject?.booleanOrNull(name: String): Boolean? =
    this?.get(name)?.takeIf { it != JsonNull }?.jsonPrimitive?.booleanOrNull

private fun JsonObject?.stringOrNull(name: String): String? =
    this?.get(name)?.stringOrNull()

private fun JsonElement.stringOrNull(): String? =
    takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonElement.asObjectOrNull(): JsonObject? =
    this as? JsonObject

private fun parseGlobalStatePolicyLogEvidence(
    logText: String?,
    pathLabel: String?
): GlobalStatePolicyLogEvidence? {
    if (logText.isNullOrBlank()) return null
    val records = logText.lineSequence()
        .mapNotNull { line -> parseLogKeyValueRecord(line) }
        .filter { record -> record["event"] != null }
        .toList()
    if (records.isEmpty()) return null
    val selected = records.firstOrNull { record ->
        record["compared_to"] != null && pathMatches(pathLabel, record["module"])
    } ?: records.firstOrNull { record ->
        record["status"] == "needs-report"
    } ?: records.firstOrNull { record ->
        record["compared_to"] != null
    } ?: records.first()

    return GlobalStatePolicyLogEvidence(
        eventId = selected["event"],
        module = selected["module"],
        comparedTo = selected["compared_to"]
    )
}

private fun parseLogKeyValueRecord(line: String): Map<String, String>? {
    val pairs = Regex("""([A-Za-z_][A-Za-z0-9_]*)=([^\s]+)""")
        .findAll(line)
        .associate { it.groupValues[1] to it.groupValues[2] }
    return pairs.ifEmpty { null }
}

private fun pathMatches(expected: String?, observed: String?): Boolean {
    if (expected == null || observed == null) return true
    return expected == observed || expected.endsWith(observed)
}

private fun recommendationFor(
    violations: List<GlobalStatePolicyViolationResult>,
    newPublicGlobals: List<GlobalStatePolicyGlobalResult>
): String =
    when {
        violations.isNotEmpty() -> "Block release until policy violations are remediated; include new public globals in the release report."
        newPublicGlobals.isNotEmpty() -> "Include new public globals in the release report; no blocking global-state policy violations were found."
        else -> "No blocking global-state policy violations or new public globals were found."
    }

private enum class GlobalStatePolicyDetailMode(val wireName: String) {
    COMPACT("compact"),
    FULL("full");

    companion object {
        fun parse(value: String): GlobalStatePolicyDetailMode =
            entries.firstOrNull { it.wireName == value.lowercase() }
                ?: throw HexanaAnalysisException(
                    HexanaAnalysisErrorCode.INVALID_INPUT,
                    "Global-state policy detail must be `compact` or `full`"
                )
    }
}

private data class GlobalStatePolicy(
    val policyId: String?,
    val allowedPublicGlobals: Set<String>,
    val requestLimitExports: Set<String>,
    val requestLimitMaxValue: Long?,
    val forbidMutableImportedGlobals: Boolean,
    val enforceAllowedPublicGlobals: Boolean
)

private data class GlobalExport(
    val export: String,
    val global: GlobalResult,
    @Suppress("unused") val exportRecord: ExportResult
)

private data class GlobalStatePolicyLogEvidence(
    val eventId: String?,
    val module: String?,
    val comparedTo: String?
)
