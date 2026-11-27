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

    /**
     * Where the module may be, longest first. A process listing that dropped
     * argument quoting (macOS `ps`) splits `/my dir/app.wasm` into `/my` and
     * `dir/app.wasm`; the candidates rejoin the words before [wasmPath], so a
     * host takes the first one that exists. Always ends with [wasmPath].
     */
    abstract val wasmPathCandidates: List<String>

    abstract val runtime: WasmAttachRuntime

    /**
     * The endpoint's port is 0: the process let the OS choose it, so the host
     * finds it among the process's listening sockets ([ListeningPorts]).
     */
    abstract val needsPortDiscovery: Boolean

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
        override val wasmPathCandidates: List<String> = listOfNotNull(wasmPath),
    ) : WasmAttachTarget() {
        override val needsPortDiscovery: Boolean get() = port == 0
    }

    /** A Node.js process started with `--inspect`, `--inspect-brk` or `--inspect-wait`. */
    data class NodeInspector(
        val host: String,
        val port: Int,
        override val wasmPath: String?,
        override val wasmPathCandidates: List<String> = listOfNotNull(wasmPath),
    ) : WasmAttachTarget() {
        override val runtime: WasmAttachRuntime get() = WasmAttachRuntime.NODE_JS
        override val needsPortDiscovery: Boolean get() = port == 0
    }

    /**
     * A Chromium-based browser started with `--remote-debugging-port`. A zero
     * [port] means the browser chose one: it writes it to
     * `<userDataDir>/DevToolsActivePort`, and it is also among the browser's
     * listening sockets.
     */
    data class ChromeDevTools(
        val port: Int,
        val userDataDir: String?,
        override val wasmPath: String?,
        override val wasmPathCandidates: List<String> = listOfNotNull(wasmPath),
    ) : WasmAttachTarget() {
        override val runtime: WasmAttachRuntime get() = WasmAttachRuntime.BROWSER
        override val needsPortDiscovery: Boolean get() = port == 0
    }
}

/**
 * Recognizes a Wasm debug target from a process's executable name and
 * argument list. Returns null for processes that serve no debug endpoint.
 * An endpoint on an OS-chosen port (`node --inspect=0`) is returned with
 * port 0 and [WasmAttachTarget.needsPortDiscovery].
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
 * printed by `ps` or `Win32_Process.CommandLine`. [executable], when known,
 * is taken verbatim from the start of the line. [windows] selects Windows
 * tokenizing ([splitWindowsCommandLine]), where `\` is a path separator, not
 * an escape. Quoting that survived the listing is honored; a listing that
 * dropped it splits paths with spaces (see [WasmAttachTarget.wasmPathCandidates]).
 */
fun detectWasmAttachTargetFromCommandLine(
    commandLine: String,
    windows: Boolean = false,
    executable: String? = null,
): WasmAttachTarget? {
    val split: (String) -> List<String> = if (windows) ::splitWindowsCommandLine else ::splitOptions
    // A known executable path (macOS `ps -o comm`) may contain spaces the
    // joined command line cannot show; cut it off instead of tokenizing it.
    if (!executable.isNullOrEmpty() && commandLine.startsWith(executable)) {
        val rest = commandLine.substring(executable.length)
        if (rest.isEmpty() || rest[0].isWhitespace()) return detectWasmAttachTarget(executable, split(rest))
    }
    val tokens = split(commandLine)
    val first = tokens.firstOrNull() ?: return null
    return detectWasmAttachTarget(first, tokens.drop(1))
}

/**
 * Splits a Windows command line the way `CommandLineToArgvW` does:
 * whitespace separates arguments outside double quotes, `""` inside quotes
 * is a literal quote, and backslashes are literal unless they precede a
 * quote (2n backslashes + `"` → n backslashes and a quote toggle;
 * 2n+1 → n backslashes and a literal quote).
 */
fun splitWindowsCommandLine(commandLine: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var hasToken = false
    var i = 0
    while (i < commandLine.length) {
        val ch = commandLine[i]
        when {
            ch == '\\' -> {
                var slashes = 0
                while (i < commandLine.length && commandLine[i] == '\\') {
                    slashes++
                    i++
                }
                if (i < commandLine.length && commandLine[i] == '"') {
                    repeat(slashes / 2) { current.append('\\') }
                    if (slashes % 2 == 1) {
                        current.append('"')
                        i++
                    }
                } else {
                    repeat(slashes) { current.append('\\') }
                }
                hasToken = true
                continue
            }
            ch == '"' -> {
                if (inQuotes && i + 1 < commandLine.length && commandLine[i + 1] == '"') {
                    current.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
                hasToken = true
            }
            ch.isWhitespace() && !inQuotes -> {
                if (hasToken) {
                    result.add(current.toString())
                    current.clear()
                    hasToken = false
                }
            }
            else -> {
                current.append(ch)
                hasToken = true
            }
        }
        i++
    }
    if (hasToken) result.add(current.toString())
    return result
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
        val module = wasmModuleOf(args)
        return WasmAttachTarget.GdbStub(WasmAttachRuntime.WASMTIME, host, port, module.path, module.candidates)
    }
    return null
}

