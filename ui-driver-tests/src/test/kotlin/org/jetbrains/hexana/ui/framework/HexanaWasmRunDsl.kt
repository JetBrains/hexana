package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Driver
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
    nodeOptions: String = "",
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
    nodeOptions: String = "",
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

internal class HexanaWasmRunDsl internal constructor(
    private val driver: Driver,
) {
    fun runWasm(
        wasmFileName: String,
        wasmtimeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ): HexanaWasmExecutionResult {
        val launchSnapshot = driver.launchWasmtimeRunConfiguration(
            wasmFileName = wasmFileName,
            wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime(),
            wasmtimeOptions = wasmtimeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
        val consoleResult = driver.awaitRunConfigurationConsoleResult()
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
        val launchSnapshot = driver.launchWasmtimeDebugRunConfiguration(
            wasmFileName = wasmFileName,
            wasmtimePath = HexanaRunConfigurationRuntimePreflight.ensureWasmtime(),
            wasmtimeOptions = wasmtimeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
        val consoleResult = driver.awaitRunConfigurationConsoleResult(5.minutes)
        return HexanaWasmExecutionResult(
            consoleOutput = consoleResult.consoleOutput,
            launchSnapshot = launchSnapshot,
        )
    }

    fun runNodeJsWasm(
        wasmFileName: String,
        nodeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ): HexanaWasmExecutionResult {
        val launchSnapshot = driver.launchNodeJsRunConfiguration(
            wasmFileName = wasmFileName,
            nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs(),
            nodeOptions = nodeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
        val consoleResult = driver.awaitRunConfigurationConsoleResult()
        return HexanaWasmExecutionResult(
            consoleOutput = consoleResult.consoleOutput,
            launchSnapshot = launchSnapshot,
        )
    }

    fun debugNodeJsWasm(
        wasmFileName: String,
        nodeOptions: String = "",
        exportName: String = "",
        programArgs: String = "",
    ): HexanaWasmExecutionResult {
        val launchSnapshot = driver.launchNodeJsDebugRunConfiguration(
            wasmFileName = wasmFileName,
            nodePath = HexanaRunConfigurationRuntimePreflight.ensureNodeJs(),
            nodeOptions = nodeOptions,
            exportName = exportName,
            programArgs = programArgs,
        )
        val consoleResult = driver.awaitRunConfigurationConsoleResult(5.minutes)
        return HexanaWasmExecutionResult(
            consoleOutput = consoleResult.consoleOutput,
            launchSnapshot = launchSnapshot,
        )
    }
}

internal data class HexanaWasmExecutionResult(
    val consoleOutput: String,
    val launchSnapshot: HexanaRunConfigurationLaunchSnapshot,
)

private fun waitForHexanaRunEditorReady() {
    Thread.sleep(3_000)
}
