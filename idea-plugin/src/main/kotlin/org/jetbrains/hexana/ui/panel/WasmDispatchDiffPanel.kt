package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import org.jetbrains.hexana.providers.format.DispatchSignatureDelta
import org.jetbrains.hexana.providers.format.WasmDispatchSurfaceDiffResult
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.jewel.ui.component.Text

/**
 * Dispatch tab of the WASM diff: per canonical `call_indirect` signature,
 * which statically known candidates entered or left the table between the two
 * builds. Candidate identity is the content-hash function matcher (an entered
 * "existing" function was already present in the baseline but newly added to
 * the table — the quiet mechanism that pins code wasm-opt cannot remove).
 * Renders a precomputed [model]; clicking a row opens the candidate's WAT in
 * the detail split below.
 */
@Composable
internal fun WasmDispatchDiffPanel(
    model: DiffLoadable<WasmDispatchSurfaceDiffResult>,
    onSelectFunction: (FunctionSelection?) -> Unit = {},
) {
    when (model) {
        DiffLoadable.Loading -> DispatchCentered("Comparing dispatch surfaces…")
        is DiffLoadable.Error -> DispatchCentered(model.message)
        is DiffLoadable.Ready -> DispatchContent(model.value, onSelectFunction)
    }
}

@Composable
private fun DispatchCentered(message: String) {
    Box(modifier = Modifier.padding(8.dp)) {
        Text(message, style = TextStyle(fontFamily = FontFamily.Monospace))
    }
}

/** One table row: a single candidate that entered or left one signature's table. */
private data class DispatchRow(
    val delta: DispatchSignatureDelta,
    val name: String,
    val entered: Boolean,
    val functionAddedOrRemoved: Boolean,
    val leftCodeIdx: Int?,
    val rightCodeIdx: Int?,
)

@Composable
private fun DispatchContent(result: WasmDispatchSurfaceDiffResult, onSelectFunction: (FunctionSelection?) -> Unit) {
    val rows = remember(result) {
        result.deltas.flatMap { delta ->
            delta.entered.map { DispatchRow(delta, it.name, true, it.functionAddedOrRemoved, it.leftCodeIdx, it.rightCodeIdx) } +
                delta.left.map { DispatchRow(delta, it.name, false, it.functionAddedOrRemoved, it.leftCodeIdx, it.rightCodeIdx) }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        if (rows.isEmpty()) {
            DispatchCentered("No dispatch-surface changes — every signature keeps its candidate set.")
            return@Column
        }
        Box(modifier = Modifier.weight(1f)) {
            DispatchTable(rows, onSelectFunction)
        }
        Text(
            "${result.deltas.size} signatures changed · ${result.unchangedSignatures} unchanged",
            style = TextStyle(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun DispatchTable(rows: List<DispatchRow>, onSelectFunction: (FunctionSelection?) -> Unit) {
    val tm = rememberTextMeasurer()
    val sigW = Modifier.width(tm.measureWidthMonospace("w".repeat(36)))
    val countW = Modifier.width(tm.measureWidthMonospace("Candidates____"))
    val changeW = Modifier.width(tm.measureWidthMonospace("Entered__"))
    val funcW = Modifier.width(tm.measureWidthMonospace("existing____"))
    val nameW = Modifier.width(tm.measureWidthMonospace("w".repeat(48)))
    val addedC = HexanaTheme.diffAddedColor
    val removedC = HexanaTheme.diffRemovedColor

    val columns = listOf(
        TableColumn("Signature", sigW) { i -> TableCellValue(rows[i].delta.signatureText) },
        TableColumn("Candidates", countW) { i ->
            TableCellValue("${rows[i].delta.baselineCount} → ${rows[i].delta.currentCount}")
        },
        TableColumn("Change", changeW) { i -> TableCellValue(if (rows[i].entered) "Entered" else "Left") },
        TableColumn("Function", funcW) { i ->
            val r = rows[i]
            TableCellValue(
                when {
                    r.entered && r.functionAddedOrRemoved -> "new"
                    !r.entered && r.functionAddedOrRemoved -> "removed"
                    else -> "existing"
                },
            )
        },
        TableColumn("Candidate", nameW) { i -> TableCellValue(rows[i].name) },
    )
    Table(
        columns = columns,
        dataCount = rows.size,
        defaultSortColumn = 0,
        defaultSortAscending = true,
        onRow = { id ->
            val r = rows[id]
            onSelectFunction(FunctionSelection(r.name, r.leftCodeIdx, r.rightCodeIdx))
        },
        rowColor = { id -> if (rows[id].entered) addedC else removedC },
    )
}
