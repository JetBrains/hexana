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

/**
 * A runtime that supports a proposal at all, and the earliest version that does.
 *
 * Unlike [ProposalRuntimeStatus] this says nothing about the version a project targets: it
 * answers "where does this run, and from which version", which is what the *Supported in*
 * column shows. Runtimes that do not support the proposal are simply absent.
 */
class ProposalRuntimeSupport(
    val runtime: String,
    /** Earliest supporting version, or `null` when upstream records support without one. */
    val sinceVersion: String?,
    val requiresFlag: Boolean,
) {
    /** `Firefox 105+`, `Wasmtime 44+`, `GraalWasm (flag)`, or a bare name. */
    val label: String
        get() = when {
            requiresFlag -> "$runtime (flag)"
            sinceVersion != null -> "$runtime $sinceVersion+"
            else -> runtime
        }
}

/** A proposal used by the module, with its documentation link and per-target verdicts. */
class ProposalCompatibility(
    val proposal: WasmProposal,
    val docUrl: String?,
    val phase: String?,
    val statuses: List<ProposalRuntimeStatus>,
    /** Every runtime the catalog knows that supports this proposal, in catalog order. */
    val supportedIn: List<ProposalRuntimeSupport> = emptyList(),
    /**
     * Runtime ids the project declared, and which therefore have a column of their own in
     * the Compatibility table. Excluded from [otherRuntimes] so the two do not repeat.
     */
    private val declaredRuntimes: Set<String> = emptySet(),
) {
    /** Whether any declared target cannot run this proposal outright or without a flag. */
    val hasWarning: Boolean get() = statuses.any { it.support.isWarning }

    /**
     * Whether every declared target's verdict is [FeatureSupport.Unknown], i.e. the whole
     * row would read `?`. The table collapses such a row to a single "no data" and sorts it
     * below the rows that do carry a verdict — repeating `?` per column says nothing, and a
     * proposal the upstream table is silent about is not what a reader came for.
     *
     * `false` when nothing is declared: there are no per-runtime verdicts to be silent.
     */
    val hasNoData: Boolean
        get() = statuses.isNotEmpty() && statuses.all { it.support == FeatureSupport.Unknown }

    /** Verdicts worth acting on, in the order the targets were declared. */
    val warnings: List<ProposalRuntimeStatus> get() = statuses.filter { it.support.isWarning }

    /**
     * Supporting runtimes the table does not already give a column to — "where else does
     * this run". Equal to [supportedIn] when the project declares no targets.
     */
    val otherRuntimes: List<ProposalRuntimeSupport>
        get() = supportedIn.filterNot { it.runtime in declaredRuntimes }

    /** One-line form for a table cell; an em dash when the list is empty. */
    val supportedInLabel: String get() = joinLabels(supportedIn)

    /** One entry per line, so a list of twenty runtimes stays readable in a tooltip. */
    val supportedInTooltip: String? get() = tooltipLabels(supportedIn)

    val otherRuntimesLabel: String get() = joinLabels(otherRuntimes)

    val otherRuntimesTooltip: String? get() = tooltipLabels(otherRuntimes)

    private fun joinLabels(entries: List<ProposalRuntimeSupport>): String =
        if (entries.isEmpty()) "—" else entries.joinToString(", ") { it.label }

    private fun tooltipLabels(entries: List<ProposalRuntimeSupport>): String? =
        if (entries.isEmpty()) null else entries.joinToString("\n") { it.label }
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
            supportedIn = supportedIn(proposal),
            declaredRuntimes = targets.targets.mapTo(mutableSetOf()) { it.runtime },
        )
    }

    /** Whether any of [proposals] warns for any declared target. */
    fun hasAnyWarning(proposals: Collection<WasmProposal>): Boolean =
        proposals.any { evaluate(it).hasWarning }

    /**
     * Every runtime that supports [proposal], with the version each supports it from.
     *
     * Always every runtime in [WasmFeatureCatalog.executionRuntimes]: the table narrows it
     * to the ones without a column of their own ([ProposalCompatibility.otherRuntimes]),
     * and a declared runtime's own column already says whether the declared version
     * suffices. Tools are left out — they are not places a module runs.
     *
     * Order follows the catalog's own, which groups browsers before standalone runtimes.
     */
    private fun supportedIn(proposal: WasmProposal): List<ProposalRuntimeSupport> {
        return catalog.executionRuntimes.keys.mapNotNull { runtimeId ->
            val cell = catalog.cell(runtimeId, proposal.featureId) ?: return@mapNotNull null
            when (val status = cell.status) {
                is FeatureStatus.SinceVersion ->
                    ProposalRuntimeSupport(runtimeId, status.version, requiresFlag = false)
                FeatureStatus.Supported -> ProposalRuntimeSupport(runtimeId, null, requiresFlag = false)
                FeatureStatus.FlagRequired -> ProposalRuntimeSupport(runtimeId, null, requiresFlag = true)
                // Not supported, or not applicable to this kind of runtime: no entry at all.
                FeatureStatus.Unsupported, FeatureStatus.NotApplicable -> null
            }
        }
    }

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
