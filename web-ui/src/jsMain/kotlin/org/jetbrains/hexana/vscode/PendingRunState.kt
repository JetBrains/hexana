package org.jetbrains.hexana.vscode

import androidx.compose.runtime.*
import org.jetbrains.hexana.wasm.components.WasmComponentExport
import org.jetbrains.hexana.wasm.sections.WasmFunctionExportRecordEntry
import org.jetbrains.hexana.wasm.sections.WasmValueType

/**
 * Holds the dialog state for all pending run actions triggered by table-tab
 * enrichers. Extracted from [HexanaPanel] so the six related mutable
 * properties live in one cohesive object.
 */
@Stable
class PendingRunState {
    // -- Core Wasm --------------------------------------------------------
    var wasmExport: WasmFunctionExportRecordEntry? by mutableStateOf(null)
        private set
    var wasmParams: List<WasmValueType<*>> by mutableStateOf(emptyList())
        private set

    // -- Component model --------------------------------------------------
    var programArgsExport: WasmComponentExport? by mutableStateOf(null)
        private set
    var funcExport: WasmComponentExport? by mutableStateOf(null)
        private set
    var funcInvokeName: String by mutableStateOf("")
        private set
    var funcArgs: List<FunctionArgDescriptor> by mutableStateOf(emptyList())
        private set

    // -- Mutators ---------------------------------------------------------

    fun requestWasmArgs(export: WasmFunctionExportRecordEntry, params: List<WasmValueType<*>>) {
        wasmExport = export
        wasmParams = params
    }

    fun clearWasm() {
        wasmExport = null
        wasmParams = emptyList()
    }

    fun requestProgramArgs(export: WasmComponentExport) {
        programArgsExport = export
    }

    fun clearProgramArgs() {
        programArgsExport = null
    }

    fun requestComponentArgs(
        invokeName: String,
        args: List<FunctionArgDescriptor>,
        export: WasmComponentExport,
    ) {
        funcExport = export
        funcInvokeName = invokeName
        funcArgs = args
    }

    fun clearComponent() {
        funcExport = null
        funcInvokeName = ""
        funcArgs = emptyList()
    }
}

@Composable
fun rememberPendingRunState(): PendingRunState = remember { PendingRunState() }
