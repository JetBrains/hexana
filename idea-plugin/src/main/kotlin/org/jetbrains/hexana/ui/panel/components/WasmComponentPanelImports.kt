package org.jetbrains.hexana.ui.panel.components

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadActionBlocking
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.componentExternDescriptionLabel
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.ui.panel.universalQualifiedNameMeasure

@Composable
internal fun WasmComponentPanelImports(
    wasmBinaryProvider: WasmComponentBinaryProvider,
    state: HexViewStateHolder) {
    val textMeasurer = rememberTextMeasurer()

    val nameColumnWidth = textMeasurer.measureWidthMonospace(universalQualifiedNameMeasure)

    val imports = remember (wasmBinaryProvider) { wasmBinaryProvider.imports }
    Table(listOf(
        TableColumn("Name", modifier = Modifier.width(nameColumnWidth)) { index ->
            val importRecord = imports[index].importName + (imports[index].version ?: "")
            TableCellValue(importRecord)
        },

        TableColumn("Extern Description", modifier = Modifier.fillMaxWidth()) { index ->
            val importRecord = imports[index]
            TableCellValue(componentExternDescriptionLabel(importRecord.externDescription))
        },

        ), imports.size,
        onRowContextMenu = { rowIndex ->
            listOfNotNull(
                ContextMenuItem("Navigate to WASM definition") {
                    invokeLater {
                        runReadActionBlocking {
                            navigateToWasmDefinition(imports[rowIndex].importName, state.project)
                        }
                    }
                },
                ContextMenuItem("Navigate to WIT definition") {
                    invokeLater {
                        runReadActionBlocking {
                            navigateToWitDefinition(imports[rowIndex].importName, state.project)
                        }
                    }
                }
            )}) { rowIndex ->
        val importRecord = imports[rowIndex] ?: return@Table
        state.scrollHexViewTo(importRecord.offset, importRecord.size)
    }
}
