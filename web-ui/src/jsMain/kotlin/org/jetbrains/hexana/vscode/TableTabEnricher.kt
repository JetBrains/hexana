package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.shared.ProblemNavigator
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.*
import org.jetbrains.hexana.shared.inspections.selectionIn
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
    val graalInfo = provider.graalWebImageInfo
    return map { tab ->
        when (tab.name) {
            "Exports" -> enrichWasmExportsTab(tab, provider, showRunButtons, host, onPendingWasmArgs)
            "Functions" -> if (graalInfo.canResolveSymbols) {
                enrichWasmFunctionsTab(tab, provider)
            } else tab
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
 * Enriches the Functions tab for GraalVM Web Image modules: adds a "Java" row action
 * that displays the demangled Java symbol for each function.
 *
 * The [RowActionDescriptor] lambdas are called per rendered row (lazy), so no
 * up-front O(n) demangle pass occurs at enrichment time. [provider.graalSymbolFor]
 * itself is O(1) per call (name-section lookup + demangler).
 *
 * Synthetic symbols (lambdas, compiler-generated stubs) do not get a tooltip or
 * action, mirroring the IntelliJ enricher's `!symbol.isSynthetic` guard.
 */
private fun enrichWasmFunctionsTab(
    tab: TableTabDescriptor,
    provider: WasmBinaryProvider,
): TableTabDescriptor {
    val importFuncCount = provider.importFuncCount

    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        defaultSortColumn = tab.defaultSortColumn,
        defaultSortAscending = tab.defaultSortAscending,
        rowSelectionProvider = tab.rowSelectionProvider,
        rowAction = RowActionDescriptor(
            label = "Java",
            tooltipProvider = { index ->
                val symbol = provider.graalSymbolFor(importFuncCount + index)
                if (symbol != null && !symbol.isSynthetic) symbol.display() else null
            },
            action = { /* no navigation target on the web side */ },
        ),
    )
}

/**
 * How a Problems row navigates in the webview, mirroring IntelliJ: a problem inside a Wasm
 * function switches to the WAT tab at that function; anything else with a position
 * selects its bytes in the hex viewer, which is what the bridge does for a row without a
 * click action. A runtime-compatibility problem selects the Compatibility tab instead.
 */
internal fun problemNavigator(
    provider: BinaryProvider,
    selection: HexSelectionState,
    onSelectTab: (String) -> Unit,
    onNavigateToWatFunc: (Int) -> Unit,
): ProblemNavigator = ProblemNavigator(
    selectTab = onSelectTab,
    open = { problem ->
        val funcIdx = problem.funcIdx
        val bytes = problem.byteRange?.selectionIn(provider.bytes.size)
        if (funcIdx != null && provider is WasmBinaryProvider) {
            onNavigateToWatFunc(funcIdx)
        } else if (bytes != null) {
            selection.anchorIndex = bytes.offset
            selection.activeIndex = bytes.offset + bytes.size - 1
            selection.cursorIndex = bytes.offset
            scrollHexViewerTo(bytes.offset)
        }
    },
)

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
            format = { text -> formatComponentArgWave(text, param.value) },
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
    val primitive = (type as? org.jetbrains.hexana.wasm.components.ComponentPrimitiveValueType)?.type
    // Char and string params accept whitespace-only values, so they are
    // checked before the generic blank rejection. Any text is a valid
    // string; quoting for the WAVE invoke expression happens downstream
    // in formatComponentArgWave.
    if (primitive is org.jetbrains.hexana.wasm.components.ComponentPrimitiveString) return null
    if (primitive is org.jetbrains.hexana.wasm.components.ComponentPrimitiveCharType) {
        return if (isSingleUnicodeCharacter(text)) null else "Expected a single character"
    }
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
    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        rowClickAction = { rowIndex ->
            val moduleRecord = modules.getOrNull(rowIndex) ?: return@TableTabDescriptor
            val moduleBytes = provider.submoduleBytes(moduleRecord.contentOffset, moduleRecord.contentSize)
                ?: return@TableTabDescriptor
            host.openModule(
                rowIndex,
                submoduleFileName(fileName, rowIndex, moduleRecord.contentOffset, isComponent = false),
                moduleBytes,
            )
        },
    )
}

private fun enrichComponentNestedComponentsTab(
    tab: TableTabDescriptor,
    provider: WasmComponentBinaryProvider,
    fileName: String,
    host: Host,
): TableTabDescriptor {
    val components = provider.nestedComponents
    return TableTabDescriptor(
        name = tab.name,
        columns = tab.columns,
        rowCount = tab.rowCount,
        rowClickAction = { rowIndex ->
            val section = components.getOrNull(rowIndex)?.section ?: return@TableTabDescriptor
            val componentBytes = provider.submoduleBytes(section.contentOffset, section.contentSize)
                ?: return@TableTabDescriptor
            host.openModule(
                rowIndex,
                submoduleFileName(fileName, rowIndex, section.contentOffset, isComponent = true),
                componentBytes,
            )
        },
    )
}
