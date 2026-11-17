package org.jetbrains.hexana.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.hexana.toCommonByteBuffer
import org.jetbrains.hexana.shared.WasmRunInput
import org.jetbrains.hexana.shared.buildWasmtimeRunArgs
import org.jetbrains.hexana.shared.classifyDependencies
import java.io.File

class WasmtimeCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val wasmtimePath = configuration.wasmtimePath.ifBlank {
            WasmRuntimeSettings.getInstance().effectiveWasmtimePath()
        }

        val wasmBuffer = File(configuration.wasmFilePath).toCommonByteBuffer()
        val resolvedDeps = resolveDeps(configuration, wasmBuffer, this.environment.project)

        val (coreModuleDeps, componentDeps) = classifyDependencies(resolvedDeps) { path ->
            val f = File(path)
            if (f.exists()) f.readBytes() else null
        }

        val effectiveWasmPath = if (componentDeps.isNotEmpty()) {
            val result = WacComponentComposer.compose(configuration.wasmFilePath, componentDeps)
            if (!result.composed && result.error != null) {
                LOG.warn("Component composition failed: ${result.error}")
            }
            result.wasmPath
        } else {
            configuration.wasmFilePath
        }

        val input = WasmRunInput(
            wasmFilePath = configuration.wasmFilePath,
            wasmBuffer = wasmBuffer,
            programArguments = configuration.programArguments,
            workingDirectory = configuration.workingDirectory,
            runtimeOptions = configuration.wasmtimeOptions,
            effectiveWasmPath = effectiveWasmPath,
        )

        val cmdLine = buildWasmtimeRunArgs(input, coreModuleDeps)
        return runCommandLine(wasmtimePath, cmdLine)
    }

    companion object {
        private val LOG = Logger.getInstance(WasmtimeCommandLineState::class.java)

        val DEFAULT_WASMTIME_PATH: String = run {
            val exeName = if (SystemInfo.isWindows) "wasmtime.exe" else "wasmtime"
            System.getProperty("user.home") + File.separator + ".wasmtime" + File.separator + "bin" + File.separator + exeName
        }

        fun resolveDefaultWasmtimePath(): String {
            val defaultPath = DEFAULT_WASMTIME_PATH
            return if (File(defaultPath).exists()) defaultPath else {
                if (SystemInfo.isWindows) "wasmtime.exe" else "wasmtime"
            }
        }
    }
}
