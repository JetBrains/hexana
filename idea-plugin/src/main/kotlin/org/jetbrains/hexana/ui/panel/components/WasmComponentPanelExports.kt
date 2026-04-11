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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.BinaryVirtualFileProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.run.WasmConfigurationFactory
import org.jetbrains.hexana.run.WasmDependencyResolver
import org.jetbrains.hexana.run.WasmRunConfiguration
import org.jetbrains.hexana.run.WasmRunConfigurationType
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

private fun isComponentFunctionExport(export: WasmComponentExport, provider: WasmComponentBinaryProvider): Boolean {
    val sort = export.sortIndex.sort
    if (sort is ComponentSort.Func ||
        (sort is ComponentSortWithCoreSort && sort.cs is ComponentCoreSort.Func)) {
        return true
    }
    if (sort is ComponentSort.Instance) {
        val instance = provider.instances.getOrNull(export.sortIndex.index.toInt())
        if (instance is InlineInstanceExpression) {
            return instance.e.any { it is FuncInstanceInlineExport }
        }
        if (instance is ComponentInstanceExpression) {
            val componentExports = provider.nestedComponentExports(instance.index.toInt())
            return componentExports.any {
                it.sortIndex.sort is ComponentSort.Func ||
                    (it.sortIndex.sort is ComponentSortWithCoreSort && (it.sortIndex.sort as ComponentSortWithCoreSort).cs is ComponentCoreSort.Func)
            }
        }
        if (instance == null && looksLikeWitInterfaceExport(export.exportName)) {
            return true
        }
    }
    if (sort is ComponentSortWithCoreSort && sort.cs is ComponentCoreSort.Instance) {
        val coreInstance = provider.coreInstances.getOrNull(export.sortIndex.index.toInt())
        if (coreInstance is InlineInstanceCoreExpression) {
            return coreInstance.e.any { it is FuncInlineCoreExpression }
        }
    }
    return false
}

internal val WIT_INTERFACE_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9-]*:[a-zA-Z][a-zA-Z0-9-]*/([a-zA-Z][a-zA-Z0-9-]*)(@.+)?$")

internal fun looksLikeWitInterfaceExport(name: String): Boolean = WIT_INTERFACE_PATTERN.matches(name)

internal fun extractNameFromWitExport(name: String): String? =
    WIT_INTERFACE_PATTERN.matchEntire(name)?.groupValues?.get(1)

internal fun resolveComponentFunctionType(
    export: WasmComponentExport,
    provider: WasmComponentBinaryProvider,
): ComponentRoutineType? {
    val ed = export.externDescription
    if (ed is ComponentExternType) {
        val type = provider.types.getOrNull(ed.idx.toInt())
        if (type is ComponentRoutineType) return type
    }
    if (ed is ComponentExternInstance) {
        val instanceType = provider.types.getOrNull(ed.idx.toInt())
        if (instanceType is ComponentInstanceType) {
            val funcDecl = instanceType.cd.filterIsInstance<ExportDeclaration>()
                .firstOrNull { it.ed is ComponentExternType }
            if (funcDecl != null) {
                val funcType = provider.types.getOrNull((funcDecl.ed as ComponentExternType).idx.toInt())
                if (funcType is ComponentRoutineType) return funcType
            }
        }
    }
    // Go deeper into component instances and nested components, same as resolveInvokeName
    val sort = export.sortIndex.sort
    if (sort is ComponentSort.Instance) {
        val instance = provider.instances.getOrNull(export.sortIndex.index.toInt())
        if (instance is ComponentInstanceExpression) {
            val nestedExports = provider.nestedComponentExports(instance.index.toInt())
            val nestedTypes = provider.nestedComponentTypes(instance.index.toInt())
            val funcExport = nestedExports.firstOrNull {
                it.sortIndex.sort is ComponentSort.Func ||
                    (it.sortIndex.sort is ComponentSortWithCoreSort && (it.sortIndex.sort as ComponentSortWithCoreSort).cs is ComponentCoreSort.Func)
            }
            if (funcExport != null) {
                val nestedEd = funcExport.externDescription
                if (nestedEd is ComponentExternType) {
                    val type = nestedTypes.getOrNull(nestedEd.idx.toInt())
                    if (type is ComponentRoutineType) return type
                }
                // The type index may reference the full type index space (imports, aliases, etc.)
                // rather than just the types section entries. Fall back to the first routine type.
                val fallbackType = nestedTypes.filterIsInstance<ComponentRoutineType>().firstOrNull()
                if (fallbackType != null) return fallbackType
            }
        }
        // When instance lookup fails but export name matches a WIT interface pattern,
        // try to resolve the type from the parent component's type definitions
        if (instance == null && looksLikeWitInterfaceExport(export.exportName)) {
            val routineType = provider.types.filterIsInstance<ComponentRoutineType>().firstOrNull()
            if (routineType != null) return routineType
        }
    }
    return null
}

internal fun isTrivialComponentType(type: ComponentValueType): Boolean {
    if (type !is ComponentPrimitiveValueType) return false
    return when (type.type) {
        is ComponentPrimitiveBoolType,
        is ComponentPrimitiveU8Type, is ComponentPrimitiveS8Type,
        is ComponentPrimitiveU16Type, is ComponentPrimitiveS16Type,
        is ComponentPrimitiveU32Type, is ComponentPrimitiveS32Type,
        is ComponentPrimitiveU64Type, is ComponentPrimitiveS64Type,
        is ComponentPrimitiveF32Type, is ComponentPrimitiveF64Type,
        is ComponentPrimitiveCharType, is ComponentPrimitiveString -> true
        else -> false
    }
}

