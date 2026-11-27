@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.WasmAttachTarget
import org.jetbrains.hexana.shared.detectWasmAttachTargetFromCommandLine

// ── Debugger attach exports ───────────────────────────────────────────────────
//
// Thin wrapper around the shared [WasmAttachTarget] detection so the VS Code
// attach picker recognizes exactly the processes IntelliJ's Attach to Process
// dialog does.

/**
 * A running process a `wasm` debug session can attach to.
 *
 * [runtime] is the debug-configuration runtime id (`wasmtime`, `wamr`,
 * `nodejs`, `browser`). [host] is null for browsers, which are always reached
 * on loopback. A zero [port] on a browser means it chose its own port, which
 * it writes to `<userDataDir>/DevToolsActivePort`.
 */
@JsExport
class JsWasmAttachTarget(
    val runtime: String,
    val host: String?,
    val port: Int,
    val userDataDir: String?,
    val wasmPath: String?,
)

/** Recognizes a Wasm debug target from a full process command line; null when there is none. */
@JsExport
fun detectAttachTarget(commandLine: String): JsWasmAttachTarget? =
    detectWasmAttachTargetFromCommandLine(commandLine)?.toJs()

private fun WasmAttachTarget.toJs(): JsWasmAttachTarget = when (this) {
    is WasmAttachTarget.GdbStub -> JsWasmAttachTarget(runtime.id, host, port, null, wasmPath)
    is WasmAttachTarget.NodeInspector -> JsWasmAttachTarget(runtime.id, host, port, null, wasmPath)
    is WasmAttachTarget.ChromeDevTools -> JsWasmAttachTarget(runtime.id, null, port, userDataDir, wasmPath)
}
