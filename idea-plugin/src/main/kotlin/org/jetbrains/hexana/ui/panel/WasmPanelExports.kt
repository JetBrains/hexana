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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
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
import javax.swing.JComponent

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

    val signatureCache = remember(wasmBinaryProvider) {
        exports.map { export ->
            if (export is WasmFunctionExportRecordEntry) {
                resolveFunctionSignature(wasmBinaryProvider, export)?.let { sig ->
                    formatSignature(sig)
                } ?: ""
            } else ""
        }
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

            TableColumn("Name", modifier = Modifier.fillMaxWidth(0.5f)) { index ->
                val export = exports[index]
                TableCellValue(export.name)
            },

            TableColumn("Signature", modifier = Modifier.fillMaxWidth()) { index ->
                val sig = signatureCache[index]
                TableCellValue(sig, tooltip = sig.takeIf { it.isNotEmpty() })
            }
        ), dataCount = exports.size,
        onRow = { rowIndex ->
            val exportRecord = exports[rowIndex] ?: return@Table
            state.scrollHexViewTo(exportRecord.offset, exportRecord.size)
        },
        actionColumn = if (canRun) { { index ->
            val export = exports[index]
            val name = export.name
            if (export is WasmFunctionExportRecordEntry && !isInternalName(name)) {
                Tooltip(tooltip = { Text("Run '${export.name}'") }) {
                    IconButton(onClick = {
                        runExportedFunction(binaryProvider, wasmBinaryProvider, state, export)
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

private fun isInternalName(name: String): Boolean = name.startsWith("__")

private fun resolveFunctionSignature(
    wasmBinaryProvider: WasmBinaryProvider,
    export: WasmFunctionExportRecordEntry,
): WasmSectionFunctionTypeRecord? {
    val importedFunctionCount = wasmBinaryProvider.imports.count { it.type is WasmTypeIndexExternalType }
    val localIndex = export.index.toInt() - importedFunctionCount
    if (localIndex < 0) return null
    return wasmBinaryProvider.functions.getOrNull(localIndex) as? WasmSectionFunctionTypeRecord
}

private fun isTrivialType(type: WasmValueType<*>): Boolean = type is WasmNumType

private fun wasmTypeName(type: WasmValueType<*>): String = when (type) {
    is WasmI32Type -> "i32"
    is WasmI64Type -> "i64"
    is WasmF32Type -> "f32"
    is WasmF64Type -> "f64"
    else -> type.toString()
}

private fun formatSignature(sig: WasmSectionFunctionTypeRecord): String {
    val params = sig.params.joinToString(", ") { wasmTypeName(it) }
    val result = sig.retTypes.firstOrNull()?.let { wasmTypeName(it) } ?: "void"
    return "($params) -> $result"
}

private class WasmFunctionArgsDialog(
    project: Project,
    private val exportName: String,
    private val params: List<WasmValueType<*>>,
) : DialogWrapper(project, false) {

    val argValues = MutableList(params.size) { "0" }

    init {
        title = "Arguments for '$exportName'"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            for ((i, param) in params.withIndex()) {
                val typeName = wasmTypeName(param)
                row("arg$i ($typeName):") {
                    val field = textField()
                        .bindText({ argValues[i] }, { argValues[i] = it })
                        .validationInfo { field ->
                            validateArg(field.text, param)
                        }
                    if (i == 0) field.focused()
                }
            }
        }
    }

    private fun validateArg(text: String, type: WasmValueType<*>): ValidationInfo? {
        if (text.isBlank()) return ValidationInfo("Value is required")
        return when (type) {
            is WasmI32Type -> if (text.toIntOrNull() == null) ValidationInfo("Expected an i32 integer") else null
            is WasmI64Type -> if (text.toLongOrNull() == null) ValidationInfo("Expected an i64 integer") else null
            is WasmF32Type -> if (text.toFloatOrNull() == null) ValidationInfo("Expected an f32 number") else null
            is WasmF64Type -> if (text.toDoubleOrNull() == null) ValidationInfo("Expected an f64 number") else null
            else -> null
        }
    }
}

private fun runExportedFunction(
    binaryProvider: BinaryProvider,
    wasmBinaryProvider: WasmBinaryProvider,
    state: HexViewStateHolder,
    export: WasmFunctionExportRecordEntry,
) {
    val project = state.project
    val file = (binaryProvider as? BinaryVirtualFileProvider)?.file ?: return
    val exportName = export.name

    val signature = resolveFunctionSignature(wasmBinaryProvider, export)
    val params = signature?.params ?: emptyList()

    var programArgs = ""
    if (params.isNotEmpty() && params.all { isTrivialType(it) }) {
        val dialog = WasmFunctionArgsDialog(project, exportName, params)
        if (!dialog.showAndGet()) return
        programArgs = dialog.argValues.joinToString(" ")
    }

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

    (settings.configuration as? WasmRunConfiguration)?.programArguments = programArgs

    runManager.selectedConfiguration = settings
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}
