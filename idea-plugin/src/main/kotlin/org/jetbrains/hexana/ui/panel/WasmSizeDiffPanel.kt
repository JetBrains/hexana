package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.NodeKind
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffNode
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffSummary
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/**
 * Size-impact comparison of two WASM modules (diff feature). Renders a precomputed
 * [model] — the parse + size analysis is done off the EDT by [WasmDiffEditorView] and
 * held there, so switching tabs does not recompute it. Shows a summary header (absolute
 * base/head module sizes + total Δ) plus a Δ-sorted table of changed sections, functions
 * and data segments. Identical entities are hidden, with a footer count.
 */
@Composable
internal fun WasmSizeDiffPanel(
    model: DiffLoadable<SizeReady>,
    leftName: String,
    rightName: String,
    onSelectFunction: (FunctionSelection?) -> Unit = {},
) {
    when (model) {
        DiffLoadable.Loading -> Centered("Comparing modules…")
        is DiffLoadable.Error -> Centered(model.message)
        is DiffLoadable.Ready -> DiffContent(model.value, leftName, rightName, onSelectFunction)
    }
}

/** The render-ready size diff: summary roll-up plus flattened rows (built off the EDT). */
internal class SizeReady(val summary: SizeDiffSummary, val rows: List<DiffRow>)

/** One flattened, displayable diff entry. */
internal data class DiffRow(
    val section: String,
    val name: String,
    val classification: DiffClass,
    val sizeA: Int?,
    val sizeB: Int?,
    val delta: Int,
    val confidence: Float?,
    val kind: NodeKind,
    val key: String,
) {
    /** Classification label with the matcher confidence appended when it is below 1.0. */
    fun changeLabel(): String {
        val base = classification.label()
        val conf = confidence?.takeIf { it < 1f }?.let { " ${(it * 100).toInt()}%" }.orEmpty()
        return base + conf
    }

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
    ready: SizeReady,
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
    val moved = summary.sections.sumOf { it.movedFns }
    val sectionLine = summary.sections
        .filter { it.delta != 0 }
        .joinToString("    ") { "${it.name} ${signedSize(it.delta)}" }

    // Absolute module sizes when available (base → head), with the total Δ; otherwise
    // just the Δ. headTotal - baseTotal == totalDelta, so the three reconcile.
    val baseTotal = summary.baseTotal
    val headTotal = summary.headTotal
    val sizeLine = if (baseTotal != null && headTotal != null) {
        "${formatSize(baseTotal)}  →  ${formatSize(headTotal)}    (${signedSize(summary.totalDelta)})"
    } else {
        "${signedSize(summary.totalDelta)} total"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text("$leftName  →  $rightName", style = TextStyle(fontFamily = FontFamily.Monospace))
        Text(
            sizeLine,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
        )
        if (sectionLine.isNotEmpty()) {
            Text(sectionLine, style = TextStyle(fontFamily = FontFamily.Monospace))
        }
        Text(
            "functions: +$added added, -$removed removed, ~$modified modified, $moved moved",
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun DiffTable(rows: List<DiffRow>, onSelectFunction: (FunctionSelection?) -> Unit) {
    val tm = rememberTextMeasurer()
    val sectionW = Modifier.width(tm.measureWidthMonospace("custom_seg_"))
    val nameW = Modifier.width(tm.measureWidthMonospace("w".repeat(44)))
    val changeW = Modifier.width(tm.measureWidthMonospace("Modified 100%_"))
    val sizeW = Modifier.width(tm.measureWidthMonospace("-9999.9 KB_"))
    val addedC = HexanaTheme.diffAddedColor
    val removedC = HexanaTheme.diffRemovedColor
    val modifiedC = HexanaTheme.diffModifiedColor
    val movedC = HexanaTheme.diffMovedColor

    val columns = listOf(
        TableColumn("Section", sectionW) { i -> TableCellValue(rows[i].section) },
        TableColumn("Name", nameW) { i -> TableCellValue(rows[i].name) },
        TableColumn("Change", changeW) { i -> TableCellValue(rows[i].changeLabel()) },
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
internal fun buildRows(root: SizeDiffNode): List<DiffRow> {
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
        confidence = confidence,
        kind = kind,
        key = key.value,
    )
