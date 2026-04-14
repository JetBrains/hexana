package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexHighlightRange
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.ui.HexanaTheme
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageReason
import org.jetbrains.hexana.wasm.analysis.garbage.GarbageResult
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text

private enum class GarbageViewMode { Functions, Types, Data }

@Composable
fun WasmPanelGarbage(
    provider: WasmBinaryProvider,
    state: HexViewStateHolder,
    onShowInDominators: ((funcIndex: Int) -> Unit)? = null,
    onShowCallers: ((funcIndex: Int) -> Unit)? = null,
) {
    var result by remember { mutableStateOf<GarbageResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(provider) {
        result = withContext(Dispatchers.IO) { provider.garbageAnalysis }
        loading = false
    }

    if (loading) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text("Loading...", style = TextStyle(fontFamily = FontFamily.Monospace))
        }
        return
    }

    val garbage = result
    if (garbage == null) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text("Analysis unavailable", style = TextStyle(fontFamily = FontFamily.Monospace))
        }
        return
    }

    var viewMode by remember { mutableStateOf(GarbageViewMode.Functions) }

    // Hex overlay toggle
    var overlayEnabled by remember { mutableStateOf(false) }
    val highlightColor = HexanaTheme.garbageHighlightColor
    val importFuncCount = remember(provider) { provider.importFuncCount }
    val codeOffsets = remember(provider) { provider.codeOffsets }

    LaunchedEffect(overlayEnabled, result) {
        if (overlayEnabled && garbage.entries.isNotEmpty()) {
            val ranges = garbage.entries
                .filter { it.size > 0 }
                .mapNotNull { entry ->
                    val codeIdx = entry.funcIndex - importFuncCount
                    val offset = codeOffsets.getOrNull(codeIdx)
                    if (offset != null) {
                        HexHighlightRange(offset.offset.toLong(), offset.size, highlightColor)
                    } else null
                }
            state.setHighlightRanges(ranges)
        } else {
            state.clearHighlightRanges()
        }
    }

    DisposableEffect(Unit) {
        onDispose { state.clearHighlightRanges() }
    }

    Column {
        // Mode toggle row + overlay button
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (mode in GarbageViewMode.entries) {
                val count = when (mode) {
                    GarbageViewMode.Functions -> garbage.entries.size
                    GarbageViewMode.Types -> garbage.typeEntries.size
                    GarbageViewMode.Data -> garbage.dataSegmentEntries.size
                }
                Text(
                    "${mode.name} ($count)",
                    modifier = Modifier
                        .clickable { viewMode = mode }
                        .background(if (viewMode == mode) Color.Gray.copy(alpha = 0.3f) else Color.Transparent)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        color = if (viewMode == mode) Color.White else Color.Gray,
                    ),
                )
            }

            Box(modifier = Modifier.weight(1f))

            if (viewMode == GarbageViewMode.Functions) {
                IconButton(onClick = { overlayEnabled = !overlayEnabled }) {
                    Text(
                        if (overlayEnabled) "\u2B1B" else "\u2B1C",
                        style = TextStyle(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }

        when (viewMode) {
            GarbageViewMode.Functions -> GarbageFunctionsTable(
                garbage, importFuncCount, codeOffsets, state, onShowInDominators, onShowCallers,
            )
            GarbageViewMode.Types -> GarbageTypesTable(garbage)
            GarbageViewMode.Data -> GarbageDataTable(garbage)
        }
    }
}

@Composable
private fun GarbageFunctionsTable(
    garbage: GarbageResult,
    importFuncCount: Int,
    codeOffsets: List<org.jetbrains.hexana.wasm.sections.CodeEntryOffset>,
    state: HexViewStateHolder,
    onShowInDominators: ((funcIndex: Int) -> Unit)?,
    onShowCallers: ((funcIndex: Int) -> Unit)?,
) {
    val entries = garbage.entries
    if (entries.isEmpty()) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text("No unreachable functions detected", style = TextStyle(fontFamily = FontFamily.Monospace))
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val nameColumnWidth = textMeasurer.measureWidthMonospace(universalNameMeasure)
    val indexColumnWidth = textMeasurer.measureWidthMonospace("Index")
    val sizeColumnWidth = textMeasurer.measureWidthMonospace("00000.0 KB")
    val moduleColumnWidth = textMeasurer.measureWidthMonospace("component:env")

    val pct = if (garbage.codeSectionTotalBytes > 0) {
        "%.1f%%".format(garbage.totalWastedBytes * 100.0 / garbage.codeSectionTotalBytes)
    } else "\u2014"

    Column {
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                "${entries.size} unreachable, ${formatSize(garbage.totalWastedBytes)} wasted ($pct of code)",
                style = TextStyle(fontFamily = FontFamily.Monospace),
            )
        }

        val columns = listOf(
            TableColumn("Index", modifier = Modifier.width(indexColumnWidth)) { index ->
                TableCellValue(entries[index].funcIndex)
            },
            TableColumn("Name", modifier = Modifier.width(nameColumnWidth)) { index ->
                val name = entries[index].name
                TableCellValue(name, tooltip = name.takeIf { it.length > universalNameMeasure.length })
            },
            TableColumn("Size", modifier = Modifier.width(sizeColumnWidth)) { index ->
                val e = entries[index]
                if (e.size > 0) TableCellValue(e.size, formatSize(e.size))
                else TableCellValue(0, "\u2014")
            },
            TableColumn("Module", modifier = Modifier.width(moduleColumnWidth)) { index ->
                TableCellValue(entries[index].module ?: "", representation = entries[index].module ?: "")
            },
            TableColumn("Reason", modifier = Modifier.fillMaxWidth()) { index ->
                val reason = when (entries[index].reason) {
                    GarbageReason.NO_CALLERS -> "No callers"
                    GarbageReason.CALLERS_UNREACHABLE -> "Callers unreachable"
                }
                TableCellValue(reason)
            },
        )

        Table(columns, entries.size, onRowContextMenu = { rowIndex ->
            buildList {
                if (onShowInDominators != null) {
                    add(ContextMenuItem("Show in Dominators") {
                        onShowInDominators(entries[rowIndex].funcIndex)
                    })
                }
                if (onShowCallers != null && entries[rowIndex].reason != GarbageReason.NO_CALLERS) {
                    add(ContextMenuItem("Show Callers") {
                        onShowCallers(entries[rowIndex].funcIndex)
                    })
                }
            }
        }) { rowIndex ->
            val entry = entries[rowIndex]
            if (entry.size > 0) {
                val codeIdx = entry.funcIndex - importFuncCount
                val offset = codeOffsets.getOrNull(codeIdx) ?: return@Table
                state.scrollHexViewTo(offset.offset, offset.size)
            }
        }
    }
}

