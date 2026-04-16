package org.jetbrains.hexana.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager

/**
 * A [GenericProgramRunner] that handles the **Debug** executor for
 * [WasmRunConfiguration] when the selected engine is **Wasmtime**.
 *
 * It launches wasmtime under LLDB so that breakpoints set in the IDE
 * are forwarded to the native debugger and actually hit when the Wasm
 * binary contains DWARF debug info.
 *
 * The LLDB process wraps the wasmtime invocation:
 * ```
 * lldb -- wasmtime run -D debug-info [options] foo.wasm
 * ```
 *
 * On macOS, LLDB's JIT loader plugin is enabled automatically so that
 * symbols in JIT-compiled code are visible to the debugger.
 */
class WasmDebugRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID) return false
        val config = profile as? WasmRunConfiguration ?: return false
        return config.engine == WasmEngine.WASMTIME
    }

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val configuration = environment.runProfile as? WasmRunConfiguration
            ?: throw ExecutionException("Expected WasmRunConfiguration")

        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                val buildResult = WasmtimeDebugCommandLineState.buildWasmtimeArguments(configuration)
                val wasmtimePath = configuration.wasmtimePath.ifBlank {
                    WasmtimeCommandLineState.resolveDefaultWasmtimePath()
                }

                val commandLine = GeneralCommandLine().apply {
                    exePath = "lldb"
                    addParameter("--")
                    addParameter(wasmtimePath)
                    addParameters(buildResult.arguments)

                    val workDir = configuration.workingDirectory.ifBlank { null }
                    if (workDir != null) {
                        withWorkDirectory(workDir)
                    }
                    withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                }

                LOG.info("Starting LLDB: ${commandLine.commandLineString}")

                val processHandler = OSProcessHandler(commandLine)

                // Create the LLDB communicator and attach it as a process listener
                // *before* starting the process handler, so no output is missed.
                val lldb = LldbCommunicator(processHandler)
                lldb.attachToProcessHandler()

                ProcessTerminatedListener.attach(processHandler)
                processHandler.startNotify()

                val console = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(environment.project)
                    .console
                    .also { it.attachToProcess(processHandler) }

                // Enable JIT loader for macOS so LLDB can see JIT-compiled symbols
                if (System.getProperty("os.name")?.lowercase()?.contains("mac") == true) {
                    lldb.sendCommand("settings set plugin.jit-loader.gdb.enable on")
                }

                // Note: we do NOT call lldb.runProcess() here.
                // The platform will first call registerBreakpoint() for every
                // existing breakpoint and then call sessionInitialized().
                // We start the inferior in sessionInitialized() so that all
                // breakpoints are already set in LLDB when wasmtime launches.

                return WasmDebugProcess(
                    session,
                    console,
                    processHandler,
                    lldb,
                    buildResult.compositionUsed,
                )
            }
        }

        val result = XDebuggerManager.getInstance(environment.project)
            .newSessionBuilder(starter)
            .environment(environment)
            .startSession()

        return result.runContentDescriptor
    }

    companion object {
        private val LOG = Logger.getInstance(WasmDebugRunner::class.java)
        const val RUNNER_ID = "WasmDebugRunner"
    }
}
