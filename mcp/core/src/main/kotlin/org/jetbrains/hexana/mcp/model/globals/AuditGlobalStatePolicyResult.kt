package org.jetbrains.hexana.mcp.model.globals

import kotlinx.serialization.Serializable

@Serializable
data class AuditGlobalStatePolicyResult(
    val policyId: String? = null,
    val globals: List<GlobalStatePolicyGlobalResult>,
    val newPublicGlobals: List<GlobalStatePolicyGlobalResult>,
    val allowedPublicGlobals: List<GlobalStatePolicyGlobalResult>,
    val requestLimits: List<GlobalStatePolicyRequestLimitResult>,
    val violations: List<GlobalStatePolicyViolationResult>,
    val targetedGlobalIndices: List<Int>,
    val evidence: List<GlobalStatePolicyEvidenceResult>,
    val warnings: List<String>
)

@Serializable
data class GlobalStatePolicyGlobalResult(
    val export: String? = null,
    val importNameQualified: String? = null,
    val globalidx: Int,
    val type: String,
    val mutable: Boolean,
    val initExpr: String? = null,
    val origin: String,
    val importModule: String? = null,
    val importName: String? = null,
    val status: String,
    val allowed: Boolean,
    val allowedBy: String? = null
)

@Serializable
data class GlobalStatePolicyRequestLimitResult(
    val export: String,
    val globalidx: Int,
    val value: Long?,
    val maxValue: Long?,
    val allowed: Boolean,
    val violation: String? = null
)

@Serializable
data class GlobalStatePolicyViolationResult(
    val kind: String,
    val globalidx: Int,
    val export: String? = null,
    val importNameQualified: String? = null,
    val field: String,
    val observed: String? = null,
    val policy: String? = null,
    val message: String
)

@Serializable
data class GlobalStatePolicyEvidenceResult(
    val kind: String,
    val globalidx: Int,
    val export: String? = null,
    val importNameQualified: String? = null,
    val type: String,
    val mutable: Boolean,
    val initExpr: String? = null,
    val origin: String
)
