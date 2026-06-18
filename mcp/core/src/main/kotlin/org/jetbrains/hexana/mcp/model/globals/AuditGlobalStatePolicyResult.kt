package org.jetbrains.hexana.mcp.model.globals

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuditGlobalStatePolicyResult(
    val module: GlobalStatePolicyModuleIdentity,
    val policyId: String? = null,
    val detail: GlobalStatePolicyDetailResult,
    val globals: List<GlobalStatePolicyGlobalResult>,
    val newPublicGlobals: List<GlobalStatePolicyGlobalResult>,
    val allowedPublicGlobals: List<GlobalStatePolicyGlobalResult>,
    val allowedPublicGlobalSummary: GlobalStatePolicyAllowedGlobalsSummaryResult,
    val unchangedGlobalSummary: GlobalStatePolicyUnchangedGlobalsSummaryResult,
    val requestLimits: List<GlobalStatePolicyRequestLimitResult>,
    val violations: List<GlobalStatePolicyViolationResult>,
    val targetedGlobalIndices: List<Int>,
    val evidence: List<GlobalStatePolicyEvidenceResult>,
    val recommendedReport: GlobalStatePolicyRecommendedReportResult,
    val warnings: List<String>
)

@Serializable
data class GlobalStatePolicyDetailResult(
    val mode: String,
    val compactPayloadBudgetEstimatedTokens: Int,
    val allowedPublicGlobalsReturned: Int,
    val allowedPublicGlobalsTotal: Int,
    val evidenceReturned: Int,
    val evidenceTotal: Int
)

@Serializable
data class GlobalStatePolicyModuleIdentity(
    val path: String? = null,
    val baselinePath: String? = null,
    val policyPath: String? = null,
    val logPath: String? = null
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
data class GlobalStatePolicyAllowedGlobalsSummaryResult(
    val count: Int,
    val names: List<String>,
    val returnedAsObjects: Int,
    val omittedObjectCount: Int
)

@Serializable
data class GlobalStatePolicyUnchangedGlobalsSummaryResult(
    val currentGlobalCount: Int,
    val importedGlobalCount: Int,
    val localGlobalCount: Int,
    val unchangedPublicGlobalCount: Int,
    val unchangedPublicGlobalNames: List<String>
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

@Serializable
data class GlobalStatePolicyRecommendedReportResult(
    @SerialName("policy_id")
    val policyId: String? = null,
    val module: String? = null,
    @SerialName("compared_to")
    val comparedTo: String? = null,
    @SerialName("log_event_id")
    val logEventId: String? = null,
    val violations: List<GlobalStatePolicyReportViolationResult>,
    @SerialName("new_public_globals")
    val newPublicGlobals: List<GlobalStatePolicyReportGlobalResult>,
    @SerialName("allowed_public_globals")
    val allowedPublicGlobals: List<String>,
    @SerialName("targeted_global_indices")
    val targetedGlobalIndices: List<Int>,
    val recommendation: String
)

@Serializable
data class GlobalStatePolicyReportViolationResult(
    val kind: String,
    val globalidx: Int,
    @SerialName("import")
    val importName: String? = null,
    val type: String,
    val mutable: Boolean
)

@Serializable
data class GlobalStatePolicyReportGlobalResult(
    val export: String,
    val globalidx: Int,
    val origin: String,
    val type: String,
    val mutable: Boolean,
    @SerialName("init_expr")
    val initExpr: String? = null
)
