package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.*
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmValueType


fun BinaryProvider.getTabsEnricher(host: Host): List<TableTabDescriptor>.(
        fileName: String,
        pendingRun: PendingRunState,
    ) -> List<TableTabDescriptor> {
    return when (val provider = this) {
        is WasmBinaryProvider -> {name, run -> enrichWasmTabs(provider, name, host, run::requestWasmArgs)}
        is WasmComponentBinaryProvider -> {name, run -> enrichComponentTabs(provider, name, host, run::requestProgramArgs, run::requestComponentArgs)}
        else -> {_, _ -> this}
    }
}

/**
 * Enriches [TableTabDescriptor]s produced by [WasmTableTabsProvider] with
 * VS Code-specific actions: run buttons on exported functions.
 *
 * Run actions post a message to the extension host. When a function has
 * trivial parameters, [onPendingWasmArgs] is invoked so the composable layer
 * can show a dialog to collect argument values before posting.
 *
 * @param onPendingWasmArgs callback invoked when a core Wasm export needs
 *   argument input; receives the export entry and its parameter types
 */
internal fun List<TableTabDescriptor>.enrichWasmTabs(
    provider: WasmBinaryProvider,
    fileName: String,
    host: Host,
    onPendingWasmArgs: (WasmFunctionExportRecordEntry, List<WasmValueType<*>>) -> Unit,
): List<TableTabDescriptor> {
    val showRunButtons = !isSubmoduleFileName(fileName) && host.canRunExport
    return map { tab ->
        when (tab.name) {
            "Exports" -> enrichWasmExportsTab(tab, provider, showRunButtons, host, onPendingWasmArgs)
            else -> tab
        }
    }
}

/**
 * Enriches [TableTabDescriptor]s produced by [WasmComponentTableTabsProvider]
 * with VS Code-specific actions: run buttons on exports, and open-in-editor
 * click on modules and nested components.
 *
 * @param onPendingProgramArgs callback when a wasi:cli/run export is clicked
 * @param onPendingComponentArgs callback when a component function export with
 *   trivial params is clicked; receives the invoke name and arg descriptors
 */
internal fun List<TableTabDescriptor>.enrichComponentTabs(
    provider: WasmComponentBinaryProvider,
    fileName: String,
    host: Host,
    onPendingProgramArgs: (org.jetbrains.hexana.wasm.components.WasmComponentExport) -> Unit,
    onPendingComponentArgs: (String, List<FunctionArgDescriptor>, org.jetbrains.hexana.wasm.components.WasmComponentExport) -> Unit,
): List<TableTabDescriptor> {
    val showRunButtons = !isSubmoduleFileName(fileName) && host.canRunExport
    return map { tab ->
        when (tab.name) {
            "Exports" -> enrichComponentExportsTab(tab, provider, showRunButtons, host, onPendingProgramArgs, onPendingComponentArgs)
            "Modules" -> enrichComponentModulesTab(tab, provider, fileName, host)
            "Components" -> enrichComponentNestedComponentsTab(tab, provider, fileName, host)
            else -> tab
        }
    }
}

// ---------------------------------------------------------------------------
// Wasm Exports enrichment
// ---------------------------------------------------------------------------

private fun enrichWasmExportsTab(
    tab: TableTabDescriptor,
    provider: WasmBinaryProvider,
    showRunButtons: Boolean,
    host: Host,
    onPendingWasmArgs: (WasmFunctionExportRecordEntry, List<WasmValueType<*>>) -> Unit,
): TableTabDescriptor {
    if (!showRunButtons) return tab

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
                if (export is WasmFunctionExportRecordEntry && !isInternalName(export.name)) {
                    val signature = resolveFunctionSignature(provider, export)
                    val params = signature?.params ?: emptyList()

                    if (params.isEmpty() || params.all { isTrivialType(it) }) {
                        onPendingWasmArgs(export, params)
                    } else {
                        host.runExport(
                            exportName = export.name,
                            invokeArgs = "--invoke ${export.name}",
                            programArgs = "",
                            isComponent = false,
                            wasmBytes = provider.bytes.toByteArray(),
                            runtime = null,
                            action = null,
                        )
                    }
                }
            },
        ),
    )
}

/**
 * Enriches the Problems tab with VS Code navigation, mirroring the IntelliJ
 * behavior: a row with a funcIdx switches to the WAT tab and scrolls to the
 * function; other rows fall back to selecting the failed byte region in the
 * hex viewer.
 */
