package org.jetbrains.hexana.shared

import org.jetbrains.hexana.shared.features.ProposalCompatibility
import org.jetbrains.hexana.shared.features.RUNTIME_TARGETS_PATH

/** Tab title, also the key [WasmTableTabsProvider.singleTab] resolves. */
internal const val COMPATIBILITY_TAB_NAME = "Compatibility"

/**
 * Builds the **Compatibility** tab: one row per proposal the module uses, one column per
 * runtime declared in `.hexana/runtimes.json`, and a documentation link per row taken from
 * `features.json`.
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
    }

    val docsAction = openUrl?.let { open ->
        RowActionDescriptor(
            label = "Docs",
            tooltipProvider = { index -> proposals[index].docUrl?.let { "Open the proposal documentation" } },
            action = { index -> proposals[index].docUrl?.let(open) },
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
        prologueText = prologue(runtimeLabels, warningCount),
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

private fun prologue(runtimeLabels: List<String>, warningCount: Int): String = when {
    runtimeLabels.isEmpty() ->
        "No target runtimes are configured. Add them in $RUNTIME_TARGETS_PATH to see whether " +
            "these proposals are supported where this module runs."
    warningCount == 0 ->
        "Every proposal this module uses is supported by ${runtimeLabels.joinToString(", ")}."
    warningCount == 1 ->
        "One proposal this module uses is not supported by every configured runtime. " +
            "Targets come from $RUNTIME_TARGETS_PATH."
    else ->
        "$warningCount proposals this module uses are not supported by every configured runtime. " +
            "Targets come from $RUNTIME_TARGETS_PATH."
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
