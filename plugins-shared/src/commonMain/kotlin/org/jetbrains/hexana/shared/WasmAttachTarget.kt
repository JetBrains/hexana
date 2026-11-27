package org.jetbrains.hexana.shared

/**
 * The runtime behind a [WasmAttachTarget]. [id] is the runtime identifier
 * VS Code debug configurations use (`"runtime": "wamr"`).
 */
enum class WasmAttachRuntime(val id: String) {
    WASMTIME("wasmtime"),
    WAMR("wamr"),
    NODE_JS("nodejs"),
    BROWSER("browser"),
}

/**
 * A running process Hexana's Wasm debuggers can attach to, recognized from
 * its command line. Detection is purely syntactic: it reads the debug
 * endpoint the process was started with and never connects to it.
 */
sealed class WasmAttachTarget {
    /** Path of the module the process runs, as written on its command line; null when unknown. */
    abstract val wasmPath: String?

    abstract val runtime: WasmAttachRuntime

    /**
     * A gdbstub served by the runtime itself: `wasmtime run -g <host>:<port>`
     * or `iwasm -g=<host>:<port>`. LLDB connects to it with
     * `process connect --plugin wasm`.
     */
    data class GdbStub(
        override val runtime: WasmAttachRuntime,
        val host: String,
        val port: Int,
        override val wasmPath: String?,
    ) : WasmAttachTarget()

    /** A Node.js process started with `--inspect`, `--inspect-brk` or `--inspect-wait`. */
    data class NodeInspector(
        val host: String,
        val port: Int,
        override val wasmPath: String?,
    ) : WasmAttachTarget() {
        override val runtime: WasmAttachRuntime get() = WasmAttachRuntime.NODE_JS
    }

    /**
     * A Chromium-based browser started with `--remote-debugging-port`. A zero
     * [port] means the browser chose one, which it writes to
     * `<userDataDir>/DevToolsActivePort`.
     */
    data class ChromeDevTools(
        val port: Int,
        val userDataDir: String?,
        override val wasmPath: String?,
    ) : WasmAttachTarget() {
        override val runtime: WasmAttachRuntime get() = WasmAttachRuntime.BROWSER
    }
}

/**
 * Recognizes a Wasm debug target from a process's executable name and
 * argument list. Returns null for processes that serve no debug endpoint
 * Hexana can reach (including endpoints on an OS-chosen port that is not
 * discoverable from outside, such as `node --inspect=0`).
 */
fun detectWasmAttachTarget(executableName: String, args: List<String>): WasmAttachTarget? {
    val name = executableName.substringAfterLast('/').substringAfterLast('\\')
        .removeSuffix(".exe").lowercase()
    return when (name) {
        "wasmtime" -> detectWasmtime(args)
        "iwasm" -> detectIwasm(args)
        "node", "nodejs" -> detectNode(args)
        else -> detectChrome(args)
    }
}

/**
 * Detects a target from a full process command line (executable first), as
 * printed by `ps` or `Win32_Process.CommandLine`. Quoting that survived the
 * listing is honored; a listing that dropped it can split paths with spaces.
 */
fun detectWasmAttachTargetFromCommandLine(commandLine: String): WasmAttachTarget? {
    val tokens = splitOptions(commandLine)
    val executable = tokens.firstOrNull() ?: return null
    return detectWasmAttachTarget(executable, tokens.drop(1))
}

/** The `host:port` a gdbstub target is reached at; null for CDP targets. */
val WasmAttachTarget.gdbStubEndpoint: String?
    get() = (this as? WasmAttachTarget.GdbStub)?.let { "${it.host}:${it.port}" }

private fun detectWasmtime(args: List<String>): WasmAttachTarget? {
    for ((index, arg) in args.withIndex()) {
        // Arguments after the module belong to the guest program.
        if (isWasmModulePath(arg)) break
        val address = when {
            arg == "-g" || arg == "--gdbstub" -> args.getOrNull(index + 1)
            arg.startsWith("--gdbstub=") -> arg.removePrefix("--gdbstub=")
            arg.startsWith("-g=") -> arg.removePrefix("-g=")
            else -> null
        } ?: continue
        val (host, port) = parseHostPort(address, defaultHost = LOOPBACK) ?: return null
        if (port == 0) return null
        return WasmAttachTarget.GdbStub(WasmAttachRuntime.WASMTIME, host, port, firstWasmModule(args))
    }
    return null
}

private fun detectIwasm(args: List<String>): WasmAttachTarget? {
    for (arg in args) {
        if (isWasmModulePath(arg)) break
        if (!arg.startsWith("-g=")) continue
        val (host, port) = parseHostPort(arg.removePrefix("-g="), defaultHost = LOOPBACK) ?: return null
        if (port == 0) return null
        return WasmAttachTarget.GdbStub(WasmAttachRuntime.WAMR, host, port, firstWasmModule(args))
    }
    return null
}