private fun detectIwasm(args: List<String>): WasmAttachTarget? {
    for (arg in args) {
        if (isWasmModulePath(arg)) break
        if (!arg.startsWith("-g=")) continue
        val (host, port) = parseHostPort(arg.removePrefix("-g="), defaultHost = LOOPBACK) ?: return null
        val module = wasmModuleOf(args)
        return WasmAttachTarget.GdbStub(WasmAttachRuntime.WAMR, host, port, module.path, module.candidates)
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
    if (!inspectorEnabled) return null
    val module = wasmModuleOf(args).takeIf { it.path != null }
        ?: wasmPathInInlineScript(args).let { WasmModuleArgument(it, listOfNotNull(it)) }
    return WasmAttachTarget.NodeInspector(host, port, module.path, module.candidates)
}

/** Node options whose value is the next argument rather than `=value`. */
private val NODE_OPTIONS_WITH_SEPARATE_VALUE = setOf(
    "-r", "--require", "--import", "--loader", "--experimental-loader",
    "-e", "--eval", "-p", "--print", "-C", "--conditions", "--env-file", "--title",
)

/**
 * Finds a `.wasm` path inside an inline script, e.g. the
 * `fs.readFileSync('/path/module.wasm')` runner Hexana passes to `node -e`.
 * The quotes are gone when the script came through a tokenizer (a `ps`
 * listing re-split by [splitOptions]), so `readFileSync(<path>)` without
 * them is recognized too.
 */
private fun wasmPathInInlineScript(args: List<String>): String? =
    args.firstNotNullOfOrNull { arg ->
        INLINE_WASM_PATH.find(arg)?.groupValues?.get(2)
            ?: INLINE_READ_FILE_WASM_PATH.find(arg)?.groupValues?.get(1)
    }?.replace("\\\\", "\\")

private val INLINE_WASM_PATH = Regex("""(['"`])([^'"`]+?\.wasm)\1""")
private val INLINE_READ_FILE_WASM_PATH = Regex("""readFileSync\(\s*([^'"`()]+?\.wasm)\s*\)""")

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
    val module = wasmModuleOf(args)
    return WasmAttachTarget.ChromeDevTools(port, userDataDir, module.path, module.candidates)
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

private class WasmModuleArgument(val path: String?, val candidates: List<String>)

/**
 * The first module argument plus its [WasmAttachTarget.wasmPathCandidates]:
 * the argument rejoined with up to [MAX_REJOINED_WORDS] preceding words
 * that can be part of a path, longest first. Options, `key=value` words and
 * `host:port`/URL words end the rejoin; they are option values, not paths.
 */
private fun wasmModuleOf(args: List<String>): WasmModuleArgument {
    val index = args.indexOfFirst(::isWasmModulePath)
    if (index < 0) return WasmModuleArgument(null, emptyList())
    var first = index
    while (first > 0 && index - first < MAX_REJOINED_WORDS && canBePathWord(args[first - 1])) first--
    val candidates = (first..index).map { start -> args.subList(start, index + 1).joinToString(" ") }
    return WasmModuleArgument(args[index], candidates)
}

private const val MAX_REJOINED_WORDS = 8

private fun canBePathWord(word: String): Boolean =
    !word.startsWith("-") && '=' !in word && (':' !in word || WINDOWS_DRIVE_PREFIX.matches(word))

private val WINDOWS_DRIVE_PREFIX = Regex("""[A-Za-z]:[\\/][^:]*""")

private fun isWasmModulePath(arg: String): Boolean =
    !arg.startsWith("-") && WASM_EXTENSIONS.any { arg.endsWith(it, ignoreCase = true) }

private val WASM_EXTENSIONS = listOf(".wasm", ".cwasm")

private const val LOOPBACK = "127.0.0.1"

/** Port Node.js listens on when an inspect flag carries no explicit port. */
const val NODE_DEFAULT_INSPECT_PORT = 9229
