package org.jetbrains.hexana.run

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Manages communication with an LLDB process.
 *
 * Commands are sent via the process's stdin. Output is received through
 * an [OSProcessHandler] listener (so the handler's built-in stream
 * reading is not bypassed). The communicator parses output lines
 * looking for stop events (breakpoint hits, step completions, process
 * exit) and makes them available via [waitForStopEvent].
 */
class LldbCommunicator(private val processHandler: OSProcessHandler) {

    private val writer: BufferedWriter =
        BufferedWriter(OutputStreamWriter(processHandler.process.outputStream))

    private val stopEvents = LinkedBlockingQueue<StopEvent>()

    /**
     * Lock protecting command-response collection state.
     * When a caller uses [sendCommandAndCollectOutput], incoming output
     * lines between the start and end fence markers are captured here.
     */
    private val responseLock = Any()
    private var responseCollector: MutableList<String>? = null
    private var responseLatch: CountDownLatch? = null
    private var responseEndMarker: String? = null

    /**
     * When `true`, stop-event parsing is suppressed so that output
     * from informational commands (like `bt`, `frame info`, `frame variable`)
     * is not mistaken for a real process stop. Without this guard,
     * a `bt` response that contains `stop reason = breakpoint 1.1`
     * would feed a spurious [StopEvent] into the queue and cause
     * the event loop to trigger [WasmDebugProcess.handleStop] again,
     * which in turn issues another `bt` — creating an infinite loop.
     */
    @Volatile
    @PublishedApi
    internal var suppressStopEvents = false

    @Volatile
    var isRunning = false
        private set

    /**
     * Installs a [ProcessAdapter] on the handler that parses every
     * output line for stop-event patterns. Call this before starting
     * the process handler.
     */
    fun attachToProcessHandler() {
        processHandler.addProcessListener(object : ProcessAdapter() {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text ?: return
                for (line in text.lines()) {
                    if (line.isBlank()) continue
                    LOG.debug("LLDB: $line")
                    var insideResponse = false
                    synchronized(responseLock) {
                        val endMarker = responseEndMarker
                        if (endMarker != null) {
                            insideResponse = true
                            if (line.trim() == endMarker) {
                                responseEndMarker = null
                                responseLatch?.countDown()
                            } else {
                                responseCollector?.add(line)
                            }
                        }
                    }
                    // Skip stop-event detection for lines that are part
                    // of a command response or while suppression is active.
                    // Without this guard, `bt` or `frame info` output that
                    // echoes "stop reason = breakpoint" would be
                    // misinterpreted as a new process stop.
                    if (!insideResponse && !suppressStopEvents) {
                        val stopEvent = parseStopEvent(line)
                        if (stopEvent != null) {
                            stopEvents.offer(stopEvent)
                        }
                    }
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                isRunning = false
                stopEvents.offer(StopEvent.ProcessExited(event.exitCode))
            }
        })
    }

    /**
     * Sends a command to LLDB via stdin.
     */
    fun sendCommand(command: String) {
        LOG.debug("LLDB CMD: $command")
        try {
            writer.write(command)
            writer.newLine()
            writer.flush()
        } catch (e: Exception) {
            LOG.warn("Failed to send LLDB command: $command", e)
        }
    }

