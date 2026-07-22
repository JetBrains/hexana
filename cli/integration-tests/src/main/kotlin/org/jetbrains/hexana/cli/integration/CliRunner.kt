package org.jetbrains.hexana.cli.integration

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CliProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val destroyedProcessIds: Set<Long>,
) {
    fun describe(): String = "exit=$exitCode timedOut=$timedOut stdout=$stdout stderr=$stderr destroyed=$destroyedProcessIds"
}

object CliRunner {
    fun run(
        command: List<String>,
        stdin: ByteArray? = null,
        environment: Map<String, String> = emptyMap(),
        timeout: Duration = Duration.ofSeconds(10),
        disableTelemetry: Boolean = true,
        unsetJavaHome: Boolean = false,
    ): CliProcessResult {
        val process = ProcessBuilder(command).redirectErrorStream(false).apply {
            environment().keys.removeIf { it.startsWith("HEXANA_CLI_") || it.startsWith("HEXANA_MCP_") }
            environment().keys.removeAll(JAVA_OPTION_ENVIRONMENT_VARIABLES)
            if (unsetJavaHome) environment().remove("JAVA_HOME")
            if (disableTelemetry) environment()["HEXANA_CLI_TELEMETRY_ENABLED"] = "false"
            environment().putAll(environment)
        }.start()
        val streams = Executors.newFixedThreadPool(3)
        val stdout = streams.submit<ByteArray> { process.inputStream.use { it.readBytes() } }
        val stderr = streams.submit<ByteArray> { process.errorStream.use { it.readBytes() } }
        val stdinWriter = streams.submit {
            process.outputStream.use { output ->
                stdin?.let {
                    output.write(it)
                    output.flush()
                }
            }
        }
        try {
            val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            val destroyed = if (finished) emptySet() else destroyProcessTree(process).also { runCatching { process.outputStream.close() } }
            runCatching { stdinWriter.get(2, TimeUnit.SECONDS) }
            return CliProcessResult(
                exitCode = if (finished) process.exitValue() else null,
                stdout = stdout.get(2, TimeUnit.SECONDS).toString(Charsets.UTF_8),
                stderr = stderr.get(2, TimeUnit.SECONDS).toString(Charsets.UTF_8),
                timedOut = !finished,
                destroyedProcessIds = destroyed,
            )
        } finally {
            if (process.isAlive) destroyProcessTree(process)
            runCatching { process.outputStream.close() }
            streams.shutdownNow()
        }
    }

    private fun destroyProcessTree(process: Process): Set<Long> {
        val root = process.toHandle()
        val descendants = runCatching { root.descendants().use { it.toList() } }.getOrDefault(emptyList())
        val cleanupDeadline = System.nanoTime() + PROCESS_TREE_CLEANUP_TIMEOUT_NANOS
        val descendantDeadline = cleanupDeadline - ROOT_CLEANUP_RESERVE_NANOS
        var interrupted = false

        try {
            terminate(descendants, forcibly = false)
            interrupted = waitUntilExited(descendants, phaseDeadline(DESCENDANT_GRACE_NANOS, descendantDeadline)) || interrupted
            terminate(descendants, forcibly = true)
            interrupted = waitUntilExited(descendants, descendantDeadline) || interrupted

            terminate(listOf(root), forcibly = false)
            interrupted = waitUntilExited(listOf(root), phaseDeadline(ROOT_GRACE_NANOS, cleanupDeadline)) || interrupted
            terminate(listOf(root), forcibly = true)
            interrupted = waitUntilExited(listOf(root), cleanupDeadline) || interrupted
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }

        return descendants.map(ProcessHandle::pid).toSet() + root.pid()
    }

    private fun terminate(handles: Collection<ProcessHandle>, forcibly: Boolean) =
        handles.filter(::isAlive).forEach { handle ->
            runCatching { if (forcibly) handle.destroyForcibly() else handle.destroy() }
        }

    private fun waitUntilExited(handles: Collection<ProcessHandle>, deadline: Long): Boolean {
        var interrupted = false
        while (handles.any(::isAlive)) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            try {
                TimeUnit.NANOSECONDS.sleep(minOf(CLEANUP_POLL_NANOS, remaining))
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return interrupted
    }

    private fun isAlive(handle: ProcessHandle): Boolean = runCatching { handle.isAlive }.getOrDefault(false)

    private fun phaseDeadline(durationNanos: Long, cleanupDeadline: Long): Long =
        minOf(System.nanoTime() + durationNanos, cleanupDeadline)

    private val JAVA_OPTION_ENVIRONMENT_VARIABLES = setOf("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS")
    private val PROCESS_TREE_CLEANUP_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2)
    private val ROOT_CLEANUP_RESERVE_NANOS = TimeUnit.MILLISECONDS.toNanos(500)
    private val DESCENDANT_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(500)
    private val ROOT_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(100)
    private val CLEANUP_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10)
}
