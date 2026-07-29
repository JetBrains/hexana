package org.jetbrains.hexana.mcp.server

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

internal class HexanaInstalledServer private constructor(
    val port: Int,
    val endpoint: String,
    private val command: List<String>,
    private val process: Process,
    private val pumpScope: CoroutineScope,
    private val stdoutPump: Job,
    private val stderrPump: Job,
    private val stdoutBuffer: StringBuffer,
    private val stderrBuffer: StringBuffer,
    internal val diagnosticDirectory: Path,
    private val diagnosticDirectoryOwner: DiagnosticDirectoryOwner,
) {
    private val logger = LoggerFactory.getLogger(HexanaInstalledServer::class.java)
    private val closed = AtomicBoolean()

    val origin: String = "http://127.0.0.1:$port"
    val baseUrl: String = "http://127.0.0.1:$port$endpoint"

    val stdout: String
        get() = stdoutBuffer.toString()

    val stderr: String
        get() = stderrBuffer.toString()

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            logger.info("Stopping installed server (pid={}, baseUrl={})", process.pid(), baseUrl)
            withContext(NonCancellable) {
                stopInstalledProcessTree(process, logger, "HTTP server")
                closeInstalledProcessStreams(process, logger, "HTTP server")
                stdoutPump.cancelAndJoinWithin("HTTP stdout pump")
                stderrPump.cancelAndJoinWithin("HTTP stderr pump")
                pumpScope.coroutineContext[Job]?.cancel()
                diagnosticDirectoryOwner.close()
            }
            logger.info(
                "Installed server stopped with exitCode={}, alive={}, stdoutBytes={}, stderrBytes={}",
                process.exitCodeOrNull(),
                process.isAlive,
                stdout.length,
                stderr.length
            )
        } catch (error: Throwable) {
            closed.set(false)
            throw error
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(HexanaInstalledServer::class.java)
        private const val HTTP_READY_TIMEOUT_PROPERTY = "hexana.integration.httpReadyTimeoutMillis"
        private val DEFAULT_HTTP_READY_TIMEOUT: Duration = Duration.ofSeconds(20)

        suspend fun start(
            launcher: HttpLauncher = HttpLauncher.Server,
            endpoint: String = "/mcp",
            logLevel: HexanaLogLevel = HexanaLogLevel.QUIET,
            toolProfile: HexanaMcpToolProfile? = null,
            allowedRoots: List<Path> = emptyList(),
        ): HexanaInstalledServer {
            val processSpec = processSpec(launcher)
            val port = findFreePort()
            val stdoutBuffer = StringBuffer()
            val stderrBuffer = StringBuffer()

            val command = buildCommand(processSpec, launcher, port, endpoint, logLevel, toolProfile, allowedRoots)
            logger.info(
                "Starting installed server from {} on http://127.0.0.1:{}{} with logLevel={} command={}",
                processSpec.workingDirectory,
                port,
                endpoint,
                logLevel.cliValue(),
                command.joinToString(" ")
            )
            val processBuilder = ProcessBuilder(command)
                .directory(processSpec.workingDirectory.toFile())
                .useCurrentJvm(logger)
            val diagnosticDirectoryOwner = processBuilder.useIsolatedDiagnosticDirectory()
            val process = try {
                withContext(Dispatchers.IO) { processBuilder.start() }
            } catch (error: Throwable) {
                diagnosticDirectoryOwner.closeSuppressing(error)
                throw error
            }
            logger.info("Process started with pid={}", process.pid())

            val pumpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val stdoutPump = pumpScope.launch {
                process.inputStream.pumpTo(stdoutBuffer, "stdout")
            }
            val stderrPump = pumpScope.launch {
                process.errorStream.pumpTo(stderrBuffer, "stderr")
            }

            try {
                waitUntilReady(process, port, stderrBuffer)
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    runCatching { stopInstalledProcessTree(process, logger, "failed HTTP server") }
                        .onFailure(error::addSuppressed)
                    closeInstalledProcessStreams(process, logger, "failed HTTP server")
                    stdoutPump.cancelAndJoinWithin("failed HTTP stdout pump")
                    stderrPump.cancelAndJoinWithin("failed HTTP stderr pump")
                    pumpScope.coroutineContext[Job]?.cancel()
                    diagnosticDirectoryOwner.closeSuppressing(error)
                }
                throw error
            }
            logger.info("Installed server is ready on http://127.0.0.1:{}{}", port, endpoint)

            return HexanaInstalledServer(
                port = port,
                endpoint = endpoint,
                command = command,
                process = process,
                pumpScope = pumpScope,
                stdoutPump = stdoutPump,
                stderrPump = stderrPump,
                stdoutBuffer = stdoutBuffer,
                stderrBuffer = stderrBuffer,
                diagnosticDirectory = diagnosticDirectoryOwner.path,
                diagnosticDirectoryOwner = diagnosticDirectoryOwner,
            )
        }

        private fun processSpec(launcher: HttpLauncher): HttpProcessSpec {
            launcher.executableSystemProperty?.let { systemPropertyName ->
                val executable = integrationPath(systemPropertyName)
                require(Files.isRegularFile(executable)) { "Native executable does not exist: $executable" }
                require(Files.isExecutable(executable)) { "Native executable is not executable: $executable" }
                return HttpProcessSpec(
                    workingDirectory = executable.parent ?: Path.of(".").toAbsolutePath(),
                    executable = executable,
                )
            }

            val installDir = integrationPath(launcher.installDirSystemProperty)
            require(Files.isDirectory(installDir)) { "Install directory does not exist: $installDir" }
            return HttpProcessSpec(
                workingDirectory = installDir,
                executable = null,
            )
        }

        private fun integrationPath(systemPropertyName: String): Path {
            val rawPath = System.getProperty(systemPropertyName)
                ?: error("Missing `$systemPropertyName` system property")
            return Path.of(rawPath)
        }

        private fun buildCommand(
            processSpec: HttpProcessSpec,
            launcher: HttpLauncher,
            port: Int,
            endpoint: String,
            logLevel: HexanaLogLevel,
            toolProfile: HexanaMcpToolProfile?,
            allowedRoots: List<Path>,
        ): List<String> {
            val arguments = buildList {
                add("http")
                add("--host")
                add("127.0.0.1")
                add("--port")
                add(port.toString())
                add("--endpoint")
                add(endpoint)
                add("--log-level")
                add(logLevel.cliValue())
                if (toolProfile != null) {
                    add("--tool-profile")
                    add(toolProfile.cliValue())
                }
                allowedRoots.forEach { allowedRoot ->
                    add("--allowed-root")
                    add(allowedRoot.toString())
                }
            }
            processSpec.executable?.let { executable ->
                return listOf(executable.toString()) + arguments
            }

            return if (isWindows()) {
                listOf(
                    "cmd.exe",
                    "/c",
                    processSpec.workingDirectory.resolve(launcher.windowsRelativePath).toString(),
                ) + arguments
            } else {
                listOf(processSpec.workingDirectory.resolve(launcher.unixRelativePath).toString()) + arguments
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

internal data class HttpLauncher(
    val installDirSystemProperty: String,
    val unixRelativePath: String,
    val windowsRelativePath: String,
    val executableSystemProperty: String? = null,
) {
    companion object {
        val Server = HttpLauncher(
            installDirSystemProperty = "hexana.integration.installDir",
            unixRelativePath = "bin/server",
            windowsRelativePath = "bin/server.bat",
        )
        val NativeExecutable = HttpLauncher(
            installDirSystemProperty = "hexana.integration.nativeExecutable",
            unixRelativePath = "",
            windowsRelativePath = "",
            executableSystemProperty = "hexana.integration.nativeExecutable",
        )
    }
}

private data class HttpProcessSpec(
    val workingDirectory: Path,
    val executable: Path?,
)

private suspend fun Job.cancelAndJoinWithin(name: String) {
    cancel()
    val completed = withTimeoutOrNull(2_000) {
        join()
        true
    } == true
    if (!completed) {
        LoggerFactory.getLogger(HexanaInstalledServer::class.java)
            .warn("{} did not finish within 2s after cancellation", name)
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
    launcher: HttpLauncher = HttpLauncher.Server,
    endpoint: String = "/mcp",
    logLevel: HexanaLogLevel = HexanaLogLevel.QUIET,
    toolProfile: HexanaMcpToolProfile? = null,
    allowedRoots: List<Path> = emptyList(),
    crossinline block: suspend (HexanaInstalledServer) -> T
): T {
    val server = HexanaInstalledServer.start(
        launcher = launcher,
        endpoint = endpoint,
        logLevel = logLevel,
        toolProfile = toolProfile,
        allowedRoots = allowedRoots,
    )
    return try {
        block(server)
    } finally {
        server.close()
    }
}
