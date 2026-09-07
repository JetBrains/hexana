package org.jetbrains.hexana.shared

import org.jetbrains.hexana.shared.features.ProposalCompatibility
import org.jetbrains.hexana.shared.features.ProposalRuntimeSupport
import org.jetbrains.hexana.shared.features.RUNTIME_TARGETS_PATH

/**
 * Tab title, also the key [WasmTableTabsProvider.singleTab] resolves.
 *
 * Public because hosts address the tab by name: the proposals badge selects it on click, and
 * IntelliJ re-resolves it by name when `.hexana/runtimes.json` changes.
 */
const val COMPATIBILITY_TAB_NAME = "Compatibility"

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

    val columns = buildList {
        add(
            ColumnDescriptor(
                name = "Proposal",
                widthBehavior = ColumnWidth.MeasuredMin("Non-trapping float-to-int conversions"),
            ) { index ->
                val entry = proposals[index]
                // The warning marker is repeated per row so a sorted or filtered table still
                // shows which rows the badge is warning about.
                val label = if (entry.hasWarning) "⚠ ${entry.proposal.displayName}" else entry.proposal.displayName
                CellValue(entry.proposal.displayName, label, tooltip = proposalTooltip(entry))
            }
        )
        add(
            ColumnDescriptor(name = "Phase", widthBehavior = ColumnWidth.Measured("inactive")) { index ->
                val phase = proposals[index].phase
                CellValue(phase ?: "", phase ?: "—", tooltip = phaseTooltip(phase))
            }
        )
        // With nothing declared the whole catalog *is* the answer, so it leads. With targets
        // declared their own columns lead and this becomes a trailing "where else" summary.
        if (runtimeLabels.isEmpty()) {
            add(
                supportingRuntimesColumn(
                    name = "Supported in",
                    entries = { index -> proposals[index].supportedIn },
                    label = { index -> proposals[index].supportedInLabel },
                    tooltip = { index -> proposals[index].supportedInTooltip },
                )
            )
        }
        runtimeLabels.forEachIndexed { runtimeIndex, label ->
            add(
                ColumnDescriptor(name = label, widthBehavior = ColumnWidth.MeasuredMin("No (since 000)")) { index ->
                    val status = proposals[index].statuses.getOrNull(runtimeIndex)
                        ?: return@ColumnDescriptor CellValue("", "—")
                    // Sort on the verdict so a click groups the failures together.
                    CellValue(status.support.name, status.shortLabel, tooltip = status.note ?: status.describe)
                }
            )
        }
        if (runtimeLabels.isNotEmpty()) {
            add(
                supportingRuntimesColumn(
                    name = "Other runtimes",
                    entries = { index -> proposals[index].otherRuntimes },
                    label = { index -> proposals[index].otherRuntimesLabel },
                    tooltip = { index -> proposals[index].otherRuntimesTooltip },
                )
            )
        }
    }

    val docsAction = openUrl?.let { open ->
        RowActionDescriptor(
            label = "Docs",
            tooltipProvider = { index -> proposals[index].docUrl?.let { "Open the proposal documentation" } },
            action = { index -> proposals[index].docUrl?.let(open) },
            kind = RowActionKind.Documentation,
        )
    }

    val contextMenuItems = buildList {
        if (openUrl != null) {
            add(
                RowContextMenuItemDescriptor(
                    labelProvider = { "Open documentation" },
                    action = { index -> proposals[index].docUrl?.let { openUrl(it) } },
                    isVisible = { index -> proposals[index].docUrl != null },
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

    val warningCount = proposals.count { it.hasWarning }

    return TableTabDescriptor(
        name = COMPATIBILITY_TAB_NAME,
        columns = columns,
        rowCount = proposals.size,
        rowAction = docsAction,
        contextMenuItems = contextMenuItems,
        prologueText = prologue(runtimeLabels, warningCount, canConfigure = openRuntimeSettings != null),
        // A link rather than a file path in the prose: the prologue is the one place that has
        // to explain the feature, so it should also be able to act on it.
        prologueAction = openRuntimeSettings?.let {
            PrologueActionDescriptor(
                label = if (runtimeLabels.isEmpty()) "Configure target runtimes…" else "Change target runtimes…",
                action = it,
            )
        },
        showIdColumn = false,
        priority = TabPriority.Medium,
        predefinedFilters = if (warningCount > 0) {
            // Pre-selected, because the reason to open this tab is usually the badge warning.
            listOf(PredefinedFilter("Unsupported only") { index -> proposals[index].hasWarning })
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
 * @param canConfigure whether the host offers a settings page, in which case the prologue
 *   defers to the [PrologueActionDescriptor] link instead of naming the settings file. A
 *   host without one still needs to be told where the file is.
 */
private fun prologue(runtimeLabels: List<String>, warningCount: Int, canConfigure: Boolean): String {
    val whereTargetsLive = if (canConfigure) "" else " Targets come from $RUNTIME_TARGETS_PATH."
    return when {
        runtimeLabels.isEmpty() ->
            "Supported in lists every runtime WebAssembly's feature data knows about. Declaring the " +
                "ones this project targets narrows it to those and warns when a module uses " +
                "something they cannot run." +
                if (canConfigure) "" else " Declare them in $RUNTIME_TARGETS_PATH."
        warningCount == 0 ->
            "Every proposal this module uses is supported by ${runtimeLabels.joinToString(", ")}." +
                whereTargetsLive
        warningCount == 1 ->
            "One proposal this module uses is not supported by every configured runtime." +
                whereTargetsLive
        else ->
            "$warningCount proposals this module uses are not supported by every configured runtime." +
                whereTargetsLive
    }
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
