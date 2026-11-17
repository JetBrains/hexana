package org.jetbrains.hexana.mcp.model.functions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AuditLocalStackBudgetResult(
    val module: LocalStackBudgetModuleIdentity,
    val runtimeId: String? = null,
    val budget: LocalStackBudgetBudgetResult,
    val checkedExports: List<String>,
    val functions: List<LocalStackBudgetFunctionResult>,
    val violations: List<LocalStackBudgetViolationResult>,
    val cleanExports: List<String>,
    val evidence: LocalStackBudgetEvidenceResult,
    val minimalFix: LocalStackBudgetMinimalFixResult,
    val recommendedReport: LocalStackBudgetRecommendedReportResult,
    val warnings: List<String> = emptyList()
)

@Serializable
data class LocalStackBudgetModuleIdentity(
    val path: String? = null,
    val policyPath: String? = null,
    val logPath: String? = null
)

@Serializable
data class LocalStackBudgetBudgetResult(
    val maxLocals: Int? = null,
    val countParams: Boolean
)

@Serializable
data class LocalStackBudgetFunctionResult(
    val export: String,
    val funcidx: Int,
    val origin: String,
    val declaredLocalCount: Int,
    val paramCount: Int,
    val localCount: Int,
    val localsByType: Map<String, Int>,
    val exceedsBudget: Boolean? = null,
    val excess: Int? = null
)

@Serializable
data class LocalStackBudgetViolationResult(
    val export: String,
    val funcidx: Int,
    val localCount: Int,
    val localsByType: Map<String, Int>,
    val excess: Int
)

@Serializable
data class LocalStackBudgetEvidenceResult(
    val logEventId: String? = null,
    val checkedExports: List<String>
)

@Serializable
data class LocalStackBudgetMinimalFixResult(
    val splitOrReduceExports: List<String>
)

@Serializable
data class LocalStackBudgetRecommendedReportResult(
    @SerialName("runtime_id")
    val runtimeId: String? = null,
    val module: String? = null,
    val budget: LocalStackBudgetRecommendedBudgetResult,
    val violations: List<LocalStackBudgetRecommendedViolationResult>,
    @SerialName("clean_exports")
    val cleanExports: List<String>,
    val evidence: LocalStackBudgetRecommendedEvidenceResult,
    @SerialName("minimal_fix")
    val minimalFix: LocalStackBudgetRecommendedMinimalFixResult
)

@Serializable
data class LocalStackBudgetRecommendedBudgetResult(
    @SerialName("max_locals")
    val maxLocals: Int? = null,
    @SerialName("count_params")
    val countParams: Boolean
)

@Serializable
data class LocalStackBudgetRecommendedViolationResult(
    val export: String,
    val funcidx: Int,
    @SerialName("local_count")
    val localCount: Int,
    @SerialName("locals_by_type")
    val localsByType: Map<String, Int>,
    val excess: Int
)

@Serializable
data class LocalStackBudgetRecommendedEvidenceResult(
    @SerialName("log_event_id")
    val logEventId: String? = null,
    @SerialName("checked_exports")
    val checkedExports: List<String>
)

@Serializable
data class LocalStackBudgetRecommendedMinimalFixResult(
    @SerialName("split_or_reduce_exports")
    val splitOrReduceExports: List<String>
)
