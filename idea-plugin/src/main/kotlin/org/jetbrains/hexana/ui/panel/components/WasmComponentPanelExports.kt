package org.jetbrains.hexana.ui.panel.components

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.NavigatableFileEditor
import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.parentOfType
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.wit.WitIndexId
import org.jetbrains.hexana.wit.processIndexEntries
import org.jetbrains.hexana.wit.psi.WitResolver

@Composable
fun WasmComponentPanelExports( wasmBinaryProvider: WasmComponentBinaryProvider,
                               state: HexViewStateHolder) {
    val textMeasurer = rememberTextMeasurer()
    val minNameColumnModifier = textMeasurer.measureWidthMonospace("wasi:xxxxxxxx/environent:2.9.0")

    val exports = remember (wasmBinaryProvider) { wasmBinaryProvider.exports }
    Table(listOf(
        TableColumn("Name", modifier = Modifier.widthIn(min = minNameColumnModifier)) { index ->
            val exportRecord = exports[index].exportName + (exports[index].version ?: "")
            TableCellValue(exportRecord)
        },
        TableColumn("Extern Description", modifier = Modifier.fillMaxWidth()) { index ->
            val exportRecord = exports[index]
            TableCellValue(exportRecord.externDescription.toStringRepresentation())
        },

        ), exports.size,
        onRowContextMenu = { rowIndex ->
            listOfNotNull(
                ContextMenuItem("Navigate to WIT definition") {
                    val exportRecord = exports[rowIndex]
                    navigateToWitDefinition(exportRecord.exportName, state.project)
                }
            )}) { rowIndex ->
        val exportRecords = exports[rowIndex] ?: return@Table
        state.scrollHexViewTo(exportRecords.offset, exportRecords.size)
    }
}