package org.jetbrains.hexana.firmware.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.hexana.firmware.HexanaFirmwareSettings
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.providers.firmware.buildQemuCommandLine
import org.jetbrains.hexana.providers.firmware.toCliName
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CommandLineState for running ELF firmware under QEMU.
 *
 * Builds the QEMU command line from [buildQemuCommandLine] (commonMain pure generator),
 * resolves the binary path via [HexanaFirmwareSettings], and launches via
 * [KillableColoredProcessHandler] with process-tree kill enabled.
 *
 * A startup watchdog is armed after the process starts: if no output appears within
 * [FirmwareRunConfiguration.startupTimeoutSeconds] seconds, the process is destroyed
 * and a diagnostic is printed to the console.
 *
 * @param configuration The run configuration with ELF path and extra args.
 * @param environment The execution environment.
 * @param selection The resolved [BackendSelection.Ok] (must have [Backend.Qemu]).
 * @param gdbStubPort When non-null, appends `-gdb tcp::<port> -S` for a debug session.
 */
class QemuCommandLineState(
    private val configuration: FirmwareRunConfiguration,
    environment: ExecutionEnvironment,
    private val selection: BackendSelection.Ok,
    val gdbStubPort: Int? = null,
) : CommandLineState(environment) {

    private val LOG = Logger.getInstance(QemuCommandLineState::class.java)

    override fun startProcess(): ProcessHandler {
        val settings = HexanaFirmwareSettings.getInstance()
        val binary = selection.qemuBinary
            ?: throw ExecutionException("QEMU binary not set in backend selection")

        val resolvedBinary = settings.effectiveQemuPath(binary) ?: binary.toCliName()

        val extraArgs = configuration.extraQemuArgs
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

        val args = buildQemuCommandLine(
            selection = selection,
            elfPath = configuration.elfPath,
            extraArgs = extraArgs,
            gdbStubPort = gdbStubPort,
        )
        // args[0] is the bare binary name; use resolved path instead.
        val cmdLine = GeneralCommandLine().apply {
            exePath = resolvedBinary
            addParameters(args.drop(1))
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        }

        LOG.info("QEMU launch: $resolvedBinary ${args.drop(1).joinToString(" ")}")

        val handler = KillableColoredProcessHandler(cmdLine)
        ProcessTerminatedListener.attach(handler)
        registerNoOrphan(handler)
        return handler
    }

    /** Attaches the console notice and watchdog after the process starts. */
    override fun execute(executor: com.intellij.execution.Executor, runner: com.intellij.execution.runners.ProgramRunner<*>):
        com.intellij.execution.ExecutionResult {
        val result = super.execute(executor, runner)
        val console = result.executionConsole as? ConsoleView
        val handler = result.processHandler

        console?.print(
            "Running under QEMU emulation: timing differs from hardware; unmodeled peripherals read zero.\n",
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )

        val firstOutputSeen = AtomicBoolean(false)
        armStartupWatchdog(
            handler = handler,
            timeoutSeconds = configuration.startupTimeoutSeconds,
            firstOutputSeen = firstOutputSeen,
            ringBuffer = { emptyList() }, // QEMU stdout is the only channel; no separate ring buffer
            isRenode = false,
            console = console ?: return result,
        )

        return result
    }
}
