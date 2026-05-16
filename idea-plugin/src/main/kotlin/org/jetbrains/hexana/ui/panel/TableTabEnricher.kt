package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexanaBinaryVirtualFile
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.run.WasmRunMode
import org.jetbrains.hexana.run.runWasmConfiguration
import org.jetbrains.hexana.run.showEnginePopup
import org.jetbrains.hexana.shared.*
import org.jetbrains.hexana.wasm.components.ComponentDefType
import org.jetbrains.hexana.wasm.components.ComponentEnumType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveBoolType
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveF32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveF64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS16Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveS8Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveString
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU16Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU32Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU64Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveU8Type
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveValueType
import org.jetbrains.hexana.wasm.components.ComponentValueIdxType
import org.jetbrains.hexana.wasm.components.ComponentValueType
import org.jetbrains.hexana.wasm.components.LabelValueType
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmI32Type
import org.jetbrains.hexana.wasm.sections.WasmI64Type
import org.jetbrains.hexana.wasm.sections.WasmF32Type
import org.jetbrains.hexana.wasm.sections.WasmF64Type
import org.jetbrains.hexana.wasm.sections.WasmValueType
import com.intellij.openapi.ui.ValidationInfo
import org.jetbrains.hexana.wasm.components.ComponentPrimitiveCharType

/**
 * Enriches [TableTabDescriptor]s produced by [WasmTableTabsProvider] with
 * IDE-specific actions: run buttons on exported functions, unreachable
 * function badges and cross-panel navigation on the Functions tab.
 *
 * @param onShowPaths callback invoked when the user selects "Show Paths
 *   from Roots" on a function row; receives the function index
 * @param unreachableFuncIndicesProvider returns the set of function indices
 *   that are unreachable (garbage); evaluated lazily at cell render time
 */
internal fun List<TableTabDescriptor>.enrichWasmTabs(
    provider: WasmBinaryProvider,
    file: VirtualFile,
    project: Project,
    onShowPaths: ((funcIndex: Int) -> Unit)? = null,
    unreachableFuncIndicesProvider: () -> Set<Int> = { emptySet() },
): List<TableTabDescriptor> = map { tab ->
    when (tab.name) {
        "Exports" -> enrichWasmExportsTab(tab, provider, file, project)
        "Functions" -> enrichWasmFunctionsTab(tab, provider, onShowPaths, unreachableFuncIndicesProvider)
        else -> tab
    }
}

/**
 * Enriches [TableTabDescriptor]s produced by [WasmComponentTableTabsProvider]
 * with IDE-specific actions: run buttons on exports, navigation menus on
 * imports, and open-in-editor click on modules.
 */
internal fun List<TableTabDescriptor>.enrichComponentTabs(
    provider: WasmComponentBinaryProvider,
    file: VirtualFile,
    project: Project,
): List<TableTabDescriptor> = map { tab ->
    when (tab.name) {
        "Exports" -> enrichComponentExportsTab(tab, provider, file, project)
        "Imports" -> enrichComponentImportsTab(tab, provider, project)
        "Modules" -> enrichComponentModulesTab(tab, provider, file, project)
        else -> tab
    }
}

// ---------------------------------------------------------------------------
// Wasm Exports enrichment
// ---------------------------------------------------------------------------

private fun enrichWasmExportsTab(
    tab: TableTabDescriptor,
    provider: WasmBinaryProvider,
    file: VirtualFile,
    project: Project,
): TableTabDescriptor {
    val exports = provider.exports
    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowAction = RowActionDescriptor(
            label = "Run",
            tooltipProvider = { index ->
                val export = exports.getOrNull(index) ?: return@RowActionDescriptor null
                if (export is WasmFunctionExportRecordEntry && !isInternalName(export.name)) {
                    "Run '${export.name}'"
                } else null
            },
            action = { index ->
                val export = exports.getOrNull(index) ?: return@RowActionDescriptor
                if (export is WasmFunctionExportRecordEntry) {
                    showEnginePopup { engine, mode ->
                        runWasmExportedFunction(provider, file, project, export, engine, mode)
                    }
                }
            },
        ),
    )
}

