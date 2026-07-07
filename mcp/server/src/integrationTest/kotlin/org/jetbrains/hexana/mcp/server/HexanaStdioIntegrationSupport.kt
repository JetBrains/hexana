package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.LoggerFactory
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

internal data class StdioLauncher(
    val installDirSystemProperty: String,
    val unixRelativePath: String,
    val windowsRelativePath: String,
    val clientName: String,
    val executableSystemProperty: String? = null,
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
        val CodexPluginWrapper = StdioLauncher(
            installDirSystemProperty = "hexana.integration.codexPluginDir",
            unixRelativePath = "bin/hexana-mcp",
            windowsRelativePath = "bin/hexana-mcp.cmd",
            clientName = "hexana-codex-plugin-integration-test",
        )
        val NativeExecutable = StdioLauncher(
            installDirSystemProperty = "hexana.integration.nativeExecutable",
            unixRelativePath = "",
            windowsRelativePath = "",
            clientName = "hexana-native-integration-test",
            executableSystemProperty = "hexana.integration.nativeExecutable",
        )
    }
}

internal class HexanaInstalledStdioClient private constructor(
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
        val clientClosed = withTimeoutOrNull(5_000.milliseconds) {
            runCatching { client.close() }
                .onFailure { logger.warn("Failed to close MCP stdio client cleanly", it) }
            true
        } == true
        if (!clientClosed) {
            logger.warn("MCP stdio client close timed out after 5s; destroying server process tree")
        }

        stopInstalledProcessTree(process, logger, "stdio server")
        closeInstalledProcessStreams(process, logger, "stdio server")
        logger.info(
            "Installed stdio server stopped with exitCode={}, alive={}, stderrBytes={}",
            process.exitCodeOrNull(),
            process.isAlive,
            stderr.length
        )
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 20_000L
        private const val COMMAND_TIMEOUT_SECONDS = 5L

        private val logger = LoggerFactory.getLogger(HexanaInstalledStdioClient::class.java)

        suspend fun start(
            logLevel: HexanaLogLevel = HexanaLogLevel.INFO,
            launcher: StdioLauncher = StdioLauncher.Server,
            toolProfile: HexanaMcpToolProfile? = null,
            allowedRoots: List<Path> = emptyList(),
        ): HexanaInstalledStdioClient {
            val processSpec = processSpec(launcher)
            val command = buildCommand(
                processSpec = processSpec,
                launcher = launcher,
                arguments = buildList {
                    add("stdio")
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
            )
            logger.info(
                "Starting stdio server from {} with logLevel={} command={}",
                processSpec.workingDirectory,
                logLevel.cliValue(),
                command.joinToString(" ")
            )

            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(command)
                    .directory(processSpec.workingDirectory.toFile())
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
            val processSpec = processSpec(launcher)
            val command = buildCommand(
                processSpec = processSpec,
                launcher = launcher,
                arguments = arguments.toList()
            )
            logger.info("Starting command from {} command={}", processSpec.workingDirectory, command.joinToString(" "))

            val process = withContext(Dispatchers.IO) {
                ProcessBuilder(command)
                    .directory(processSpec.workingDirectory.toFile())
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
                stopInstalledProcessTree(process, logger, "installed command")
                closeInstalledProcessStreams(process, logger, "installed command")
            }
            val stdout = process.inputStream.readTextSafely(logger, "installed command stdout")
            val stderr = process.errorStream.readTextSafely(logger, "installed command stderr")
            return InstalledCommandResult(
                command = command,
                exitCode = process.exitCodeOrNull() ?: -1,
                stdout = stdout,
                stderr = stderr,
                timedOut = !completed,
            )
        }

        private fun processSpec(launcher: StdioLauncher): StdioProcessSpec {
            launcher.executableSystemProperty?.let { systemPropertyName ->
                val executable = integrationPath(systemPropertyName)
                require(Files.isRegularFile(executable)) { "Native executable does not exist: $executable" }
                require(Files.isExecutable(executable)) { "Native executable is not executable: $executable" }
                return StdioProcessSpec(
                    workingDirectory = executable.parent ?: Path.of(".").toAbsolutePath(),
                    executable = executable,
                )
            }

            val installDir = integrationPath(launcher.installDirSystemProperty)
            require(Files.isDirectory(installDir)) { "Install directory does not exist: $installDir" }
            return StdioProcessSpec(
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
            processSpec: StdioProcessSpec,
            launcher: StdioLauncher,
            arguments: List<String>,
        ): List<String> {
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
            stopInstalledProcessTree(process, logger, "failed stdio server")
            closeInstalledProcessStreams(process, logger, "failed stdio server")
        }
    }
}

private suspend fun InputStream.readTextSafely(logger: org.slf4j.Logger, label: String): String {
    return withContext(Dispatchers.IO) {
        runCatching { bufferedReader().readText() }
            .onFailure { logger.warn("Failed to read {}", label, it) }
            .getOrDefault("")
    }
}

private data class StdioProcessSpec(
    val workingDirectory: Path,
    val executable: Path?,
)

internal data class InstalledCommandResult(
    val command: List<String>,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    val unexpectedStderr: String
        get() = stderr.withoutJvmLauncherDiagnostics()
}

private fun String.withoutJvmLauncherDiagnostics(): String =
    lineSequence()
        .filterNot { it.trimStart().isJvmLauncherDiagnostic() }
        .joinToString("\n")
        .trim()

private fun String.isJvmLauncherDiagnostic(): Boolean =
    startsWith("Picked up JAVA_TOOL_OPTIONS:") ||
        startsWith("Picked up _JAVA_OPTIONS:") ||
        startsWith("NOTE: Picked up JDK_JAVA_OPTIONS:")

internal suspend inline fun <T> withInstalledStdioClient(
    logLevel: HexanaLogLevel = HexanaLogLevel.INFO,
    launcher: StdioLauncher = StdioLauncher.Server,
    toolProfile: HexanaMcpToolProfile? = null,
    allowedRoots: List<Path> = emptyList(),
    crossinline block: suspend (HexanaInstalledStdioClient) -> T
): T {
    val server = HexanaInstalledStdioClient.start(
        logLevel = logLevel,
        launcher = launcher,
        toolProfile = toolProfile,
        allowedRoots = allowedRoots,
    )
    return try {
        block(server)
    } finally {
        server.close()
    }
}
