package org.jetbrains.hexana.ui

import com.intellij.openapi.util.Key

/**
 * Navigation target handed to [WasmPanelWatVirtualized] when an external
 * view (e.g. the JIT inline-frame click) wants the WASM editor to scroll
 * to a specific function's body.
 *
 * Producers stash an instance in [WASM_FUNCTION_NAV_TARGET_KEY] on the
 * [com.intellij.openapi.vfs.VirtualFile] before opening, or push it via
 * [org.jetbrains.hexana.HexanaFileEditor.requestWasmFunctionNav] for
 * editors that are already open.
 *
 * [funcIdx] is the global function index (0-based, including imports). The
 * WAT virtualized panel delegates to [WasmWatNavigator.gotoFunc], which
 * handles imported functions (redirects to the Import section entry) and
 * out-of-range indices (no-op).
 */
data class WasmFunctionNavTarget(val funcIdx: Int)

val WASM_FUNCTION_NAV_TARGET_KEY: Key<WasmFunctionNavTarget> =
    Key.create("hexana.wasm.functionNavTarget")
