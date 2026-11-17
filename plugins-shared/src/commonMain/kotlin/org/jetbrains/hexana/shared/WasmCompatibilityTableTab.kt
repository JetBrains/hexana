package org.jetbrains.hexana.shared

import org.jetbrains.hexana.shared.features.FeatureSupport
import org.jetbrains.hexana.shared.features.ProposalCompatibility
import org.jetbrains.hexana.shared.features.ProposalRuntimeSupport
import org.jetbrains.hexana.shared.features.RUNTIME_TARGETS_PATH
import org.jetbrains.hexana.shared.features.WASM_FEATURES_PAGE_URL

/**
 * Tab title, also the key [WasmTableTabsProvider.singleTab] resolves.
 *
 * Public because hosts address the tab by name: the proposals badge selects it on click, and
 * IntelliJ re-resolves it by name when `.hexana/runtimes.json` changes.
 */
const val COMPATIBILITY_TAB_NAME = "Compatibility"

/** What a row whose every per-runtime verdict is `?` reads instead of a row of them. */
private const val NO_DATA_LABEL = "No data"

/**
 * Builds the **Compatibility** tab: one row per proposal the module uses, one column per
 * runtime declared in `.hexana/runtimes.json`, and a documentation link per row taken from
 * `features.json`.
 *
 * The column naming supporting runtimes changes role with the configuration, which is why it
 * has two forms:
 * - **Nothing declared** — a leading *Supported in* column listing every runtime in the
 *   catalog. This is what makes the tab worth opening before anything is configured; there
 *   are no per-runtime columns to read instead.
 * - **Targets declared** — their own columns lead, and a trailing *Other runtimes* column
 *   lists the supporting runtimes that do *not* have a column, so the two never repeat.
 *
 * Rows the upstream table says nothing about at all are collapsed to a single *No data*
 * and sorted last, so what is left above them is the part worth reading.
 *
 * Returns `null` when there is nothing to show — no host-supplied compatibility data, or a
 * module that uses no proposals at all.
 *
 * @param proposals the proposals detected in the binary, already evaluated against the
 *   declared targets
 */
internal fun compatibilityTabDescriptor(
    proposals: List<ProposalCompatibility>,
    runtimeLabels: List<String>,
    openUrl: ((String) -> Unit)?,
    openRuntimeSettings: (() -> Unit)?,
): TableTabDescriptor? {
    if (proposals.isEmpty()) return null

    // A stable sort, so the proposal order survives within each of the two groups.
    val rows = proposals.sortedBy { it.hasNoData }

    val columns = buildList {
        add(
            ColumnDescriptor(
                name = "Proposal",
                widthBehavior = ColumnWidth.MeasuredMin("Non-trapping float-to-int conversions"),
            ) { index ->
                val entry = rows[index]
                // The warning marker is repeated per row so a sorted or filtered table still
                // shows which rows the runtime-compatibility problems are about.
                val label = if (entry.hasWarning) "⚠ ${entry.proposal.displayName}" else entry.proposal.displayName
                CellValue(entry.proposal.displayName, label, tooltip = proposalTooltip(entry))
            }
        )
        add(
            ColumnDescriptor(name = "Phase", widthBehavior = ColumnWidth.Measured("inactive")) { index ->
                val phase = rows[index].phase
                CellValue(phase ?: "", phase ?: "—", tooltip = phaseTooltip(phase))
            }
        )
        // With nothing declared the whole catalog *is* the answer, so it leads. With targets
        // declared their own columns lead and this becomes a trailing "where else" summary.
        if (runtimeLabels.isEmpty()) {
            add(
                supportingRuntimesColumn(
                    name = "Supported in",
                    entries = { index -> rows[index].supportedIn },
                    label = { index -> rows[index].supportedInLabel },
                    tooltip = { index -> rows[index].supportedInTooltip },
                )
            )
        }
        runtimeLabels.forEachIndexed { runtimeIndex, label ->
            add(
                ColumnDescriptor(name = label, widthBehavior = ColumnWidth.MeasuredMin("No (since 000)")) { index ->
                    val entry = rows[index]
                    // A row of nothing but `?` becomes one label carried by the first column
                    // and blanks after it, so it reads as a single cell spanning the row.
                    if (entry.hasNoData) {
                        val text = if (runtimeIndex == 0) NO_DATA_LABEL else ""
                        return@ColumnDescriptor CellValue(
                            FeatureSupport.Unknown.name,
                            text,
                            tooltip = noDataTooltip(entry).takeIf { runtimeIndex == 0 },
                        )
                    }
                    val status = entry.statuses.getOrNull(runtimeIndex)
                        ?: return@ColumnDescriptor CellValue("", "—")
                    // Sort on the verdict so a click groups the failures together. The tint
                    // points at the runtime that fails, not just the row that has a failure.
                    CellValue(
                        status.support.name,
                        status.shortLabel,
                        tooltip = status.note ?: status.describe,
                        tint = RowTint.Warning.takeIf { status.support.isWarning },
                    )
                }
            )
        }
        if (runtimeLabels.isNotEmpty()) {
            add(
                supportingRuntimesColumn(
                    name = "Other runtimes",
                    entries = { index -> rows[index].otherRuntimes },
                    label = { index -> rows[index].otherRuntimesLabel },
                    tooltip = { index -> rows[index].otherRuntimesTooltip },
                )
            )
        }
    }

    val docsAction = openUrl?.let { open ->
        RowActionDescriptor(
            label = "Docs",
            tooltipProvider = { index -> rows[index].docUrl?.let { "Open the proposal documentation" } },
            action = { index -> rows[index].docUrl?.let(open) },
            kind = RowActionKind.Documentation,
        )
    }

    val contextMenuItems = buildList {
        if (openUrl != null) {
            add(
                RowContextMenuItemDescriptor(
                    labelProvider = { "Open documentation" },
                    action = { index -> rows[index].docUrl?.let { openUrl(it) } },
                    isVisible = { index -> rows[index].docUrl != null },
                )
            )
        }
        if (openRuntimeSettings != null) {
            add(
                RowContextMenuItemDescriptor(
                    labelProvider = { "Configure runtimes…" },
                    action = { openRuntimeSettings() },
                )
            )
        }
    }

    val warningCount = rows.count { it.hasWarning }

    return TableTabDescriptor(
        name = COMPATIBILITY_TAB_NAME,
        columns = columns,
        rowCount = rows.size,
        rowAction = docsAction,
        contextMenuItems = contextMenuItems,
        prologueText = prologue(runtimeLabels, warningCount, canConfigure = openRuntimeSettings != null),
        prologueActions = prologueActions(runtimeLabels, openUrl, openRuntimeSettings),
        showIdColumn = false,
        priority = TabPriority.Medium,
        predefinedFilters = if (warningCount > 0) {
            // Pre-selected, because the reason to open this tab is usually a runtime-compatibility problem.
            listOf(PredefinedFilter("Unsupported only") { index -> rows[index].hasWarning })
        } else {
            emptyList()
        },
        defaultFilterShowAll = warningCount == 0,
    )
}

