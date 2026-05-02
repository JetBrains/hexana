package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

internal data class StdioLauncher(
    val installDirSystemProperty: String,
    val unixRelativePath: String,
    val windowsRelativePath: String,
    val clientName: String,
) {
    companion object {
        val Server = StdioLauncher(
            installDirSystemProperty = "hexana.integration.installDir",
            unixRelativePath = "bin/server",
            windowsRelativePath = "bin/server.bat",
            clientName = "hexana-integration-test",
        )
        val ClaudePluginWrapper = StdioLauncher(
            installDirSystemProperty = "hexana.integration.claudePluginDir",
            unixRelativePath = "bin/hexana-mcp",
            windowsRelativePath = "bin/hexana-mcp.cmd",
            clientName = "hexana-claude-plugin-integration-test",
        )
    }
}

internal class HexanaInstalledStdioClient private constructor(
    private val installDir: Path,
    private val command: List<String>,
    private val process: Process,
    val client: Client,
    private val stderrBuffer: StringBuffer
) {
    private val logger = LoggerFactory.getLogger(HexanaInstalledStdioClient::class.java)

    val stderr: String
        get() = stderrBuffer.toString()

    suspend fun close() {
        logger.info("Closing installed stdio server (pid={}, command={})", process.pid(), command.joinToString(" "))
        runCatching { client.close() }
            .onFailure { logger.warn("Failed to close MCP stdio client cleanly", it) }

        waitForProcessExit()
        logger.info(
            "Installed stdio server stopped with exitCode={}, stderrBytes={}",
            process.exitValue(),
            stderr.length
        )
    }

    private suspend fun waitForProcessExit() {
        withContext(NonCancellable + Dispatchers.IO) {
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                return@withContext
            }

            logger.warn("Process did not exit within 5s after client shutdown, destroying pid={}", process.pid())
            process.destroy()
            if (process.waitFor(5, TimeUnit.SECONDS)) {
                return@withContext
            }

            logger.warn("Process still alive after destroy(), forcing pid={}", process.pid())
            process.destroyForcibly()
            process.waitFor()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val COMMAND_TIMEOUT_SECONDS = 5L

        private val logger = LoggerFactory.getLogger(HexanaInstalledStdioClient::class.java)

        suspend fun start(
            logLevel: HexanaLogLevel = HexanaLogLevel.INFO,
            launcher: StdioLauncher = StdioLauncher.Server,
        ): HexanaInstalledStdioClient {
            val installDir = integrationInstallDir(launcher.installDirSystemProperty)
            val command = buildCommand(
                installDir = installDir,
                launcher = launcher,
                arguments = listOf("stdio", "--log-level", logLevel.cliValue())
            )
            logger.info(
                "Starting installed stdio server from {} with logLevel={} command={}",
                installDir,
                logLevel.cliValue(),
                command.joinToString(" ")
            )

            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(command)
                    .directory(installDir.toFile())
                    .useCurrentJvm(logger)
                    .start()
            }
            logger.info("STDIO process started with pid={}", process.pid())

            val stderrBuffer = StringBuffer()
            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
                error = process.errorStream.asSource().buffered(),
                classifyStderr = { chunk ->
                    captureStderr(stderrBuffer, chunk)
                    StdioClientTransport.StderrSeverity.IGNORE
                }
            )
            val client = Client(Implementation(name = launcher.clientName, version = "1.0"))

            try {
                logger.info("Connecting MCP stdio client to pid={}", process.pid())
                withTimeout(CONNECT_TIMEOUT_MILLIS.milliseconds) {
                    client.connect(transport)
                }
                logger.info("Connected MCP stdio client to pid={}", process.pid())
                return HexanaInstalledStdioClient(
                    installDir = installDir,
                    command = command,
                    process = process,
                    client = client,
                    stderrBuffer = stderrBuffer
                )
            } catch (error: Throwable) {
                runCatching { client.close() }
                stopFailedProcess(process)
                throw IllegalStateException(
                    buildString {
                        append("Failed to start installed stdio MCP server.")
                        val stderr = stderrBuffer.toString().trim()
                        if (stderr.isNotEmpty()) {
                            append(" stderr: ")
                            append(stderr)
                        }
                    },
                    error
                )
            }
        }

        suspend fun runCommand(
            launcher: StdioLauncher,
            vararg arguments: String
        ): InstalledCommandResult {
            val installDir = integrationInstallDir(launcher.installDirSystemProperty)
            val command = buildCommand(
                installDir = installDir,
                launcher = launcher,
                arguments = arguments.toList()
            )
            logger.info("Starting installed command from {} command={}", installDir, command.joinToString(" "))

            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(command)
                    .directory(installDir.toFile())
                    .useCurrentJvm(logger)
                    .start()
            }
            val completed = withContext(Dispatchers.IO) {
                process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            if (!completed) {
                logger.warn(
                    "Installed command did not exit within {}s, forcing pid={}",
                    COMMAND_TIMEOUT_SECONDS,
                    process.pid()
                )
                withContext(Dispatchers.IO) {
                    process.destroyForcibly()
                    process.waitFor()
                }
            }

            val stdout = withContext(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
            val stderr = withContext(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
            return InstalledCommandResult(
                command = command,
                exitCode = process.exitValue(),
                stdout = stdout,
                stderr = stderr,
                timedOut = !completed,
            )
        }

        private fun integrationInstallDir(systemPropertyName: String): Path {
            val rawPath = System.getProperty(systemPropertyName)
                ?: error("Missing `$systemPropertyName` system property")
            val path = Path.of(rawPath)
            require(Files.isDirectory(path)) { "Install directory does not exist: $path" }
            return path
        }

        private fun buildCommand(
            installDir: Path,
            launcher: StdioLauncher,
            arguments: List<String>,
        ): List<String> {
            return if (isWindows()) {
                listOf(
                    "cmd.exe",
                    "/c",
                    installDir.resolve(launcher.windowsRelativePath).toString(),
                ) + arguments
            } else {
                listOf(installDir.resolve(launcher.unixRelativePath).toString()) + arguments
            }
        }

        private fun isWindows(): Boolean {
            return System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
        }

        private fun captureStderr(stderrBuffer: StringBuffer, chunk: String) {
            synchronized(stderrBuffer) {
                stderrBuffer.append(chunk)
            }

            chunk.lineSequence()
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    logger.info("server[stderr] {}", line)
                }
        }

        private suspend fun stopFailedProcess(process: Process) {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!process.isAlive) {
                    return@withContext
                }

                process.destroy()
                if (process.waitFor(5, TimeUnit.SECONDS)) {
                    return@withContext
                }

                process.destroyForcibly()
                process.waitFor()
            }
        }
    }
}

internal data class InstalledCommandResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
)

internal suspend inline fun <T> withInstalledStdioClient(
    logLevel: HexanaLogLevel = HexanaLogLevel.INFO,
    launcher: StdioLauncher = StdioLauncher.Server,
    crossinline block: suspend (HexanaInstalledStdioClient) -> T
): T {
    val server = HexanaInstalledStdioClient.start(logLevel = logLevel, launcher = launcher)
    return try {
        block(server)
    } finally {
        server.close()
    }
}
