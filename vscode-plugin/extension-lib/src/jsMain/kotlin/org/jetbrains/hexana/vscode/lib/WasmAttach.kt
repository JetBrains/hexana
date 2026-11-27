@file:OptIn(ExperimentalJsExport::class)

package org.jetbrains.hexana.vscode.lib

import org.jetbrains.hexana.shared.ListeningEndpoint
import org.jetbrains.hexana.shared.ListeningPorts
import org.jetbrains.hexana.shared.WasmAttachTarget
import org.jetbrains.hexana.shared.detectWasmAttachTarget
import org.jetbrains.hexana.shared.detectWasmAttachTargetFromCommandLine

// ── Debugger attach exports ───────────────────────────────────────────────────
//
// Thin wrappers around the shared [WasmAttachTarget] detection and
// [ListeningPorts] parsing, so the VS Code attach picker recognizes exactly the
// processes IntelliJ's Attach to Process dialog does.

/**
 * A running process a `wasm` debug session can attach to.
 *
 * [runtime] is the debug-configuration runtime id (`wasmtime`, `wamr`,
 * `nodejs`, `browser`). [host] is null for browsers, which are always reached
 * on loopback. A zero [port] ([needsPortDiscovery]) means the process let the
 * OS choose it: find it among the process's listening sockets (and, for a
 * browser, in `<userDataDir>/DevToolsActivePort`). [wasmPathCandidates] lists
 * where the module may be, longest first; take the first that exists.
 */
@JsExport
class JsWasmAttachTarget(
    val runtime: String,
    val host: String?,
    val port: Int,
    val userDataDir: String?,
    val wasmPath: String?,
    val wasmPathCandidates: Array<String>,
    val needsPortDiscovery: Boolean,
)

/**
 * Recognizes a Wasm debug target from a full process command line; null when
 * there is none. [windows] selects Windows tokenizing; [executable], when
 * known, is cut off the start of the line verbatim (it may contain spaces).
 */
@JsExport
fun detectAttachTarget(commandLine: String, windows: Boolean, executable: String?): JsWasmAttachTarget? =
    detectWasmAttachTargetFromCommandLine(commandLine, windows, executable)?.toJs()

/** Recognizes a Wasm debug target from an exact argument list (Linux `/proc/<pid>/cmdline`). */
@JsExport
fun detectAttachTargetFromArgs(executable: String, args: Array<String>): JsWasmAttachTarget? =
    detectWasmAttachTarget(executable, args.toList())?.toJs()

@JsExport
class JsListeningEndpoint(val host: String, val port: Int)

@JsExport
fun lsofListeningArguments(pid: Int): Array<String> = ListeningPorts.lsofArguments(pid).toTypedArray()

@JsExport
fun parseLsofListening(output: String): Array<JsListeningEndpoint> = ListeningPorts.parseLsof(output).toJs()

@JsExport
fun procSocketInode(linkTarget: String): String? = ListeningPorts.socketInode(linkTarget)

@JsExport
fun parseProcNetTcpListening(content: String, socketInodes: Array<String>): Array<JsListeningEndpoint> =
    ListeningPorts.parseProcNetTcp(content, socketInodes.toSet()).toJs()

@JsExport
fun netstatListeningArguments(): Array<String> = ListeningPorts.NETSTAT_ARGUMENTS.toTypedArray()

@JsExport
fun parseNetstatListening(output: String, pid: Int): Array<JsListeningEndpoint> =
    ListeningPorts.parseNetstat(output, pid).toJs()

private fun List<ListeningEndpoint>.toJs(): Array<JsListeningEndpoint> =
    map { JsListeningEndpoint(it.host, it.port) }.toTypedArray()

private fun WasmAttachTarget.toJs(): JsWasmAttachTarget {
    val (host, port, userDataDir) = when (this) {
        is WasmAttachTarget.GdbStub -> Triple(host, port, null)
        is WasmAttachTarget.NodeInspector -> Triple(host, port, null)
        is WasmAttachTarget.ChromeDevTools -> Triple(null, port, userDataDir)
    }
    return JsWasmAttachTarget(
        runtime = runtime.id,
        host = host,
        port = port,
        userDataDir = userDataDir,
        wasmPath = wasmPath,
        wasmPathCandidates = wasmPathCandidates.toTypedArray(),
        needsPortDiscovery = needsPortDiscovery,
    )
}
