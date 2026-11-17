package org.jetbrains.hexana.mcp.model.memory

import kotlinx.serialization.Serializable

@Serializable
data class CheckMemoryContractResult(
    val module: MemoryContractModuleIdentity,
    val memories: List<MemoryContractEntryResult>,
    val violations: List<MemoryContractViolationResult>,
    val warnings: List<String> = emptyList()
)

@Serializable
data class MemoryContractModuleIdentity(
    val path: String? = null,
    val baselinePath: String? = null,
    val policyPath: String? = null,
    val logPath: String? = null
)

@Serializable
data class MemoryContractEntryResult(
    val memoryIdx: Int,
    val exportedAs: List<String> = emptyList(),
    val declaredLimits: MemoryLimitsResult,
    val previousLimits: MemoryLimitsResult? = null,
    val policyLimits: MemoryContractPolicyLimitsResult,
    val violations: List<MemoryContractViolationResult> = emptyList(),
    val minimalCompatibleLimits: MemoryContractCompatibleLimitsResult
)

@Serializable
data class MemoryContractPolicyLimitsResult(
    val maxMin: Long? = null,
    val maxMax: Long? = null,
    val requireMax: Boolean? = null
)

@Serializable
data class MemoryContractCompatibleLimitsResult(
    val addressType: String,
    val min: Long,
    val max: Long? = null,
    val hasMax: Boolean,
    val range: String
)

@Serializable
data class MemoryContractViolationResult(
    val memoryIdx: Int,
    val exportedAs: List<String> = emptyList(),
    val kind: String,
    val field: String,
    val previous: Long? = null,
    val declared: Long? = null,
    val policy: Long? = null,
    val message: String
)
