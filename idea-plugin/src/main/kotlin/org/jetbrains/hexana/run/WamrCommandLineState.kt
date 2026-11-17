package org.jetbrains.hexana.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.shared.WasmRunInput
import org.jetbrains.hexana.shared.buildWamrRunArgs
import org.jetbrains.hexana.toCommonByteBuffer
import java.io.File

/**
 * A command-line state that launches [iwasm](https://github.com/bytecodealliance/wasm-micro-runtime)
 * to run a Wasm module.
 *
 * WAMR (WebAssembly Micro Runtime) uses `iwasm` as its CLI executable.
 * Unlike Wasmtime, WAMR does not support the Component Model or
 * `--preload` for multi-module linking, so dependency handling is
 * intentionally omitted here.
 */
class WamrCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val iwasm = configuration.wamrPath.ifBlank {
            WasmRuntimeSettings.getInstance().effectiveWamrPath()
        }

        val wasmBuffer = File(configuration.wasmFilePath).toCommonByteBuffer()

        val input = WasmRunInput(
            wasmFilePath = configuration.wasmFilePath,
            wasmBuffer = wasmBuffer,
            programArguments = configuration.programArguments,
            workingDirectory = configuration.workingDirectory,
            runtimeOptions = configuration.wamrOptions,
            exportName = configuration.exportName,
        )

        val cmdLine = buildWamrRunArgs(input, JvmStubFileWriter)
        return runCommandLine(iwasm, cmdLine)
    }

    companion object {
        private val LOG = Logger.getInstance(WamrCommandLineState::class.java)

        val DEFAULT_WAMR_PATH: String = if (SystemInfo.isWindows) "iwasm.exe" else "iwasm"

        fun resolveDefaultWamrPath(): String {
            val exeName = if (SystemInfo.isWindows) "iwasm.exe" else "iwasm"
            val candidates = buildList {
                add(System.getProperty("user.home") + File.separator + ".wamr" + File.separator + "bin" + File.separator + exeName)
                if (!SystemInfo.isWindows) {
                    add("/usr/local/bin/iwasm")
                }
            }
            for (candidate in candidates) {
                if (File(candidate).exists()) return candidate
            }
            return DEFAULT_WAMR_PATH
        }

        /**
         * If the given Wasm binary has imports, generates stub `.wasm`
         * modules and writes them into a temporary directory. Returns
         * the directory, or `null` when no stubs are needed.
         *
         * The files are named `<module_name>.wasm` so that WAMR's
         * built-in module reader can locate them by name.
         */
        fun generateImportStubFiles(wasmBuffer: CommonByteBuffer?): File? {
            val stubDir = org.jetbrains.hexana.shared.generateWamrStubFiles(wasmBuffer, JvmStubFileWriter)
            return stubDir?.let { File(it) }
        }
    }
}
