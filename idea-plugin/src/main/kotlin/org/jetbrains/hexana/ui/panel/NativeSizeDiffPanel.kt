package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.SizeDiffNode
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/** The render-ready native size diff: summary + flattened rows (built off the EDT). */
internal class NativeSizeReady(
    val baseTotal: Int?,
    val headTotal: Int?,
    val totalDelta: Int,
    val rows: List<NativeDiffRow>,
)

/** One flattened, displayable diff entry for a native binary section. */
internal data class NativeDiffRow(
    val name: String,
    val classification: DiffClass,
    val sizeA: Int?,
    val sizeB: Int?,
    val delta: Int,
    val children: List<NativeDiffRow> = emptyList(),
)

/**
 * Size-impact comparison of two native binaries (diff feature). Renders a precomputed
 * [model] — the parse + size analysis is done off the EDT by [NativeDiffEditorView] and
 * held there, so switching tabs does not recompute it. Shows a summary header (absolute
 * base/head sizes + total Δ) plus a Δ-sorted table of changed sections. Identical
 * entities are hidden, with a footer count.
 */
@Composable
internal fun NativeSizeDiffPanel(
    model: DiffLoadable<NativeSizeReady>,
    leftName: String,
    rightName: String,
) {
    when (model) {
        DiffLoadable.Loading -> NativeCentered("Comparing binaries…")
        is DiffLoadable.Error -> NativeCentered(model.message)
        is DiffLoadable.Ready -> NativeDiffContent(model.value, leftName, rightName)
    }
}

@Composable
private fun NativeCentered(message: String) {
    Box(modifier = Modifier.padding(8.dp)) {
        Text(message, style = TextStyle(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun NativeDiffContent(
    ready: NativeSizeReady,
    leftName: String,
    rightName: String,
) {
    val changed = ready.rows.filter { it.classification != DiffClass.IDENTICAL }
    val hidden = ready.rows.size - changed.size

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        NativeSummaryHeader(ready, leftName, rightName)
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        if (changed.isEmpty()) {
            NativeCentered("Binaries are identical — no size changes.")
            return@Column
        }
        Box(modifier = Modifier.weight(1f)) {
            NativeDiffTable(changed)
        }
        Text(
            "$hidden identical ${if (hidden == 1) "section" else "sections"} hidden",
            style = TextStyle(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun NativeSummaryHeader(ready: NativeSizeReady, leftName: String, rightName: String) {
    val baseTotal = ready.baseTotal
    val headTotal = ready.headTotal
    val sizeLine = if (baseTotal != null && headTotal != null) {
        "${formatSize(baseTotal)}  →  ${formatSize(headTotal)}    (${nativeSignedSize(ready.totalDelta)})"
    } else {
        "${nativeSignedSize(ready.totalDelta)} total"
    }

    val added = ready.rows.count { it.classification == DiffClass.ADDED }
    val removed = ready.rows.count { it.classification == DiffClass.REMOVED }
    val modified = ready.rows.count { it.classification == DiffClass.MODIFIED }

    val sectionLine = ready.rows
        .filter { it.delta != 0 }
        .joinToString("    ") { "${it.name} ${nativeSignedSize(it.delta)}" }

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
            "sections: +$added added, -$removed removed, ~$modified modified",
            style = TextStyle(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun NativeDiffTable(rows: List<NativeDiffRow>) {
    val tm = rememberTextMeasurer()
    val nameW = Modifier.width(tm.measureWidthMonospace("w".repeat(32)))
    val changeW = Modifier.width(tm.measureWidthMonospace("Modified__"))
    val sizeW = Modifier.width(tm.measureWidthMonospace("-9999.9 KB_"))
    val addedC = HexanaTheme.diffAddedColor
    val removedC = HexanaTheme.diffRemovedColor
    val modifiedC = HexanaTheme.diffModifiedColor

    val columns = listOf(
        TableColumn("Name", nameW) { i -> TableCellValue(rows[i].name) },
        TableColumn("Change", changeW) { i -> TableCellValue(nativeDiffLabel(rows[i].classification)) },
        TableColumn("Base", sizeW) { i -> TableCellValue(rows[i].sizeA?.let { formatSize(it) } ?: "—") },
        TableColumn("Head", sizeW) { i -> TableCellValue(rows[i].sizeB?.let { formatSize(it) } ?: "—") },
        TableColumn("Δ", sizeW) { i -> TableCellValue(rows[i].delta, nativeSignedSize(rows[i].delta)) },
    )
    Table(
        columns = columns,
        dataCount = rows.size,
        defaultSortColumn = 4,
        defaultSortAscending = false,
        showIdColumn = false,
        rowColor = { id ->
            when (rows[id].classification) {
                DiffClass.ADDED -> addedC
                DiffClass.REMOVED -> removedC
                DiffClass.MODIFIED -> modifiedC
                DiffClass.MOVED -> modifiedC
                DiffClass.IDENTICAL -> null
            }
        },
    )
}

private fun nativeDiffLabel(cls: DiffClass): String = when (cls) {
    DiffClass.ADDED -> "Added"
    DiffClass.REMOVED -> "Removed"
    DiffClass.MODIFIED -> "Modified"
    DiffClass.MOVED -> "Moved"
    DiffClass.IDENTICAL -> "Identical"
}

private fun nativeSignedSize(delta: Int): String = when {
    delta > 0 -> "+${formatSize(delta)}"
    delta < 0 -> "-${formatSize(-delta)}"
    else -> "0 B"
}

/**
 * Flattens the diff tree into displayable rows. Each top-level child (section/segment)
 * becomes a row; if a section has children (e.g. Mach-O segments with sections), those
 * are flattened as additional rows. Includes identical rows so the caller can show a
 * hidden-count footer.
 */
internal fun buildNativeRows(root: SizeDiffNode): List<NativeDiffRow> {
    val rows = ArrayList<NativeDiffRow>()
    for (child in root.children) {
        if (child.children.isNotEmpty()) {
            // Parent node (e.g. Mach-O segment) — emit its children as individual rows
            for (sub in child.children) {
                rows.add(sub.toNativeRow(prefix = "${child.displayName}/"))
            }
            // Also emit the parent itself as a summary row
            rows.add(child.toNativeRow())
        } else {
            rows.add(child.toNativeRow())
        }
    }
    return rows
}

private fun SizeDiffNode.toNativeRow(prefix: String = ""): NativeDiffRow =
    NativeDiffRow(
        name = prefix + displayName,
        classification = classification,
        sizeA = sizeA,
        sizeB = sizeB,
        delta = delta,
    )
