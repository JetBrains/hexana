package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase

/**
 * Unit tests for [classifyConsoleCommand].
 *
 * Each rule defined in [DebuggerConsoleGuardrail.kt] has at least one positive
 * case and at least one negative/boundary case so tests can actually fail when
 * a rule is broken.
 */
class DebuggerConsoleGuardrailTest : UsefulTestCase() {

    // -----------------------------------------------------------------------
    // LLDB_WASMTIME_JIT — navigation intercepts
    // -----------------------------------------------------------------------

    fun testLldb_continue_interceptsResume() {
        assertIntercept(InterceptAction.RESUME, "continue", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_c_interceptsResume() {
        assertIntercept(InterceptAction.RESUME, "c", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_processContinue_interceptsResume() {
        assertIntercept(InterceptAction.RESUME, "process continue", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_next_interceptsStepOver() {
        assertIntercept(InterceptAction.STEP_OVER, "next", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_n_interceptsStepOver() {
        assertIntercept(InterceptAction.STEP_OVER, "n", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_threadStepOver_interceptsStepOver() {
        assertIntercept(InterceptAction.STEP_OVER, "thread step-over", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_step_interceptsStepInto() {
        assertIntercept(InterceptAction.STEP_INTO, "step", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_s_interceptsStepInto() {
        assertIntercept(InterceptAction.STEP_INTO, "s", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_threadStepIn_interceptsStepInto() {
        assertIntercept(InterceptAction.STEP_INTO, "thread step-in", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_finish_interceptsStepOut() {
        assertIntercept(InterceptAction.STEP_OUT, "finish", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_threadStepOut_interceptsStepOut() {
        assertIntercept(InterceptAction.STEP_OUT, "thread step-out", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_threadStepInst_interceptsStepOver() {
        assertIntercept(InterceptAction.STEP_OVER, "thread step-inst", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_threadStepInstOver_interceptsStepOver() {
        assertIntercept(InterceptAction.STEP_OVER, "thread step-inst-over", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_processInterrupt_interceptsPause() {
        assertIntercept(InterceptAction.PAUSE, "process interrupt", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    // -----------------------------------------------------------------------
    // LLDB_WASMTIME_JIT — session-ending intercepts
    // -----------------------------------------------------------------------

    fun testLldb_quit_interceptsStop() {
        assertIntercept(InterceptAction.STOP, "quit", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_q_interceptsStop() {
        assertIntercept(InterceptAction.STOP, "q", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_exit_interceptsStop() {
        assertIntercept(InterceptAction.STOP, "exit", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_processKill_interceptsStop() {
        assertIntercept(InterceptAction.STOP, "process kill", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_processDetach_interceptsStop() {
        assertIntercept(InterceptAction.STOP, "process detach", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    // -----------------------------------------------------------------------
    // LLDB_WASMTIME_JIT — blocked commands
    // -----------------------------------------------------------------------

    fun testLldb_run_isBlocked() {
        assertBlock("run", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_r_isBlocked() {
        assertBlock("r", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_processLaunch_isBlocked() {
        assertBlock("process launch", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    // -----------------------------------------------------------------------
    // LLDB_WASMTIME_JIT — allowed commands
    // -----------------------------------------------------------------------

    fun testLldb_frameInfo_isAllowed() {
        assertAllow("frame info", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_bt_isAllowed() {
        assertAllow("bt", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_breakpointList_isAllowed() {
        assertAllow("breakpoint list", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    fun testLldb_expressionCall_isAllowed() {
        assertAllow("expression 1 + 1", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    // -----------------------------------------------------------------------
    // Boundary: single-letter commands must NOT match longer words
    // -----------------------------------------------------------------------

    /** "call foo()" must NOT be caught by the "c" (continue) rule. */
    fun testLldb_call_isNotContinue() {
        assertAllow("call foo()", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    /** "quit-help" (hypothetical) must NOT be caught by the "quit" (stop) rule. */
    fun testLldb_quitDash_isAllowed() {
        // "quit-help" starts with "quit" but the next char is '-', not ' '.
        assertAllow("quit-help", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    /** "running" must NOT be caught by the "run" (block) rule. */
    fun testLldb_running_isAllowed() {
        assertAllow("running", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    /** "nexti" must NOT be caught by the "next" rule (it's not in LLDB vocabulary for intercept). */
    fun testLldb_nexti_isAllowed() {
        // LLDB uses "thread step-inst-over", not "nexti" — it should be allowed.
        assertAllow("nexti", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    // -----------------------------------------------------------------------
    // LLDB_WAMR — qWasmStackValue guard
    // -----------------------------------------------------------------------

    fun testWamr_qWasmStackValue_isBlocked() {
        assertBlock(
            "process plugin packet send qWasmStackValue:0;0",
            ConsoleSessionKind.LLDB_WAMR,
        )
    }

    /** The same packet on WASMTIME_GDBSTUB must be allowed (different stub). */
    fun testWasmtimeGdb_qWasmStackValue_isAllowed() {
        assertAllow(
            "process plugin packet send qWasmStackValue:0;0",
            ConsoleSessionKind.LLDB_WASMTIME_GDBSTUB,
        )
    }

    /** A different GDB packet on WAMR that does not contain qWasmStackValue must be allowed. */
    fun testWamr_qWasmLocal_isAllowed() {
        assertAllow(
            "process plugin packet send qWasmLocal:0;1",
            ConsoleSessionKind.LLDB_WAMR,
        )
    }

    // -----------------------------------------------------------------------
    // GDB_DAP — navigation intercepts
    // -----------------------------------------------------------------------

    fun testGdb_continue_interceptsResume() {
        assertIntercept(InterceptAction.RESUME, "continue", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_c_interceptsResume() {
        assertIntercept(InterceptAction.RESUME, "c", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_execContinueDash_interceptsResume() {
        assertIntercept(InterceptAction.RESUME, "-exec-continue", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_execContinueSpace_interceptsResume() {
        assertIntercept(InterceptAction.RESUME, "-exec continue", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_next_interceptsStepOver() {
        assertIntercept(InterceptAction.STEP_OVER, "next", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_nexti_interceptsStepOver() {
        assertIntercept(InterceptAction.STEP_OVER, "nexti", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_ni_interceptsStepOver() {
        assertIntercept(InterceptAction.STEP_OVER, "ni", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_step_interceptsStepInto() {
        assertIntercept(InterceptAction.STEP_INTO, "step", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_stepi_interceptsStepInto() {
        assertIntercept(InterceptAction.STEP_INTO, "stepi", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_si_interceptsStepInto() {
        assertIntercept(InterceptAction.STEP_INTO, "si", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_finish_interceptsStepOut() {
        assertIntercept(InterceptAction.STEP_OUT, "finish", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_quit_interceptsStop() {
        assertIntercept(InterceptAction.STOP, "quit", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_q_interceptsStop() {
        assertIntercept(InterceptAction.STOP, "q", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_kill_interceptsStop() {
        assertIntercept(InterceptAction.STOP, "kill", ConsoleSessionKind.GDB_DAP)
    }

    // -----------------------------------------------------------------------
    // GDB_DAP — blocked commands
    // -----------------------------------------------------------------------

    fun testGdb_run_isBlocked() {
        assertBlock("run", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_start_isBlocked() {
        assertBlock("start", ConsoleSessionKind.GDB_DAP)
    }

    // -----------------------------------------------------------------------
    // GDB_DAP — allowed commands
    // -----------------------------------------------------------------------

    fun testGdb_info_registers_isAllowed() {
        assertAllow("info registers", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_print_isAllowed() {
        assertAllow("print foo", ConsoleSessionKind.GDB_DAP)
    }

    fun testGdb_unknown_isAllowed() {
        assertAllow("some unknown command", ConsoleSessionKind.GDB_DAP)
    }

    // -----------------------------------------------------------------------
    // LLDB_FIRMWARE — navigation intercepts (same LLDB classifier; no qWasmStackValue guard)
    // -----------------------------------------------------------------------

    fun testFirmware_continue_interceptsResume() {
        assertIntercept(InterceptAction.RESUME, "continue", ConsoleSessionKind.LLDB_FIRMWARE)
    }

    fun testFirmware_next_interceptsStepOver() {
        assertIntercept(InterceptAction.STEP_OVER, "next", ConsoleSessionKind.LLDB_FIRMWARE)
    }

    fun testFirmware_step_interceptsStepInto() {
        assertIntercept(InterceptAction.STEP_INTO, "step", ConsoleSessionKind.LLDB_FIRMWARE)
    }

    fun testFirmware_finish_interceptsStepOut() {
        assertIntercept(InterceptAction.STEP_OUT, "finish", ConsoleSessionKind.LLDB_FIRMWARE)
    }

    fun testFirmware_quit_interceptsStop() {
        assertIntercept(InterceptAction.STOP, "quit", ConsoleSessionKind.LLDB_FIRMWARE)
    }

    fun testFirmware_run_isBlocked() {
        assertBlock("run", ConsoleSessionKind.LLDB_FIRMWARE)
    }

    /** qWasmStackValue must be ALLOWED for LLDB_FIRMWARE — the WAMR guard does NOT apply here. */
    fun testFirmware_qWasmStackValue_isAllowed() {
        assertAllow(
            "process plugin packet send qWasmStackValue:0;0",
            ConsoleSessionKind.LLDB_FIRMWARE,
        )
    }

    fun testFirmware_unknown_isAllowed() {
        assertAllow("info threads", ConsoleSessionKind.LLDB_FIRMWARE)
    }

    // -----------------------------------------------------------------------
    // Whitespace trimming
    // -----------------------------------------------------------------------

    fun testLldb_leadingWhitespace_continue_interceptsResume() {
        assertIntercept(InterceptAction.RESUME, "  continue  ", ConsoleSessionKind.LLDB_WASMTIME_JIT)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun assertAllow(command: String, kind: ConsoleSessionKind) {
        val ruling = classifyConsoleCommand(command, kind)
        assertTrue(
            "Expected Allow for '$command' in $kind, got $ruling",
            ruling is ConsoleCommandRuling.Allow,
        )
    }

    private fun assertBlock(command: String, kind: ConsoleSessionKind) {
        val ruling = classifyConsoleCommand(command, kind)
        assertTrue(
            "Expected Block for '$command' in $kind, got $ruling",
            ruling is ConsoleCommandRuling.Block,
        )
    }

    private fun assertIntercept(expected: InterceptAction, command: String, kind: ConsoleSessionKind) {
        val ruling = classifyConsoleCommand(command, kind)
        assertTrue(
            "Expected Intercept($expected) for '$command' in $kind, got $ruling",
            ruling is ConsoleCommandRuling.Intercept && ruling.action == expected,
        )
    }
}
