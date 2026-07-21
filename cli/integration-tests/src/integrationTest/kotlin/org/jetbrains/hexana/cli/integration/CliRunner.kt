package org.jetbrains.hexana.cli.integration

import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class CliProcessResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val destroyedProcessIds: Set<Long>,
) {
    fun describe(): String = "exit=$exitCode timedOut=$timedOut stdout=$stdout stderr=$stderr destroyed=$destroyedProcessIds"
}

internal object CliRunner {
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
            val destroyed = if (finished) {
                emptySet()
            } else {
                destroyProcessTree(process).also { runCatching { process.outputStream.close() } }
            }
            if (finished) {
                stdinWriter.get(2, TimeUnit.SECONDS)
            } else {
                runCatching { stdinWriter.get(2, TimeUnit.SECONDS) }
            }
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
        val handles = (process.toHandle().descendants().toList() + process.toHandle()).distinctBy(ProcessHandle::pid)
        handles.asReversed().forEach(ProcessHandle::destroy)
        handles.asReversed().forEach { handle ->
            if (handle.isAlive) handle.destroyForcibly()
        }
        handles.forEach { it.onExit().get(2, TimeUnit.SECONDS) }
        return handles.map(ProcessHandle::pid).toSet()
    }

    private val JAVA_OPTION_ENVIRONMENT_VARIABLES = setOf(
        "JAVA_TOOL_OPTIONS",
        "_JAVA_OPTIONS",
        "JDK_JAVA_OPTIONS",
        "JAVA_OPTS",
    )
}
