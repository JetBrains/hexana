package org.jetbrains.hexana.firmware.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.hexana.firmware.Dts2ReplRunner
import org.jetbrains.hexana.firmware.HexanaFirmwareSettings
import org.jetbrains.hexana.firmware.run.registerNoOrphan
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.providers.firmware.DEFAULT_UART_PERIPHERAL
import org.jetbrains.hexana.providers.firmware.ReplResult
import org.jetbrains.hexana.providers.firmware.ReplSource
import org.jetbrains.hexana.providers.firmware.buildRescScript
import org.jetbrains.hexana.providers.firmware.parseDtsConsoleUart
import org.jetbrains.hexana.providers.firmware.renodeLibraryReplFor
import java.io.File
import java.net.ServerSocket

/**
 * Launches Renode with a gdbstub port and frozen start.
 *
 * The generated `.resc` calls `machine StartGdbServer <port> false` and omits the
 * `start` command: the machine stays unstarted through gdb attach and breakpoint
 * materialization, and the debug process starts emulation with `monitor start`
 * afterwards (autostart=true would start emulation at gdb TCP connect, letting fast
 * firmware boot to idle before any breakpoint exists).
 *
 * Readiness is determined by polling the gdb port with TCP connect probes, same as
 * [QemuGdbStubLauncher].
 *
 * @param selection Resolved Renode backend selection.
 * @param elfPath Absolute path to the ELF binary.
 * @param dtsPath Path to zephyr.dts (required when replSource is Generated).
 * @param extraLines Extra Renode monitor commands from the run configuration.
 * @param port GDB stub port (pre-allocated by the caller).
 * @param timeoutMs Maximum time to wait for the stub port to accept connections.
 */
class RenodeGdbStubLauncher(
    private val selection: BackendSelection.Ok,
    private val elfPath: String,
    private val dtsPath: String,
    private val extraLines: List<String>,
    private val port: Int,
    private val timeoutMs: Long = QemuGdbStubLauncher.STUB_READY_TIMEOUT_MS,
) : FirmwareDebugBackend {

    private val LOG = Logger.getInstance(RenodeGdbStubLauncher::class.java)

    override fun startFrozen(): StubEndpoint {
        val settings = HexanaFirmwareSettings.getInstance()
        val renodePath = settings.effectiveRenodesPath()
            ?: throw ExecutionException(
                "Renode executable not found. Set the path in Settings → Build → Firmware Emulator."
            )

        val replPath: String?
        val libraryPlatform: String?
        when (selection.replSource) {
            ReplSource.Library -> {
                replPath = null
                // Extract boardId from the rationale string ("...for 'nrf52840dk'...").
                val boardId = selection.rationale.substringAfter("'", "").substringBefore("'", "")
                libraryPlatform = renodeLibraryReplFor(boardId)
                    ?: throw ExecutionException(
                        "No Renode library platform found for board '$boardId'. " +
                            "Use the Generated platform source in the run configuration."
                    )
            }
            ReplSource.Generated, null -> {
                val dtsText = File(dtsPath).takeIf { it.exists() }?.readText()
                    ?: throw ExecutionException(
                        "zephyr.dts is required for Renode platform generation but was not found. " +
                            "Set the DTS path in the run configuration."
                    )
                val result = try {
                    Dts2ReplRunner().convert(dtsText, boardHint = null)
                } catch (e: Exception) {
                    throw ExecutionException("dts2repl conversion failed: ${e.message}", e)
                }
                when (result) {
                    is ReplResult.Failed -> throw ExecutionException(
                        "dts2repl conversion failed: ${result.message}"
                    )
                    is ReplResult.Ok -> {
                        val replFile = FileUtil.createTempFile("hexana-renode-debug-", ".repl", true)
                        replFile.writeText(result.replText)
                        replPath = replFile.absolutePath
                        libraryPlatform = null
                    }
                }
            }
        }

        // UART bridge mirrors the run path: server-socket terminal on a free port, console
        // peripheral read from the DTS chosen node (usart2 on STM32F4, uart0 on nRF52840).
        val uartPort = ServerSocket(0).use { it.localPort }
        val dtsTextForUart = File(dtsPath).takeIf { it.exists() }?.readText()
        val uartPeripheral = dtsTextForUart?.let(::parseDtsConsoleUart) ?: DEFAULT_UART_PERIPHERAL

        val rescText = buildRescScript(
            replPath = replPath,
            libraryPlatform = libraryPlatform,
            elfPath = elfPath,
            uartPort = uartPort,
            uartPeripheral = uartPeripheral,
            extraLines = extraLines,
            gdbPort = port,
        )

        val rescFile = FileUtil.createTempFile("hexana-renode-debug-", ".resc", true)
        rescFile.writeText(rescText)

        LOG.info("Renode gdbstub launch (port=$port): $renodePath --disable-xwt --console ${rescFile.absolutePath}")
        LOG.debug("Generated debug .resc:\n$rescText")

        val cmdLine = GeneralCommandLine().apply {
            exePath = renodePath
            addParameter("--disable-xwt")
            addParameter("--console")
            addParameter(rescFile.absolutePath)
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        }

        val handler = KillableColoredProcessHandler(cmdLine)
        ProcessTerminatedListener.attach(handler)
        registerNoOrphan(handler)
        handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                rescFile.delete()
                replPath?.let { p -> if (p.contains("hexana-renode-debug-")) File(p).delete() }
            }
        })
        handler.startNotify()

        if (!QemuGdbStubLauncher.waitForPortAccept("127.0.0.1", port, timeoutMs)) {
            handler.destroyProcess()
            throw ExecutionException(
                "Renode gdbstub did not open port $port within ${timeoutMs / 1000}s. " +
                    "Check the Renode path and the generated platform."
            )
        }

        LOG.info("Renode gdbstub ready on port $port (uart on $uartPort via $uartPeripheral)")
        return StubEndpoint(port = port, processHandler = handler, uartPort = uartPort)
    }

}
