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
import org.jetbrains.hexana.providers.format.ImportDelta
import org.jetbrains.hexana.providers.format.WasmEntityDiffResult
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.analysis.diff.DiffClass
import org.jetbrains.hexana.wasm.analysis.diff.EntityKind
import org.jetbrains.hexana.wasm.analysis.diff.EntityDiffEntry
import org.jetbrains.jewel.ui.Orientation
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Text

/**
 * Entities tab of the WASM diff: classifies imports/exports/functions/globals/
 * tables/memories/types/data/element segments as added/removed/modified, with a
 * supply-chain new-imports banner. Renders a precomputed [model] — the diff is run
 * off the EDT by [WasmDiffEditorView] and held there, so switching tabs does not
 * recompute it.
 */
@Composable
internal fun WasmEntityDiffPanel(
    model: DiffLoadable<WasmEntityDiffResult>,
    onSelectFunction: (FunctionSelection?) -> Unit = {},
) {
    when (model) {
        DiffLoadable.Loading -> Centered("Comparing entities…")
        is DiffLoadable.Error -> Centered(model.message)
        is DiffLoadable.Ready -> EntityContent(model.value, onSelectFunction)
    }
}

@Composable
private fun Centered(message: String) {
    Box(modifier = Modifier.padding(8.dp)) {
        Text(message, style = TextStyle(fontFamily = FontFamily.Monospace))
    }
}

@Composable
private fun EntityContent(result: WasmEntityDiffResult, onSelectFunction: (FunctionSelection?) -> Unit) {
    val changed = result.entities.entries.filter { it.classification != DiffClass.IDENTICAL }
    val hidden = result.entities.entries.size - changed.size

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        NewImportsBanner(result.newImports, result.removedImports)
        Divider(orientation = Orientation.Horizontal, modifier = Modifier.fillMaxWidth())
        if (changed.isEmpty()) {
            Centered("No entity changes — modules match at the entity level.")
            return@Column
        }
        Box(modifier = Modifier.weight(1f)) {
            EntityTable(changed, onSelectFunction)
        }
        Text(
            "$hidden identical ${if (hidden == 1) "entity" else "entities"} hidden",
            style = TextStyle(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun NewImportsBanner(newImports: List<ImportDelta>, removedImports: List<ImportDelta>) {
    if (newImports.isEmpty() && removedImports.isEmpty()) return
    val hostCount = newImports.count { it.isHost }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (newImports.isNotEmpty()) {
            Text(
                "New imports: ${newImports.size} (${hostCount} from host namespaces)",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            )
            // Host imports first — the supply-chain signal that matters most.
            newImports.sortedByDescending { it.isHost }.forEach { imp ->
                val tag = if (imp.isHost) " [host]" else ""
                Text(
                    "  + ${imp.module}.${imp.field}  (${imp.kind})$tag",
                    style = TextStyle(fontFamily = FontFamily.Monospace),
                )
            }
        }
        if (removedImports.isNotEmpty()) {
            Text(
                "Removed imports: ${removedImports.size}",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold),
            )
            removedImports.forEach { imp ->
                Text(
                    "  - ${imp.module}.${imp.field}  (${imp.kind})",
                    style = TextStyle(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun EntityTable(entries: List<EntityDiffEntry>, onSelectFunction: (FunctionSelection?) -> Unit) {
    val tm = rememberTextMeasurer()
    val kindW = Modifier.width(tm.measureWidthMonospace("ELEMENT__"))
    val nameW = Modifier.width(tm.measureWidthMonospace("w".repeat(40)))
    val changeW = Modifier.width(tm.measureWidthMonospace("Modified__"))
    val detailW = Modifier.width(tm.measureWidthMonospace("w".repeat(48)))
    val addedC = HexanaTheme.diffAddedColor
    val removedC = HexanaTheme.diffRemovedColor
    val modifiedC = HexanaTheme.diffModifiedColor
    val movedC = HexanaTheme.diffMovedColor

    val columns = listOf(
        TableColumn("Kind", kindW) { i -> TableCellValue(entries[i].kind.name) },
        TableColumn("Name", nameW) { i -> TableCellValue(entries[i].name ?: entries[i].key.value) },
        TableColumn("Change", changeW) { i -> TableCellValue(entries[i].classification.label()) },
        TableColumn("Detail", detailW) { i -> TableCellValue(entries[i].detailString()) },
    )
    Table(
        columns = columns,
        dataCount = entries.size,
        defaultSortColumn = 0,
        defaultSortAscending = true,
        onRow = { id -> onSelectFunction(entries[id].toFunctionSelection()) },
        rowColor = { id ->
            when (entries[id].classification) {
                DiffClass.ADDED -> addedC
                DiffClass.REMOVED -> removedC
                DiffClass.MODIFIED -> modifiedC
                DiffClass.MOVED -> movedC
                DiffClass.IDENTICAL -> null
            }
        },
    )
}

/** Function entries carry their code-index pair in the key (`fn:L->R` / `fn:rm:L` / `fn:add:R`). */
private fun EntityDiffEntry.toFunctionSelection(): FunctionSelection? {
    if (kind != EntityKind.FUNCTION) return null
    val v = key.value
    val displayName = name ?: v
    return when {
        v.startsWith("fn:rm:") -> FunctionSelection(displayName, v.removePrefix("fn:rm:").toIntOrNull(), null)
        v.startsWith("fn:add:") -> FunctionSelection(displayName, null, v.removePrefix("fn:add:").toIntOrNull())
        v.startsWith("fn:") && "->" in v -> {
            val parts = v.removePrefix("fn:").split("->")
            FunctionSelection(displayName, parts.getOrNull(0)?.toIntOrNull(), parts.getOrNull(1)?.toIntOrNull())
        }
        else -> FunctionSelection(displayName, null, null)
    }
}

private fun DiffClass.label(): String = when (this) {
    DiffClass.ADDED -> "Added"
    DiffClass.REMOVED -> "Removed"
    DiffClass.MODIFIED -> "Modified"
    DiffClass.MOVED -> "Moved"
    DiffClass.IDENTICAL -> "Identical"
}

private fun EntityDiffEntry.detailString(): String = when (classification) {
    DiffClass.MODIFIED -> "${detailA.orEmpty()} → ${detailB.orEmpty()}"
    DiffClass.MOVED -> {
        val shift = indexDelta?.let { " (index ${if (it > 0) "+$it" else "$it"})" }.orEmpty()
        "moved$shift: ${detailB ?: detailA ?: ""}"
    }
    else -> detailB ?: detailA ?: ""
}
