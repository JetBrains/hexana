package org.jetbrains.hexana.shared

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.shared.inspections.BinaryProblem
import org.jetbrains.hexana.shared.inspections.InspectionContext
import org.jetbrains.hexana.shared.inspections.ProblemSeverity
import org.jetbrains.hexana.shared.inspections.inspectBinary
import org.jetbrains.hexana.shared.inspections.mayHaveProblems
import org.jetbrains.hexana.shared.inspections.selectionIn

const val PROBLEMS_TAB_NAME = "Problems"

/**
 * How a host takes the user to a problem. Supplied through [TableTabsContext.problemNavigator]
 * so the Problems tab is built once, here, and each host contributes only navigation.
 *
 * @param open called when a row is clicked; typically the Wasm function for a problem with
 *   a [BinaryProblem.funcIdx], otherwise its bytes in the hex view
 * @param menuItems extra row context-menu entries
 */
class ProblemNavigator(
    val open: (BinaryProblem) -> Unit,
    val menuItems: List<ProblemMenuItem> = emptyList(),
)

class ProblemMenuItem(
    val label: String,
    val isVisible: (BinaryProblem) -> Boolean = { true },
    val action: (BinaryProblem) -> Unit,
)

/** The inspection inputs a [TableTabsContext] carries. */
fun TableTabsContext.inspectionContext(): InspectionContext =
    InspectionContext(runtimeCompatibility = runtimeCompatibility, hostProblems = hostProblems)

/**
 * The Problems tab, for every format: whatever [inspectBinary] reports.
 *
 * Registered last in [TableTabsProviderRegistry] so it sits after a format's own tabs.
 */
class BinaryProblemsTableTabsProvider : TableTabsProvider {
    override fun tableTabs(provider: BinaryProvider): List<TableTabDescriptor> =
        tableTabs(provider, TableTabsContext())

    override fun tableTabs(provider: BinaryProvider, context: TableTabsContext): List<TableTabDescriptor> =
        listOfNotNull(singleTab(PROBLEMS_TAB_NAME, provider, context))

    override fun availableTabNames(
        provider: BinaryProvider,
        context: TableTabsContext,
    ): Pair<List<String>, List<String>> {
        // Optimistic: singleTab returns null for a clean binary and the placeholder goes.
        val names = if (mayHaveProblems(provider, context.inspectionContext())) listOf(PROBLEMS_TAB_NAME) else emptyList()
        return Pair(names, emptyList())
    }

    override fun singleTab(name: String, provider: BinaryProvider, context: TableTabsContext): TableTabDescriptor? {
        if (name != PROBLEMS_TAB_NAME) return null
        // Forces the lazy parses the inspections read; singleTab is always called off the UI thread.
        return problemsTableTab(inspectBinary(provider, context.inspectionContext()), provider.bytes.size, context.problemNavigator)
    }
}

/** The Problems table over [problems] in a file of [fileSize] bytes; `null` when there are none. */
fun problemsTableTab(problems: List<BinaryProblem>, fileSize: Int, navigator: ProblemNavigator? = null): TableTabDescriptor? {
    if (problems.isEmpty()) return null
    return TableTabDescriptor(
        name = PROBLEMS_TAB_NAME,
        columns = listOf(
            col("Severity", ColumnWidth.MeasuredMin("Warning")) { index ->
                val severity = problems[index].severity
                // Sorting on the ordinal puts errors first when ascending.
                CellValue(severity.ordinal, severity.label)
            },
            col("Kind", ColumnWidth.MeasuredMin("Unknown opcode".repeat(2))) { index ->
                CellValue(problems[index].kind)
            },
            col("Location", ColumnWidth.MeasuredMin("func 9999")) { index ->
                CellValue(problems[index].location.orEmpty())
            },
            col("Offset", ColumnWidth.MeasuredMin("0x00000000")) { index ->
                val range = problems[index].byteRange
                if (range == null) CellValue(-1L, "") else CellValue(range.offset, hex(range.offset))
            },
            col("Size", ColumnWidth.MeasuredMin("0x0000")) { index ->
                val range = problems[index].byteRange
                if (range == null) CellValue(-1L, "") else CellValue(range.size, hex(range.size))
            },
            col("Message", ColumnWidth.Fill()) { index ->
                val message = problems[index].message
                CellValue(message, tooltip = message.takeIf { it.length > 40 })
            },
        ),
        rowCount = problems.size,
        rowSelectionProvider = { index -> problems.getOrNull(index)?.byteRange?.selectionIn(fileSize) },
        // Without a navigator the host's default applies: select the row's bytes.
        rowClickAction = navigator?.let { nav -> { index -> problems.getOrNull(index)?.let(nav.open) } },
        contextMenuItems = navigator?.menuItems.orEmpty().map { item ->
            RowContextMenuItemDescriptor(
                labelProvider = { item.label },
                isVisible = { index -> problems.getOrNull(index)?.let(item.isVisible) == true },
                action = { index -> problems.getOrNull(index)?.let(item.action) },
            )
        },
        predefinedFilters = listOfNotNull(
            PredefinedFilter("Errors and warnings") { index ->
                problems[index].severity != ProblemSeverity.Info
            }.takeIf { problems.any { it.severity == ProblemSeverity.Info } },
        ),
        defaultFilterShowAll = true,
    )
}
