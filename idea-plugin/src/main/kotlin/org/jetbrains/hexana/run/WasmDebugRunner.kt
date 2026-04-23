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
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.ThrowableComputable
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
        // Pre-compute DWARF resolver in a modal progress task so that
        // file I/O does not block the EDT. The modal dialog pumps UI
        // events, keeping the IDE responsive.
        val dwarfResolver = ProgressManager.getInstance()
            .runProcessWithProgressSynchronously(
                ThrowableComputable { createDwarfResolver(configuration) },
                "Preparing Wasmtime debug session\u2026",
                true,
                environment.project,
            )

        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                val buildResult = WasmtimeDebugCommandLineState.buildWasmtimeArguments(configuration, environment.project)
                val wasmtimePath = configuration.wasmtimePath.ifBlank {
                    WasmRuntimeSettings.getInstance().effectiveWasmtimePath()
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
     * Result holder for the blocking preparatory work that must
     * complete before the WAMR debug session UI is created.
     */
    private class WamrPrepResult(
        val iwasmHandler: OSProcessHandler,
        val dwarfResolver: WasmDwarfVariableResolver?,
        val debugHost: String,
        val debugPort: Int,
    )

    private fun doExecuteWamr(
        configuration: WasmRunConfiguration,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor? {
        // All blocking preparatory work (iwasm launch, server
        // readiness wait, DWARF parsing) runs inside a modal
        // progress task. The modal dialog pumps UI events so the
        // IDE stays responsive while we wait.
        val prep = ProgressManager.getInstance()
            .runProcessWithProgressSynchronously(
                ThrowableComputable { prepareWamrSession(configuration) },
                "Starting WAMR debug server\u2026",
                true,
                environment.project,
            )

        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
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

                return WamrDebugProcess(
                    session,
                    console,
                    lldbHandler,
                    prep.iwasmHandler,
                    lldb,
                    debugHost = prep.debugHost,
                    debugPort = prep.debugPort,
                    engine = WasmEngine.WAMR,
                ).also {
                    it.dwarfVariableResolver = prep.dwarfResolver
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
     * Performs all blocking preparatory work for a WAMR debug
     * session: launches iwasm, waits for the debug server to
     * become ready, and pre-parses DWARF metadata. This method
     * is intended to run inside a modal progress task so the
     * EDT stays responsive.
     */
    private fun prepareWamrSession(
        configuration: WasmRunConfiguration,
    ): WamrPrepResult {
        val buildResult = WamrDebugCommandLineState.buildWamrArguments(configuration)
        val iwasmPath = configuration.wamrPath.ifBlank {
            WasmRuntimeSettings.getInstance().effectiveWamrPath()
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
                if (looksLikeDebugNotSupported(output)) {
                    append("\n\nThe installed iwasm does not support ")
                    append("the \"-g\" debug option. This means it ")
                    append("was built without debug interpreter ")
                    append("support.")
                    append("\n\nRebuild iwasm with:")
                    append("\n  cmake .. -DWAMR_BUILD_DEBUG_INTERP=1")
                    append("\n  make")
                    append("\n\nSee: https://bytecodealliance.github.io")
                    append("/wamr.dev/blog/wamr-source-debugging-basic/")
                } else {
                    if (output.isNotEmpty()) {
                        append("\n\niwasm output:\n")
                        append(output)
                    }
                    append("\n\nCommand: ${iwasmCommandLine.commandLineString}")
                    append("\n\nPossible causes:")
                    append("\n  - iwasm was not built with debug ")
                    append("support (-DWAMR_BUILD_DEBUG_INTERP=1)")
                    append("\n  - The Wasm module path is incorrect ")
                    append("or the module has missing imports")
                    append("\n  - The debug port is already in use")
                }
            }
            LOG.warn(details)
            throw ExecutionException(details)
        }

        val dwarfResolver = createDwarfResolver(configuration)

        return WamrPrepResult(
            iwasmHandler = iwasmHandler,
            dwarfResolver = dwarfResolver,
            debugHost = buildResult.debugHost,
            debugPort = buildResult.debugPort,
        )
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
        internal val DEFAULT_LLDB_PATH = if (SystemInfo.isWindows) "lldb.exe" else "lldb"

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
            if (configured.isNotBlank()) return configured
            return WasmRuntimeSettings.getInstance().effectiveLldbPath()
        }

        /**
         * Detects whether iwasm output indicates the `-g` debug
         * option is not supported by the current build.
         *
         * When iwasm is built without `WAMR_BUILD_DEBUG_INTERP=1`,
         * the `-g=ip:port` argument is unrecognized. iwasm then
         * prints its help/usage text and exits. The help text from
         * a non-debug build will contain the usage header (e.g.
         * "Usage:" or "iwasm") but will NOT contain the `-g=`
         * option description, because that line is conditionally
         * compiled only when `WASM_ENABLE_DEBUG_INTERP != 0`.
         */
        fun looksLikeDebugNotSupported(output: String): Boolean {
            if (output.isBlank()) return false
            // iwasm help text contains "Usage:" and lists options.
            // If we see help-like output but the `-g=` option is
            // absent, the build lacks debug interpreter support.
            val looksLikeHelp = output.contains("Usage:") ||
                output.contains("iwasm") && output.contains("--")
            val hasDebugOption = output.contains("-g=")
            return looksLikeHelp && !hasDebugOption
        }
    }
}