internal fun componentTypeName(type: ComponentValueType): String = when (type) {
    is ComponentPrimitiveValueType -> when (type.type) {
        is ComponentPrimitiveBoolType -> "bool"
        is ComponentPrimitiveU8Type -> "u8"
        is ComponentPrimitiveS8Type -> "s8"
        is ComponentPrimitiveU16Type -> "u16"
        is ComponentPrimitiveS16Type -> "s16"
        is ComponentPrimitiveU32Type -> "u32"
        is ComponentPrimitiveS32Type -> "s32"
        is ComponentPrimitiveU64Type -> "u64"
        is ComponentPrimitiveS64Type -> "s64"
        is ComponentPrimitiveF32Type -> "f32"
        is ComponentPrimitiveF64Type -> "f64"
        is ComponentPrimitiveCharType -> "char"
        is ComponentPrimitiveString -> "string"
        else -> type.type.toString()
    }
    is ComponentValueIdxType -> "type(${type.idx})"
    else -> type.toString()
}

internal fun formatComponentSignature(funcType: ComponentRoutineType): String {
    val params = funcType.params.joinToString(", ") { "${it.label}: ${componentTypeName(it.value)}" }
    val result = when (val ret = funcType.ret) {
        is ComponentValueType -> componentTypeName(ret)
        is ComponentVoidType -> "void"
        else -> ret.toString()
    }
    return "($params) -> $result"
}

private class ComponentFunctionArgsDialog(
    project: Project,
    private val exportName: String,
    private val params: List<LabelValueType>,
) : DialogWrapper(project, false) {

    val argValues = MutableList(params.size) { "0" }

    init {
        title = "Arguments for '$exportName'"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            for ((i, param) in params.withIndex()) {
                val typeName = componentTypeName(param.value)
                row("${param.label} ($typeName):") {
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

    private fun validateArg(text: String, type: ComponentValueType): ValidationInfo? {
        if (text.isBlank()) return ValidationInfo("Value is required")
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

private fun resolveInvokeName(export: WasmComponentExport, provider: WasmComponentBinaryProvider): String {
    val sort = export.sortIndex.sort
    if (sort is ComponentSort.Instance) {
        val instance = provider.instances.getOrNull(export.sortIndex.index.toInt())
        if (instance is InlineInstanceExpression) {
            val funcExport = instance.e.filterIsInstance<FuncInstanceInlineExport>().firstOrNull()
            if (funcExport != null) return funcExport.name
        }
        if (instance is ComponentInstanceExpression) {
            val componentExports = provider.nestedComponentExports(instance.index.toInt())
            val funcExport = componentExports.firstOrNull {
                it.sortIndex.sort is ComponentSort.Func ||
                    (it.sortIndex.sort is ComponentSortWithCoreSort && (it.sortIndex.sort as ComponentSortWithCoreSort).cs is ComponentCoreSort.Func)
            }
            if (funcExport != null) return funcExport.exportName
        }
        val witName = extractNameFromWitExport(export.exportName)
        if (witName != null) return witName
    }
    if (sort is ComponentSortWithCoreSort && sort.cs is ComponentCoreSort.Instance) {
        val coreInstance = provider.coreInstances.getOrNull(export.sortIndex.index.toInt())
        if (coreInstance is InlineInstanceCoreExpression) {
            val funcExport = coreInstance.e.filterIsInstance<FuncInlineCoreExpression>().firstOrNull()
            if (funcExport != null) return funcExport.name
        }
    }
    return export.exportName
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

    var argsString = ""
    if (params.isNotEmpty() && params.all { isTrivialComponentType(it.value) }) {
        val dialog = ComponentFunctionArgsDialog(project, invokeName, params)
        if (!dialog.showAndGet()) return
        argsString = dialog.argValues.joinToString(", ")
    }

    val invokeExpression = "${invokeName}($argsString)"

    val runManager = RunManager.getInstance(project)
    val configurationType = ConfigurationTypeUtil.findConfigurationType(WasmRunConfigurationType::class.java)
    val factory = configurationType.configurationFactories.first { it is WasmConfigurationFactory }

    val configName = "${file.nameWithoutExtension}::${invokeName}"

    val existing = runManager.allSettings.firstOrNull { settings ->
        settings.type == configurationType &&
            (settings.configuration as? WasmRunConfiguration)?.let { config ->
                config.wasmFilePath == file.path &&
                    config.wasmtimeOptions.contains("--invoke") &&
                    config.wasmtimeOptions.contains(invokeName)
            } == true
    }

    val settings = existing ?: runManager.createConfiguration(configName, factory).also { newSettings ->
        (newSettings.configuration as WasmRunConfiguration).apply {
            wasmFilePath = file.path
            workingDirectory = file.parent?.path ?: ""
        }
        runManager.addConfiguration(newSettings)
    }

    (settings.configuration as? WasmRunConfiguration)?.wasmtimeOptions = "--invoke \"$invokeExpression\""

    val wasmBytes = file.contentsToByteArray()
    val resolved = WasmDependencyResolver.resolveDependencies(wasmBytes, project, excludeFile = file)
    (settings.configuration as? WasmRunConfiguration)?.dependencies =
        resolved.map { (name, path) -> "$name=$path" }

    runManager.selectedConfiguration = settings
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}
