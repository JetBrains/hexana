package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.format.WasmSizeDiffOrchestrator
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.NodeKind
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffNode
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffSummary
import org.jetbrains.hexana.wasm.analysis.diff.summarize
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/**
 * Size-impact comparison of two WASM modules (diff feature, v1). Computes the
 * diff off the EDT — mirroring [PanelSize] — and shows a summary header plus a
 * Δ-sorted table of changed sections, functions and data segments. Identical
 * entities are hidden, with a footer count.
 */
@Composable
fun WasmSizeDiffPanel(
    leftBuffer: CommonByteBuffer,
    rightBuffer: CommonByteBuffer,
    leftName: String,
    rightName: String,
    onSelectFunction: (FunctionSelection?) -> Unit = {},
) {
    // The diff (parse + size analysis of both modules) is expensive and MUST NOT
    // run on the EDT. LaunchedEffect cancels this coroutine if the panel leaves
    // composition, so no manual scope management is needed.
    var state by remember(leftBuffer, rightBuffer) { mutableStateOf<DiffLoadState>(DiffLoadState.Loading) }
    LaunchedEffect(leftBuffer, rightBuffer) {
        state = withContext(Dispatchers.IO) {
            val diff = WasmSizeDiffOrchestrator.diff(
                WasmBinaryProvider(leftBuffer.duplicate()),
                WasmBinaryProvider(rightBuffer.duplicate()),
            )
            if (diff == null) {
                DiffLoadState.Error("Could not analyze one or both modules (not a core WASM module?).")
            } else {
                DiffLoadState.Ready(diff.summarize(), buildRows(diff))
            }
        }
    }

    when (val s = state) {
        DiffLoadState.Loading -> Centered("Comparing modules…")
        is DiffLoadState.Error -> Centered(s.message)
        is DiffLoadState.Ready -> DiffContent(s, leftName, rightName, onSelectFunction)
    }
}

private sealed class DiffLoadState {
    data object Loading : DiffLoadState()
    data class Error(val message: String) : DiffLoadState()
    data class Ready(val summary: SizeDiffSummary, val rows: List<DiffRow>) : DiffLoadState()
}

