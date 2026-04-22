package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.ContextMenuItem
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.getDescription
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace

@Composable
fun WasmPanelFunctions(
    wasmBinaryProvider: WasmBinaryProvider,
    state: HexViewStateHolder,
    onShowPaths: ((funcIndex: Int) -> Unit)? = null,
    unreachableFuncIndices: Set<Int> = emptySet(),
) {
    val functions = remember(wasmBinaryProvider) { wasmBinaryProvider.functions }
    val nameSection = remember(wasmBinaryProvider) { wasmBinaryProvider.nameSection }

    val functionNamesMap = nameSection?.functionSubsection?.namemap
    val typeNames = nameSection?.typesSection?.namemap

    if (functions.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()

    val columns: List<TableColumn> = buildList {
        if (functionNamesMap != null) {
            val nameColumnWidth = textMeasurer.measureWidthMonospace(universalNameMeasure)
            add(TableColumn("Name", modifier = Modifier.width(nameColumnWidth)) { index ->
                val nameResolved = functionNamesMap[index] ?: ""
                val isUnreachable = index in unreachableFuncIndices
                val displayName = if (isUnreachable) "\u25CF $nameResolved" else nameResolved
                TableCellValue(
                    displayName,
                    tooltip = nameResolved.takeIf { it.length > universalNameMeasure.length },
                )
            })
        }
    }

    Table(columns + listOf(
        TableColumn("Signature", modifier = Modifier.fillMaxWidth()) { index ->
            val signatureDescription = functions[index].getDescription(index, typeNames)
            TableCellValue(signatureDescription, tooltip = signatureDescription)
        },
    ), functions.size, onRowContextMenu = { rowIndex ->
        buildList {
            if (onShowPaths != null) {
                add(ContextMenuItem("Show Paths from Roots") {
                    onShowPaths(rowIndex)
                })
            }
        }
    }) { rowIndex ->
        val funcRecord = functions[rowIndex] ?: return@Table
        state.scrollHexViewTo(funcRecord.offset, funcRecord.size)
    }
}