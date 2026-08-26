package org.jetbrains.hexana.run

/**
 * Identifies the kind of debug session backing a [DebuggerConsoleTab].
 *
 * The kind governs which commands are intercepted or blocked so the IDE
 * debugger state stays in sync with the underlying runtime.
 */
enum class ConsoleSessionKind {
    /** Wasmtime JIT session: LLDB wraps wasmtime via the GDB JIT interface. */
    LLDB_WASMTIME_JIT,
    /** WAMR session: LLDB connects to the WAMR gdbstub via the wasm process plugin. */
    LLDB_WAMR,
    /** Wasmtime gdbstub session: LLDB connects via the wasm process plugin. */
    LLDB_WASMTIME_GDBSTUB,
    /** Firmware session running over GDB-DAP transport. */
    GDB_DAP,
    /** Firmware session: LLDB connects to a GDB-stub emulator (QEMU or Renode) via gdb-remote. */
    LLDB_FIRMWARE,
}

/**
 * Result of classifying a raw console command string.
 *
 * @see classifyConsoleCommand
 */
sealed interface ConsoleCommandRuling {
    /** The command may be forwarded to the debug adapter unchanged. */
    data object Allow : ConsoleCommandRuling

    /**
     * The command maps to a named IDE debugger action.
     *
     * The console must NOT forward the raw command string; instead it
     * invokes [action] through the [com.intellij.xdebugger.XDebugSession]
     * API so the IDE's own state machine advances correctly.
     *
     * @property message Human-readable explanation shown in the console
     *   output, e.g. "routed to the debugger Resume action so the IDE
     *   stays in sync".
     */
    data class Intercept(val action: InterceptAction, val message: String) : ConsoleCommandRuling

    /**
     * The command must not be executed at all.
     *
     * @property reason Explanation printed to the console as error output.
     */
    data class Block(val reason: String) : ConsoleCommandRuling
}

/**
 * Debugger action that a console command maps to when it is intercepted.
 */
enum class InterceptAction { RESUME, PAUSE, STEP_OVER, STEP_INTO, STEP_OUT, STOP }

/**
 * Classifies a raw console [command] string for the given session [kind].
 *
 * The function trims leading/trailing whitespace and matches the first
 * token(s) of the command string against the known LLDB and GDB-MI
 * command vocabulary. Matching is case-sensitive to reflect how LLDB
 * and GDB parse their command lines.
 *
 * Rules for LLDB sessions ([ConsoleSessionKind.LLDB_WASMTIME_JIT],
 * [ConsoleSessionKind.LLDB_WAMR], [ConsoleSessionKind.LLDB_WASMTIME_GDBSTUB],
 * [ConsoleSessionKind.LLDB_FIRMWARE]):
 * - Navigation commands (`continue`, `next`, `step`, `finish`, etc.) are
 *   intercepted and routed to the matching [XDebugSession] action.
 * - Session-ending commands (`quit`, `process kill`, etc.) are intercepted
 *   as [InterceptAction.STOP] so the IDE tears down the session cleanly.
 * - Re-launch commands (`run`, `process launch`) are blocked because the
 *   session is already running.
 * - On [ConsoleSessionKind.LLDB_WAMR], any command that contains the
 *   string `qWasmStackValue` is blocked; WAMR's stub does not implement
 *   this packet and sending it desynchronises the stub's internal state.
 * - Everything else is [ConsoleCommandRuling.Allow].
 *
 * Rules for [ConsoleSessionKind.GDB_DAP] sessions follow the GDB-MI / GDB
 * CLI vocabulary equivalents.
 */
fun classifyConsoleCommand(command: String, kind: ConsoleSessionKind): ConsoleCommandRuling {
    val trimmed = command.trim()
    return when (kind) {
        ConsoleSessionKind.LLDB_WASMTIME_JIT,
        ConsoleSessionKind.LLDB_WAMR,
        ConsoleSessionKind.LLDB_WASMTIME_GDBSTUB,
        ConsoleSessionKind.LLDB_FIRMWARE -> classifyLldbCommand(trimmed, kind)
        ConsoleSessionKind.GDB_DAP -> classifyGdbDapCommand(trimmed)
    }
}

