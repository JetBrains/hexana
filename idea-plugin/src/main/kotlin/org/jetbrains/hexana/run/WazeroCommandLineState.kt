package org.jetbrains.hexana.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.hexana.shared.WasmRunInput
import org.jetbrains.hexana.shared.buildWazeroRunArgs
import java.io.File

/**
 * A command-line state that launches [wazero](https://wazero.io/)
 * to run a Wasm module.
 *
 * Wazero is a zero-dependency Go-based WebAssembly runtime.
 * It does not support the Component Model or debugging.
 */
class WazeroCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val wazeroExe = configuration.wazeroPath.ifBlank {
            WasmRuntimeSettings.getInstance().effectiveWazeroPath()
        }

        val wasmFile = File(configuration.wasmFilePath)
        val wasmBytes = if (wasmFile.exists()) wasmFile.readBytes() else null

        val input = WasmRunInput(
            wasmFilePath = configuration.wasmFilePath,
            wasmBytes = wasmBytes,
            programArguments = configuration.programArguments,
            workingDirectory = configuration.workingDirectory,
            runtimeOptions = configuration.wazeroOptions,
            exportName = configuration.exportName,
        )

        val cmdLine = buildWazeroRunArgs(input, JvmStubFileWriter)
        return runCommandLine(wazeroExe, cmdLine)
    }

    companion object {
        val DEFAULT_WAZERO_PATH: String = if (SystemInfo.isWindows) "wazero.exe" else "wazero"

        fun resolveDefaultWazeroPath(): String {
            val exeName = if (SystemInfo.isWindows) "wazero.exe" else "wazero"
            val candidates = buildList {
                add(System.getProperty("user.home") + File.separator + "go" + File.separator + "bin" + File.separator + exeName)
                if (!SystemInfo.isWindows) {
                    add("/usr/local/bin/wazero")
                    add("/usr/bin/wazero")
                }
            }
            for (candidate in candidates) {
                if (File(candidate).exists()) return candidate
            }
            return DEFAULT_WAZERO_PATH
        }
    }
}
