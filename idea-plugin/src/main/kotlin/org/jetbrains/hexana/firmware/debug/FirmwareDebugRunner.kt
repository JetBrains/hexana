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
 * Connects an LLDB instance to the emulator gdbstub (QEMU or Renode) using
 * [LldbCommunicator.connectGdbRemote]. The ELF binary is loaded into LLDB via
 * `target create <elf>` so LLDB resolves DWARF symbols locally, independent of Hexana's
 * own debug-info pipeline.
 *
 * ### Sequence (modal progress, off EDT)
 * 1. Validate the LLDB path.
 * 2. Allocate a free TCP port for the gdbstub.
 * 3. Call [checkConfiguration][FirmwareRunConfiguration.checkConfiguration] to obtain
 *    [resolvedSelection][FirmwareRunConfiguration.resolvedSelection].
 * 4. Launch the chosen backend (QEMU or Renode) frozen via [FirmwareDebugBackend.startFrozen].
 * 5. Start LLDB; attach [LldbCommunicator].
 * 6. `target create <elf>` — LLDB reads ELF + DWARF.
 * 7. Probe LLDB architecture support via `target list`.
 * 8. `gdb-remote 127.0.0.1:<port>` via [LldbCommunicator.connectGdbRemote].
 * 9. Apply stop-at-entry policy inside [FirmwareDebugProcess.sessionInitialized].
 * 10. Return [FirmwareDebugProcess] via [XDebugProcessStarter].
 *
 * Stopping the debug session destroys both the LLDB and emulator process handles.
 *
 * **Line breakpoints in source files** are not wired this iteration** (no new
 * XBreakpointType registered). Users set breakpoints via the LLDB console in the
 * Threads tab, or rely on stop-at-entry/main. This limitation is documented here and
 * in [FirmwareDebugProcess].
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
        )

        val prep = ProgressManager.getInstance()
            .runProcessWithProgressSynchronously(
                ThrowableComputable<PrepResult, Exception> {
                    // Step 1: Resolve LLDB.
                    val lldbExe = effectiveLldbExe()
                    if (!File(lldbExe).exists() && !isOnPath(lldbExe)) {
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
                            dtsPath = configuration.dtsPath,
                            extraLines = configuration.extraRescLines
                                .lines().filter { it.isNotBlank() },
                            port = port,
                        )
                    }
                    val stubEndpoint = backend.startFrozen()

                    PrepResult(selection = selection, stubEndpoint = stubEndpoint, lldbExe = lldbExe)
                },
                "Starting firmware debug session…",
                true,
                environment.project,
            )

        val starter = object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess {
                // Step 5: Start LLDB (no inferior; we connect remotely).
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

                // Step 6: Load the ELF so LLDB reads DWARF symbols.
                lldb.sendCommand("target create \"${configuration.elfPath}\"")

                // Step 7: Probe architecture support. If LLDB cannot parse the ELF
                // machine type it will error in target list output; fail early here.
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

                // Step 8: Connect to the gdbstub.
                val connected = lldb.connectGdbRemote("127.0.0.1", prep.stubEndpoint.port)
                if (!connected) {
                    LOG.warn("gdb-remote did not produce a stop event on port ${prep.stubEndpoint.port}; proceeding")
                    session.reportMessage(
                        "LLDB did not receive a stop event from the gdbstub on port ${prep.stubEndpoint.port}. " +
                            "Check the firmware console for errors.",
                        MessageType.WARNING,
                    )
                }

                // Step 9 (stop-at-entry policy) runs in FirmwareDebugProcess.sessionInitialized.
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
