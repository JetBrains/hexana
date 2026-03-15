package org.jetbrains.hexana.ui.panel.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.intellij.openapi.fileEditor.FileEditorManager
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaBinaryVirtualFile
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryVirtualFileProvider
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn

@Composable
fun WasmComponentPanelModules( wasmBinaryProvider: WasmComponentBinaryProvider,
                               state: HexViewStateHolder) {
    val modules = remember (wasmBinaryProvider) { wasmBinaryProvider.modules }
    Table(listOf(
        TableColumn("Data", modifier = Modifier.fillMaxWidth()) { index ->
            val exportRecord = "module:@${modules[index].contentOffset.toHexString()}"
            TableCellValue(exportRecord)
        },

        ), modules.size) { rowIndex ->
        val moduleRecord = modules[rowIndex] ?: return@Table
        val buffer = wasmBinaryProvider.bytes.slice(
            IntRange(moduleRecord.contentOffset, moduleRecord.contentSize)
        )
        val hexedOffset = moduleRecord.contentOffset.toHexString()
        val fileName = (wasmBinaryProvider as? WasmComponentBinaryVirtualFileProvider)?.let {
            "${it.file.nameWithoutExtension}_${rowIndex}@$hexedOffset.${it.file.extension}"
        } ?: "module_${rowIndex}@$hexedOffset.wasm"
        val virtualFile = HexanaBinaryVirtualFile(fileName, buffer, baseOffset = moduleRecord.contentOffset)
        FileEditorManager.getInstance(state.project).openFile(virtualFile, true)
    }
}