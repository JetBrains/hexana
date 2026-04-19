package org.jetbrains.hexana.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A [GenericProgramRunner] that handles the **Debug** executor for
 * [WasmRunConfiguration] when the selected engine is **Wasmtime** or
 * **WAMR**.
 *
 * ### Wasmtime
 * Launches wasmtime under LLDB so that breakpoints set in the IDE
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
 *
 * ### WAMR
 * Launches `iwasm -g=<host>:<port> module.wasm` which starts a GDB
 * remote debug stub, then launches LLDB separately and connects to
 * the stub via `process connect --plugin wasm connect://<host>:<port>`.
 */
class WasmDebugRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID) return false
        val config = profile as? WasmRunConfiguration ?: return false
        return config.engine == WasmEngine.WASMTIME || config.engine == WasmEngine.WAMR
    }

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val configuration = environment.runProfile as? WasmRunConfiguration
            ?: throw ExecutionException("Expected WasmRunConfiguration")

        return when (configuration.engine) {
            WasmEngine.WASMTIME -> doExecuteWasmtime(configuration, environment)
            WasmEngine.WAMR -> doExecuteWamr(configuration, environment)
            else -> throw ExecutionException("Debug is not supported for ${configuration.engine.displayName}")
        }
    }

    private fun doExecuteWasmtime(
        configuration: WasmRunConfiguration,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor? {
        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                val buildResult = WasmtimeDebugCommandLineState.buildWasmtimeArguments(configuration, environment.project)
                val wasmtimePath = configuration.wasmtimePath.ifBlank {
                    WasmtimeCommandLineState.resolveDefaultWasmtimePath()
                }

                val lldbExe = resolveLldbPath(configuration)
                val commandLine = GeneralCommandLine().apply {
                    exePath = lldbExe
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

                // Parse DWARF variable metadata from the Wasm binary so
                // the debugger can use location descriptions to resolve
                // variables that LLDB's standard `frame variable` cannot.
                val dwarfResolver = createDwarfResolver(configuration)

                return WasmDebugProcess(
                    session,
                    console,
                    processHandler,
                    lldb,
                    compositionUsed = buildResult.compositionUsed,
                    dwarfInjected = buildResult.dwarfInjected,
                    engine = WasmEngine.WASMTIME,
                ).also {
                    it.dwarfVariableResolver = dwarfResolver
                }
            }
        }

        val result = XDebuggerManager.getInstance(environment.project)
            .newSessionBuilder(starter)
            .environment(environment)
            .startSession()

        return result.runContentDescriptor
    }

    /**
     * Launches a WAMR debug session:
     * 1. Starts `iwasm -g=host:port module.wasm` as a background process.
     * 2. Starts a separate LLDB process.
     * 3. Connects LLDB to the WAMR debug stub via
     *    `process connect --plugin wasm connect://host:port`.
     */
    private fun doExecuteWamr(
        configuration: WasmRunConfiguration,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor? {
        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                val buildResult = WamrDebugCommandLineState.buildWamrArguments(configuration)
                val iwasmPath = configuration.wamrPath.ifBlank {
                    WamrCommandLineState.resolveDefaultWamrPath()
                }

                // Step 1: Start iwasm with debug server
                val iwasmCommandLine = GeneralCommandLine().apply {
                    exePath = iwasmPath
                    addParameters(buildResult.arguments)

                    // When stub modules were generated, use the stub
                    // directory as the working directory so WAMR can
                    // find the <module_name>.wasm files.
                    val workDir = buildResult.stubDir?.absolutePath
                        ?: configuration.workingDirectory.ifBlank { null }
                    if (workDir != null) {
                        withWorkDirectory(workDir)
                    }
                    withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                }

                LOG.info("Starting WAMR debug server: ${iwasmCommandLine.commandLineString}")

                val iwasmHandler = OSProcessHandler(iwasmCommandLine)
                ProcessTerminatedListener.attach(iwasmHandler)

                // Wait for the WAMR debug server to start listening
                // before launching LLDB. iwasm prints a line like
                //   "Debug server listening on 127.0.0.1:1234"
                // when the GDB remote stub is ready for connections.
                // We also collect all output so that if iwasm exits
                // early we can include the actual error in the message.
                val serverReadyLatch = CountDownLatch(1)
                val iwasmOutput = Collections.synchronizedList(mutableListOf<String>())
                iwasmHandler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        val text = event.text ?: return
                        iwasmOutput.add(text)
                        if (text.contains("Debug server listening on") ||
                            text.contains("debug server listening on")) {
                            serverReadyLatch.countDown()
                        }
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        // Unblock the latch if iwasm exits before we see
                        // the listening message (e.g. bad arguments).
                        serverReadyLatch.countDown()
                    }
                })
                iwasmHandler.startNotify()

                val serverReady = serverReadyLatch.await(
                    WAMR_DEBUG_SERVER_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS
                )
                if (!serverReady) {
                    LOG.warn(
                        "Timed out waiting for WAMR debug server to start " +
                            "listening (${WAMR_DEBUG_SERVER_READY_TIMEOUT_MS}ms). " +
                            "Proceeding with LLDB connection anyway."
                    )
                }
                if (iwasmHandler.isProcessTerminated) {
                    val output = iwasmOutput.joinToString("").trim()
                    val exitCode = iwasmHandler.exitCode
                    val details = buildString {
                        append("WAMR (iwasm) process terminated before debug ")
                        append("server started (exit code $exitCode).")
                        if (output.isNotEmpty()) {
                            append("\n\niwasm output:\n")
                            append(output)
                        }
                        append("\n\nCommand: ${iwasmCommandLine.commandLineString}")
                        append("\n\nPossible causes:")
                        append("\n  - iwasm was not built with debug support ")
                        append("(WAMR_BUILD_DEBUG_INTERP=1)")
                        append("\n  - The Wasm module path is incorrect or ")
                        append("the module has missing imports")
                        append("\n  - The debug port is already in use")
                    }
                    LOG.warn(details)
                    throw ExecutionException(details)
                }

                // Step 2: Start LLDB (no inferior — we'll connect remotely)
                val lldbExe = resolveLldbPath(configuration)
                val lldbCommandLine = GeneralCommandLine().apply {
                    exePath = lldbExe
                    withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                }

                LOG.info("Starting LLDB for WAMR remote connection")

                val lldbHandler = OSProcessHandler(lldbCommandLine)

                val lldb = LldbCommunicator(lldbHandler)
                lldb.attachToProcessHandler()

                ProcessTerminatedListener.attach(lldbHandler)
                lldbHandler.startNotify()

                val console = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(environment.project)
                    .console
                    .also { it.attachToProcess(lldbHandler) }

                val dwarfResolver = createDwarfResolver(configuration)

                return WamrDebugProcess(
                    session,
                    console,
                    lldbHandler,
                    iwasmHandler,
                    lldb,
                    debugHost = buildResult.debugHost,
                    debugPort = buildResult.debugPort,
                    engine = WasmEngine.WAMR,
                ).also {
                    it.dwarfVariableResolver = dwarfResolver
                }
            }
        }

        val result = XDebuggerManager.getInstance(environment.project)
            .newSessionBuilder(starter)
            .environment(environment)
            .startSession()

        return result.runContentDescriptor
    }

    private fun createDwarfResolver(configuration: WasmRunConfiguration): WasmDwarfVariableResolver? {
        return try {
            val wasmFile = File(configuration.wasmFilePath)
            if (wasmFile.exists()) {
                WasmDwarfVariableResolver.create(wasmFile.readBytes())
            } else null
        } catch (e: Exception) {
            LOG.debug("Failed to create DWARF variable resolver: ${e.message}")
            null
        }
    }

    companion object {
        private val LOG = Logger.getInstance(WasmDebugRunner::class.java)
        const val RUNNER_ID = "WasmDebugRunner"

        /**
         * Maximum time to wait for the WAMR debug server to print its
         * "Debug server listening on ..." message. If the timeout
         * expires, the connection attempt proceeds anyway (the connect
         * retry loop in [WamrDebugProcess] will handle the failure).
         */
        private const val WAMR_DEBUG_SERVER_READY_TIMEOUT_MS = 10_000L

        /** Default LLDB executable name, resolved via PATH. */
        private const val DEFAULT_LLDB_PATH = "lldb"

        /**
         * Resolves the LLDB executable path from the run configuration.
         *
         * WAMR debugging requires LLDB 22+ which includes the Wasm
         * process plugin. On macOS the system LLDB (Apple's build)
         * does not include this plugin, so users must install a
         * standalone LLVM/LLDB build (e.g. via Homebrew) and point
         * the configuration to it.
         */
        fun resolveLldbPath(configuration: WasmRunConfiguration): String {
            val configured = configuration.lldbPath.trim()
            return configured.ifBlank { DEFAULT_LLDB_PATH }
        }
    }
}
