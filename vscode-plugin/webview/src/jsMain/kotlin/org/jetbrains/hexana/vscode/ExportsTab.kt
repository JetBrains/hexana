package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.compose.web.dom.*
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.WasmComponentBinaryProvider
import org.jetbrains.hexana.shared.*
import org.jetbrains.hexana.wasm.components.*
import org.jetbrains.hexana.wasm.sections.*

@Composable
fun ExportsTabContent(
    buffer: CommonByteBuffer,
    providerType: BinaryProviderType,
    selection: HexSelectionState,
    wasmBytes: ByteArray? = null,
    fileName: String = "",
) {
    val showRunButtons = !isSubmoduleFileName(fileName)
    when (providerType) {
        is BinaryProviderType.Wasm -> CoreWasmExports(buffer, selection, wasmBytes, showRunButtons)
        is BinaryProviderType.WasmComponent -> ComponentExports(buffer, selection, wasmBytes, showRunButtons)
        is BinaryProviderType.Generic -> Div { Text("Not a recognized WebAssembly binary.") }
    }
}

@Composable
private fun CoreWasmExports(buffer: CommonByteBuffer, selection: HexSelectionState, wasmBytes: ByteArray? = null, showRunButtons: Boolean = true) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse core Wasm module.") }
        return
    }

    val exports = remember(provider) { provider.exports }
    val functions = remember(provider) { provider.functions }
    val importFuncCount = remember(provider) { provider.importFuncCount }

    if (exports.isEmpty()) {
        Div({ classes("empty-hint") }) {
            Text("No exports found.")
        }
        return
    }

    // Dialog state: which export is pending argument input
    var pendingExport by remember { mutableStateOf<WasmFunctionExportRecordEntry?>(null) }
    var pendingParams by remember { mutableStateOf<List<WasmValueType<*>>>(emptyList()) }

    // Show the function args dialog when a function with params is clicked
    val pending = pendingExport
    if (pending != null && pendingParams.isNotEmpty()) {
        FunctionArgsDialog(
            exportName = pending.name,
            args = wasmArgDescriptors(pendingParams),
            isComponent = false,
            onConfirm = { result ->
                val programArgs = result.args.joinToString(" ")
                postRunExportToHost(
                    exportName = pending.name,
                    invokeArgs = "--invoke ${pending.name}",
                    programArgs = programArgs,
                    wasmBytes = wasmBytes,
                    runtime = result.runtime,
                    action = result.action,
                )
                pendingExport = null
                pendingParams = emptyList()
            },
            onCancel = {
                pendingExport = null
                pendingParams = emptyList()
            },
        )
    }

    SearchableTable(
        headers = listOf("", "ID", "Kind", "Name", "Index", "Signature"),
        columnAligns = listOf("center", "right", "left", "left", "right", "left"),
    ) {
        for ((index, export) in exports.withIndex()) {
            val kind = exportKindLabel(export)
            val sig = if (export is WasmFunctionExportRecordEntry) {
                resolveCoreSignature(export, functions, importFuncCount)
            } else ""

            val isRunnable = showRunButtons && export is WasmFunctionExportRecordEntry && !isInternalName(export.name)

            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == export.offset &&
                    selection.selectionEnd == export.offset + export.size - 1,
                onClick = {
                    selection.anchorIndex = export.offset
                    selection.activeIndex = export.offset + export.size - 1
                    selection.cursorIndex = export.offset
                    scrollHexViewerTo(export.offset)
                },
            ) {
                Td({ classes("cell-id"); style { property("width", "12px") } }) {
                    if (isRunnable) {
                        RunArrowButton(
                            tooltip = "Run '${export.name}'",
                            onClick = {
                                val funcExport = export as WasmFunctionExportRecordEntry
                                val signature = resolveCoreExportSignature(funcExport, functions, importFuncCount)
                                val params = signature?.params ?: emptyList()

                                if (params.isNotEmpty() && params.all { isTrivialType(it) }) {
                                    pendingExport = funcExport
                                    pendingParams = params
                                } else {
                                    postRunExportToHost(
                                        exportName = export.name,
                                        invokeArgs = "--invoke ${export.name}",
                                        wasmBytes = wasmBytes,
                                    )
                                }
                            }
                        )
                    }
                }
                Td({ classes("cell-id") }) {
                    Text(index.toString())
                }
                Td({ classes("cell") }) { Text(kind) }
                Td({ classes("cell") }) { Text(export.name) }
                Td({ classes("cell-right") }) {
                    Text(exportIndex(export))
                }
                Td({ classes("cell-muted") }) { Text(sig) }
            }
        }
    }
}