/**
 * A column listing supporting runtimes as `Firefox 120+, Wasmtime 47+`.
 *
 * Fills the slack, since it is the longest cell in the table by far — with nothing declared
 * it lists the whole catalog. Sorts on how many runtimes are listed rather than on the text,
 * which would order by whichever runtime happens to come first; ascending then puts the
 * narrowly supported proposals — the risky ones — at the top.
 */
private fun supportingRuntimesColumn(
    name: String,
    entries: (index: Int) -> List<ProposalRuntimeSupport>,
    label: (index: Int) -> String,
    tooltip: (index: Int) -> String?,
): ColumnDescriptor = ColumnDescriptor(name = name, widthBehavior = ColumnWidth.Fill()) { index ->
    CellValue(entries(index).size, label(index), tooltip = tooltip(index))
}

/**
 * Links offered under the prologue: the settings page, and the upstream table the verdicts
 * come from.
 *
 * The source link matters because every verdict here is somebody else's data — a reader who
 * finds one surprising needs to be able to check it, and the page is more use to them than
 * the JSON the plugin actually downloads.
 */
private fun prologueActions(
    runtimeLabels: List<String>,
    openUrl: ((String) -> Unit)?,
    openRuntimeSettings: (() -> Unit)?,
): List<PrologueActionDescriptor> = buildList {
    // A link rather than a file path in the prose: the prologue is the one place that has to
    // explain the feature, so it should also be able to act on it.
    openRuntimeSettings?.let {
        add(
            PrologueActionDescriptor(
                label = if (runtimeLabels.isEmpty()) "Configure target runtimes…" else "Change target runtimes…",
                action = it,
            )
        )
    }
    openUrl?.let { open ->
        add(PrologueActionDescriptor("See the full list of proposals and runtimes at webassembly.org/features") { open(WASM_FEATURES_PAGE_URL) })
    }
}

/**
 * @param canConfigure whether the host offers a settings page, in which case the prologue
 *   defers to the [PrologueActionDescriptor] link instead of naming the settings file. A
 *   host without one still needs to be told where the file is.
 */
private fun prologue(runtimeLabels: List<String>, warningCount: Int, canConfigure: Boolean): String {
    val whereTargetsLive = if (canConfigure) "" else " Targets come from $RUNTIME_TARGETS_PATH."
    return when {
        runtimeLabels.isEmpty() ->
            "No runtimes specified. Every proposal used by this WASM is shown together with all supported runtimes." +
                if (canConfigure) "" else " Declare runtimes in $RUNTIME_TARGETS_PATH."
        warningCount == 0 ->
            "Every proposal this module uses is supported by ${runtimeLabels.joinToString(", ")}." +
                whereTargetsLive
        warningCount == 1 ->
            "One proposal this module uses is not supported by a configured runtime." +
                whereTargetsLive
        else ->
            "$warningCount proposals this module uses are not supported by a configured runtime." +
                whereTargetsLive
    }
}

/**
 * Tooltip for the collapsed cell. Keeps the per-runtime lines the row no longer shows, and
 * any upstream note — a note on an otherwise-empty cell usually explains why it is empty.
 */
private fun noDataTooltip(entry: ProposalCompatibility): String = buildString {
    append("The feature table records nothing about this proposal for:")
    entry.statuses.forEach { append('\n').append(it.note ?: it.describe) }
}

private fun proposalTooltip(entry: ProposalCompatibility): String = buildString {
    append(entry.proposal.displayName)
    entry.docUrl?.let { append('\n').append(it) }
    if (entry.statuses.isNotEmpty()) {
        append('\n')
        entry.statuses.joinTo(this, separator = "\n") { it.describe }
    }
}

private fun phaseTooltip(phase: String?): String? = when (phase) {
    null -> null
    "inactive" -> "Inactive proposal"
    else -> "Standardization phase $phase"
}
