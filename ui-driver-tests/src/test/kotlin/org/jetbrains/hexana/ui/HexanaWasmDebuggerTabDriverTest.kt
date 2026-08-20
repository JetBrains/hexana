package org.jetbrains.hexana.ui

import com.intellij.tools.ide.util.common.logOutput
import org.jetbrains.hexana.ui.framework.HexanaWasmDebuggerStep
import org.jetbrains.hexana.ui.framework.HexanaWasmDebuggerTabState
import org.jetbrains.hexana.ui.framework.hexanaIde
import org.jetbrains.hexana.ui.framework.withWasmRunDsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * UI driver tests for the "Wasm" debugger tab: the decompiled instruction list
 * shown next to the symbolic operand stack while a wasm debug session is paused,
 * and the per-instruction stepping it enables.
 *
 * Covers:
 * - A paused Node.js/CDP session shows disassembly with the current instruction highlighted.
 * - Clicking the tab makes it the selected debugger content, which is what switches stepping
 *   from source-line to wasm-instruction granularity.
 * - Step over / step into move the highlight to the next wasm instruction (Node.js/CDP and the
 *   Wasmtime gdbstub, the two engines that implement instruction stepping).
 * - Step out leaves the wasm frame without leaving stale disassembly behind.
 *
 * Tab selection goes through the real debugger layout (a Driver click), because only the
 * production `ContentManagerListener` flips the stepping granularity. The step commands
 * themselves are issued through the packaged probe, which calls the same
 * `XDebugSession.stepOver/stepInto/stepOut` entry points as the debugger toolbar actions —
 * matching how the existing debug flows in this module drive resume.
 *
 * Node.js must be pre-installed; Wasmtime is downloaded automatically and the Wasmtime case
 * additionally needs an LLDB with the wasm plugin, same as the DWARF breakpoint tests in
 * [HexanaRunConfigurationDriverTest].
 */
@Tag("hexana")
class HexanaWasmDebuggerTabDriverTest {

    @Test
    fun wasmDebuggerTabShowsHighlightedDisassemblyForPausedNodeJsSession() {
        hexanaIde(testName = "hexana-wasm-debugger-tab-nodejs") {
            maximizeWindow()
            closeProjectToolWindow()
            withWasmRunDsl(DEBUG_WASM_FILE) {
                try {
                    startNodeJsDebugPausedAtBreakpoint(
                        wasmFileName = DEBUG_WASM_FILE,
                        sourceFileName = DEBUG_SOURCE_FILE,
                        breakpointLine = BREAKPOINT_LINE,
                        exportName = EXPORT_NAME,
                        programArgs = PROGRAM_ARGS,
                    )

                    val paused = awaitHighlightedWasmInstruction()
                    logOutput("Node.js Wasm debugger tab at the breakpoint: $paused")

                    assertTrue(
                        paused.tabRegistered,
                        "Expected the Wasm debugger tab to be registered for a Node.js wasm session; state: $paused",
                    )
                    assertTrue(
                        paused.disassemblyRowCount > 0,
                        "Expected disassembly rows for the paused function; state: $paused",
                    )
                    assertTrue(
                        paused.selectedRow in 0 until paused.disassemblyRowCount,
                        "Expected the highlighted row to be inside the disassembly; state: $paused",
                    )
                    assertTrue(
                        paused.selectedOffset > 0,
                        "Expected the highlighted row to carry a module byte offset; state: $paused",
                    )
                    assertTrue(
                        paused.selectedInstruction.isNotBlank(),
                        "Expected the highlighted row to render an instruction; state: $paused",
                    )

                    selectWasmDebuggerTab()
                    val selected = awaitWasmDebuggerTabState("the Wasm tab to become the selected debugger content") {
                        it.tabSelected
                    }
                    assertTrue(
                        selected.hasHighlightedInstruction,
                        "Expected the highlight to survive selecting the Wasm tab; state: $selected",
                    )
                    assertEquals(
                        paused.selectedOffset,
                        selected.selectedOffset,
                        "Expected selecting the Wasm tab to keep the highlight on the paused instruction",
                    )
                } finally {
                    stopWasmDebugSession()
                }
            }
        }
    }

    @Test
    fun steppingInWasmDebuggerTabAdvancesHighlightedInstructionForNodeJs() {
        hexanaIde(testName = "hexana-wasm-debugger-tab-stepping-nodejs") {
            maximizeWindow()
            closeProjectToolWindow()
            withWasmRunDsl(DEBUG_WASM_FILE) {
                try {
                    startNodeJsDebugPausedAtBreakpoint(
                        wasmFileName = DEBUG_WASM_FILE,
                        sourceFileName = DEBUG_SOURCE_FILE,
                        breakpointLine = BREAKPOINT_LINE,
                        exportName = EXPORT_NAME,
                        programArgs = PROGRAM_ARGS,
                    )
                    val visited = stepThroughWasmInstructions(stepOverCount = STEP_OVER_COUNT)

                    // Stepping out of `add` returns to the JavaScript caller, so the tab must
                    // drop the disassembly instead of keeping the previous frame's rows. The
                    // session may also finish, since `add` is invoked exactly once.
                    stepInWasmDebugger(HexanaWasmDebuggerStep.OUT)
                    val afterStepOut = awaitWasmDebuggerTabState("step out to leave the wasm frame") { state ->
                        !state.sessionActive || (state.sessionSuspended && !state.hasHighlightedInstruction)
                    }
                    logOutput("Node.js Wasm debugger tab after step out: $afterStepOut")
                    logOutput("Node.js instruction walk: ${visited.joinToString { it.describeHighlight() }}")
                } finally {
                    stopWasmDebugSession()
                }
            }
        }
    }

