package org.jetbrains.hexana.shared

/**
 * Pure, platform-agnostic parsers for LLDB output.
 *
 * These functions extract structured data from LLDB's textual output
 * (stop events, expression results, frame locations, function names).
 * Both `idea-plugin` and `vscode-plugin:extension-lib` delegate to
 * these parsers so the logic is not duplicated.
 */

// ── Stop-event parsing ──────────────────────────────────────────────

/**
 * Represents a stop event parsed from a single LLDB output line.
 */
sealed class LldbStopEvent {
    data class BreakpointHit(val threadId: Int, val breakpointId: String) : LldbStopEvent()
    data object Stepped : LldbStopEvent()
    data object StoppedAtEntry : LldbStopEvent()
    data class ProcessExited(val exitCode: Int) : LldbStopEvent()
}

private val BREAKPOINT_STOP_PATTERN =
    """thread #(\d+).*stop reason = breakpoint (\d+\.\d+)""".toRegex()

private val THREAD_ID_PATTERN =
    """thread #(\d+)""".toRegex()

private val EXIT_PATTERN =
    """Process \d+ exited with status = (-?\d+)""".toRegex()

/**
 * Parses a single LLDB output line looking for a stop reason.
 *
 * Common patterns:
 * - `* thread #1, stop reason = breakpoint 1.1`
 * - `* thread #1, stop reason = EXC_BREAKPOINT (code=1, subcode=0x...)`
 * - `* thread #1, stop reason = step in`
 * - `* thread #1, stop reason = plan complete`
 * - `* thread #1, stop reason = instruction step into` (wasm gdbstub)
 * - `* thread #1, stop reason = instruction step over` (wasm gdbstub)
 * - `* thread #1, stop reason = signal SIGSTOP` (stop-at-entry)
 * - `Process 12345 exited with status = 0 (0x00000000)`
 *
 * Wasmtime JIT on macOS/ARM may report `EXC_BREAKPOINT`
 * (a Mach exception from a hardware trap instruction) instead
 * of a regular `breakpoint N.M` stop reason. We treat
 * `EXC_BREAKPOINT` as a breakpoint hit.
 *
 * On Windows, LLDB reports `Exception 0x80000003` (the
 * `STATUS_BREAKPOINT` NT exception code) for hardware trap
 * instructions (`brk` on ARM64, `int3` on x86_64). We treat
 * this as a breakpoint hit as well.
 *
 * @return the parsed [LldbStopEvent], or `null` when the line
 *         does not match any recognised stop pattern.
 */
fun parseLldbStopEvent(line: String): LldbStopEvent? {
    val trimmed = line.trim()
    if (trimmed.contains("stop reason = breakpoint") ||
        trimmed.contains("stop reason = EXC_BREAKPOINT") ||
        trimmed.contains("stop reason = Exception 0x80000003")
    ) {
        val match = BREAKPOINT_STOP_PATTERN.find(trimmed)
        return if (match != null) {
            LldbStopEvent.BreakpointHit(
                threadId = match.groupValues[1].toIntOrNull() ?: 1,
                breakpointId = match.groupValues[2],
            )
        } else {
            val tid = THREAD_ID_PATTERN.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            LldbStopEvent.BreakpointHit(threadId = tid, breakpointId = "")
        }
    }
    if (trimmed.contains("stop reason = signal SIGSTOP")) {
        return LldbStopEvent.StoppedAtEntry
    }
    if (trimmed.contains("stop reason = signal") || trimmed.contains("stop reason = trace") ||
        trimmed.contains("stop reason = step") || trimmed.contains("stop reason = plan complete") ||
        trimmed.contains("stop reason = instruction step")
    ) {
        return LldbStopEvent.Stepped
    }
    if (trimmed.startsWith("Process") && trimmed.contains("exited")) {
        val exitMatch = EXIT_PATTERN.find(trimmed)
        val exitCode = exitMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return LldbStopEvent.ProcessExited(exitCode)
    }
    return null
}