// ---------------------------------------------------------------------------
// LLDB classification
// ---------------------------------------------------------------------------

/**
 * LLDB resolves unique prefixes of top-level commands, so `cont`, `pro c`, etc.
 * are treated identically to their full forms. This table maps every length->=2
 * prefix that uniquely identifies an execution-control command (the guardrail's
 * scope) to the canonical name. Single-character aliases (`c`, `n`, `s`, `r`,
 * `q`) are already handled as explicit tokens above the expansion step.
 *
 * Ambiguous prefixes are left absent from the table: `st` prefixes both `step`
 * and `statistics`/`settings`, so it is not expanded. `re` prefixes `register`,
 * `return`, etc. Top-level ambiguity means LLDB would error, so the guardrail
 * must not intercept.
 *
 * For two-word commands the first token is expanded here; the second-token
 * tables below handle sub-command prefixes for `process` and `thread`.
 */
private val LLDB_FIRST_TOKEN_EXPANSIONS: Map<String, String> = mapOf(
    // continue -- `co` through `continu` are unique prefixes
    "co" to "continue", "con" to "continue", "cont" to "continue",
    "conti" to "continue", "contin" to "continue", "continu" to "continue",
    // finish
    "fi" to "finish", "fin" to "finish", "fini" to "finish",
    "finis" to "finish",
    // next -- `ne` through `nex` are unique (nexti is a separate top-level alias)
    "ne" to "next", "nex" to "next",
    // step -- `ste` uniquely prefixes step (statistics = s-t-a, settings = s-e)
    "ste" to "step", "ste" to "step",
    // process
    "pr" to "process", "pro" to "process", "proc" to "process",
    "proce" to "process", "proces" to "process",
    // thread
    "th" to "thread", "thr" to "thread", "thre" to "thread",
    "threa" to "thread",
    // quit -- `qu` through `qui` are unique
    "qu" to "quit", "qui" to "quit",
    // run -- `ru` is the unique 2-char prefix (`re` is ambiguous)
    "ru" to "run",
)

/** Sub-command prefixes for `process <sub>`. */
private val LLDB_PROCESS_SUB_EXPANSIONS: Map<String, String> = mapOf(
    // Single-char `c` is the unique prefix of `continue` among process sub-commands.
    "c" to "continue",
    "co" to "continue", "con" to "continue", "cont" to "continue",
    "conti" to "continue", "contin" to "continue", "continu" to "continue",
    "in" to "interrupt", "int" to "interrupt", "inte" to "interrupt",
    "inter" to "interrupt", "interr" to "interrupt", "interru" to "interrupt",
    "interrup" to "interrupt",
    "ki" to "kill", "kil" to "kill",
    "de" to "detach", "det" to "detach", "deta" to "detach",
    "detac" to "detach",
    "la" to "launch", "lau" to "launch", "laun" to "launch",
    "launc" to "launch",
)

/** Sub-command prefixes for `thread <sub>` (hyphenated forms). */
private val LLDB_THREAD_SUB_EXPANSIONS: Map<String, String> = mapOf(
    "step-over" to "step-over",
    "step-in" to "step-in",
    "step-inst-over" to "step-inst-over",
    // Unambiguous prefixes of step-over / step-out:
    // "step-o" is ambiguous (step-over vs step-out), so leave it absent.
    "step-ov" to "step-over", "step-ove" to "step-over",
    // step-out unambiguous prefix only from "step-ou":
    "step-ou" to "step-out",
    "step-i" to "step-in",
    // step-inst vs step-inst-over: "step-inst" itself is a valid command,
    // so "step-inst-" prefixes go to step-inst-over.
    "step-inst-" to "step-inst-over", "step-inst-o" to "step-inst-over",
    "step-inst-ov" to "step-inst-over", "step-inst-ove" to "step-inst-over",
)

/**
 * Expands [token] via [table] if it is a known unambiguous prefix, otherwise
 * returns [token] unchanged. Full matches pass through as-is (the table also
 * contains the canonical forms as identity entries, but checking avoids a map
 * lookup -- just return the token when its length equals a canonical name's length
 * and it equals the value).
 */
private fun expandToken(token: String, table: Map<String, String>): String =
    table[token] ?: token

