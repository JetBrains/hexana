package org.jetbrains.hexana.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager

/**
 * A [GenericProgramRunner] that handles the **Debug** executor for
 * [WasmRunConfiguration] when the selected engine is **Wasmtime**.
 *
 * It launches wasmtime with the `-D debug-info` flag so that the JIT-compiled
 * native code carries translated DWARF symbols when they are present in the
 * binary.  The process appears in the IDE's Debug tool window.
 *
 * If the Wasm binary contains DWARF metadata, a native debugger (LLDB / GDB)
 * can attach to the running wasmtime process and use the translated symbols
 * for source-level debugging.
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

        val debugState = WasmtimeDebugCommandLineState(configuration, environment)

        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                val executionResult = debugState.execute(environment.executor, this@WasmDebugRunner)
                    ?: throw ExecutionException("Failed to start wasmtime debug process")
                return WasmDebugProcess(
                    session,
                    executionResult.executionConsole,
                    executionResult.processHandler,
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
        const val RUNNER_ID = "WasmDebugRunner"
    }
}