// ── Expression result parsing ───────────────────────────────────────

/**
 * Parsed result of an LLDB `expression` command.
 *
 * @property typeName the type name from the LLDB output, or `null`
 *                    when the format was not recognised
 * @property displayValue the display value (everything after `= `)
 */
data class LldbExpressionResult(
    val typeName: String?,
    val displayValue: String,
)

/**
 * LLDB expression results typically look like:
 * ```
 * (int) $0 = 42
 * (const char *) $1 = 0x... "hello"
 * ```
 * This pattern captures the type, the scratch variable name, and
 * the value portion.
 */
private val EXPRESSION_RESULT_PATTERN =
    """\((.+?)\)\s+\$\d+\s+=\s+([\s\S]+)""".toRegex()

/**
 * Parses the textual output of an LLDB `expression` command into
 * a structured [LldbExpressionResult].
 *
 * @param text the raw output from LLDB (may be multi-line for
 *             struct/aggregate values)
 * @return a result with the extracted type name and display value,
 *         or a fallback with `typeName = null` when the pattern
 *         does not match
 */
fun parseLldbExpressionResult(text: String): LldbExpressionResult {
    val match = EXPRESSION_RESULT_PATTERN.find(text)
    return if (match != null) {
        LldbExpressionResult(
            typeName = match.groupValues[1].trim(),
            displayValue = match.groupValues[2].trim(),
        )
    } else {
        LldbExpressionResult(typeName = null, displayValue = text)
    }
}

// ── Frame location parsing ──────────────────────────────────────────

/**
 * A source location parsed from an LLDB frame output line.
 *
 * @property filePath the file path as reported by LLDB (may be
 *                    relative or a build-time absolute path)
 * @property line the 1-based line number
 */
data class LldbFrameLocation(
    val filePath: String,
    val line: Int,
)

/**
 * Matches LLDB frame location: `at /path/to/file.c:42` or
 * `at /path/to/file.c:42:5`.
 */
private val FRAME_LOCATION_PATTERN =
    """\bat\s+(.+?):(\d+)(?::\d+)?""".toRegex()

/**
 * Parses the file path and line number from LLDB frame output lines.
 *
 * LLDB typically outputs lines like:
 * ```
 * frame #0: 0x... module`func at file.c:42:5
 * ```
 *
 * Scans lines in reverse order (most recent frame first).
 *
 * @return the parsed [LldbFrameLocation], or `null` when no
 *         location pattern is found
 */
fun parseLldbFrameLocation(lines: List<String>): LldbFrameLocation? {
    for (line in lines.asReversed()) {
        val match = FRAME_LOCATION_PATTERN.find(line) ?: continue
        val filePath = match.groupValues[1]
        val lineNum = match.groupValues[2].toIntOrNull() ?: continue
        return LldbFrameLocation(filePath, lineNum)
    }
    return null
}

/**
 * Matches the program counter in LLDB frame output:
 * `frame #0: 0x0000000102290abc ...`.
 */
private val FRAME_PC_PATTERN =
    """frame #\d+: (0x[0-9a-fA-F]+)""".toRegex()

/**
 * Parses the program counter from LLDB frame output lines (e.g. the
 * output of `frame info`).
 *
 * Scans lines in reverse order (most recent frame first), mirroring
 * [parseLldbFrameLocation].
 *
 * @return the pc as an unsigned 64-bit address, or `null` when no
 *         frame line with a hex pc is found
 */
fun parseLldbFramePc(lines: List<String>): ULong? {
    for (line in lines.asReversed()) {
        val match = FRAME_PC_PATTERN.find(line) ?: continue
        val pc = match.groupValues[1].removePrefix("0x").toULongOrNull(16) ?: continue
        return pc
    }
    return null
}

/**
 * Extracts the raw file path from LLDB frame output without
 * attempting to resolve it to a local file. This is the path
 * exactly as LLDB reports it in the `at <path>:<line>` portion
 * of `frame info`.
 */
