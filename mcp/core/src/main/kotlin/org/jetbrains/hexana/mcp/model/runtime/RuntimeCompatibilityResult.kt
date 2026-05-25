package org.jetbrains.hexana.mcp.model.runtime

import kotlinx.serialization.Serializable

@Serializable
data class ProposalEvidenceResult(
    val featureFlag: String,
    val sections: List<String>,
    val instructions: List<String>,
    val customSections: List<String>,
    val notes: List<String>
)

@Serializable
data class RequiredProposalResult(
    val proposal: String,
    val required: Boolean,
    val evidence: ProposalEvidenceResult
)

@Serializable
data class RequiredProposalsSummaryResult(
    val requiredCount: Int,
    val proposals: List<String>
)

@Serializable
data class ListRequiredProposalsResult(
    val proposals: List<RequiredProposalResult>,
    val summary: RequiredProposalsSummaryResult
)

@Serializable
data class DiffRequiredProposalsSummaryResult(
    val currentRequiredCount: Int,
    val baselineRequiredCount: Int,
    val newlyRequiredCount: Int,
    val noLongerRequiredCount: Int
)

@Serializable
data class DiffRequiredProposalsResult(
    val path: String?,
    val baselinePath: String?,
    val currentRequiredProposals: List<String>,
    val baselineRequiredProposals: List<String>,
    val newlyRequiredProposals: List<String>,
    val noLongerRequiredProposals: List<String>,
    val newlyRequiredEvidence: List<RequiredProposalResult>,
    val noLongerRequiredEvidence: List<RequiredProposalResult>,
    val summary: DiffRequiredProposalsSummaryResult
)

@Serializable
data class RuntimeProposalStatusResult(
    val proposal: String,
    val status: String,
    val note: String,
    val evidence: ProposalEvidenceResult
)

@Serializable
data class CheckRuntimeCompatibilityResult(
    val runtime: String,
    val version: String?,
    val compatible: Boolean,
    val requiredProposals: List<String>,
    val unsupportedProposals: List<String>,
    val unknownProposals: List<String>,
    val proposalStatuses: List<RuntimeProposalStatusResult>,
    val evidence: List<RequiredProposalResult>,
    val notes: List<String>
)
