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
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.hexana.*
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.run.runWasmConfiguration
import org.jetbrains.hexana.ui.components.Table
import org.jetbrains.hexana.ui.components.TableCellValue
import org.jetbrains.hexana.ui.components.TableColumn
import org.jetbrains.hexana.ui.components.measureWidthMonospace
import org.jetbrains.hexana.ui.panel.scrollHexViewTo
import org.jetbrains.hexana.ui.panel.universalQualifiedNameMeasure
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.Tooltip
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import javax.swing.JComponent

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
            if (isComponentFunctionExport(export, wasmBinaryProvider)) {
                Tooltip(tooltip = { Text("Run '${export.exportName}'") }) {
                    Icon(
                        AllIconsKeys.Actions.Execute,
                        contentDescription = "Run",
                        iconClass = AllIcons::class.java,
                        modifier = Modifier.size(16.dp).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            runComponentExportedFunction(binaryProvider, wasmBinaryProvider, state, export)
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

private class ComponentFunctionArgsDialog(
    project: Project,
    private val exportName: String,
    private val params: List<LabelValueType>,
    private val types: List<ComponentDefType> = emptyList(),
) : DialogWrapper(project, false) {

    val argValues = MutableList(params.size) { i ->
        val resolved = resolveComponentValueType(params[i].value, types)
        if (resolved is ComponentEnumType) resolved.entries.firstOrNull() ?: "" else "0"
    }

    init {
        title = "Arguments for '$exportName'"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            for ((i, param) in params.withIndex()) {
                val typeName = componentTypeName(param.value, types)
                val resolved = resolveComponentValueType(param.value, types)
                row("${param.label} ($typeName):") {
                    if (resolved is ComponentEnumType) {
                        val model = com.intellij.openapi.ui.ComboBox(resolved.entries.toTypedArray())
                        val combo = cell(model)
                            .bind(
                                { component -> component.selectedItem as? String ?: "" },
                                { component, value -> component.selectedItem = value },
                                com.intellij.ui.dsl.builder.MutableProperty({ argValues[i] }, { argValues[i] = it }),
                            )
                        if (i == 0) combo.focused()
                    } else {
                        val field = textField()
                            .bindText({ argValues[i] }, { argValues[i] = it })
                            .validationInfo { field ->
                                validateArg(field.text, param.value)
                            }
                        if (i == 0) field.focused()
                    }
                }
            }
        }
    }

    private fun validateArg(text: String, type: ComponentValueType): ValidationInfo? {
        if (text.isBlank()) return ValidationInfo("Value is required")
        if (type is ComponentValueIdxType) {
            val resolved = types.getOrNull(type.idx.toInt())
            if (resolved is ComponentEnumType) {
                return if (text !in resolved.entries) ValidationInfo("Expected one of: ${resolved.entries.joinToString(", ")}") else null
            }
            return null
        }
        if (type !is ComponentPrimitiveValueType) return null
        return when (type.type) {
            is ComponentPrimitiveBoolType -> if (text != "true" && text != "false") ValidationInfo("Expected 'true' or 'false'") else null
            is ComponentPrimitiveU8Type -> if (text.toIntOrNull()?.let { it in 0..255 } != true) ValidationInfo("Expected a u8 integer (0..255)") else null
            is ComponentPrimitiveS8Type -> if (text.toIntOrNull()?.let { it in -128..127 } != true) ValidationInfo("Expected an s8 integer (-128..127)") else null
            is ComponentPrimitiveU16Type -> if (text.toIntOrNull()?.let { it in 0..65535 } != true) ValidationInfo("Expected a u16 integer (0..65535)") else null
            is ComponentPrimitiveS16Type -> if (text.toIntOrNull()?.let { it in -32768..32767 } != true) ValidationInfo("Expected an s16 integer (-32768..32767)") else null
            is ComponentPrimitiveU32Type -> if (text.toLongOrNull()?.let { it in 0..4294967295L } != true) ValidationInfo("Expected a u32 integer") else null
            is ComponentPrimitiveS32Type -> if (text.toIntOrNull() == null) ValidationInfo("Expected an s32 integer") else null
            is ComponentPrimitiveU64Type -> if (text.toULongOrNull() == null) ValidationInfo("Expected a u64 integer") else null
            is ComponentPrimitiveS64Type -> if (text.toLongOrNull() == null) ValidationInfo("Expected an s64 integer") else null
            is ComponentPrimitiveF32Type -> if (text.toFloatOrNull() == null) ValidationInfo("Expected an f32 number") else null
            is ComponentPrimitiveF64Type -> if (text.toDoubleOrNull() == null) ValidationInfo("Expected an f64 number") else null
            is ComponentPrimitiveCharType -> if (text.length != 1) ValidationInfo("Expected a single character") else null
            is ComponentPrimitiveString -> null
            else -> null
        }
    }
}

private fun runComponentExportedFunction(
    binaryProvider: BinaryProvider,
    wasmBinaryProvider: WasmComponentBinaryProvider,
    state: HexViewStateHolder,
    export: WasmComponentExport,
) {
    val project = state.project
    val file = (binaryProvider as? BinaryVirtualFileProvider)?.file ?: return
    val invokeName = resolveInvokeName(export, wasmBinaryProvider)

    val funcType = resolveComponentFunctionType(export, wasmBinaryProvider)
    val params = funcType?.params ?: emptyList()

    val componentTypes = wasmBinaryProvider.types
    var argsString = ""
    if (params.isNotEmpty() && params.all { isTrivialComponentType(it.value, componentTypes) }) {
        val dialog = ComponentFunctionArgsDialog(project, invokeName, params, componentTypes)
        if (!dialog.showAndGet()) return
        argsString = dialog.argValues.joinToString(", ")
    }

    runWasmConfiguration(project, file, invokeName, "", "--invoke \"${invokeName}($argsString)\"")
}
