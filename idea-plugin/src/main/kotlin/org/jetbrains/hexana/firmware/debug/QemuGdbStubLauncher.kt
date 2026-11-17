package org.jetbrains.hexana.firmware.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.hexana.firmware.HexanaFirmwareSettings
import org.jetbrains.hexana.firmware.run.registerNoOrphan
import org.jetbrains.hexana.providers.firmware.BackendSelection
import org.jetbrains.hexana.providers.firmware.buildQemuCommandLine
import org.jetbrains.hexana.providers.firmware.toCliName
import java.io.IOException
import java.net.Socket

/**
 * Launches QEMU in gdbstub mode (frozen: `-gdb tcp::<port> -S`) and waits until the
 * stub port accepts a TCP connection before returning.
 *
 * QEMU opens its gdbstub socket immediately at startup, so a TCP connect probe is
 * the fastest and most reliable readiness signal (no output-line parsing needed).
 *
 * @param selection Resolved QEMU backend selection.
 * @param elfPath Absolute path to the ELF binary passed to `-kernel`.
 * @param extraArgs Extra QEMU arguments from the run configuration.
 * @param port GDB stub port (must be pre-allocated by the caller via `ServerSocket(0)`).
 * @param timeoutMs Maximum time to wait for the stub port to accept connections.
 */
class QemuGdbStubLauncher(
    private val selection: BackendSelection.Ok,
    private val elfPath: String,
    private val extraArgs: List<String>,
    private val port: Int,
    private val timeoutMs: Long = STUB_READY_TIMEOUT_MS,
) : FirmwareDebugBackend {

    private val LOG = Logger.getInstance(QemuGdbStubLauncher::class.java)

    override fun startFrozen(): StubEndpoint {
        val settings = HexanaFirmwareSettings.getInstance()
        val binary = selection.qemuBinary
            ?: throw ExecutionException("QEMU binary not set in backend selection")

        val resolvedBinary = settings.effectiveQemuPath(binary) ?: binary.toCliName()
        val args = buildQemuCommandLine(
            selection = selection,
            elfPath = elfPath,
            extraArgs = extraArgs,
            gdbStubPort = port,
        )

        val cmdLine = GeneralCommandLine().apply {
            exePath = resolvedBinary
            addParameters(args.drop(1)) // args[0] is the bare binary name
            withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        }

        LOG.info("QEMU gdbstub launch (port=$port): $resolvedBinary ${args.drop(1).joinToString(" ")}")

        val handler = KillableColoredProcessHandler(cmdLine)
        ProcessTerminatedListener.attach(handler)
        registerNoOrphan(handler)
        handler.startNotify()

        // Poll until the gdb port accepts a connection (QEMU opens the socket at startup).
        if (!waitForPortAccept("127.0.0.1", port, timeoutMs)) {
            handler.destroyProcess()
            throw ExecutionException(
                "QEMU gdbstub did not open port $port within ${timeoutMs / 1000}s. " +
                    "Check the QEMU binary and machine configuration."
            )
        }

        LOG.info("QEMU gdbstub ready on port $port")
        return StubEndpoint(port = port, processHandler = handler)
    }

    companion object {
        internal const val STUB_READY_TIMEOUT_MS = 15_000L

        /**
         * Polls [host]:[port] with TCP connect probes until the port accepts a connection
         * or [timeoutMs] elapses. Returns `true` when the port is accepting.
         */
        internal fun waitForPortAccept(host: String, port: Int, timeoutMs: Long): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    Socket(host, port).use { return true }
                } catch (_: IOException) {
                    Thread.sleep(200)
                }
            }
            return false
        }
    }
}
