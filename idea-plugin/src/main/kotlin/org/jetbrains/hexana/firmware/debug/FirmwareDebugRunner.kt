package org.jetbrains.hexana.firmware.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
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
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.execution.process.KillableProcessHandler
import kotlinx.serialization.json.*
import org.jetbrains.hexana.firmware.EmulatorDetection
import org.jetbrains.hexana.firmware.run.FirmwareRunConfiguration
import org.jetbrains.hexana.firmware.run.StopAtEntry
import org.jetbrains.hexana.providers.firmware.Backend
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.run.LldbCommunicator
import org.jetbrains.hexana.run.WasmRuntimeSettings
import java.io.File
import java.net.ServerSocket

/**
 * Debug runner for the Hexana firmware emulator lane.
 *
 * ### Transport selection
 * Prefers the GDB-DAP transport when [EmulatorDetection.detectGdb] finds GDB >= 14 with
 * Python. In that case the session is driven by [GdbDapDebugProcess] via [GdbDapClient].
 * When no suitable GDB is found the runner falls back to the LLDB transport, connecting
 * via [LldbCommunicator.connectGdbRemote] and returning [FirmwareDebugProcess].
 *
 * ### Sequence (modal progress, off EDT)
 * 1. Detect transport (GDB-DAP preferred, LLDB fallback). Validate the chosen debugger path.
 * 2. Allocate a free TCP port for the gdbstub.
 * 3. Call [checkConfiguration][FirmwareRunConfiguration.checkConfiguration] to obtain
 *    [resolvedSelection][FirmwareRunConfiguration.resolvedSelection].
 * 4. Launch the chosen backend (QEMU or Renode) frozen via [FirmwareDebugBackend.startFrozen].
 * 5. (GDB-DAP) Spawn `gdb -i=dap`; wrap in [GdbDapClient]; attach via DAP `attach` request.
 *    (LLDB fallback) Start LLDB; attach [LldbCommunicator]; connect via `gdb-remote`.
 * 6. Apply stop-at-entry policy inside `sessionInitialized`.
 * 7. Return the debug process via [XDebugProcessStarter].
 *
 * Stopping the debug session destroys both the debugger and emulator process handles.
 *
 * **Line breakpoints in source files** are not wired this iteration (no new XBreakpointType
 * registered). Users set breakpoints via the debugger console in the Threads tab, or rely on
 * stop-at-entry/main. This limitation applies to both transports.
 */
class FirmwareDebugRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = RUNNER_ID

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID) return false
        val config = profile as? FirmwareRunConfiguration ?: return false
        return config.elfPath.isNotBlank()
    }

    override fun doExecute(
        state: RunProfileState,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor? {
        val configuration = environment.runProfile as? FirmwareRunConfiguration
            ?: throw ExecutionException("Expected FirmwareRunConfiguration")

        data class PrepResult(
            val selection: BackendSelection.Ok,
            val stubEndpoint: StubEndpoint,
            val lldbExe: String,
            val gdbTool: EmulatorDetection.DetectedTool?,
        )

        val prep = ProgressManager.getInstance()
            .runProcessWithProgressSynchronously(
                ThrowableComputable<PrepResult, Exception> {
                    // Step 1: Detect transport. GDB-DAP preferred; LLDB is the fallback.
                    val gdbTool = EmulatorDetection.detectGdb()
                    if (gdbTool != null) {
                        LOG.info("GDB-DAP transport selected: ${gdbTool.path} ${gdbTool.version}")
                    } else {
                        LOG.info("LLDB transport selected: no suitable GDB-DAP found")
                    }

                    // Resolve LLDB (needed for fallback path only, but validate eagerly so the
                    // error appears before we launch the emulator).
                    val lldbExe = effectiveLldbExe()
                    if (gdbTool == null && !File(lldbExe).exists() && !isOnPath(lldbExe)) {
                        throw ExecutionException(
                            "LLDB not found at '$lldbExe'. " +
                                "Set a multi-arch LLDB path in Settings → Build → WebAssembly (Debugger).",
                        )
                    }

                    // Step 2: Validate and resolve backend selection.
                    try {
                        configuration.checkConfiguration()
                    } catch (e: com.intellij.execution.configurations.RuntimeConfigurationException) {
                        throw ExecutionException(
                            e.message ?: "Run configuration validation failed", e
                        )
                    }
                    val selection = configuration.resolvedSelection
                        ?: throw ExecutionException(
                            "Backend selection unavailable after checkConfiguration. " +
                                "Validate the run configuration."
                        )

                    // Step 3: Allocate gdbstub port.
                    val port = try {
                        ServerSocket(0).use { it.localPort }
                    } catch (e: Exception) {
                        throw ExecutionException(
                            "Failed to allocate gdbstub port: ${e.message}", e
                        )
                    }

                    // Step 4: Launch emulator frozen.
                    val backend: FirmwareDebugBackend = when (selection.backend) {
                        Backend.Qemu -> QemuGdbStubLauncher(
                            selection = selection,
                            elfPath = configuration.elfPath,
                            extraArgs = configuration.extraQemuArgs
                                .split("\\s+".toRegex()).filter { it.isNotBlank() },
                            port = port,
                        )
                        Backend.Renode -> RenodeGdbStubLauncher(
                            selection = selection,
                            elfPath = configuration.elfPath,
                            dtsPath = configuration.effectiveDtsPath() ?: configuration.dtsPath,
                            extraLines = configuration.extraRescLines
                                .lines().filter { it.isNotBlank() },
                            port = port,
                        )
                    }
                    val stubEndpoint = backend.startFrozen()

                    PrepResult(
                        selection = selection,
                        stubEndpoint = stubEndpoint,
                        lldbExe = lldbExe,
                        gdbTool = gdbTool,
                    )
                },
                "Starting firmware debug session…",
                true,
                environment.project,
            )

        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                // GDB-DAP transport (preferred when GDB >= 14 with Python is available).
                if (prep.gdbTool != null) {
                    return startGdbDapProcess(session)
                }
                // LLDB fallback transport.
                return startLldbProcess(session)
            }

            private fun startGdbDapProcess(session: XDebugSession): XDebugProcess {
                val gdbPath = prep.gdbTool!!.path
                LOG.info("GdbDapDebugProcess: spawning $gdbPath -i=dap")
                val gdbCommandLine = GeneralCommandLine().apply {
                    exePath = gdbPath
                    addParameter("-i=dap")
                    withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                }
                val gdbHandler = KillableProcessHandler(gdbCommandLine)

                // Create GdbDapClient with raw streams BEFORE startNotify().
                // startNotify() starts the handler's internal reader threads, which would
                // compete with the DAP reader for process.inputStream bytes.
                val client = GdbDapClient(
                    input = gdbHandler.process.inputStream,
                    output = gdbHandler.process.outputStream,
                )

                ProcessTerminatedListener.attach(gdbHandler)
                gdbHandler.startNotify()

                val gdbConsole = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(environment.project)
                    .console
                    .also { it.attachToProcess(gdbHandler) }

                return GdbDapDebugProcess(
                    session = session,
                    console = gdbConsole,
                    gdbHandler = gdbHandler,
                    emulatorHandler = prep.stubEndpoint.processHandler,
                    client = client,
                    elfPath = configuration.elfPath,
                    stubPort = prep.stubEndpoint.port,
                    stopAtEntry = configuration.stopAtEntry,
                )
            }

            private fun startLldbProcess(session: XDebugSession): XDebugProcess {
                LOG.info("Starting LLDB for firmware debug: ${prep.lldbExe}")
                val lldbCommandLine = GeneralCommandLine().apply {
                    exePath = prep.lldbExe
                    withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                }
                val lldbHandler = OSProcessHandler(lldbCommandLine)
                val lldb = LldbCommunicator(lldbHandler)
                lldb.attachToProcessHandler()
                ProcessTerminatedListener.attach(lldbHandler)
                lldbHandler.startNotify()

                val console = TextConsoleBuilderFactory.getInstance()
                    .createBuilder(environment.project)
                    .console
                    .also { it.attachToProcess(lldbHandler) }

                lldb.sendCommand("settings set target.disable-aslr false")
                lldb.sendCommand("target create \"${configuration.elfPath}\"")

                val targetList = lldb.sendCommandAndCollectOutput("target list", timeoutMs = 5_000)
                val targetText = targetList.joinToString(" ")
                if (targetText.contains("error", ignoreCase = true) &&
                    (targetText.contains("unsupported", ignoreCase = true) ||
                        targetText.contains("unknown architecture", ignoreCase = true))
                ) {
                    lldb.destroy()
                    lldbHandler.destroyProcess()
                    prep.stubEndpoint.processHandler.destroyProcess()
                    val archLabel = prep.selection.qemuBinary?.name ?: "unknown"
                    throw ExecutionException(
                        "The configured lldb cannot debug $archLabel ELF binaries. " +
                            "Set a multi-arch lldb path in Settings | Build | WebAssembly (Debugger)."
                    )
                }

                val connected = lldb.connectGdbRemote("127.0.0.1", prep.stubEndpoint.port)
                if (!connected) {
                    LOG.warn("gdb-remote did not produce a stop event on port ${prep.stubEndpoint.port}; proceeding")
                    session.reportMessage(
                        "LLDB did not receive a stop event from the gdbstub on port ${prep.stubEndpoint.port}. " +
                            "Check the firmware console for errors.",
                        MessageType.WARNING,
                    )
                }

                return FirmwareDebugProcess(
                    session = session,
                    console = console,
                    lldbHandler = lldbHandler,
                    emulatorHandler = prep.stubEndpoint.processHandler,
                    lldb = lldb,
                    stopAtEntry = configuration.stopAtEntry,
                )
            }
        }

        return startDebugSession(environment, starter)
    }

    companion object {
        private val LOG = Logger.getInstance(FirmwareDebugRunner::class.java)
        const val RUNNER_ID = "HexanaFirmwareDebugRunner"

        /**
         * Produces the ordered DAP (command, argsJson) pairs for a GDB-DAP attach sequence.
         * Extracted as a pure function so [GdbDapSequenceTest] can golden-test it.
         *
         * The sequence does NOT include the wait for the `initialized` event — that is an I/O
         * concern handled by [GdbDapDebugProcess]. The list encodes only the request order.
         */
        fun buildDapAttachRequests(
            elfPath: String,
            port: Int,
            stopAtEntry: StopAtEntry,
        ): List<Pair<String, String>> = buildList {
            val initArgs = buildJsonObject {
                put("adapterId", "hexana-firmware")
                put("linesStartAt1", true)
                put("columnsStartAt1", true)
                put("pathFormat", "path")
            }
            add("initialize" to initArgs.toString())

            val attachArgs = buildJsonObject {
                put("program", elfPath)
                put("target", "localhost:$port")
            }
            add("attach" to attachArgs.toString())

            when (stopAtEntry) {
                StopAtEntry.MAIN -> {
                    val bpArgs = buildJsonObject {
                        putJsonArray("breakpoints") { addJsonObject { put("name", "main") } }
                    }
                    add("setFunctionBreakpoints" to bpArgs.toString())
                    add("configurationDone" to "{}")
                    add("continue" to buildJsonObject { put("threadId", 1) }.toString())
                }
                StopAtEntry.ENTRY -> {
                    add("configurationDone" to "{}")
                }
                StopAtEntry.NONE -> {
                    add("configurationDone" to "{}")
                    add("continue" to buildJsonObject { put("threadId", 1) }.toString())
                }
            }
        }

        /**
         * Produces the ordered LLDB command list for a given (elfPath, port, stopAtEntry) triple.
         * Extracted as a pure function so [FirmwareDebugSequenceTest] can golden-test it.
         */
        fun buildAttachSequence(
            elfPath: String,
            port: Int,
            stopAtEntry: StopAtEntry,
        ): List<String> = buildList {
            add("settings set target.disable-aslr false")
            add("target create \"$elfPath\"")
            add("target list")
            add("gdb-remote 127.0.0.1:$port")
            when (stopAtEntry) {
                StopAtEntry.ENTRY -> { /* QEMU -S already paused at reset vector; no extra command */ }
                StopAtEntry.MAIN -> {
                    add("breakpoint set --name main")
                    add("continue")
                }
                StopAtEntry.NONE -> add("continue")
            }
        }

        /**
         * Resolves the LLDB executable path from [WasmRuntimeSettings], sharing the
         * configured LLDB with the WASM debug lane.
         */
        internal fun effectiveLldbExe(): String =
            WasmRuntimeSettings.getInstance().effectiveLldbPath()

        private fun isOnPath(exe: String): Boolean =
            PathEnvironmentVariableUtil.findInPath(exe) != null

        /**
         * Starts the XDebug session. Uses the 261+ builder API when available; falls back to
         * the legacy `startSession` for older SDK baselines. Inlines the logic from
         * `org.jetbrains.hexana.run.startDebugSessionForRunner` (which is `internal` to its
         * package and therefore inaccessible here).
         */
        private fun startDebugSession(
            environment: ExecutionEnvironment,
            starter: XDebugProcessStarter,
        ): RunContentDescriptor? =
            if (ApplicationInfo.getInstance().build.baselineVersion >= 261) {
                @Suppress("DEPRECATION")
                XDebuggerManager.getInstance(environment.project)
                    .newSessionBuilder(starter)
                    .environment(environment)
                    .startSession()
                    .runContentDescriptor
            } else {
                @Suppress("DEPRECATION")
                XDebuggerManager.getInstance(environment.project)
                    .startSession(environment, starter)
                    .runContentDescriptor
            }
    }
}
