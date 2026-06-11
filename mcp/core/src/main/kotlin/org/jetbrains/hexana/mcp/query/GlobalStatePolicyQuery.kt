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
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyModuleIdentity
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyEvidenceResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyGlobalResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyRequestLimitResult
import org.jetbrains.hexana.mcp.model.globals.GlobalStatePolicyViolationResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

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
    logPathLabel: String? = null
): AuditGlobalStatePolicyResult {
    val policy = parseGlobalStatePolicy(policyText)
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
            .filterNot { it.allowed }
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

    val targetedGlobalIndices = (violations.map { it.globalidx } + globals.map { it.globalidx } + allowedPublicGlobals.map { it.globalidx })
        .distinct()
        .sorted()

    val evidence = (globals + allowedPublicGlobals)
        .distinctBy { it.globalidx to (it.export ?: it.importNameQualified).orEmpty() }
        .map { it.toEvidence() }
        .sortedWith(compareBy<GlobalStatePolicyEvidenceResult> { it.globalidx }.thenBy { it.export.orEmpty() }.thenBy { it.importNameQualified.orEmpty() })

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
        globals = globals,
        newPublicGlobals = newPublicGlobals,
        allowedPublicGlobals = allowedPublicGlobals,
        requestLimits = requestLimits,
        violations = violations,
        targetedGlobalIndices = targetedGlobalIndices,
        evidence = evidence,
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
            root.allowedGlobalExports("allowedGlobals") +
            requestLimits.allowedGlobalExports("allowedPublicGlobals") +
            requestLimits.allowedGlobalExports("exports") +
            requestLimits.stringOrNull("export")
        )
        .filterNotNull()
        .toSet()

    val requestLimitExports = (
        requestLimits.allowedGlobalExports("exports") +
            requestLimits.allowedGlobalExports("allowedPublicGlobals") +
            requestLimits.stringOrNull("export") +
            root.stringOrNull("requestLimitExport")
        )
        .filterNotNull()
        .ifEmpty { listOf("request_limit") }
        .toSet()

    return GlobalStatePolicy(
        policyId = root.stringOrNull("policyId") ?: root.stringOrNull("policy_id"),
        allowedPublicGlobals = allowedPublicGlobals,
        requestLimitExports = requestLimitExports,
        requestLimitMaxValue = requestLimits.longOrNull("maxValue")
            ?: requestLimits.longOrNull("max")
            ?: requestLimits.longOrNull("maximum")
            ?: root.longOrNull("maxRequestLimit"),
        forbidMutableImportedGlobals = root.booleanOrNull("forbidMutableImportedGlobals") ?: true
    )
}

private fun GlobalStatePolicy.allowedBy(export: GlobalExport): String? =
    when (export.export) {
        in requestLimitExports -> "request_limits"
        in allowedPublicGlobals -> "allowed_public_globals"
        else -> null
    }

private fun GlobalStatePolicy.isRequestLimitExport(export: String): Boolean =
    export in requestLimitExports || export.contains("request_limit", ignoreCase = true)

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

private data class GlobalStatePolicy(
    val policyId: String?,
    val allowedPublicGlobals: Set<String>,
    val requestLimitExports: Set<String>,
    val requestLimitMaxValue: Long?,
    val forbidMutableImportedGlobals: Boolean
)

private data class GlobalExport(
    val export: String,
    val global: GlobalResult,
    @Suppress("unused") val exportRecord: ExportResult
)