internal fun enrichProblemsTab(
    tab: TableTabDescriptor,
    provider: WasmBinaryProvider,
    selection: HexSelectionState,
    onNavigateToWatFunc: (Int) -> Unit,
): TableTabDescriptor {
    val problems = provider.parseProblems

    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        defaultSortColumn = tab.defaultSortColumn,
        defaultSortAscending = tab.defaultSortAscending,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowClickAction = { index ->
            val funcIdx = problems.getOrNull(index)?.funcIdx
            if (funcIdx != null) {
                onNavigateToWatFunc(funcIdx)
            } else {
                // Same behavior as the bridge's default (no rowClickAction) path.
                val selData = tab.rowSelectionProvider(index)
                if (selData != null) {
                    selection.anchorIndex = selData.offset
                    selection.activeIndex = selData.offset + selData.size - 1
                    selection.cursorIndex = selData.offset
                    scrollHexViewerTo(selData.offset)
                }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// Component Exports enrichment
// ---------------------------------------------------------------------------

private fun enrichComponentExportsTab(
    tab: TableTabDescriptor,
    provider: WasmComponentBinaryProvider,
    showRunButtons: Boolean,
    host: Host,
    onPendingProgramArgs: (org.jetbrains.hexana.wasm.components.WasmComponentExport) -> Unit,
    onPendingComponentArgs: (String, List<FunctionArgDescriptor>, org.jetbrains.hexana.wasm.components.WasmComponentExport) -> Unit,
): TableTabDescriptor {
    if (!showRunButtons) return tab

    val exports = provider.exports
    val types = provider.types

    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowAction = RowActionDescriptor(
            label = "Run",
            tooltipProvider = { index ->
                val export = exports.getOrNull(index) ?: return@RowActionDescriptor null
                if (isWasiCliRunExportName(export.exportName) || isComponentFunctionExport(export, provider)) {
                    "Run '${export.exportName}'"
                } else null
            },
            action = { index ->
                val export = exports.getOrNull(index) ?: return@RowActionDescriptor
                val isWasiCliRun = isWasiCliRunExportName(export.exportName)
                if (isWasiCliRun) {
                    onPendingProgramArgs(export)
                } else if (isComponentFunctionExport(export, provider)) {
                    val invokeName = resolveInvokeName(export, provider)
                    val funcType = resolveComponentFunctionType(export, provider)
                    val params = funcType?.params ?: emptyList()

                    if (params.isEmpty() || params.all { isTrivialComponentType(it.value, types) }) {
                        val argDescriptors = componentArgDescriptors(params, types)
                        onPendingComponentArgs(invokeName, argDescriptors, export)
                    } else {
                        host.runExport(
                            exportName = export.exportName,
                            invokeArgs = "--invoke \"$invokeName\"",
                            programArgs = "",
                            isComponent = true,
                            wasmBytes = provider.bytes.toByteArray(),
                            runtime = null,
                            action = null,
                        )
                    }
                }
            },
        ),
    )
}

/**
 * Builds argument descriptors for core Wasm function parameters.
 */
internal fun wasmArgDescriptors(params: List<WasmValueType<*>>): List<FunctionArgDescriptor> =
    params.mapIndexed { i, param ->
        FunctionArgDescriptor(
            label = "arg$i",
            typeName = wasmValueTypeName(param),
            validate = { text -> validateWasmArg(text, param) },
        )
    }

private fun validateWasmArg(text: String, type: WasmValueType<*>): String? {
    if (text.isBlank()) return "Value is required"
    return when (type) {
        is org.jetbrains.hexana.wasm.sections.WasmI32Type -> if (text.toIntOrNull() == null) "Expected an i32 integer" else null
        is org.jetbrains.hexana.wasm.sections.WasmI64Type -> if (text.toLongOrNull() == null) "Expected an i64 integer" else null
        is org.jetbrains.hexana.wasm.sections.WasmF32Type -> if (text.toFloatOrNull() == null) "Expected an f32 number" else null
        is org.jetbrains.hexana.wasm.sections.WasmF64Type -> if (text.toDoubleOrNull() == null) "Expected an f64 number" else null
        else -> null
    }
}

/**
 * Builds argument descriptors for component-model function parameters.
 */
private fun componentArgDescriptors(
    params: List<org.jetbrains.hexana.wasm.components.LabelValueType>,
    types: List<org.jetbrains.hexana.wasm.components.ComponentDefType>,
): List<FunctionArgDescriptor> =
    params.map { param ->
        val resolved = resolveComponentValueType(param.value, types)
        val enumEntries = (resolved as? org.jetbrains.hexana.wasm.components.ComponentEnumType)?.entries
        FunctionArgDescriptor(
            label = param.label,
            typeName = componentTypeName(param.value, types),
            defaultValue = if (enumEntries != null) enumEntries.firstOrNull() ?: "" else "0",
            enumEntries = enumEntries,
            validate = { text -> validateComponentArg(text, param.value, types) },
        )
    }

/**
 * Validates a component-model argument value.
 */
private fun validateComponentArg(
    text: String,
    type: org.jetbrains.hexana.wasm.components.ComponentValueType,
    types: List<org.jetbrains.hexana.wasm.components.ComponentDefType>,
): String? {
    if (text.isBlank()) return "Value is required"
    if (type is org.jetbrains.hexana.wasm.components.ComponentValueIdxType) {
        val resolved = types.getOrNull(type.idx.toInt())
        if (resolved is org.jetbrains.hexana.wasm.components.ComponentEnumType) {
            return if (text !in resolved.entries) "Expected one of: ${resolved.entries.joinToString(", ")}" else null
        }
        return null
    }
    if (type !is org.jetbrains.hexana.wasm.components.ComponentPrimitiveValueType) return null
    return when (type.type) {
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveBoolType -> if (text != "true" && text != "false") "Expected 'true' or 'false'" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveU8Type -> if (text.toIntOrNull()?.let { it in 0..255 } != true) "Expected a u8 integer (0..255)" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveS8Type -> if (text.toIntOrNull()?.let { it in -128..127 } != true) "Expected an s8 integer (-128..127)" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveU16Type -> if (text.toIntOrNull()?.let { it in 0..65535 } != true) "Expected a u16 integer (0..65535)" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveS16Type -> if (text.toIntOrNull()?.let { it in -32768..32767 } != true) "Expected an s16 integer (-32768..32767)" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveU32Type -> if (text.toLongOrNull()?.let { it in 0..4294967295L } != true) "Expected a u32 integer" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveS32Type -> if (text.toIntOrNull() == null) "Expected an s32 integer" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveU64Type -> if (text.toULongOrNull() == null) "Expected a u64 integer" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveS64Type -> if (text.toLongOrNull() == null) "Expected an s64 integer" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveF32Type -> if (text.toFloatOrNull() == null) "Expected an f32 number" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveF64Type -> if (text.toDoubleOrNull() == null) "Expected an f64 number" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveCharType -> if (text.length != 1) "Expected a single character" else null
        is org.jetbrains.hexana.wasm.components.ComponentPrimitiveString -> null
        else -> null
    }
}

// ---------------------------------------------------------------------------
// Component Modules enrichment
// ---------------------------------------------------------------------------

private fun enrichComponentModulesTab(
    tab: TableTabDescriptor,
    provider: WasmComponentBinaryProvider,
    fileName: String,
    host: Host,
): TableTabDescriptor {
    val modules = provider.modules
    val baseName = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBeforeLast('.')
    val extension = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .let { name ->
            val dot = name.lastIndexOf('.')
            if (dot >= 0) name.substring(dot + 1) else "wasm"
        }

    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        rowClickAction = { rowIndex ->
            val moduleRecord = modules.getOrNull(rowIndex) ?: return@TableTabDescriptor
            val hexOffset = moduleRecord.contentOffset.toString(16)
            val moduleFileName = "${baseName}_${rowIndex}@${hexOffset}.$extension"
            val moduleBytes = ByteArray(moduleRecord.contentSize)
            val buffer = provider.bytes
            buffer.position(moduleRecord.contentOffset)
            buffer.get(moduleBytes, 0, moduleRecord.contentSize)
            buffer.position(0)
            host.openModule(rowIndex, moduleFileName, moduleBytes)
        },
    )
}

private fun enrichComponentNestedComponentsTab(
    tab: TableTabDescriptor,
    provider: WasmComponentBinaryProvider,
    fileName: String,
): TableTabDescriptor {
    val section = provider.wasmModule?.componentSection ?: return tab
    val baseName = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBeforeLast('.')
    val extension = fileName
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .let { name ->
            val dot = name.lastIndexOf('.')
            if (dot >= 0) name.substring(dot + 1) else "wasm"
        }

    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        rowClickAction = { rowIndex ->
            val hexOffset = section.contentOffset.toString(16)
            val componentFileName = "${baseName}_component_${rowIndex}@${hexOffset}.$extension"
            val componentBytes = ByteArray(section.contentSize)
            val buffer = provider.bytes
            buffer.position(section.contentOffset)
            buffer.get(componentBytes, 0, section.contentSize)
            buffer.position(0)
            postOpenModuleToHost(rowIndex, componentFileName, componentBytes)
        },
    )
}
