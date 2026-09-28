package org.jetbrains.hexana.shared.inspections

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.features.FeatureSupport

/**
 * Proposals the module uses that a runtime declared in `.hexana/runtimes.json` cannot run,
 * or runs only behind a flag — the same verdicts that turn a proposals badge yellow and
 * fill the Compatibility tab, reported one problem per proposal.
 *
 * A warning rather than an error, matching the badge: the declaration says where the
 * project intends to run, and a verdict comes from upstream data that can lag a release.
 * `Unknown` verdicts are not reported, for the reason given on [FeatureSupport.Unknown].
 *
 * Proposal detection has no byte position, so these problems are file-level.
 */
object WasmRuntimeCompatibilityInspection : BinaryInspection {
    override val id = "wasm.runtime-compatibility"
    override val displayName = "Runtime compatibility"
    override val description =
        "Proposals the module uses that a runtime declared in .hexana/runtimes.json cannot run without a flag."

    const val UNSUPPORTED_CODE = "wasm.proposal_unsupported"
    const val FLAG_REQUIRED_CODE = "wasm.proposal_flag_required"

    override fun mayReport(provider: BinaryProvider, context: InspectionContext): Boolean =
        provider is WasmBinaryProvider && context.hasDeclaredTargets

    override fun inspect(provider: BinaryProvider, context: InspectionContext): List<BinaryProblem> {
        if (provider !is WasmBinaryProvider || !provider.canProvideWasmModule()) return emptyList()
        val compatibility = context.runtimeCompatibility?.takeIf { context.hasDeclaredTargets } ?: return emptyList()
        return compatibility.evaluate(provider.usedProposals).mapNotNull { entry ->
            val warnings = entry.warnings.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // Unsupported somewhere outranks needing a flag somewhere else.
            val unsupported = warnings.any { it.support == FeatureSupport.Unsupported }
            BinaryProblem(
                inspectionId = id,
                code = if (unsupported) UNSUPPORTED_CODE else FLAG_REQUIRED_CODE,
                severity = ProblemSeverity.Warning,
                kind = if (unsupported) "Unsupported proposal" else "Proposal needs a flag",
                // The message names the proposal itself, so there is no separate location.
                message = "${entry.proposal.displayName}: ${warnings.joinToString("; ") { it.describe }}",
            )
        }
    }

    private val InspectionContext.hasDeclaredTargets: Boolean
        get() = runtimeCompatibility?.targets?.targets?.isNotEmpty() == true
}
