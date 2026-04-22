package org.jetbrains.hexana.ui.panel

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ValidationInfo
import org.jetbrains.hexana.*
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.run.WasmEngine
import org.jetbrains.hexana.run.WasmRunMode
import org.jetbrains.hexana.run.runWasmConfiguration
import org.jetbrains.hexana.run.showEnginePopup
import org.jetbrains.hexana.shared.formatSignature
import org.jetbrains.hexana.shared.isInternalName
import org.jetbrains.hexana.shared.isTrivialType
import org.jetbrains.hexana.shared.resolveFunctionSignature
import org.jetbrains.hexana.shared.wasmValueTypeName
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.wasm.sections.*
import org.jetbrains.jewel.ui.component.Icon
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
                    Icon(
                        AllIconsKeys.Actions.Execute,
                        contentDescription = "Run",
                        iconClass = AllIcons::class.java,
                        modifier = Modifier.size(16.dp).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            showEnginePopup { engine, mode ->
                                runExportedFunction(binaryProvider, wasmBinaryProvider, state, export, engine, mode)
                            }
                        },
                    )
                }
            }
        } } else null,
        actionColumnWidth = actionColumnWidth,
    )
}


private fun wasmArgDescriptors(params: List<WasmValueType<*>>): List<FunctionArgDescriptor> =
    params.mapIndexed { i, param ->
        FunctionArgDescriptor(
            label = "arg$i",
            typeName = wasmValueTypeName(param),
            validate = { text -> validateWasmArg(text, param) },
        )
    }

private fun validateWasmArg(text: String, type: WasmValueType<*>): ValidationInfo? {
    if (text.isBlank()) return ValidationInfo("Value is required")
    return when (type) {
        is WasmI32Type -> if (text.toIntOrNull() == null) ValidationInfo("Expected an i32 integer") else null
        is WasmI64Type -> if (text.toLongOrNull() == null) ValidationInfo("Expected an i64 integer") else null
        is WasmF32Type -> if (text.toFloatOrNull() == null) ValidationInfo("Expected an f32 number") else null
        is WasmF64Type -> if (text.toDoubleOrNull() == null) ValidationInfo("Expected an f64 number") else null
        else -> null
    }
}

private fun runExportedFunction(
    binaryProvider: BinaryProvider,
    wasmBinaryProvider: WasmBinaryProvider,
    state: HexViewStateHolder,
    export: WasmFunctionExportRecordEntry,
    engine: WasmEngine,
    mode: WasmRunMode = WasmRunMode.RUN,
) {
    val project = state.project
    val file = (binaryProvider as? BinaryVirtualFileProvider)?.file ?: return
    val exportName = export.name

    val signature = resolveFunctionSignature(wasmBinaryProvider, export)
    val params = signature?.params ?: emptyList()

    var programArgs = ""
    if (params.isNotEmpty() && params.all { isTrivialType(it) }) {
        val dialog = FunctionArgsDialog(project, exportName, wasmArgDescriptors(params))
        if (!dialog.showAndGet()) return
        programArgs = dialog.argValues.joinToString(" ")
    }

    runWasmConfiguration(project, file, exportName, programArgs, "--invoke $exportName", engine, mode)
}