private fun classifyLldbCommand(cmd: String, kind: ConsoleSessionKind): ConsoleCommandRuling {
    // WAMR-specific guard: the qWasmStackValue packet desynchronises WAMR's stub.
    if (kind == ConsoleSessionKind.LLDB_WAMR && "qWasmStackValue" in cmd) {
        return ConsoleCommandRuling.Block(
            "qWasmStackValue is not supported by the WAMR debug stub; sending it " +
                "would desynchronise the stub's internal state. Use the Variables " +
                "panel to inspect Wasm locals instead."
        )
    }

    // Split into first and optional rest (everything after the first space).
    val spaceIdx = cmd.indexOf(' ')
    val firstToken = if (spaceIdx < 0) cmd else cmd.substring(0, spaceIdx)
    val rest = if (spaceIdx < 0) "" else cmd.substring(spaceIdx + 1).trim()

    // Expand the first token if it is a length->=2 unambiguous prefix.
    // Single-char tokens are handled below as explicit aliases.
    val expandedFirst = if (firstToken.length >= 2) expandToken(firstToken, LLDB_FIRST_TOKEN_EXPANSIONS)
    else firstToken

    // Reconstruct a normalised command string for the matching logic below.
    val normCmd = if (rest.isEmpty()) expandedFirst else "$expandedFirst $rest"

    // For two-word commands (process/thread), also expand the sub-command token.
    val normCmdFull: String = when (expandedFirst) {
        "process" -> {
            val subSpaceIdx = rest.indexOf(' ')
            val subToken = if (subSpaceIdx < 0) rest else rest.substring(0, subSpaceIdx)
            val expandedSub = expandToken(subToken, LLDB_PROCESS_SUB_EXPANSIONS)
            val subRest = if (subSpaceIdx < 0) "" else rest.substring(subSpaceIdx + 1)
            if (subRest.isEmpty()) "$expandedFirst $expandedSub"
            else "$expandedFirst $expandedSub $subRest"
        }
        "thread" -> {
            val subSpaceIdx = rest.indexOf(' ')
            val subToken = if (subSpaceIdx < 0) rest else rest.substring(0, subSpaceIdx)
            val expandedSub = expandToken(subToken, LLDB_THREAD_SUB_EXPANSIONS)
            val subRest = if (subSpaceIdx < 0) "" else rest.substring(subSpaceIdx + 1)
            if (subRest.isEmpty()) "$expandedFirst $expandedSub"
            else "$expandedFirst $expandedSub $subRest"
        }
        else -> normCmd
    }

    // continue / c (but NOT commands whose first token is longer, e.g. "call")
    if (normCmdFull == "continue" || normCmdFull.startsWithToken("continue") ||
        cmd == "c" || cmd.startsWithToken("c")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.RESUME,
            "Routed to the debugger Resume action so the IDE stays in sync."
        )
    }

    // process continue
    if (normCmdFull == "process continue" || normCmdFull.startsWithToken("process continue")) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.RESUME,
            "Routed to the debugger Resume action so the IDE stays in sync."
        )
    }

    // next / n / thread step-over
    if (normCmdFull == "next" || normCmdFull.startsWithToken("next") ||
        cmd == "n" || cmd.startsWithToken("n") ||
        normCmdFull == "thread step-over" || normCmdFull.startsWithToken("thread step-over")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_OVER,
            "Routed to the debugger Step Over action so the IDE stays in sync."
        )
    }

    // step / s / thread step-in
    if (normCmdFull == "step" || normCmdFull.startsWithToken("step") ||
        cmd == "s" || cmd.startsWithToken("s") ||
        normCmdFull == "thread step-in" || normCmdFull.startsWithToken("thread step-in")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_INTO,
            "Routed to the debugger Step Into action so the IDE stays in sync."
        )
    }

    // finish / thread step-out
    if (normCmdFull == "finish" || normCmdFull.startsWithToken("finish") ||
        normCmdFull == "thread step-out" || normCmdFull.startsWithToken("thread step-out")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_OUT,
            "Routed to the debugger Step Out action so the IDE stays in sync."
        )
    }

    // thread step-inst / thread step-inst-over (instruction stepping)
    if (normCmdFull == "thread step-inst" || normCmdFull.startsWithToken("thread step-inst") ||
        normCmdFull == "thread step-inst-over" || normCmdFull.startsWithToken("thread step-inst-over")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_OVER,
            "Instruction stepping routed to the debugger Step Over action. " +
                "Use the Wasm disassembly tab for per-instruction stepping."
        )
    }

    // process interrupt
    if (normCmdFull == "process interrupt" || normCmdFull.startsWithToken("process interrupt")) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.PAUSE,
            "Routed to the debugger Pause action so the IDE stays in sync."
        )
    }

    // run / r / process launch -- blocked because the session is already running
    if (normCmdFull == "run" || normCmdFull.startsWithToken("run") ||
        cmd == "r" || cmd.startsWithToken("r") ||
        normCmdFull == "process launch" || normCmdFull.startsWithToken("process launch")
    ) {
        return ConsoleCommandRuling.Block(
            "The session is already running. To relaunch, stop this session " +
                "and start a new one from the run configuration."
        )
    }

    // quit / q / exit / process kill / process detach
    if (normCmdFull == "quit" || normCmdFull.startsWithToken("quit") ||
        cmd == "q" || cmd.startsWithToken("q") ||
        normCmdFull == "exit" || normCmdFull.startsWithToken("exit") ||
        normCmdFull == "process kill" || normCmdFull.startsWithToken("process kill") ||
        normCmdFull == "process detach" || normCmdFull.startsWithToken("process detach")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STOP,
            "Routed to the debugger Stop action so the IDE tears down the session cleanly."
        )
    }

    return ConsoleCommandRuling.Allow
}

