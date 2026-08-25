package org.jetbrains.hexana.firmware.run

import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.ShutDownTracker
import com.intellij.util.concurrency.AppExecutorUtil

import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = Logger.getInstance("FirmwareProcessHygiene")

/**
 * Unimplemented-peripheral warning pattern from Renode logs.
 *
 * Matches all four log-line shapes documented in the emulator-tooling-facts.md:
 * - WriteDoubleWord/ReadDoubleWord to non existing peripheral
 * - (tag: '...') ReadDoubleWord from non existing peripheral
 * - Unhandled write/read to offset
 * - Tried to access bytes at non-existing peripheral
 */
private val RENODE_UNIMPLEMENTED_PATTERN = Regex(
    "\\[WARNING]\\s+(\\w+):\\s+(?:Unhandled|Tried to access|.+non existing peripheral.*)"
)

/**
 * Registers a [ProcessHandler] with the IDE shutdown tracker so orphan processes
 * are killed when the IDE exits. The registration is removed when the process terminates.
 */
fun registerNoOrphan(handler: ProcessHandler) {
    val shutdownTask = Runnable {
        if (!handler.isProcessTerminated) {
            handler.destroyProcess()
        }
    }
    ShutDownTracker.getInstance().registerShutdownTask(shutdownTask)

    handler.addProcessListener(object : ProcessAdapter() {
        override fun processTerminated(event: ProcessEvent) {
            ShutDownTracker.getInstance().unregisterShutdownTask(shutdownTask)
        }
    })
}

/**
 * Arms a startup watchdog for [handler].
 *
 * The watchdog fires when [timeoutSeconds] elapse without [firstOutputSeen] being set.
 * On timeout it destroys the process tree and prints a diagnostic to [console].
 *
 * When [firstOutputSeen] is set before the timeout the watchdog is cancelled.
 * The watchdog is also cancelled when the process terminates normally.
 *
 * @param handler The process handler to watch.
 * @param timeoutSeconds Maximum seconds to wait for the first output line.
 * @param firstOutputSeen An [AtomicBoolean] flipped to `true` by the UART/console pump
 *   when the first non-empty output line is received.
 * @param ringBuffer Supplier of recent log lines (last ~200) used for peripheral diagnostics.
 * @param isRenode When `true`, the timeout message includes a peripheral-access diagnostic
 *   extracted from the Renode ring buffer.
 * @param console The console view to print the timeout diagnostic to.
 * @return The [ScheduledFuture] for the watchdog task (callers may cancel it explicitly).
 */
fun armStartupWatchdog(
    handler: ProcessHandler,
    timeoutSeconds: Int,
    firstOutputSeen: AtomicBoolean,
    ringBuffer: () -> List<String>,
    isRenode: Boolean,
    console: ConsoleView,
): ScheduledFuture<*> {
    val future = AppExecutorUtil.getAppScheduledExecutorService().schedule({
        if (firstOutputSeen.get()) return@schedule
        if (handler.isProcessTerminated || handler.isProcessTerminating) return@schedule

        val lines = ringBuffer()
        val peripheralLine: String? = if (isRenode) {
            lines.firstOrNull { RENODE_UNIMPLEMENTED_PATTERN.containsMatchIn(it) }
        } else null

        val msg = if (isRenode && peripheralLine != null) {
            "Firmware did not reach console output within ${timeoutSeconds}s. " +
                "It is likely blocking on unmodeled hardware. " +
                "First unimplemented peripheral access: $peripheralLine"
        } else if (isRenode) {
            "Firmware did not reach console output within ${timeoutSeconds}s. " +
                "It is likely blocking on unmodeled hardware. First unimplemented peripheral access: none logged."
        } else {
            "Firmware did not reach console output within ${timeoutSeconds}s. " +
                "Check -machine and firmware entry point."
        }

        LOG.warn("Firmware startup watchdog triggered: $msg")
        console.print("$msg\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        handler.destroyProcess()
    }, timeoutSeconds.toLong(), TimeUnit.SECONDS)

    // Cancel on first output.
    handler.addProcessListener(object : ProcessAdapter() {
        override fun onTextAvailable(event: ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
            if (event.text.isNotBlank() && firstOutputSeen.compareAndSet(false, true)) {
                future.cancel(false)
            }
        }
        override fun processTerminated(event: ProcessEvent) {
            future.cancel(false)
        }
    })

    return future
}
