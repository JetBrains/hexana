package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.hexana.providers.format.ComponentDiffResult
import org.jetbrains.hexana.providers.format.SubmoduleKind
import org.jetbrains.hexana.providers.format.SubmodulePairing
import org.jetbrains.hexana.providers.format.SubmoduleStatus
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/**
 * Top-level view for a Component Model diff: a table of the two components' embedded
 * submodules (core modules + the nested component, if any), each flagged
 * Identical / Modified / Added / Removed.
 *
 * Clicking a row asks the host to open that submodule pair in its own tab via
 * [onOpenSubmodule] — a paired core module reuses the existing size/entity/WAT diff,
 * a paired nested component recurses into another component diff, and a one-sided
 * submodule opens that single side's structure. The expensive per-submodule comparison
 * is therefore deferred to the click.
 *
 * The submodule diff is NOT computed here: it is computed once, off the EDT, by
 * [org.jetbrains.hexana.HexanaFileEditor] and supplied as [modelFlow] — editor-scoped
 * state that survives recomposition / composition teardown for the lifetime of the tab.
 * Hosted for a [org.jetbrains.hexana.HexanaComponentDiffVirtualFile].
 */
@Composable
internal fun WasmComponentDiffEditorView(
    leftName: String,
    rightName: String,
    modelFlow: StateFlow<DiffLoadable<ComponentDiffResult>>,
    onOpenSubmodule: (SubmodulePairing) -> Unit,
) {
    val model by modelFlow.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Text("$leftName  ↔  $rightName", style = TextStyle(fontFamily = FontFamily.Monospace))
        when (val m = model) {
            DiffLoadable.Loading -> Centered("Comparing components…")
            is DiffLoadable.Error -> Centered(m.message)
            is DiffLoadable.Ready -> {
                val pairings = m.value.pairings
                if (pairings.isEmpty()) {
                    Centered("No embedded submodules to compare.")
                    return@Column
                }
                Text(
                    submoduleSummary(pairings.size, m.value.differingCount),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
                Box(modifier = Modifier.weight(1f)) {
                    SubmoduleTable(pairings, onOpenSubmodule)
                }
                Text(
                    "Click a submodule to compare it in a separate tab.",
                    style = TextStyle(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Centered(message: String) {
    Box(modifier = Modifier.padding(8.dp)) {
        Text(message, style = TextStyle(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun SubmoduleTable(pairings: List<SubmodulePairing>, onOpen: (SubmodulePairing) -> Unit) {
    val tm = rememberTextMeasurer()
    val labelW = Modifier.width(tm.measureWidthMonospace("component[000]_"))
    val kindW = Modifier.width(tm.measureWidthMonospace("component_"))
    val sizeW = Modifier.width(tm.measureWidthMonospace("-9999.9 KB_"))
    val statusW = Modifier.width(tm.measureWidthMonospace("Identical_"))
    val addedC = HexanaTheme.diffAddedColor
    val removedC = HexanaTheme.diffRemovedColor
    val modifiedC = HexanaTheme.diffModifiedColor

    val columns = listOf(
        TableColumn("Submodule", labelW) { i -> TableCellValue(rowLabel(pairings[i])) },
        TableColumn("Kind", kindW) { i -> TableCellValue(kindLabel(pairings[i].kind)) },
        TableColumn("Base", sizeW) { i ->
            TableCellValue(pairings[i].left?.contentSize?.let { formatSize(it) } ?: "—")
        },
        TableColumn("Head", sizeW) { i ->
            TableCellValue(pairings[i].right?.contentSize?.let { formatSize(it) } ?: "—")
        },
        TableColumn("Status", statusW) { i -> TableCellValue(statusLabel(pairings[i].status)) },
    )
    Table(
        columns = columns,
        dataCount = pairings.size,
        defaultSortColumn = 4,
        defaultSortAscending = true,
        onRow = { id -> onOpen(pairings[id]) },
        rowColor = { id ->
            when (pairings[id].status) {
                SubmoduleStatus.ADDED -> addedC
                SubmoduleStatus.REMOVED -> removedC
                SubmoduleStatus.MODIFIED -> modifiedC
                SubmoduleStatus.IDENTICAL -> null
            }
        },
    )
}

/** Both sides of a pair share a label; for a one-sided row, use whichever side is present. */
private fun rowLabel(p: SubmodulePairing): String = (p.left ?: p.right)?.label ?: ""

private fun kindLabel(kind: SubmoduleKind): String = when (kind) {
    SubmoduleKind.CORE_MODULE -> "module"
    SubmoduleKind.COMPONENT -> "component"
}

private fun statusLabel(status: SubmoduleStatus): String = when (status) {
    SubmoduleStatus.IDENTICAL -> "Identical"
    SubmoduleStatus.MODIFIED -> "Modified"
    SubmoduleStatus.ADDED -> "Added"
    SubmoduleStatus.REMOVED -> "Removed"
}

private fun submoduleSummary(total: Int, differing: Int): String {
    val unit = if (total == 1) "submodule" else "submodules"
    return if (differing == 0) {
        "$total $unit — all identical"
    } else {
        "$total $unit, $differing differing"
    }
}
