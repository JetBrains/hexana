package org.jetbrains.hexana.ui.panel.components

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadActionBlocking
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.run.WasmConfigurationFactory
import org.jetbrains.hexana.run.WasmRunConfiguration
import org.jetbrains.hexana.run.WasmRunConfigurationType
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.ui.panel.universalQualifiedNameMeasure
import org.jetbrains.hexana.wasm.components.ComponentCoreSort
import org.jetbrains.hexana.wasm.components.ComponentSort
import org.jetbrains.hexana.wasm.components.ComponentSortWithCoreSort
import org.jetbrains.hexana.wasm.components.WasmComponentExport
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys

@Composable
fun WasmComponentPanelExports(wasmBinaryProvider: WasmComponentBinaryProvider,
                              state: HexViewStateHolder) {
    WasmComponentPanelExports(wasmBinaryProvider, wasmBinaryProvider, state)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WasmComponentPanelExports(binaryProvider: BinaryProvider,
                              wasmBinaryProvider: WasmComponentBinaryProvider,
                              state: HexViewStateHolder) {
    val textMeasurer = rememberTextMeasurer()
    val nameColumnWidth = textMeasurer.measureWidthMonospace(universalQualifiedNameMeasure)
    val sortIndexColumnWidth = textMeasurer.measureWidthMonospace("Component(000)")
    val actionColumnWidth = textMeasurer.measureWidthMonospace("__")

    val exports = remember(wasmBinaryProvider) { wasmBinaryProvider.exports }

    val canRun = remember(binaryProvider) {
        binaryProvider is BinaryVirtualFileProvider
    }

    Table(listOf(
        TableColumn("Name", modifier = Modifier.width(nameColumnWidth)) { index ->
            val exportRecord = exports[index].exportName + (exports[index].version ?: "")
            TableCellValue(exportRecord)
        },
        TableColumn("Sort Index", modifier = Modifier.widthIn(min = sortIndexColumnWidth)) { index ->
            val exportRecord = exports[index]
            TableCellValue(exportRecord.sortIndex.toStringRepresentation() ?: "")
        },
        TableColumn("Extern Description", modifier = Modifier.fillMaxWidth()) { index ->
            val exportRecord = exports[index]
            TableCellValue(exportRecord.externDescription?.toStringRepresentation() ?: "")
        },

        ), exports.size,
        onRowContextMenu = { rowIndex ->
            listOfNotNull(
                ContextMenuItem("Navigate to WIT definition") {
                    val exportRecord = exports[rowIndex]
                    invokeLater {
                        runReadActionBlocking {
                            navigateToWitDefinition(exportRecord.exportName, state.project)
                        }
                    }
                }
            )},
        actionColumn = if (canRun) { { index ->
            val export = exports[index]
            if (isComponentFunctionExport(export)) {
                Tooltip(tooltip = { Text("Run '${export.exportName}'") }) {
                    Icon(
                        AllIconsKeys.Actions.Execute,
                        contentDescription = "Run",
                        iconClass = AllIcons::class.java,
                        modifier = Modifier.size(16.dp).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            runComponentExportedFunction(binaryProvider, state, export)
                        },
                    )
                }
            }
        } } else null,
        actionColumnWidth = actionColumnWidth,
    ) { rowIndex ->
        val exportRecords = exports[rowIndex] ?: return@Table
        state.scrollHexViewTo(exportRecords.offset, exportRecords.size)
    }
}

private fun isComponentFunctionExport(export: WasmComponentExport): Boolean {
    val sort = export.sortIndex.sort
    return sort is ComponentSort.Func ||
        (sort is ComponentSortWithCoreSort && sort.cs is ComponentCoreSort.Func)
}

private fun runComponentExportedFunction(
    binaryProvider: BinaryProvider,
    state: HexViewStateHolder,
    export: WasmComponentExport,
) {
    val project = state.project
    val file = (binaryProvider as? BinaryVirtualFileProvider)?.file ?: return
    val exportName = export.exportName

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

    (settings.configuration as? WasmRunConfiguration)?.programArguments = ""

    runManager.selectedConfiguration = settings
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}
