package org.jetbrains.hexana.firmware.debug

import com.intellij.execution.process.ProcessHandler

/**
 * Endpoint returned by [FirmwareDebugBackend.startFrozen]: the gdbstub is listening
 * on [port] and the emulator process is held by [processHandler].
 *
 * @param port TCP port the gdbstub is accepting on.
 * @param processHandler Handle to the running emulator process (QEMU or Renode).
 * @param uartPort TCP port of the Renode UART server-socket terminal, or null when the
 *   UART arrives on the emulator's stdout instead (QEMU with `-serial mon:stdio`).
 */
data class StubEndpoint(
    val port: Int,
    val processHandler: ProcessHandler,
    val uartPort: Int? = null,
)

/**
 * Abstraction over the two GDB-stub emulator backends (QEMU and Renode).
 *
 * Each implementation starts its emulator in a frozen state (CPU halted, waiting
 * for GDB attach) and returns a [StubEndpoint] when the gdbstub port is confirmed
 * to be accepting connections. The debug runner attaches LLDB via [StubEndpoint.port]
 * using `gdb-remote`.
 *
 * This seam allows a gdb-dap client to dock here in a future iteration without
 * touching the debug runner or process logic.
 */
interface FirmwareDebugBackend {
    /**
     * Launches the emulator frozen and waits until its gdbstub TCP port accepts a
     * connection. Blocking: intended to run inside a modal-progress task.
     *
     * @return the ready [StubEndpoint].
     * @throws com.intellij.execution.ExecutionException on launch failure or timeout.
     */
    fun startFrozen(): StubEndpoint
}
