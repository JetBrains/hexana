package org.jetbrains.hexana.run

import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.execution.ParametersListUtil
import java.io.File

/**
 * A command-line state that launches `iwasm` with the `-g=<host>:<port>`
 * flag so that WAMR starts a GDB remote stub that LLDB can attach to.
 *
 * Unlike Wasmtime (which is wrapped directly inside LLDB), WAMR uses a
 * remote debugging model:
 * 1. `iwasm -g=127.0.0.1:<port> module.wasm` starts the runtime and
 *    listens for a debugger connection.
 * 2. LLDB connects via `process connect --plugin wasm connect://127.0.0.1:<port>`.
 *
 * This class builds the `iwasm` argument list for the debug session.
 * The actual LLDB connection is handled by [WasmDebugRunner].
 */
class WamrDebugCommandLineState(
    private val configuration: WasmRunConfiguration,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val iwasm = configuration.wamrPath.ifBlank { WamrCommandLineState.resolveDefaultWamrPath() }
        val result = buildWamrArguments(configuration)

        val commandLine = GeneralCommandLine().apply {
            exePath = iwasm
            addParameters(result.arguments)

            // When stub modules were generated, use the stub
            // directory as the working directory so WAMR can find
            // the <module_name>.wasm files.
            val workDir = result.stubDir?.absolutePath
                ?: configuration.workingDirectory.ifBlank { null }
            if (workDir != null) {
                withWorkDirectory(workDir)
            }

            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        }

        val handler = OSProcessHandler(commandLine)
        ProcessTerminatedListener.attach(handler)
        return handler
    }

    companion object {
        private val LOG = Logger.getInstance(WamrDebugCommandLineState::class.java)

        /** Default host for the WAMR debug server. */
        const val DEFAULT_DEBUG_HOST = "127.0.0.1"

        /** Default port for the WAMR debug server. */
        const val DEFAULT_DEBUG_PORT = 1234

        /**
         * Builds the iwasm argument list for a debug run.
         *
         * The `-g=<host>:<port>` flag tells iwasm to start a GDB
         * remote stub and wait for a debugger to connect before
         * executing the module.
         *
         * @return a [DebugBuildResult] containing the argument list
         *         and the debug connection address.
         */
        fun buildWamrArguments(
            configuration: WasmRunConfiguration,
            host: String = DEFAULT_DEBUG_HOST,
            port: Int = DEFAULT_DEBUG_PORT,
        ): DebugBuildResult {
            val args = mutableListOf<String>()

            val wasmFile = File(configuration.wasmFilePath)
            val wasmBytes = if (wasmFile.exists()) wasmFile.readBytes() else null
            val stubDir = WamrCommandLineState.generateImportStubFiles(wasmBytes)

            // Enable debug server
            args.add("-g=$host:$port")

            val options = configuration.wamrOptions.trim()
            if (options.isNotEmpty()) {
                args.addAll(ParametersListUtil.parse(options))
            }

            // iwasm uses `-f <function_name>` to invoke a specific
            // exported function instead of the default entry point.
            val functionName = configuration.exportName.trim()
            if (functionName.isNotEmpty()) {
                args.add("-f")
                args.add(functionName)
            }

            args.add(configuration.wasmFilePath)

            val programArgs = configuration.programArguments.trim()
            if (programArgs.isNotEmpty()) {
                args.addAll(programArgs.split("\\s+".toRegex()))
            }

            return DebugBuildResult(
                arguments = args,
                debugHost = host,
                debugPort = port,
                stubDir = stubDir,
            )
        }

        /**
         * Result of building iwasm debug arguments.
         *
         * @property arguments the command-line arguments for iwasm
         * @property debugHost the host address for the GDB remote stub
         * @property debugPort the port for the GDB remote stub
         */
        data class DebugBuildResult(
            val arguments: List<String>,
            val debugHost: String,
            val debugPort: Int,
            val stubDir: File? = null,
        )
    }
}
