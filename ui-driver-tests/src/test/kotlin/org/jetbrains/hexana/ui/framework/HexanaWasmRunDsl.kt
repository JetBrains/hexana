package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.ui
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

internal fun HexanaIde.runWasm(
    wasmFileName: String,
    wasmtimeOptions: String = "",
    exportName: String = "",
    programArgs: String = "",
): HexanaWasmExecutionResult {
    hexanaEditor(wasmFileName)
    waitForHexanaRunEditorReady()
    return HexanaWasmRunDsl(driver).runWasm(
        wasmFileName = wasmFileName,
        wasmtimeOptions = wasmtimeOptions,
        exportName = exportName,
        programArgs = programArgs,
    )
}

internal fun HexanaIde.debugWasm(
    wasmFileName: String,
    wasmtimeOptions: String = "",
    exportName: String = "",
    programArgs: String = "",
): HexanaWasmExecutionResult {
    hexanaEditor(wasmFileName)
    waitForHexanaRunEditorReady()
    return HexanaWasmRunDsl(driver).debugWasm(
        wasmFileName = wasmFileName,
        wasmtimeOptions = wasmtimeOptions,
        exportName = exportName,
        programArgs = programArgs,
    )
}

internal fun HexanaIde.runNodeJsWasm(
    wasmFileName: String,
    nodeOptions: String? = null,
    exportName: String = "",
    programArgs: String = "",
): HexanaWasmExecutionResult {
    hexanaEditor(wasmFileName)
    waitForHexanaRunEditorReady()
    return HexanaWasmRunDsl(driver).runNodeJsWasm(
        wasmFileName = wasmFileName,
        nodeOptions = nodeOptions,
        exportName = exportName,
        programArgs = programArgs,
    )
}

internal fun HexanaIde.debugNodeJsWasm(
    wasmFileName: String,
    nodeOptions: String? = null,
    exportName: String = "",
    programArgs: String = "",
): HexanaWasmExecutionResult {
    hexanaEditor(wasmFileName)
    waitForHexanaRunEditorReady()
    return HexanaWasmRunDsl(driver).debugNodeJsWasm(
        wasmFileName = wasmFileName,
        nodeOptions = nodeOptions,
        exportName = exportName,
        programArgs = programArgs,
    )
}

internal fun HexanaIde.debugNodeJsWasmWithBreakpoint(
    wasmFileName: String,
    sourceFileName: String,
    breakpointLine: Int,
    nodeOptions: String? = null,
    exportName: String = "",
    programArgs: String = "",
): HexanaWasmBreakpointDebugResult {
    hexanaEditor(wasmFileName)
    waitForHexanaRunEditorReady()
    return HexanaWasmRunDsl(driver).debugNodeJsWasmWithBreakpoint(
        wasmFileName = wasmFileName,
        sourceFileName = sourceFileName,
        breakpointLine = breakpointLine,
        nodeOptions = nodeOptions,
        exportName = exportName,
        programArgs = programArgs,
    )
}

internal fun HexanaIde.debugWasmWithBreakpoint(
    wasmFileName: String,
    sourceFileName: String,
    breakpointLine: Int,
    wasmtimeOptions: String = "",
    exportName: String = "",
    programArgs: String = "",
): HexanaWasmBreakpointDebugResult {
    hexanaEditor(wasmFileName)
    waitForHexanaRunEditorReady()
    return HexanaWasmRunDsl(driver).debugWasmWithBreakpoint(
        wasmFileName = wasmFileName,
        sourceFileName = sourceFileName,
        breakpointLine = breakpointLine,
        wasmtimeOptions = wasmtimeOptions,
        exportName = exportName,
        programArgs = programArgs,
    )
}

internal fun <T> HexanaIde.withWasmRunDsl(
    wasmFileName: String,
    action: HexanaWasmRunDsl.() -> T,
): T {
    hexanaEditor(wasmFileName)
    waitForHexanaRunEditorReady()
    return HexanaWasmRunDsl(driver).action()
}

