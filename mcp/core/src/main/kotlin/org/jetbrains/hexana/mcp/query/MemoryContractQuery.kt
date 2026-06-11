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
import org.jetbrains.hexana.mcp.model.memory.CheckMemoryContractResult
import org.jetbrains.hexana.mcp.model.memory.MemoryContractCompatibleLimitsResult
import org.jetbrains.hexana.mcp.model.memory.MemoryContractEntryResult
import org.jetbrains.hexana.mcp.model.memory.MemoryContractModuleIdentity
import org.jetbrains.hexana.mcp.model.memory.MemoryContractPolicyLimitsResult
import org.jetbrains.hexana.mcp.model.memory.MemoryContractViolationResult
import org.jetbrains.hexana.mcp.model.memory.MemoryResult
import org.jetbrains.hexana.providers.WasmBinaryProvider

private val memoryContractPolicyJson = Json {
    ignoreUnknownKeys = true
}

fun WasmBinaryProvider.checkMemoryContract(
    baseline: WasmBinaryProvider,
    policyText: String,
    logText: String? = null,
    pathLabel: String? = null,
    baselinePathLabel: String? = null,
    policyPathLabel: String? = null,
    logPathLabel: String? = null
): CheckMemoryContractResult {
    val policy = parseMemoryContractPolicy(policyText)
    val previousByIndex = baseline.readMemory().memories.associateBy { it.memoryIdx }
    val entries = readMemory().memories.map { memory ->
        memory.toMemoryContractEntry(
            previous = previousByIndex[memory.memoryIdx],
            policy = policy.policyFor(memory)
        )
    }
    val violations = entries.flatMap { it.violations }
    val warnings = buildList {
        if (logText != null && logText.isBlank()) {
            add("Runtime log was empty.")
        }
    }
    return CheckMemoryContractResult(
        module = MemoryContractModuleIdentity(
            path = pathLabel,
            baselinePath = baselinePathLabel,
            policyPath = policyPathLabel,
            logPath = logPathLabel
        ),
        memories = entries,
        violations = violations,
        warnings = warnings
    )
}

private fun MemoryResult.toMemoryContractEntry(
    previous: MemoryResult?,
    policy: MemoryContractPolicyLimitsResult
): MemoryContractEntryResult {
    val current = this
    val violations = buildList {
        current.addLimitChangeViolations(this, previous)
        current.addPolicyViolations(this, policy)
    }
    return MemoryContractEntryResult(
        memoryIdx = memoryIdx,
        exportedAs = exportedAs,
        declaredLimits = limit,
        previousLimits = previous?.limit,
        policyLimits = policy,
        violations = violations,
        minimalCompatibleLimits = minimalCompatibleLimits(previous, policy)
    )
}

private fun MemoryResult.addLimitChangeViolations(
    target: MutableList<MemoryContractViolationResult>,
    previous: MemoryResult?
) {
    if (previous == null) return
    if (previous.limit.min != limit.min) {
        target.add(
            violation(
                kind = if (limit.min > previous.limit.min) "min_increased" else "min_decreased",
                field = "min",
                previous = previous.limit.min,
                declared = limit.min,
                message = "Memory minimum changed from ${previous.limit.min} to ${limit.min} pages."
            )
        )
    }
    val previousMax = previous.limit.max
    val currentMax = limit.max
    if (previousMax != currentMax) {
        val kind = when {
            previousMax == null -> "max_added"
            currentMax == null -> "max_removed"
            currentMax > previousMax -> "max_increased"
            else -> "max_decreased"
        }
        target.add(
            violation(
                kind = kind,
                field = "max",
                previous = previousMax,
                declared = currentMax,
                message = "Memory maximum changed from ${previousMax?.toString() ?: "none"} to ${currentMax?.toString() ?: "none"} pages."
            )
        )
    }
}

private fun MemoryResult.addPolicyViolations(
    target: MutableList<MemoryContractViolationResult>,
    policy: MemoryContractPolicyLimitsResult
) {
    policy.maxMin?.let { maxAllowedMin ->
        if (limit.min > maxAllowedMin) {
            target.add(
                violation(
                    kind = "policy_min_exceeded",
                    field = "min",
                    declared = limit.min,
                    policy = maxAllowedMin,
                    message = "Memory minimum ${limit.min} exceeds policy maximum minimum $maxAllowedMin pages."
                )
            )
        }
    }
    policy.requireMax?.let { requireMax ->
        if (requireMax && !limit.hasMax) {
            target.add(
                violation(
                    kind = "policy_max_required",
                    field = "hasMax",
                    declared = 0,
                    policy = 1,
                    message = "Policy requires an explicit memory maximum."
                )
            )
        }
    }
    policy.maxMax?.let { maxAllowedMax ->
        val declaredMax = limit.max
        if (declaredMax == null || declaredMax > maxAllowedMax) {
            target.add(
                violation(
                    kind = "policy_max_exceeded",
                    field = "max",
                    declared = declaredMax,
                    policy = maxAllowedMax,
                    message = "Memory maximum ${declaredMax?.toString() ?: "none"} exceeds policy maximum $maxAllowedMax pages."
                )
            )
        }
    }
}