    /**
     * Waits for the next stop event from LLDB (breakpoint hit, signal, exit).
     * Returns `null` if the timeout expires.
     */
    fun waitForStopEvent(timeoutMs: Long = 30_000): StopEvent? {
        return stopEvents.poll(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun sendBreakpoint(filePath: String, line: Int) {
        sendCommand("breakpoint set --file \"$filePath\" --line $line")
    }

    fun sendSourceMap(from: String, to: String) {
        sendCommand("settings set target.source-map \"$from\" \"$to\"")
    }

    fun removeAllBreakpoints() {
        sendCommand("breakpoint delete -f")
    }

    fun resume() {
        isRunning = true
        sendCommand("continue")
    }

    fun pause() {
        sendCommand("process interrupt")
    }

    fun stepOver() {
        isRunning = true
        sendCommand("next")
    }

    fun stepInto() {
        isRunning = true
        sendCommand("step")
    }

    fun stepOut() {
        isRunning = true
        sendCommand("finish")
    }

    fun runProcess() {
        isRunning = true
        sendCommand("run")
    }

    /**
     * Launches the inferior and stops it at the entry point.
     *
     * This is preferred over [runProcess] for debug sessions because it
     * allows wasmtime to JIT-compile the Wasm module and register debug
     * symbols with the JIT loader before user code executes. Pending
     * breakpoints can then resolve against the JIT-compiled code.
     *
     * After launch the process will be in a stopped state; call
     * [resume] to continue execution.
     */
    fun launchStoppedAtEntry() {
        isRunning = true
        sendCommand("process launch --stop-at-entry")
    }

    /**
     * Temporarily suppresses stop-event detection on incoming LLDB
     * output lines for the duration of [block]. This is intended for
     * code paths that issue informational LLDB commands (like
     * `frame info`) whose output may echo stop reasons but should
     * not be treated as new process stops.
     */
    internal inline fun <T> withStopEventsSuppressed(block: () -> T): T {
        suppressStopEvents = true
        try {
            return block()
        } finally {
            suppressStopEvents = false
        }
    }

    /**
     * Sends [command] to LLDB and collects the output lines produced
     * by that command. A unique end-of-response marker is emitted after
     * the real command so the collector knows when all output has arrived.
     *
     * Stop-event detection is suppressed while the response is being
     * collected so that output echoing stop reasons (e.g. `bt` printing
     * `stop reason = breakpoint 1.1`) does not trigger spurious events.
     *
     * This method blocks the calling thread until the response is
     * fully collected or the [timeoutMs] expires.
     *
     * @return the collected output lines, or an empty list on timeout.
     */
    fun sendCommandAndCollectOutput(
        command: String,
        timeoutMs: Long = 5_000,
    ): List<String> {
        val marker = "__HEXANA_END_${System.nanoTime()}__"
        val latch = CountDownLatch(1)
        val collector = mutableListOf<String>()

        synchronized(responseLock) {
            responseCollector = collector
            responseLatch = latch
            responseEndMarker = marker
        }

        sendCommand(command)
        // The marker is printed by LLDB's built-in Python, so it
        // appears in the normal output stream right after the command
        // output finishes.
        sendCommand("script print(\"$marker\")")

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)

        synchronized(responseLock) {
            responseCollector = null
            responseLatch = null
            responseEndMarker = null
        }

        if (!completed) {
            LOG.warn("Timed out collecting output for LLDB command: $command")
        }
        return collector.toList()
    }

    fun destroy() {
        try {
            sendCommand("process kill")
            sendCommand("quit")
        } catch (_: Exception) {
            // Ignore — process may already be dead
        }
    }

    /**
     * Parses an LLDB output line looking for a stop reason.
     *
     * Common patterns:
     * - `* thread #1, stop reason = breakpoint 1.1`
     * - `* thread #1, stop reason = step in`
     * - `* thread #1, stop reason = plan complete`
     * - `* thread #1, stop reason = signal SIGSTOP` (stop-at-entry)
     * - `Process 12345 exited with status = 0 (0x00000000)`
     */
    private fun parseStopEvent(line: String): StopEvent? {
        val trimmed = line.trim()
        if (trimmed.contains("stop reason = breakpoint")) {
            val match = BREAKPOINT_STOP_PATTERN.find(trimmed)
            isRunning = false
            return if (match != null) {
                StopEvent.BreakpointHit(
                    threadId = match.groupValues[1].toIntOrNull() ?: 1,
                    breakpointId = match.groupValues[2],
                )
            } else {
                StopEvent.BreakpointHit(threadId = 1, breakpointId = "")
            }
        }
        if (trimmed.contains("stop reason = signal SIGSTOP")) {
            isRunning = false
            return StopEvent.StoppedAtEntry
        }
        if (trimmed.contains("stop reason = signal") || trimmed.contains("stop reason = trace") ||
            trimmed.contains("stop reason = step") || trimmed.contains("stop reason = plan complete")
        ) {
            isRunning = false
            return StopEvent.Stepped
        }
        if (trimmed.startsWith("Process") && trimmed.contains("exited")) {
            isRunning = false
            val exitMatch = EXIT_PATTERN.find(trimmed)
            val exitCode = exitMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return StopEvent.ProcessExited(exitCode)
        }
        return null
    }

    sealed class StopEvent {
        data class BreakpointHit(val threadId: Int, val breakpointId: String) : StopEvent()
        data object Stepped : StopEvent()
        data object StoppedAtEntry : StopEvent()
        data class ProcessExited(val exitCode: Int) : StopEvent()
    }

    companion object {
        private val LOG = Logger.getInstance(LldbCommunicator::class.java)

        private val BREAKPOINT_STOP_PATTERN =
            """thread #(\d+).*stop reason = breakpoint (\d+\.\d+)""".toRegex()

        private val EXIT_PATTERN =
            """Process \d+ exited with status = (\d+)""".toRegex()
    }
}