// ---------------------------------------------------------------------------
// GDB-DAP classification
// ---------------------------------------------------------------------------

private fun classifyGdbDapCommand(cmd: String): ConsoleCommandRuling {
    // continue / c / -exec-continue / -exec continue
    if (cmd == "continue" || cmd.startsWithToken("continue") ||
        cmd == "c" || cmd.startsWithToken("c") ||
        cmd == "-exec-continue" || cmd.startsWithToken("-exec-continue") ||
        cmd == "-exec continue" || cmd.startsWithToken("-exec continue")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.RESUME,
            "Routed to the debugger Resume action so the IDE stays in sync."
        )
    }

    // next / n / nexti / ni
    if (cmd == "next" || cmd.startsWithToken("next") ||
        cmd == "n" || cmd.startsWithToken("n") ||
        cmd == "nexti" || cmd.startsWithToken("nexti") ||
        cmd == "ni" || cmd.startsWithToken("ni")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_OVER,
            "Routed to the debugger Step Over action so the IDE stays in sync."
        )
    }

    // step / s / stepi / si
    if (cmd == "step" || cmd.startsWithToken("step") ||
        cmd == "s" || cmd.startsWithToken("s") ||
        cmd == "stepi" || cmd.startsWithToken("stepi") ||
        cmd == "si" || cmd.startsWithToken("si")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_INTO,
            "Routed to the debugger Step Into action so the IDE stays in sync."
        )
    }

    // finish
    if (cmd == "finish" || cmd.startsWithToken("finish")) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_OUT,
            "Routed to the debugger Step Out action so the IDE stays in sync."
        )
    }

    // run / start -- blocked: session already running
    if (cmd == "run" || cmd.startsWithToken("run") ||
        cmd == "start" || cmd.startsWithToken("start")
    ) {
        return ConsoleCommandRuling.Block(
            "The session is already running. To relaunch, stop this session " +
                "and start a new one from the run configuration."
        )
    }

    // quit / q / kill
    if (cmd == "quit" || cmd.startsWithToken("quit") ||
        cmd == "q" || cmd.startsWithToken("q") ||
        cmd == "kill" || cmd.startsWithToken("kill")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STOP,
            "Routed to the debugger Stop action so the IDE tears down the session cleanly."
        )
    }

    return ConsoleCommandRuling.Allow
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Returns `true` when [this] starts with [token] followed by a space or
 * end-of-string, i.e. [token] is the entire first word of the command.
 *
 * This prevents "call foo()" from being matched by the "c" rule.
 */
private fun String.startsWithToken(token: String): Boolean {
    if (!startsWith(token)) return false
    if (length == token.length) return true
    return this[token.length] == ' '
}