/** One flattened, displayable diff entry. */
private data class DiffRow(
    val section: String,
    val name: String,
    val classification: DiffClass,
    val sizeA: Int?,
    val sizeB: Int?,
    val delta: Int,
    val kind: NodeKind,
    val key: String,
) {
    /** Function rows map to a WAT-comparison selection; other kinds clear it. */
    fun toSelection(): FunctionSelection? {
        if (kind != NodeKind.FUNCTION) return null
        // Keys mirror WasmSizeDiffOrchestrator: semantic pairing fn:pair:<L>:<R>, one-sided
        // fn:rmL:<L> / fn:addR:<R>; fn:idx:/fn:name: only when the matcher could not run.
        return when {
            key.startsWith("fn:pair:") -> {
                val parts = key.removePrefix("fn:pair:").split(":")
                FunctionSelection(name, parts.getOrNull(0)?.toIntOrNull(), parts.getOrNull(1)?.toIntOrNull())
            }
            key.startsWith("fn:rmL:") -> FunctionSelection(name, key.removePrefix("fn:rmL:").toIntOrNull(), null)
            key.startsWith("fn:addR:") -> FunctionSelection(name, null, key.removePrefix("fn:addR:").toIntOrNull())
            key.startsWith("fn:idx:") -> {
                val n = key.removePrefix("fn:idx:").toIntOrNull()
                FunctionSelection(name, n, n)
            }
            else -> FunctionSelection(name, null, null) // fn:name: — detail resolves by name per side
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
private fun DiffContent(
    ready: DiffLoadState.Ready,
    leftName: String,
    rightName: String,
    onSelectFunction: (FunctionSelection?) -> Unit,
) {
    val changed = ready.rows.filter { it.classification != DiffClass.IDENTICAL }
    val hidden = ready.rows.size - changed.size

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        SummaryHeader(ready.summary, leftName, rightName)
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        if (changed.isEmpty()) {
            Centered("Modules are identical — no size changes.")
            return@Column
        }
        Box(modifier = Modifier.weight(1f)) {
            DiffTable(changed, onSelectFunction)
        }
        Text(
            "$hidden identical ${if (hidden == 1) "entity" else "entities"} hidden",
            style = TextStyle(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SummaryHeader(summary: SizeDiffSummary, leftName: String, rightName: String) {
    val added = summary.sections.sumOf { it.addedFns }
    val removed = summary.sections.sumOf { it.removedFns }
    val modified = summary.sections.sumOf { it.modifiedFns }
    val sectionLine = summary.sections
        .filter { it.delta != 0 }
        .joinToString("    ") { "${it.name} ${signedSize(it.delta)}" }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text("$leftName  →  $rightName", style = TextStyle(fontFamily = FontFamily.Monospace))
        Text(
            "${signedSize(summary.totalDelta)} total",
            style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        )
        if (sectionLine.isNotEmpty()) {
            Text(sectionLine, style = TextStyle(fontFamily = FontFamily.Monospace))
        }
        Text(
            "functions: +$added added, -$removed removed, ~$modified modified",
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun DiffTable(rows: List<DiffRow>, onSelectFunction: (FunctionSelection?) -> Unit) {
    val tm = rememberTextMeasurer()
    val sectionW = Modifier.width(tm.measureWidthMonospace("custom_seg_"))
    val nameW = Modifier.width(tm.measureWidthMonospace("w".repeat(44)))
    val changeW = Modifier.width(tm.measureWidthMonospace("Modified__"))
    val sizeW = Modifier.width(tm.measureWidthMonospace("-9999.9 KB_"))
    val addedC = HexanaTheme.diffAddedColor
    val removedC = HexanaTheme.diffRemovedColor
    val modifiedC = HexanaTheme.diffModifiedColor
    val movedC = HexanaTheme.diffMovedColor

    val columns = listOf(
        TableColumn("Section", sectionW) { i -> TableCellValue(rows[i].section) },
        TableColumn("Name", nameW) { i -> TableCellValue(rows[i].name) },
        TableColumn("Change", changeW) { i -> TableCellValue(rows[i].classification.label()) },
        TableColumn("Base", sizeW) { i -> TableCellValue(rows[i].sizeA?.let { formatSize(it) } ?: "—") },
        TableColumn("Head", sizeW) { i -> TableCellValue(rows[i].sizeB?.let { formatSize(it) } ?: "—") },
        TableColumn("Δ", sizeW) { i -> TableCellValue(rows[i].delta, signedSize(rows[i].delta)) },
    )
    Table(
        columns = columns,
        dataCount = rows.size,
        defaultSortColumn = 5,
        defaultSortAscending = false,
        onRow = { id -> onSelectFunction(rows[id].toSelection()) },
        rowColor = { id ->
            when (rows[id].classification) {
                DiffClass.ADDED -> addedC
                DiffClass.REMOVED -> removedC
                DiffClass.MODIFIED -> modifiedC
                DiffClass.MOVED -> movedC
                DiffClass.IDENTICAL -> null
            }
        },
    )
}

private fun DiffClass.label(): String = when (this) {
    DiffClass.ADDED -> "Added"
    DiffClass.REMOVED -> "Removed"
    DiffClass.MODIFIED -> "Modified"
    DiffClass.MOVED -> "Moved"
    DiffClass.IDENTICAL -> "Identical"
}

private fun signedSize(delta: Int): String = when {
    delta > 0 -> "+${formatSize(delta)}"
    delta < 0 -> "-${formatSize(-delta)}"
    else -> "0 B"
}

/**
 * Flattens the diff tree into displayable rows. Sections that decompose into
 * functions/data segments contribute one row per child; sections that don't
 * (imports, exports, custom) contribute a single section-level row. Includes
 * identical rows so the caller can show a hidden-count footer.
 */
private fun buildRows(root: SizeDiffNode): List<DiffRow> {
    val rows = ArrayList<DiffRow>()
    for (section in root.children) {
        val sectionName = section.displayName
        val leaves = section.flatten().filter {
            it.kind == NodeKind.FUNCTION || it.kind == NodeKind.DATA_SEGMENT
        }
        if (leaves.isNotEmpty()) {
            leaves.forEach { rows.add(it.toRow(sectionName)) }
        } else {
            rows.add(section.toRow(sectionName))
        }
    }
    return rows
}

private fun SizeDiffNode.toRow(section: String): DiffRow =
    DiffRow(
        section = section,
        name = displayName,
        classification = classification,
        sizeA = sizeA,
        sizeB = sizeB,
        delta = delta,
        kind = kind,
        key = key.value,
    )
