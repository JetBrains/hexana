package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.invokeAction
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

internal data class HexanaWasmExecutionResult(
    val consoleOutput: String,
    val launchSnapshot: HexanaRunConfigurationLaunchSnapshot,
)

private fun waitForHexanaRunEditorReady() {
    Thread.sleep(3_000)
}