@Composable
private fun ComponentExports(buffer: CommonByteBuffer, selection: HexSelectionState, wasmBytes: ByteArray? = null, showRunButtons: Boolean = true) {
    val provider = remember(buffer) {
        buffer.position(0)
        WasmComponentBinaryProvider(buffer)
    }

    if (!provider.canProvideWasmModule()) {
        Div { Text("Failed to parse component-model binary.") }
        return
    }

    val exports = remember(provider) { provider.exports }
    val types = remember(provider) { provider.types }

    if (exports.isEmpty()) {
        Div({ classes("empty-hint") }) {
            Text("No exports found.")
        }
        return
    }

    // Dialog state for wasi:cli/run program args
    var pendingProgramArgsExport by remember { mutableStateOf<WasmComponentExport?>(null) }

    // Dialog state for component function args
    var pendingFuncExport by remember { mutableStateOf<WasmComponentExport?>(null) }
    var pendingFuncInvokeName by remember { mutableStateOf("") }
    var pendingFuncArgs by remember { mutableStateOf<List<FunctionArgDescriptor>>(emptyList()) }

    // Show program args dialog for wasi:cli/run exports
    val programArgsPending = pendingProgramArgsExport
    if (programArgsPending != null) {
        ProgramArgsDialog(
            exportName = programArgsPending.exportName,
            isComponent = true,
            onConfirm = { result ->
                postRunExportToHost(
                    exportName = programArgsPending.exportName,
                    isComponent = true,
                    programArgs = result.programArgs,
                    wasmBytes = wasmBytes,
                    runtime = result.runtime,
                    action = result.action,
                )
                pendingProgramArgsExport = null
            },
            onCancel = { pendingProgramArgsExport = null },
        )
    }

    // Show function args dialog for component function exports
    val funcPending = pendingFuncExport
    if (funcPending != null && pendingFuncArgs.isNotEmpty()) {
        FunctionArgsDialog(
            exportName = pendingFuncInvokeName,
            args = pendingFuncArgs,
            isComponent = true,
            onConfirm = { result ->
                val argsString = result.args.joinToString(", ")
                postRunExportToHost(
                    exportName = funcPending.exportName,
                    isComponent = true,
                    invokeArgs = "--invoke \"${pendingFuncInvokeName}($argsString)\"",
                    wasmBytes = wasmBytes,
                    runtime = result.runtime,
                    action = result.action,
                )
                pendingFuncExport = null
                pendingFuncInvokeName = ""
                pendingFuncArgs = emptyList()
            },
            onCancel = {
                pendingFuncExport = null
                pendingFuncInvokeName = ""
                pendingFuncArgs = emptyList()
            },
        )
    }

    SearchableTable(
        headers = listOf("", "ID", "Name", "Sort Index", "Description"),
        columnAligns = listOf("center", "right", "left", "left", "left"),
    ) {
        for ((index, export) in exports.withIndex()) {
            val isWasiCliRun = isWasiCliRunExportName(export.exportName)
            val isFunctionExport = isComponentFunctionExport(export, provider)
            val isRunnable = showRunButtons && (isWasiCliRun || isFunctionExport)

            PanelTableRow(
                isHighlighted = selection.hasSelection &&
                    selection.selectionStart == export.offset &&
                    selection.selectionEnd == export.offset + export.size - 1,
                onClick = {
                    selection.anchorIndex = export.offset
                    selection.activeIndex = export.offset + export.size - 1
                    selection.cursorIndex = export.offset
                    scrollHexViewerTo(export.offset)
                },
            ) {
                Td({ classes("cell-id"); style { property("width", "12px") } }) {
                    if (isRunnable) {
                        RunArrowButton(
                            tooltip = "Run '${export.exportName}'",
                            onClick = {
                                if (isWasiCliRun) {
                                    pendingProgramArgsExport = export
                                } else {
                                    val invokeName = resolveInvokeName(export, provider)
                                    val funcType = resolveComponentFunctionType(export, provider)
                                    val params = funcType?.params ?: emptyList()

                                    if (params.isNotEmpty() && params.all { isTrivialComponentType(it.value, types) }) {
                                        pendingFuncExport = export
                                        pendingFuncInvokeName = invokeName
                                        pendingFuncArgs = componentArgDescriptors(params, types)
                                    } else {
                                        postRunExportToHost(
                                            exportName = export.exportName,
                                            isComponent = true,
                                            invokeArgs = if (!isWasiCliRun) "--invoke \"$invokeName\"" else "",
                                            wasmBytes = wasmBytes,
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
                Td({ classes("cell-id") }) {
                    Text(index.toString())
                }
                Td({ classes("cell") }) {
                    Text(export.exportName + (export.version?.let { " $it" } ?: ""))
                }
                Td({ classes("cell") }) {
                    Text(componentSortIndexLabel(export.sortIndex))
                }
                Td({ classes("cell-muted") }) {
                    Text(export.externDescription?.let { componentExternDescriptionLabel(it) } ?: "")
                }
            }
        }
    }
}

/**
 * Resolves the function type record for a core Wasm function export.
 */
private fun resolveCoreExportSignature(
    export: WasmFunctionExportRecordEntry,
    functions: List<WasmTypeRecord>,
    importFuncCount: Int,
): WasmSectionFunctionTypeRecord? {
    val localIndex = export.index.toInt() - importFuncCount
    if (localIndex < 0) return null
    return functions.getOrNull(localIndex) as? WasmSectionFunctionTypeRecord
}

/**
 * Builds argument descriptors for core Wasm function parameters.
 */
private fun wasmArgDescriptors(params: List<WasmValueType<*>>): List<FunctionArgDescriptor> =
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
        is WasmI32Type -> if (text.toIntOrNull() == null) "Expected an i32 integer" else null
        is WasmI64Type -> if (text.toLongOrNull() == null) "Expected an i64 integer" else null
        is WasmF32Type -> if (text.toFloatOrNull() == null) "Expected an f32 number" else null
        is WasmF64Type -> if (text.toDoubleOrNull() == null) "Expected an f64 number" else null
        else -> null
    }
}

/**
 * Builds argument descriptors for component-model function parameters.
 */
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

/**
 * Small play-arrow button used as a run indicator in table rows.
 * Uses a Unicode play triangle (▶) styled to match VS Code's green run icon.
 */
@Composable
internal fun RunArrowButton(tooltip: String = "Run", onClick: () -> Unit) {
    Span({
        classes("hexana-run-arrow")
        attr("title", tooltip)
        style {
            property("cursor", "pointer")
            property("display", "inline-flex")
            property("align-items", "center")
            property("justify-content", "center")
            property("width", "16px")
            property("height", "16px")
            property("border-radius", "3px")
            property("font-size", "12px")
            property("color", "var(--vscode-debugIcon-startForeground, #89d185)")
        }
        onClick { event ->
            event.stopPropagation()
            onClick()
        }
    }) {
        Text("\u25B6")
    }
}

private fun validateComponentArg(text: String, type: ComponentValueType, types: List<ComponentDefType>): String? {
    if (text.isBlank()) return "Value is required"
    if (type is ComponentValueIdxType) {
        val resolved = types.getOrNull(type.idx.toInt())
        if (resolved is ComponentEnumType) {
            return if (text !in resolved.entries) "Expected one of: ${resolved.entries.joinToString(", ")}" else null
        }
        return null
    }
    if (type !is ComponentPrimitiveValueType) return null
    return when (type.type) {
        is ComponentPrimitiveBoolType -> if (text != "true" && text != "false") "Expected 'true' or 'false'" else null
        is ComponentPrimitiveU8Type -> if (text.toIntOrNull()?.let { it in 0..255 } != true) "Expected a u8 integer (0..255)" else null
        is ComponentPrimitiveS8Type -> if (text.toIntOrNull()?.let { it in -128..127 } != true) "Expected an s8 integer (-128..127)" else null
        is ComponentPrimitiveU16Type -> if (text.toIntOrNull()?.let { it in 0..65535 } != true) "Expected a u16 integer (0..65535)" else null
        is ComponentPrimitiveS16Type -> if (text.toIntOrNull()?.let { it in -32768..32767 } != true) "Expected an s16 integer (-32768..32767)" else null
        is ComponentPrimitiveU32Type -> if (text.toLongOrNull()?.let { it in 0..4294967295L } != true) "Expected a u32 integer" else null
        is ComponentPrimitiveS32Type -> if (text.toIntOrNull() == null) "Expected an s32 integer" else null
        is ComponentPrimitiveU64Type -> if (text.toULongOrNull() == null) "Expected a u64 integer" else null
        is ComponentPrimitiveS64Type -> if (text.toLongOrNull() == null) "Expected an s64 integer" else null
        is ComponentPrimitiveF32Type -> if (text.toFloatOrNull() == null) "Expected an f32 number" else null
        is ComponentPrimitiveF64Type -> if (text.toDoubleOrNull() == null) "Expected an f64 number" else null
        is ComponentPrimitiveCharType -> if (text.length != 1) "Expected a single character" else null
        is ComponentPrimitiveString -> null
        else -> null
    }
}