internal class HexanaWasmRunDsl internal constructor(
    private val driver: Driver,
) {
    fun runWasm(
        wasmFileName: String,
        wasmtimeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ): HexanaWasmExecutionResult {
        val previousSelectedContent = driver.selectedRunConfigurationContent()
        val launchSnapshot = driver.launchWasmtimeRunConfiguration(
            wasmFileName = wasmFileName,
            wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime(),
            wasmtimeOptions = wasmtimeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
        val consoleResult = driver.awaitRunConfigurationConsoleResult(
            expectedConfigurationName = launchSnapshot.configurationName,
            previousSelectedContent = previousSelectedContent,
        )
        return HexanaWasmExecutionResult(
            consoleOutput = consoleResult.consoleOutput,
            launchSnapshot = launchSnapshot,
        )
    }

    fun debugWasm(
        wasmFileName: String,
        wasmtimeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ): HexanaWasmExecutionResult {
        val previousSelectedContent = driver.selectedRunConfigurationContent()
        val launchSnapshot = driver.launchWasmtimeDebugRunConfiguration(
            wasmFileName = wasmFileName,
            wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime(),
            wasmtimeOptions = wasmtimeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
        val consoleResult = driver.awaitRunConfigurationConsoleResult(
            expectedConfigurationName = launchSnapshot.configurationName,
            previousSelectedContent = previousSelectedContent,
            timeout = 5.minutes,
        )
        return HexanaWasmExecutionResult(
            consoleOutput = consoleResult.consoleOutput,
            launchSnapshot = launchSnapshot,
        )
    }

    fun runNodeJsWasm(
        wasmFileName: String,
        nodeOptions: String? = null,
        exportName: String = "",
        programArgs: String = "",
    ): HexanaWasmExecutionResult {
        val previousSelectedContent = driver.selectedRunConfigurationContent()
        val launchSnapshot = driver.launchNodeJsRunConfiguration(
            wasmFileName = wasmFileName,
            nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs(),
            nodeOptions = nodeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
        val consoleResult = driver.awaitRunConfigurationConsoleResult(
            expectedConfigurationName = launchSnapshot.configurationName,
            previousSelectedContent = previousSelectedContent,
        )
        return HexanaWasmExecutionResult(
            consoleOutput = consoleResult.consoleOutput,
            launchSnapshot = launchSnapshot,
        )
    }

    fun clearNodeJsWasmRunConfigurations(
        wasmFileName: String,
        exportName: String = "",
    ): Int =
        driver.clearNodeJsRunConfigurations(
            wasmFileName = wasmFileName,
            exportName = exportName,
        )

    fun debugNodeJsWasm(
        wasmFileName: String,
        nodeOptions: String? = null,
        exportName: String = "",
        programArgs: String = "",
    ): HexanaWasmExecutionResult {
        val previousSelectedContent = driver.selectedRunConfigurationContent()
        val launchSnapshot = driver.launchNodeJsDebugRunConfiguration(
            wasmFileName = wasmFileName,
            nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs(),
            nodeOptions = nodeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
        val consoleResult = driver.awaitRunConfigurationConsoleResult(
            expectedConfigurationName = launchSnapshot.configurationName,
            previousSelectedContent = previousSelectedContent,
            timeout = 5.minutes,
        )
        return HexanaWasmExecutionResult(
            consoleOutput = consoleResult.consoleOutput,
            launchSnapshot = launchSnapshot,
        )
    }

    fun debugNodeJsWasmWithBreakpoint(
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        nodeOptions: String? = null,
        exportName: String = "",
        programArgs: String = "",
    ): HexanaWasmBreakpointDebugResult {
        val rawOutput = driver.debugNodeJsWithBreakpointAndInspectVariables(
            wasmFileName = wasmFileName,
            sourceFileName = sourceFileName,
            breakpointLine = breakpointLine,
            nodeOptions = nodeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
        return HexanaWasmBreakpointDebugResult.parse(rawOutput)
    }

    fun startNodeJsDebugWithBreakpointForCapture(
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        nodeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ) {
        driver.startNodeJsDebugWithBreakpointForCapture(
            wasmFileName,
            sourceFileName,
            breakpointLine,
            nodeOptions,
            exportName,
            programArgs,
        )
    }

    fun startWasmtimeDebugWithBreakpointForCapture(
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        wasmtimeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ) {
        driver.startWasmtimeDebugWithBreakpointForCapture(
            wasmFileName = wasmFileName,
            sourceFileName = sourceFileName,
            breakpointLine = breakpointLine,
            wasmtimeOptions = wasmtimeOptions,
            exportName = exportName,
            programArgs = programArgs,
            wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtimeForWhatsNew018Capture(),
            lldbPath = HexanaRunConfigurationRuntimePreflight.ensureWasmLldbForWhatsNew018Capture(),
        )
    }

    fun startWasmtimeComponentDebugPausedForCapture(
        wasmFileName: String,
        moduleId: Int,
        moduleRelativeOffset: Int,
    ) {
        driver.startWasmtimeComponentDebugPausedForCapture(
            wasmFileName = wasmFileName,
            moduleId = moduleId,
            moduleRelativeOffset = moduleRelativeOffset,
            wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtimeForWhatsNew018Capture(),
            lldbPath = HexanaRunConfigurationRuntimePreflight.ensureWasmLldbForWhatsNew018Capture(),
        )
    }

    fun wasmtimeDebuggerCaptureState(): HexanaWasmtimeDebuggerCaptureState =
        driver.wasmtimeDebuggerCaptureState()

    fun stepIntoInstructionForCapture() {
        driver.invokeAction("StepInto")
    }

    fun terminateDebuggerForCapture() {
        driver.terminateDebuggerForCapture()
    }

    fun cdpDebuggerCaptureState(): HexanaCdpDebuggerCaptureState = driver.cdpDebuggerCaptureState()

    fun resumeDebuggerForCapture() {
        driver.resumeDebuggerForCapture()
    }

    /**
     * Starts a Node.js/CDP debug session and leaves it paused at the DWARF
     * breakpoint in [sourceFileName]:[breakpointLine]. The caller owns the
     * paused session and must finish it with [stopWasmDebugSession].
     */
    fun startNodeJsDebugPausedAtBreakpoint(
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        nodeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ) {
        driver.startWasmDebugPausedAtBreakpoint(
            wasmFileName = wasmFileName,
            sourceFileName = sourceFileName,
            breakpointLine = breakpointLine,
            engine = "NODE_JS",
            runtimeOptions = nodeOptions,
            exportName = exportName,
            programArgs = programArgs,
            runtimePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs(),
        )
    }

    /**
     * Starts a Wasmtime gdbstub debug session and leaves it paused at the DWARF
     * breakpoint in [sourceFileName]:[breakpointLine]. The caller owns the
     * paused session and must finish it with [stopWasmDebugSession].
     */
    fun startWasmtimeDebugPausedAtBreakpoint(
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        wasmtimeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ) {
        driver.startWasmDebugPausedAtBreakpoint(
            wasmFileName = wasmFileName,
            sourceFileName = sourceFileName,
            breakpointLine = breakpointLine,
            engine = "WASMTIME",
            runtimeOptions = wasmtimeOptions,
            exportName = exportName,
            programArgs = programArgs,
            runtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime(),
        )
    }

    /**
     * Clicks the "Wasm" debugger tab through the real debugger layout, which is
     * what switches stepping to wasm-instruction granularity.
     */
    fun selectWasmDebuggerTab(timeout: Duration = 2.minutes) {
        driver.withContext {
            ui.x { and(byClass("SingleHeightLabel"), byAccessibleName("Wasm")) }.waitFound(timeout).click()
        }
    }

    /** Selects the LLDB-backed debugger console through the active debugger layout. */
    fun selectDebuggerConsoleTab() {
        driver.selectDebuggerConsoleForCapture()
    }

    /** Enters a non-mutating LLDB command into the visible Debugger Console field. */
    fun enterDebuggerConsoleCommand(command: String) {
        driver.enterDebuggerConsoleCommandForCapture(command)
    }

    /** Submits the entered command and semantically awaits its visible console output. */
    fun submitDebuggerConsoleCommand(expectedOutput: String) {
        driver.submitDebuggerConsoleCommandForCapture(expectedOutput)
    }

    fun wasmDebuggerTabState(): HexanaWasmDebuggerTabState = driver.wasmDebuggerTabState()

    /**
     * Polls the "Wasm" debugger tab state until [predicate] holds, then returns
     * the matching state. Fails with [description] and the last observed state
     * when [timeout] expires.
     */
    fun awaitWasmDebuggerTabState(
        description: String,
        timeout: Duration = 2.minutes,
        predicate: (HexanaWasmDebuggerTabState) -> Boolean,
    ): HexanaWasmDebuggerTabState {
        val deadline = System.nanoTime() + timeout.inWholeNanoseconds
        var lastState: HexanaWasmDebuggerTabState? = null
        while (System.nanoTime() < deadline) {
            lastState = wasmDebuggerTabState()
            if (predicate(lastState)) return lastState
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for $description; last Wasm debugger tab state: $lastState")
    }

    /** Waits until the session is suspended with a wasm instruction highlighted in the tab. */
    fun awaitHighlightedWasmInstruction(timeout: Duration = 2.minutes): HexanaWasmDebuggerTabState =
        awaitWasmDebuggerTabState("a paused wasm frame with a highlighted instruction", timeout) { state ->
            state.sessionActive && state.sessionSuspended && state.hasHighlightedInstruction
        }

    /**
     * Issues [step] through the same session entry point as the debugger toolbar
     * action and waits for the next pause to highlight an instruction other than
     * the one at [previousOffset].
     */
    fun stepInWasmDebuggerAndAwaitNewHighlight(
        step: HexanaWasmDebuggerStep,
        previousOffset: Int,
        timeout: Duration = 2.minutes,
    ): HexanaWasmDebuggerTabState {
        driver.stepInWasmDebugSession(step.id)
        return awaitWasmDebuggerTabState("${step.id} step to leave offset $previousOffset", timeout) { state ->
            state.sessionActive &&
                state.sessionSuspended &&
                state.hasHighlightedInstruction &&
                state.selectedOffset != previousOffset
        }
    }

    /** Issues [step] without waiting for the resulting pause. */
    fun stepInWasmDebugger(step: HexanaWasmDebuggerStep) {
        driver.stepInWasmDebugSession(step.id)
    }

    fun stopWasmDebugSession() {
        driver.stopWasmDebugSession()
    }

    fun debugWasmWithBreakpoint(
        wasmFileName: String,
        sourceFileName: String,
        breakpointLine: Int,
        wasmtimeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ): HexanaWasmBreakpointDebugResult {
        val rawOutput = driver.debugWasmtimeWithBreakpointAndInspectVariables(
            wasmFileName = wasmFileName,
            sourceFileName = sourceFileName,
            breakpointLine = breakpointLine,
            wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime(),
            wasmtimeOptions = wasmtimeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
        return HexanaWasmBreakpointDebugResult.parse(rawOutput)
    }
}

/** Debugger step actions available while the "Wasm" debugger tab is open. */
internal enum class HexanaWasmDebuggerStep(val id: String) {
    OVER("over"),
    INTO("into"),
    OUT("out"),
}

internal data class HexanaWasmExecutionResult(
    val consoleOutput: String,
    val launchSnapshot: HexanaRunConfigurationLaunchSnapshot,
)

private fun waitForHexanaRunEditorReady() {
    Thread.sleep(3_000)
}