private fun runWasmExportedFunction(
    wasmBinaryProvider: WasmBinaryProvider,
    file: VirtualFile,
    project: Project,
    export: WasmFunctionExportRecordEntry,
    engine: org.jetbrains.hexana.run.WasmEngine,
    mode: WasmRunMode,
) {
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

// ---------------------------------------------------------------------------
// Wasm Functions enrichment
// ---------------------------------------------------------------------------

private fun enrichWasmFunctionsTab(
    tab: TableTabDescriptor,
    provider: WasmBinaryProvider,
    onShowPaths: ((funcIndex: Int) -> Unit)?,
    unreachableFuncIndicesProvider: () -> Set<Int>,
): TableTabDescriptor {
    val nameSection = provider.nameSection
    val functionNamesMap = nameSection?.functionSubsection?.namemap

    // Wrap the Name column's cell provider to prefix "●" for unreachable functions.
    val enrichedColumns = if (functionNamesMap != null) {
        tab.columns.map { col ->
            if (col.name == "Name") {
                ColumnDescriptor(
                    name = col.name,
                    widthBehavior = col.widthBehavior,
                ) { index ->
                    val original = col.cellProvider(index)
                    val unreachable = index in unreachableFuncIndicesProvider()
                    if (unreachable) {
                        val displayName = "\u25CF ${original.representation}"
                        CellValue(
                            displayName,
                            tooltip = original.tooltip,
                        )
                    } else {
                        original
                    }
                }
            } else col
        }
    } else tab.columns

    return TableTabDescriptor(
        name = tab.name,
        columns = enrichedColumns,
        rowCount = tab.rowCount,
        defaultSortColumn = tab.defaultSortColumn,
        defaultSortAscending = tab.defaultSortAscending,
        rowSelectionProvider = tab.rowSelectionProvider,
        contextMenuItems = if (onShowPaths != null) {
            listOf(
                RowContextMenuItemDescriptor(
                    labelProvider = { "Show Paths from Roots" },
                    action = { index -> onShowPaths(index) },
                ),
            )
        } else emptyList(),
    )
}

// ---------------------------------------------------------------------------
// Component Exports enrichment
// ---------------------------------------------------------------------------

private fun enrichComponentExportsTab(
    tab: TableTabDescriptor,
    provider: WasmComponentBinaryProvider,
    file: VirtualFile,
    project: Project,
): TableTabDescriptor {
    val exports = provider.exports
    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowAction = RowActionDescriptor(
            label = "Run",
            tooltipProvider = { index ->
                val export = exports.getOrNull(index) ?: return@RowActionDescriptor null
                if (isWasiCliRunExport(export) || isComponentFunctionExport(export, provider)) {
                    "Run '${export.exportName}'"
                } else null
            },
            action = { index ->
                val export = exports.getOrNull(index) ?: return@RowActionDescriptor
                showEnginePopup(isComponentModel = true) { engine, mode ->
                    if (isWasiCliRunExport(export)) {
                        runWasiCliComponent(file, project, export, engine, mode)
                    } else {
                        runComponentExportedFunction(provider, file, project, export, engine, mode)
                    }
                }
            },
        ),
        contextMenuItems = listOf(
            RowContextMenuItemDescriptor(
                labelProvider = { "Navigate to WIT definition" },
                action = { index ->
                    val export = exports.getOrNull(index) ?: return@RowContextMenuItemDescriptor
                    invokeLater {
                        runReadActionBlocking {
                            navigateToWitDefinition(export.exportName, project)
                        }
                    }
                },
            ),
        ),
    )
}

private fun runWasiCliComponent(
    file: VirtualFile,
    project: Project,
    export: org.jetbrains.hexana.wasm.components.WasmComponentExport,
    engine: org.jetbrains.hexana.run.WasmEngine,
    mode: WasmRunMode,
) {
    val dialog = ProgramArgsDialog(project, export.exportName)
    if (!dialog.showAndGet()) return
    runWasmConfiguration(project, file, "", dialog.programArgs, "", engine, mode)
}