private val NODE_INSPECT_FLAGS = listOf("--inspect-brk", "--inspect-wait", "--inspect")

private fun detectNode(args: List<String>): WasmAttachTarget? {
    var inspectorEnabled = false
    var host = LOOPBACK
    var port = NODE_DEFAULT_INSPECT_PORT
    var index = 0
    while (index < args.size) {
        val arg = args[index++]
        // Node options end at the script; later arguments belong to it.
        if (!arg.startsWith("-") || arg == "--") break
        if (arg in NODE_OPTIONS_WITH_SEPARATE_VALUE) {
            index++
            continue
        }
        if (arg.startsWith("--inspect-port=")) {
            val parsed = parseHostPort(arg.removePrefix("--inspect-port="), defaultHost = host) ?: return null
            host = parsed.first
            port = parsed.second
            continue
        }
        val flag = NODE_INSPECT_FLAGS.firstOrNull { arg == it || arg.startsWith("$it=") } ?: continue
        inspectorEnabled = true
        val value = arg.removePrefix(flag).removePrefix("=")
        if (value.isNotEmpty()) {
            val parsed = parseHostPort(value, defaultHost = host) ?: return null
            host = parsed.first
            port = parsed.second
        }
    }
    if (!inspectorEnabled || port == 0) return null
    return WasmAttachTarget.NodeInspector(host, port, firstWasmModule(args) ?: wasmPathInInlineScript(args))
}

/** Node options whose value is the next argument rather than `=value`. */
private val NODE_OPTIONS_WITH_SEPARATE_VALUE = setOf(
    "-r", "--require", "--import", "--loader", "--experimental-loader",
    "-e", "--eval", "-p", "--print", "-C", "--conditions", "--env-file", "--title",
)

/**
 * Finds a `.wasm` path quoted inside an inline script, e.g. the
 * `fs.readFileSync('/path/module.wasm')` runner Hexana passes to `node -e`.
 */
private fun wasmPathInInlineScript(args: List<String>): String? =
    args.firstNotNullOfOrNull { arg -> INLINE_WASM_PATH.find(arg)?.groupValues?.get(2) }
        ?.replace("\\\\", "\\")

private val INLINE_WASM_PATH = Regex("""(['"`])([^'"`]+?\.wasm)\1""")

private fun detectChrome(args: List<String>): WasmAttachTarget? {
    // Renderer, GPU and utility helpers carry --type; only the browser
    // process owns the DevTools endpoint.
    if (args.any { it.startsWith("--type=") }) return null
    val portArg = args.firstOrNull { it.startsWith("--remote-debugging-port=") } ?: return null
    val port = portArg.removePrefix("--remote-debugging-port=").toIntOrNull() ?: return null
    if (port !in 0..65535) return null
    val userDataDir = args.firstOrNull { it.startsWith("--user-data-dir=") }
        ?.removePrefix("--user-data-dir=")
        ?.trim('"')
        ?.takeIf { it.isNotEmpty() }
    // An OS-chosen port is only discoverable through the profile directory.
    if (port == 0 && userDataDir == null) return null
    return WasmAttachTarget.ChromeDevTools(port, userDataDir, firstWasmModule(args))
}

/**
 * Parses `host:port`, `[ipv6]:port` or a bare `port`. Wildcard listen
 * addresses are mapped to loopback, since that is where a local client
 * reaches them.
 */
fun parseHostPort(value: String, defaultHost: String): Pair<String, Int>? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val (rawHost, rawPort) = when {
        trimmed.startsWith("[") -> {
            val close = trimmed.indexOf(']')
            if (close < 0) return null
            trimmed.substring(1, close) to trimmed.substring(close + 1).removePrefix(":")
        }
        trimmed.count { it == ':' } == 1 -> trimmed.substringBefore(':') to trimmed.substringAfter(':')
        trimmed.all { it.isDigit() } -> defaultHost to trimmed
        else -> return null
    }
    val port = rawPort.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
    val host = when (rawHost) {
        "", "0.0.0.0", "::", "*" -> LOOPBACK
        else -> rawHost
    }
    return host to port
}

private fun firstWasmModule(args: List<String>): String? = args.firstOrNull(::isWasmModulePath)

private fun isWasmModulePath(arg: String): Boolean =
    !arg.startsWith("-") && WASM_EXTENSIONS.any { arg.endsWith(it, ignoreCase = true) }

private val WASM_EXTENSIONS = listOf(".wasm", ".cwasm")

private const val LOOPBACK = "127.0.0.1"

/** Port Node.js listens on when an inspect flag carries no explicit port. */
const val NODE_DEFAULT_INSPECT_PORT = 9229
