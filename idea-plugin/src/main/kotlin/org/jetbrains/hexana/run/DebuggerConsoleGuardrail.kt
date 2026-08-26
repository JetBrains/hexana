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

private fun classifyLldbCommand(cmd: String, kind: ConsoleSessionKind): ConsoleCommandRuling {
    // WAMR-specific guard: the qWasmStackValue packet desynchronises WAMR's stub.
    if (kind == ConsoleSessionKind.LLDB_WAMR && "qWasmStackValue" in cmd) {
        return ConsoleCommandRuling.Block(
            "qWasmStackValue is not supported by the WAMR debug stub; sending it " +
                "would desynchronise the stub's internal state. Use the Variables " +
                "panel to inspect Wasm locals instead."
        )
    }

    // continue / c (but NOT commands whose first token is longer, e.g. "call")
    if (cmd == "continue" || cmd.startsWithToken("continue") ||
        cmd == "c" || cmd.startsWithToken("c")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.RESUME,
            "Routed to the debugger Resume action so the IDE stays in sync."
        )
    }

    // process continue
    if (cmd == "process continue" || cmd.startsWithToken("process continue")) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.RESUME,
            "Routed to the debugger Resume action so the IDE stays in sync."
        )
    }

    // next / n / thread step-over
    if (cmd == "next" || cmd.startsWithToken("next") ||
        cmd == "n" || cmd.startsWithToken("n") ||
        cmd == "thread step-over" || cmd.startsWithToken("thread step-over")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_OVER,
            "Routed to the debugger Step Over action so the IDE stays in sync."
        )
    }

    // step / s / thread step-in
    if (cmd == "step" || cmd.startsWithToken("step") ||
        cmd == "s" || cmd.startsWithToken("s") ||
        cmd == "thread step-in" || cmd.startsWithToken("thread step-in")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_INTO,
            "Routed to the debugger Step Into action so the IDE stays in sync."
        )
    }

    // finish / thread step-out
    if (cmd == "finish" || cmd.startsWithToken("finish") ||
        cmd == "thread step-out" || cmd.startsWithToken("thread step-out")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_OUT,
            "Routed to the debugger Step Out action so the IDE stays in sync."
        )
    }

    // thread step-inst / thread step-inst-over (instruction stepping)
    if (cmd == "thread step-inst" || cmd.startsWithToken("thread step-inst") ||
        cmd == "thread step-inst-over" || cmd.startsWithToken("thread step-inst-over")
    ) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.STEP_OVER,
            "Instruction stepping routed to the debugger Step Over action. " +
                "Use the Wasm disassembly tab for per-instruction stepping."
        )
    }

    // process interrupt
    if (cmd == "process interrupt" || cmd.startsWithToken("process interrupt")) {
        return ConsoleCommandRuling.Intercept(
            InterceptAction.PAUSE,
            "Routed to the debugger Pause action so the IDE stays in sync."
        )
    }

    // run / r / process launch — blocked because the session is already running
    if (cmd == "run" || cmd.startsWithToken("run") ||
        cmd == "r" || cmd.startsWithToken("r") ||
        cmd == "process launch" || cmd.startsWithToken("process launch")
    ) {
        return ConsoleCommandRuling.Block(
            "The session is already running. To relaunch, stop this session " +
                "and start a new one from the run configuration."
        )
    }

    // quit / q / exit / process kill / process detach
    if (cmd == "quit" || cmd.startsWithToken("quit") ||
        cmd == "q" || cmd.startsWithToken("q") ||
        cmd == "exit" || cmd.startsWithToken("exit") ||
        cmd == "process kill" || cmd.startsWithToken("process kill") ||
        cmd == "process detach" || cmd.startsWithToken("process detach")
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

    // run / start — blocked: session already running
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