    @Test
    fun steppingInWasmDebuggerTabAdvancesHighlightedInstructionForWasmtimeGdbstub() {
        hexanaIde(testName = "hexana-wasm-debugger-tab-stepping-wasmtime") {
            maximizeWindow()
            closeProjectToolWindow()
            withWasmRunDsl(DEBUG_WASM_FILE) {
                try {
                    startWasmtimeDebugPausedAtBreakpoint(
                        wasmFileName = DEBUG_WASM_FILE,
                        sourceFileName = DEBUG_SOURCE_FILE,
                        breakpointLine = BREAKPOINT_LINE,
                        wasmtimeOptions = "--invoke $EXPORT_NAME",
                        exportName = EXPORT_NAME,
                        programArgs = PROGRAM_ARGS,
                    )
                    val visited = stepThroughWasmInstructions(stepOverCount = WASMTIME_STEP_OVER_COUNT)
                    logOutput("Wasmtime instruction walk: ${visited.joinToString { it.describeHighlight() }}")
                } finally {
                    stopWasmDebugSession()
                }
            }
        }
    }

    /**
     * Selects the Wasm tab, walks [stepOverCount] step-overs plus one step-into from the
     * current pause, and asserts every stop highlighted a new instruction of the same
     * function at instruction granularity. Returns the visited states in order.
     */
    private fun org.jetbrains.hexana.ui.framework.HexanaWasmRunDsl.stepThroughWasmInstructions(
        stepOverCount: Int,
    ): List<HexanaWasmDebuggerTabState> {
        awaitHighlightedWasmInstruction()

        selectWasmDebuggerTab()
        val selected = awaitWasmDebuggerTabState("the Wasm tab to become the selected debugger content") {
            it.tabSelected
        }
        assertTrue(
            selected.hasHighlightedInstruction,
            "Expected an instruction to stay highlighted after selecting the Wasm tab; state: $selected",
        )

        val visited = mutableListOf(selected)
        repeat(stepOverCount) {
            visited += stepInWasmDebuggerAndAwaitNewHighlight(
                step = HexanaWasmDebuggerStep.OVER,
                previousOffset = visited.last().selectedOffset,
            )
        }
        visited += stepInWasmDebuggerAndAwaitNewHighlight(
            step = HexanaWasmDebuggerStep.INTO,
            previousOffset = visited.last().selectedOffset,
        )

        assertDistinctHighlights(visited)
        assertSteppedOneInstruction(visited)
        return visited
    }

    /** Every stop must highlight a rendered instruction, and no offset may repeat. */
    private fun assertDistinctHighlights(visited: List<HexanaWasmDebuggerTabState>) {
        visited.forEach { state ->
            assertTrue(
                state.hasHighlightedInstruction && state.selectedInstruction.isNotBlank(),
                "Expected every step to highlight a rendered instruction; state: $state",
            )
        }
        val offsets = visited.map { it.selectedOffset }
        assertEquals(
            offsets.size,
            offsets.distinct().size,
            "Expected every step to move the highlight to a new offset, got $offsets",
        )
    }

    /**
     * With the Wasm tab selected, stepping is per wasm instruction rather than per source
     * line, so at least one step within the same function must land on the very next
     * disassembly row. Source-line stepping would skip several rows on every step.
     */
    private fun assertSteppedOneInstruction(visited: List<HexanaWasmDebuggerTabState>) {
        val advancedByOneRow = visited.zipWithNext().any { (previous, next) ->
            previous.disassemblyRowCount == next.disassemblyRowCount &&
                next.selectedRow == previous.selectedRow + 1
        }
        assertTrue(
            advancedByOneRow,
            "Expected at least one step to advance exactly one disassembly row (instruction granularity), " +
                "got ${visited.joinToString { it.describeHighlight() }}",
        )
    }

    private fun HexanaWasmDebuggerTabState.describeHighlight(): String =
        "row $selectedRow/$disassemblyRowCount @0x${selectedOffset.toString(16)} '${selectedInstruction.trim()}'"

    private companion object {
        const val DEBUG_WASM_FILE = "debug-add.wasm"
        const val DEBUG_SOURCE_FILE = "debug_add.rs"
        const val BREAKPOINT_LINE = 10 // `let sum = a + b;`
        const val EXPORT_NAME = "add"
        const val PROGRAM_ARGS = "3 7"
        const val STEP_OVER_COUNT = 3
        const val WASMTIME_STEP_OVER_COUNT = 2
    }
}
