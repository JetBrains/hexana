package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.formatSize
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.analysis.MonosResult
import org.jetbrains.jewel.ui.component.Text

@Composable
fun WasmPanelMonos(provider: WasmBinaryProvider, state: HexViewStateHolder) {
    var result by remember { mutableStateOf<MonosResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(provider) {
        result = withContext(Dispatchers.IO) { provider.monosAnalysis }
        loading = false
    }

    if (loading) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text("Loading...", style = TextStyle(fontFamily = FontFamily.Monospace))
        }
        return
    }

    val monos = result
    if (monos == null || monos.groups.isEmpty()) {
        Box(modifier = Modifier.padding(8.dp)) {
            Text(
                if (monos == null) "No name section available" else "No monomorphizations detected",
                style = TextStyle(fontFamily = FontFamily.Monospace),
            )
        }
        return
    }

    val groups = monos.groups
    val importFuncCount = remember(provider) { provider.importFuncCount }
    val codeOffsets = remember(provider) { provider.codeOffsets }
    val textMeasurer = rememberTextMeasurer()
    val nameColumnWidth = textMeasurer.measureWidthMonospace(universalNameMeasure)
    val sizeColumnWidth = textMeasurer.measureWidthMonospace("00000.0 KB")
    val countColumnWidth = textMeasurer.measureWidthMonospace("Count")

    val columns = listOf(
        TableColumn("Base Name", modifier = Modifier.width(nameColumnWidth)) { index ->
            val g = groups[index]
            TableCellValue(g.baseName, tooltip = g.baseName.takeIf { it.length > universalNameMeasure.length })
        },
        TableColumn("Count", modifier = Modifier.width(countColumnWidth)) { index ->
            TableCellValue(groups[index].count)
        },
        TableColumn("Total Size", modifier = Modifier.width(sizeColumnWidth)) { index ->
            val g = groups[index]
            TableCellValue(g.totalSize, formatSize(g.totalSize))
        },
        TableColumn("Avg Size", modifier = Modifier.fillMaxWidth()) { index ->
            val g = groups[index]
            TableCellValue(g.avgSize, formatSize(g.avgSize))
        },
    )

    Table(columns, groups.size) { rowIndex ->
        val group = groups[rowIndex]
        val firstFuncIdx = group.funcIndices.firstOrNull() ?: return@Table
        val codeIdx = firstFuncIdx - importFuncCount
        val entry = codeOffsets.getOrNull(codeIdx) ?: return@Table
        state.scrollHexViewTo(entry.offset, entry.size)
    }
}