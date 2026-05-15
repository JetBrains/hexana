package org.jetbrains.hexana.run

import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Key
import org.jetbrains.hexana.shared.LldbStopEvent
import org.jetbrains.hexana.shared.parseLldbStopEvent
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
     * Set to `true` once [destroy] has been called. Commands sent
     * after this point are silently dropped — the stream is expected
     * to be (or become) closed, so IOExceptions are not logged as
     * warnings.
     */
    @Volatile
    private var destroyed = false

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
                            } else if (!line.trimStart().startsWith(LLDB_PROMPT_PREFIX)) {
                                // Skip LLDB command echoes (e.g. "(lldb) expression -- x")
                                // so they don't leak into collected output.
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
        if (destroyed) {
            LOG.debug("LLDB CMD (dropped, already destroyed): $command")
            return
        }
        LOG.debug("LLDB CMD: $command")
        try {
            writer.write(command)
            writer.newLine()
            writer.flush()
        } catch (e: java.io.IOException) {
            if (destroyed) {
                // Expected during shutdown — the process stream is closed.
                LOG.debug("LLDB stream closed while sending command: $command")
            } else {
                LOG.warn("LLDB stream closed unexpectedly while sending command: $command", e)
            }
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
        if (destroyed) return emptyList()

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

        if (!completed && !destroyed) {
            LOG.warn("Timed out collecting output for LLDB command: $command")
        }
        return collector.toList()
    }

    /**
     * Drains all pending stop events from the queue without blocking.
     *
     * This is useful before starting the event loop in scenarios where
     * LLDB has already produced stop events (e.g. the initial
     * `stop reason = trace` from a `process connect` to WAMR) that
     * should not be processed as user-visible stops.
     */
    fun drainPendingStopEvents() {
        var count = 0
        while (stopEvents.poll() != null) {
            count++
        }
        if (count > 0) {
            LOG.debug("Drained $count pending stop event(s)")
        }
    }

    fun destroy() {
        try {
            writer.write("process kill")
            writer.newLine()
            writer.write("quit")
            writer.newLine()
            writer.flush()
        } catch (_: Exception) {
            // Ignore — process may already be dead
        }
        destroyed = true
    }

    /**
     * Parses an LLDB output line looking for a stop reason.
     *
     * Delegates to the shared [parseLldbStopEvent] parser and
     * converts the result to the local [StopEvent] type.
     */
    private fun parseStopEvent(line: String): StopEvent? {
        val shared = parseLldbStopEvent(line) ?: return null
        isRunning = false
        return when (shared) {
            is LldbStopEvent.BreakpointHit -> StopEvent.BreakpointHit(
                threadId = shared.threadId,
                breakpointId = shared.breakpointId,
            )
            is LldbStopEvent.Stepped -> StopEvent.Stepped
            is LldbStopEvent.StoppedAtEntry -> StopEvent.StoppedAtEntry
            is LldbStopEvent.ProcessExited -> StopEvent.ProcessExited(shared.exitCode)
        }
    }

    sealed class StopEvent {
        data class BreakpointHit(val threadId: Int, val breakpointId: String) : StopEvent()
        data object Stepped : StopEvent()
        data object StoppedAtEntry : StopEvent()
        data class ProcessExited(val exitCode: Int) : StopEvent()
    }

    companion object {
        private val LOG = Logger.getInstance(LldbCommunicator::class.java)

        /**
         * Prefix that LLDB uses when echoing commands back in output.
         * Lines starting with this prefix are command echoes and should
         * be filtered out of collected command responses.
         */
        private const val LLDB_PROMPT_PREFIX = "(lldb) "
    }
}
