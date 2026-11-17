package org.jetbrains.hexana.mcp.query

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.model.features.ListEnabledFeaturesResult
import org.jetbrains.hexana.mcp.model.runtime.CheckRuntimeCompatibilityResult
import org.jetbrains.hexana.mcp.model.runtime.ListRequiredProposalsResult
import org.jetbrains.hexana.mcp.model.structural.IdentifiedStructuralParseResult
import org.jetbrains.hexana.mcp.model.structural.StructuralKind
import org.jetbrains.hexana.mcp.model.structural.StructuralParseStatus
import org.jetbrains.hexana.mcp.model.structural.StructuralParseResult
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.wasm.sections.WasmParseException

/** Evidence for one already-selected binary. A null core payload means core evidence is unavailable. */
data class ValidateAndExplainEvidence(
    val structural: StructuralParseResult,
    val core: CoreValidationEvidence?,
    val featureExtractionFailure: String? = null,
)

data class CoreValidationEvidence(
    val enabledFeatures: ListEnabledFeaturesResult,
    val requiredProposals: ListRequiredProposalsResult,
    val runtimeCompatibility: List<CheckRuntimeCompatibilityResult>,
)

class DuplicateRuntimeTargetsException : IllegalArgumentException("Runtime targets must be unique.")

/** Reads structural evidence and only the core-provider services applicable to a core module. */
fun ByteArray.readValidateAndExplainEvidence(runtimeTargets: List<String>): ValidateAndExplainEvidence {
    val structural = toCommonByteBuffer().readStructuralParse()
    if (structural !is IdentifiedStructuralParseResult || structural.summary.kind != StructuralKind.CORE_MODULE) {
        return ValidateAndExplainEvidence(structural, null)
    }

    val normalizedTargets = runtimeTargets.map { it.trim().lowercase() }
    if (normalizedTargets.size != normalizedTargets.distinct().size) throw DuplicateRuntimeTargetsException()
    runtimeTargets.forEach(::validateRuntimeTarget)
    val (features, proposals) = try {
        val provider = WasmBinaryProvider(toCommonByteBuffer())
        val features = provider.readEnabledFeatures()
        features to features.toRequiredProposals()
    } catch (error: Exception) {
        if (error !is WasmParseException && structural.status == StructuralParseStatus.CLEAN) throw error
        return ValidateAndExplainEvidence(
            structural = structural.copy(status = StructuralParseStatus.PARTIAL),
            core = null,
            featureExtractionFailure = error.message ?: "Core feature extraction could not complete.",
        )
    }
    return ValidateAndExplainEvidence(
        structural = structural,
        core = CoreValidationEvidence(
            enabledFeatures = features,
            requiredProposals = proposals,
            runtimeCompatibility = if (structural.status == StructuralParseStatus.CLEAN) {
                runtimeTargets.map { proposals.checkRuntimeCompatibility(it, null) }
            } else {
                emptyList()
            },
        ),
    )
}