fun parseRawDwarfPath(lines: List<String>): String? {
    for (line in lines.asReversed()) {
        val match = FRAME_LOCATION_PATTERN.find(line) ?: continue
        return match.groupValues[1]
    }
    return null
}

/**
 * Extracts the 1-based line number from the raw LLDB frame output.
 */
fun parseRawDwarfLine(lines: List<String>): Int? {
    for (line in lines.asReversed()) {
        val match = FRAME_LOCATION_PATTERN.find(line) ?: continue
        return match.groupValues[2].toIntOrNull()
    }
    return null
}

// ── Function name parsing ───────────────────────────────────────────

/**
 * Matches function name in LLDB frame output.
 *
 * Component-model (backtick delimiter):
 * `frame #0: 0x... JIT(0x...)`func_name(args) at file.c:42`
 *
 * Classic wasm module (no backtick — function name follows
 * the closing parenthesis of the JIT module identifier):
 * `frame #0: 0x... JIT(0x...)func_name(args) at file.c:42`
 *
 * The `[)\x60]\x60?` prefix consumes the closing `)` or
 * backtick (or both when a backtick follows `)`) so the
 * captured group contains only the function name. The name
 * may be followed by parenthesized arguments before `at` or
 * `+`, so the pattern uses a lazy `\S+?` and an optional
 * `(...)` group.
 */
private val FUNCTION_NAME_PATTERN =
    """[)`]`?(\S+?)(?:\(.*?\))?\s+(?:at|[+])""".toRegex()

/**
 * Fallback pattern for WAMR output where there is no module
 * identifier (no backtick, no JIT parenthesized prefix).
 *
 * WAMR frame output looks like:
 * `frame #0: 0x400000000000041a add(x=...) at main.go:15:14`
 *
 * Here the function name follows the hex address directly
 * after a space. The pattern anchors on the `0xHEX ` prefix
 * to avoid false matches.
 */
private val WAMR_FUNCTION_NAME_PATTERN =
    """0x[0-9a-fA-F]+\s+(\S+?)(?:\(.*?\))?\s+(?:at|[+])""".toRegex()

/**
 * Parses function name from LLDB frame output lines.
 *
 * The LLDB `frame info` output contains lines like:
 * ```
 * frame #0: 0x... module`func_name(args) at file.c:42
 * ```
 * This method extracts `func_name` (without the arguments).
 *
 * Scans lines in reverse order (most recent frame first).
 *
 * @return the extracted function name, or `null` when no
 *         match is found
 */
fun parseLldbFunctionName(lines: List<String>): String? {
    for (line in lines.asReversed()) {
        val match = FUNCTION_NAME_PATTERN.find(line) ?: continue
        return match.groupValues[1]
    }
    // Fallback for WAMR output where there is no module
    // identifier before the function name.
    for (line in lines.asReversed()) {
        val match = WAMR_FUNCTION_NAME_PATTERN.find(line) ?: continue
        return match.groupValues[1]
    }
    return null
}

// ── WAMR debug support detection ────────────────────────────────────

/**
 * Detects whether iwasm output indicates the `-g` debug
 * option is not supported by the current build.
 *
 * When iwasm is built without `WAMR_BUILD_DEBUG_INTERP=1`,
 * the `-g=ip:port` argument is unrecognized. iwasm then
 * prints its help/usage text and exits. The help text from
 * a non-debug build will contain the usage header (e.g.
 * "Usage:" or "iwasm") but will NOT contain the `-g=`
 * option description, because that line is conditionally
 * compiled only when `WASM_ENABLE_DEBUG_INTERP != 0`.
 */
fun looksLikeWamrDebugNotSupported(output: String): Boolean {
    if (output.isBlank()) return false
    val looksLikeHelp = output.contains("Usage:") ||
        output.contains("iwasm") && output.contains("--")
    val hasDebugOption = output.contains("-g=")
    return looksLikeHelp && !hasDebugOption
}
