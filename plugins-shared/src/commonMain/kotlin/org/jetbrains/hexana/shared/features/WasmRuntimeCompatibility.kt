package org.jetbrains.hexana.shared.features

import org.jetbrains.hexana.wasm.analysis.WasmProposal

/** Verdict for one proposal against one declared runtime target. */
enum class FeatureSupport {
    /** The target runs this proposal as configured. */
    Supported,

    /** The target cannot run this proposal, or not at the declared version. */
    Unsupported,

    /** The target runs it only behind a flag or non-default build option. */
    FlagRequired,

    /**
     * `features.json` records nothing usable for this pair. Deliberately *not* a warning:
     * the upstream table leaves many (runtime, feature) cells empty, and warning on them
     * would make the badge cry wolf on almost every module.
     */
    Unknown,
    ;

    /** Whether this verdict should raise the warning marker on the proposals badge. */
    val isWarning: Boolean get() = this == Unsupported || this == FlagRequired
}

/**
 * One cell of the rendered table: how [runtime] stands on a proposal.
 *
 * @param targetVersion the version declared in `.hexana/runtimes.json`, or `null` for "any"
 * @param sinceVersion the version the feature landed in upstream, when known — the useful
 *   half of an `Unsupported` verdict, since it says what to upgrade to
 * @param note the upstream note, which typically names the exact flag required
 */
class ProposalRuntimeStatus(
    val runtime: String,
    val targetVersion: String?,
    val support: FeatureSupport,
    val sinceVersion: String? = null,
    val note: String? = null,
) {
    /** Short cell text, e.g. `Yes`, `No (since 133)`, `Flag`, `?`. */
    val shortLabel: String
        get() = when (support) {
            FeatureSupport.Supported -> "Yes"
            FeatureSupport.Unsupported -> sinceVersion?.let { "No (since $it)" } ?: "No"
            FeatureSupport.FlagRequired -> "Flag"
            FeatureSupport.Unknown -> "?"
        }

    /** One-line description used in tooltips, e.g. `Chrome 120 — not supported, needs 133`. */
    val describe: String
        get() {
            val target = if (targetVersion.isNullOrBlank()) runtime else "$runtime $targetVersion"
            val verdict = when (support) {
                FeatureSupport.Supported -> "supported"
                FeatureSupport.Unsupported ->
                    sinceVersion?.let { "not supported, needs $it" } ?: "not supported"
                FeatureSupport.FlagRequired -> "needs a flag"
                FeatureSupport.Unknown -> "no data"
            }
            return "$target — $verdict"
        }
}

/** A proposal used by the module, with its documentation link and per-target verdicts. */
class ProposalCompatibility(
    val proposal: WasmProposal,
    val docUrl: String?,
    val phase: String?,
    val statuses: List<ProposalRuntimeStatus>,
) {
    /** Whether any declared target cannot run this proposal outright or without a flag. */
    val hasWarning: Boolean get() = statuses.any { it.support.isWarning }

    /** Verdicts worth acting on, in the order the targets were declared. */
    val warnings: List<ProposalRuntimeStatus> get() = statuses.filter { it.support.isWarning }
}

/**
 * Evaluates the proposals a module uses against the runtimes declared for the project.
 *
 * Combines a [WasmFeatureCatalog] (the upstream matrix) with a [RuntimeTargets] (the user's
 * declaration from `.hexana/runtimes.json`). Pure and host-neutral: the IntelliJ plugin and
 * the VS Code webview both build one of these and feed it to the same badge and table code.
 */
class WasmRuntimeCompatibility(
    val catalog: WasmFeatureCatalog,
    val targets: RuntimeTargets,
) {
    /** Runtime ids in declaration order; the column order of the Compatibility table. */
    val runtimeLabels: List<String> = targets.targets.map { it.toString() }

    fun evaluate(proposals: Collection<WasmProposal>): List<ProposalCompatibility> =
        proposals.sorted().map { evaluate(it) }

    fun evaluate(proposal: WasmProposal): ProposalCompatibility {
        val feature = catalog.feature(proposal.featureId)
        return ProposalCompatibility(
            proposal = proposal,
            docUrl = feature?.url,
            phase = feature?.phase,
            statuses = targets.targets.map { status(proposal, it) },
        )
    }

    /** Whether any of [proposals] warns for any declared target. */
    fun hasAnyWarning(proposals: Collection<WasmProposal>): Boolean =
        proposals.any { evaluate(it).hasWarning }

    private fun status(proposal: WasmProposal, target: RuntimeTarget): ProposalRuntimeStatus {
        val cell = catalog.cell(target.runtime, proposal.featureId)
        val declaredVersion = target.version?.takeIf { it.isNotBlank() }

        // No entry at all, or an entry that says "does not apply": nothing to claim.
        if (cell == null || cell.status is FeatureStatus.NotApplicable) {
            return ProposalRuntimeStatus(
                runtime = target.runtime,
                targetVersion = declaredVersion,
                support = FeatureSupport.Unknown,
                note = cell?.note,
            )
        }

        val support: FeatureSupport
        var sinceVersion: String? = null
        when (val status = cell.status) {
            is FeatureStatus.Supported -> support = FeatureSupport.Supported
            is FeatureStatus.Unsupported -> support = FeatureSupport.Unsupported
            is FeatureStatus.FlagRequired -> support = FeatureSupport.FlagRequired
            is FeatureStatus.SinceVersion -> {
                sinceVersion = status.version
                // No declared version means "any version" — take the runtime at its best.
                support = if (declaredVersion == null || runtimeVersionAtLeast(declaredVersion, status.version)) {
                    FeatureSupport.Supported
                } else {
                    FeatureSupport.Unsupported
                }
            }
            is FeatureStatus.NotApplicable -> support = FeatureSupport.Unknown
        }

        return ProposalRuntimeStatus(
            runtime = target.runtime,
            targetVersion = declaredVersion,
            support = support,
            sinceVersion = sinceVersion,
            note = cell.note,
        )
    }
}