private fun runComponentExportedFunction(
    wasmBinaryProvider: WasmComponentBinaryProvider,
    file: VirtualFile,
    project: Project,
    export: org.jetbrains.hexana.wasm.components.WasmComponentExport,
    engine: org.jetbrains.hexana.run.WasmEngine,
    mode: WasmRunMode,
) {
    val invokeName = resolveInvokeName(export, wasmBinaryProvider)
    val funcType = resolveComponentFunctionType(export, wasmBinaryProvider)
    val params = funcType?.params ?: emptyList()
    val componentTypes = wasmBinaryProvider.types

    var argsString = ""
    if (params.isNotEmpty() && params.all { isTrivialComponentType(it.value, componentTypes) }) {
        val dialog = FunctionArgsDialog(project, invokeName, componentArgDescriptors(params, componentTypes))
        if (!dialog.showAndGet()) return
        argsString = dialog.argValues.joinToString(", ")
    }

    runWasmConfiguration(project, file, invokeName, "", "--invoke \"${invokeName}($argsString)\"", engine, mode)
}

private fun componentArgDescriptors(
    params: List<LabelValueType>,
    types: List<ComponentDefType>,
): List<FunctionArgDescriptor> =
    params.map { param ->
        val resolved = resolveComponentValueType(param.value, types)
        val enumEntries = (resolved as? ComponentEnumType)?.entries
        FunctionArgDescriptor(
            label = param.label,
            typeName = componentTypeName(param.value, types),
            defaultValue = if (enumEntries != null) enumEntries.firstOrNull() ?: "" else "0",
            enumEntries = enumEntries,
            validate = { text -> validateComponentArg(text, param.value, types) },
        )
    }

private fun validateComponentArg(text: String, type: ComponentValueType, types: List<ComponentDefType>): ValidationInfo? {
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

// ---------------------------------------------------------------------------
// Component Imports enrichment
// ---------------------------------------------------------------------------

private fun enrichComponentImportsTab(
    tab: TableTabDescriptor,
    provider: WasmComponentBinaryProvider,
    project: Project,
): TableTabDescriptor {
    val imports = provider.imports
    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        rowSelectionProvider = tab.rowSelectionProvider,
        contextMenuItems = listOf(
            RowContextMenuItemDescriptor(
                labelProvider = { "Navigate to WASM definition" },
                action = { index ->
                    val importRecord = imports.getOrNull(index) ?: return@RowContextMenuItemDescriptor
                    invokeLater {
                        runReadActionBlocking {
                            navigateToWasmDefinition(importRecord.importName, project)
                        }
                    }
                },
            ),
            RowContextMenuItemDescriptor(
                labelProvider = { "Navigate to WIT definition" },
                action = { index ->
                    val importRecord = imports.getOrNull(index) ?: return@RowContextMenuItemDescriptor
                    invokeLater {
                        runReadActionBlocking {
                            navigateToWitDefinition(importRecord.importName, project)
                        }
                    }
                },
            ),
        ),
    )
}

// ---------------------------------------------------------------------------
// Component Modules enrichment
// ---------------------------------------------------------------------------

private fun enrichComponentModulesTab(
    tab: TableTabDescriptor,
    provider: WasmComponentBinaryProvider,
    file: VirtualFile,
    project: Project,
): TableTabDescriptor {
    val modules = provider.modules
    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        rowClickAction = { rowIndex ->
            val moduleRecord = modules.getOrNull(rowIndex) ?: return@TableTabDescriptor
            val buffer = provider.bytes.slice(moduleRecord.contentOffset, moduleRecord.contentSize)
            val hexedOffset = moduleRecord.contentOffset.toHexString()
            val fileName = "${file.nameWithoutExtension}_${rowIndex}@$hexedOffset.${file.extension}"
            val virtualFile = HexanaBinaryVirtualFile(fileName, buffer, baseOffset = moduleRecord.contentOffset, parentFile = file)
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        },
    )
}
