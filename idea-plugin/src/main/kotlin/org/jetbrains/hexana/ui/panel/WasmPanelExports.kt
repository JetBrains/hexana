package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.icons.AllIcons
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.run.WasmConfigurationFactory
import org.jetbrains.hexana.run.WasmRunConfiguration
import org.jetbrains.hexana.run.WasmRunConfigurationType
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.sections.*
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
internal fun WasmPanelExports(wasmBinaryProvider: WasmBinaryProvider, state: HexViewStateHolder) {
    WasmPanelExports(wasmBinaryProvider, wasmBinaryProvider, state)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WasmPanelExports(binaryProvider: BinaryProvider, wasmBinaryProvider: WasmBinaryProvider, state: HexViewStateHolder) {
    val exports = remember(wasmBinaryProvider) {
        wasmBinaryProvider.exports
    }

    val textMeasurer = rememberTextMeasurer()

    val kindColumnWidth = textMeasurer.measureWidthMonospace("function")
    val actionColumnWidth = textMeasurer.measureWidthMonospace("__")

    val canRun = remember(binaryProvider) {
        binaryProvider is BinaryVirtualFileProvider
    }

    Table(
        listOf(
            TableColumn("Kind", modifier = Modifier.width(kindColumnWidth)) { index ->
                val export = exports[index]
                val exportKind = when (export) {
                    is WasmFunctionExportRecordEntry -> "function"
                    is WasmGlobalExportRecordEntry -> "global"
                    is WasmMemoryExportRecordEntry -> "memory"
                    is WasmTableExportRecordEntry -> "table"
                    is WasmTagExportRecordEntry -> "tag"
                    is WasmUnsupportedExportRecordEntry -> "unknown"
                }

                TableCellValue(exportKind)
            },

            TableColumn("Name", modifier = Modifier.fillMaxWidth()) { index ->
                val export = exports[index]
                TableCellValue(export.name)
            }
        ), dataCount = exports.size,
        onRow = { rowIndex ->
            val exportRecord = exports[rowIndex] ?: return@Table
            state.scrollHexViewTo(exportRecord.offset, exportRecord.size)
        },
        actionColumn = if (canRun) { { index ->
            val export = exports[index]
            if (export is WasmFunctionExportRecordEntry) {
                Tooltip(tooltip = { Text("Run '${export.name}'") }) {
                    IconButton(onClick = {
                        runExportedFunction(binaryProvider, state, export.name)
                    }) {
                        Icon(
                            AllIconsKeys.Actions.Execute,
                            contentDescription = "Run",
                            iconClass = AllIcons::class.java,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        } } else null,
        actionColumnWidth = actionColumnWidth,
    )
}

private fun runExportedFunction(binaryProvider: BinaryProvider, state: HexViewStateHolder, exportName: String) {
    val project = state.project
    val file = (binaryProvider as? BinaryVirtualFileProvider)?.file ?: return
    val runManager = RunManager.getInstance(project)
    val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
    val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

    val configName = "${file.nameWithoutExtension}::${exportName}"

    val existing = runManager.allSettings.firstOrNull { settings ->
        settings.type == configurationType &&
            (settings.configuration as? WasmRunConfiguration)?.let { config ->
                config.wasmFilePath == file.path &&
                    config.wasmtimeOptions.contains("--invoke") &&
                    config.wasmtimeOptions.contains(exportName)
            } == true
    }

    val settings = existing ?: runManager.createConfiguration(configName, factory).also { newSettings ->
        (newSettings.configuration as WasmRunConfiguration).apply {
            wasmFilePath = file.path
            workingDirectory = file.parent?.path ?: ""
            wasmtimeOptions = "--invoke $exportName"
        }
        runManager.addConfiguration(newSettings)
    }

    runManager.selectedConfiguration = settings
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}