private fun MemoryResult.minimalCompatibleLimits(
    previous: MemoryResult?,
    policy: MemoryContractPolicyLimitsResult
): MemoryContractCompatibleLimitsResult {
    val min = maxOf(limit.min, previous?.limit?.min ?: 0L)
    val boundedMaxValues = listOfNotNull(limit.max, previous?.limit?.max)
    val max = when {
        boundedMaxValues.isNotEmpty() -> boundedMaxValues.max()
        policy.requireMax == true -> min
        else -> null
    }
    return MemoryContractCompatibleLimitsResult(
        addressType = limit.addressType,
        min = min,
        max = max,
        hasMax = max != null,
        range = "$min..${max ?: "ε"}"
    )
}

private fun MemoryResult.violation(
    kind: String,
    field: String,
    previous: Long? = null,
    declared: Long? = null,
    policy: Long? = null,
    message: String
): MemoryContractViolationResult {
    return MemoryContractViolationResult(
        memoryIdx = memoryIdx,
        exportedAs = exportedAs,
        kind = kind,
        field = field,
        previous = previous,
        declared = declared,
        policy = policy,
        message = message
    )
}

private fun parseMemoryContractPolicy(text: String): MemoryContractPolicy {
    if (text.isBlank()) {
        throw HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "Policy file must not be blank")
    }
    val root = try {
        memoryContractPolicyJson.parseToJsonElement(text).jsonObject
    } catch (error: Exception) {
        throw HexanaAnalysisException(
            HexanaAnalysisErrorCode.INVALID_INPUT,
            "Policy file must be a JSON object",
            error
        )
    }
    return MemoryContractPolicy(
        defaults = root.policyLimits(),
        memories = root["memories"]?.jsonArrayOrEmpty()?.mapNotNull { it.asObjectOrNull()?.toPolicyMemory() }.orEmpty()
    )
}

private fun JsonObject.toPolicyMemory(): MemoryContractPolicyMemory {
    return MemoryContractPolicyMemory(
        memoryIdx = longOrNull("memoryIdx")?.toInt(),
        exportedAs = exportedNames(),
        limits = policyLimits()
    )
}

private fun JsonObject.policyLimits(): MemoryContractPolicyLimitsResult {
    val limits = this["limits"]?.asObjectOrNull()
    return MemoryContractPolicyLimitsResult(
        maxMin = longOrNull("maxMin") ?: limits?.longOrNull("maxMin"),
        maxMax = longOrNull("maxMax") ?: limits?.longOrNull("maxMax"),
        requireMax = booleanOrNull("requireMax") ?: limits?.booleanOrNull("requireMax")
    )
}

private fun JsonObject.exportedNames(): List<String> {
    val direct = stringOrNull("exportedAs") ?: stringOrNull("export")
    val list = when (val element = this["exportedAs"] ?: this["exports"]) {
        is JsonArray -> element.mapNotNull { it.stringOrNull() }
        else -> emptyList()
    }
    return (listOfNotNull(direct) + list).distinct()
}

private fun MemoryContractPolicy.policyFor(memory: MemoryResult): MemoryContractPolicyLimitsResult {
    val override = memories.firstOrNull { policyMemory ->
        policyMemory.memoryIdx == memory.memoryIdx ||
            policyMemory.exportedAs.any { it in memory.exportedAs }
    }?.limits ?: MemoryContractPolicyLimitsResult()
    return MemoryContractPolicyLimitsResult(
        maxMin = override.maxMin ?: defaults.maxMin,
        maxMax = override.maxMax ?: defaults.maxMax,
        requireMax = override.requireMax ?: defaults.requireMax
    )
}

private fun JsonObject.longOrNull(name: String): Long? =
    this[name]?.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull

private fun JsonObject.booleanOrNull(name: String): Boolean? =
    this[name]?.takeIf { it != JsonNull }?.jsonPrimitive?.booleanOrNull

private fun JsonObject.stringOrNull(name: String): String? =
    this[name]?.stringOrNull()

private fun JsonElement.stringOrNull(): String? =
    takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull

private fun JsonElement.jsonArrayOrEmpty(): JsonArray =
    this as? JsonArray ?: JsonArray(emptyList())

private fun JsonElement.asObjectOrNull(): JsonObject? =
    this as? JsonObject

private data class MemoryContractPolicy(
    val defaults: MemoryContractPolicyLimitsResult,
    val memories: List<MemoryContractPolicyMemory>
)

private data class MemoryContractPolicyMemory(
    val memoryIdx: Int?,
    val exportedAs: List<String>,
    val limits: MemoryContractPolicyLimitsResult
)
