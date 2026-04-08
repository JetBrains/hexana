package org.jetbrains.hexana.mcp.server

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal class HexanaInstalledServer private constructor(
    private val installDir: Path,
    private val port: Int,
    private val endpoint: String,
    private val command: List<String>,
    private val process: Process,
    private val pumpScope: CoroutineScope,
    private val stdoutPump: Job,
    private val stderrPump: Job,
    private val stdoutBuffer: StringBuffer,
    private val stderrBuffer: StringBuffer
) {
    private val logger = LoggerFactory.getLogger(HexanaInstalledServer::class.java)

    val baseUrl: String = "http://127.0.0.1:$port$endpoint"

    val stdout: String
        get() = stdoutBuffer.toString()

    val stderr: String
        get() = stderrBuffer.toString()

    suspend fun close() {
        logger.info("Stopping installed server (pid={}, baseUrl={})", process.pid(), baseUrl)
        withContext(Dispatchers.IO) {
            process.destroy()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.warn("Process did not stop within 5s, forcing termination (pid={})", process.pid())
                process.destroyForcibly()
                process.waitFor()
            }
        }
        stdoutPump.cancelAndJoin()
        stderrPump.cancelAndJoin()
        pumpScope.coroutineContext[Job]?.cancel()
        logger.info(
            "Installed server stopped with exitCode={}, stdoutBytes={}, stderrBytes={}",
            process.exitValue(),
            stdout.length,
            stderr.length
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HexanaInstalledServer::class.java)
        private const val HTTP_READY_TIMEOUT_PROPERTY = "hexana.integration.httpReadyTimeoutMillis"
        private val DEFAULT_HTTP_READY_TIMEOUT: Duration = Duration.ofSeconds(20)

        suspend fun start(
            endpoint: String = "/mcp",
            logLevel: HexanaLogLevel = HexanaLogLevel.QUIET
        ): HexanaInstalledServer {
            val installDir = integrationInstallDir()
            val port = findFreePort()
            val stdoutBuffer = StringBuffer()
            val stderrBuffer = StringBuffer()

            val command = buildCommand(installDir, port, endpoint, logLevel)
            logger.info(
                "Starting installed server from {} on http://127.0.0.1:{}{} with logLevel={} command={}",
                installDir,
                port,
                endpoint,
                logLevel.cliValue(),
                command.joinToString(" ")
            )
            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(command)
                    .directory(installDir.toFile())
                    .useCurrentJvm(logger)
                    .start()
            }
            logger.info("Process started with pid={}", process.pid())

            val pumpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val stdoutPump = pumpScope.launch {
                process.inputStream.pumpTo(stdoutBuffer, "stdout")
            }
            val stderrPump = pumpScope.launch {
                process.errorStream.pumpTo(stderrBuffer, "stderr")
            }

            waitUntilReady(process, port, stderrBuffer)
            logger.info("Installed server is ready on http://127.0.0.1:{}{}", port, endpoint)

            return HexanaInstalledServer(
                installDir = installDir,
                port = port,
                endpoint = endpoint,
                command = command,
                process = process,
                pumpScope = pumpScope,
                stdoutPump = stdoutPump,
                stderrPump = stderrPump,
                stdoutBuffer = stdoutBuffer,
                stderrBuffer = stderrBuffer
            )
        }

        private fun integrationInstallDir(): Path {
            val rawPath = System.getProperty("hexana.integration.installDir")
                ?: error("Missing `hexana.integration.installDir` system property")
            val path = Path.of(rawPath)
            require(Files.isDirectory(path)) { "Install directory does not exist: $path" }
            return path
        }

        private fun buildCommand(
            installDir: Path,
            port: Int,
            endpoint: String,
            logLevel: HexanaLogLevel
        ): List<String> {
            val binDir = installDir.resolve("bin")
            return if (isWindows()) {
                listOf(
                    "cmd.exe",
                    "/c",
                    binDir.resolve("server.bat").toString(),
                    "http",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    port.toString(),
                    "--endpoint",
                    endpoint,
                    "--log-level",
                    logLevel.cliValue()
                )
            } else {
                listOf(
                    binDir.resolve("server").toString(),
                    "http",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    port.toString(),
                    "--endpoint",
                    endpoint,
                    "--log-level",
                    logLevel.cliValue()
                )
            }
        }

        private suspend fun waitUntilReady(process: Process, port: Int, stderrBuffer: StringBuffer) {
            val readyTimeout = httpReadyTimeout()
            val deadline = System.nanoTime() + readyTimeout.toNanos()
            var attempts = 0
            while (System.nanoTime() < deadline) {
                attempts += 1
                if (!process.isAlive) {
                    error(
                        buildString {
                            append("Hexana MCP server exited before binding HTTP port.")
                            val stderr = stderrBuffer.toString().trim()
                            if (stderr.isNotEmpty()) {
                                append(" stderr: ")
                                append(stderr)
                            }
                        }
                    )
                }

                try {
                    withContext(Dispatchers.IO) {
                        Socket("127.0.0.1", port).use { return@withContext }
                    }
                    logger.info("HTTP port {} accepted connections after {} probe(s)", port, attempts)
                    return
                } catch (_: Exception) {
                    delay(100)
                }
            }

            error(
                buildString {
                    append("Timed out waiting for Hexana MCP server to bind HTTP port ")
                    append(port)
                    append(" within ")
                    append(readyTimeout)
                    val stderr = stderrBuffer.toString().trim()
                    if (stderr.isNotEmpty()) {
                        append(". stderr: ")
                        append(stderr)
                    }
                }
            )
        }

        internal fun httpReadyTimeout(rawValue: String? = System.getProperty(HTTP_READY_TIMEOUT_PROPERTY)): Duration {
            val trimmed = rawValue?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                return DEFAULT_HTTP_READY_TIMEOUT
            }

            val timeoutMillis = trimmed.toLongOrNull()
                ?: throw IllegalArgumentException(
                    "Invalid `$HTTP_READY_TIMEOUT_PROPERTY` value: `$trimmed`. Expected a positive integer number of milliseconds."
                )
            require(timeoutMillis > 0) {
                "Invalid `$HTTP_READY_TIMEOUT_PROPERTY` value: `$trimmed`. Expected a positive integer number of milliseconds."
            }
            return Duration.ofMillis(timeoutMillis)
        }

        private suspend fun findFreePort(): Int {
            return withContext(Dispatchers.IO) {
                ServerSocket(0).use { it.localPort }
            }
        }

        private fun isWindows(): Boolean {
            return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        }
    }
}

private suspend fun java.io.InputStream.pumpTo(target: StringBuffer, streamName: String) {
    val logger = LoggerFactory.getLogger("org.jetbrains.hexana.mcp.server.HexanaInstalledServer.$streamName")
    withContext(Dispatchers.IO) {
        bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                synchronized(target) {
                    target.append(line).append('\n')
                }
                logger.info("{}", line)
            }
        }
    }
}

internal suspend inline fun <T> withInstalledServer(
    endpoint: String = "/mcp",
    logLevel: HexanaLogLevel = HexanaLogLevel.QUIET,
    crossinline block: suspend (HexanaInstalledServer) -> T
): T {
    val server = HexanaInstalledServer.start(endpoint = endpoint, logLevel = logLevel)
    return try {
        block(server)
    } finally {
        server.close()
    }
}