@Composable
private fun GarbageTypesTable(garbage: GarbageResult) {
    val entries = garbage.typeEntries
    if (entries.isEmpty()) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text("No unreachable types detected", style = TextStyle(fontFamily = FontFamily.Monospace))
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val indexColumnWidth = textMeasurer.measureWidthMonospace("Index")
    val sizeColumnWidth = textMeasurer.measureWidthMonospace("00000 B")

    Column {
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                "${entries.size} unreachable types, ${formatSize(garbage.typeWastedBytes)} wasted",
                style = TextStyle(fontFamily = FontFamily.Monospace),
            )
        }

        val columns = listOf(
            TableColumn("Index", modifier = Modifier.width(indexColumnWidth)) { index ->
                TableCellValue(entries[index].typeIndex)
            },
            TableColumn("Size", modifier = Modifier.width(sizeColumnWidth)) { index ->
                TableCellValue(entries[index].size, "${entries[index].size} B")
            },
            TableColumn("Signature", modifier = Modifier.fillMaxWidth()) { index ->
                val desc = entries[index].description
                TableCellValue(desc, tooltip = desc)
            },
        )

        Table(columns, entries.size) {}
    }
}

@Composable
private fun GarbageDataTable(garbage: GarbageResult) {
    val entries = garbage.dataSegmentEntries
    if (entries.isEmpty()) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text("No data segments", style = TextStyle(fontFamily = FontFamily.Monospace))
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val indexColumnWidth = textMeasurer.measureWidthMonospace("Index")
    val sizeColumnWidth = textMeasurer.measureWidthMonospace("00000.0 KB")
    val recordColumnWidth = textMeasurer.measureWidthMonospace("00000.0 KB")

    Column {
        Box(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                "${entries.size} data segments, ${formatSize(garbage.dataSegmentWastedBytes)} potential garbage",
                style = TextStyle(fontFamily = FontFamily.Monospace),
            )
        }

        val columns = listOf(
            TableColumn("Index", modifier = Modifier.width(indexColumnWidth)) { index ->
                TableCellValue(entries[index].index)
            },
            TableColumn("Data Size", modifier = Modifier.width(sizeColumnWidth)) { index ->
                TableCellValue(entries[index].dataSize, formatSize(entries[index].dataSize))
            },
            TableColumn("Record Size", modifier = Modifier.fillMaxWidth()) { index ->
                TableCellValue(entries[index].recordSize, formatSize(entries[index].recordSize))
            },
        )

        Table(columns, entries.size) {}
    }
}