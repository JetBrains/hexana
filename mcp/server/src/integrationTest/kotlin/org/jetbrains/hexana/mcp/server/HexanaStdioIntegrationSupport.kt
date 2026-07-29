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
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.test.assertTrue
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
    private val stderrBuffer: StringBuffer,
    internal val diagnosticDirectory: Path,
    private val diagnosticDirectoryOwner: DiagnosticDirectoryOwner,
) {
    private val logger = LoggerFactory.getLogger(HexanaInstalledStdioClient::class.java)
    private val closed = AtomicBoolean()

    val stderr: String
        get() = stderrBuffer.toString()

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
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
            diagnosticDirectoryOwner.close()
            logger.info(
                "Installed stdio server stopped with exitCode={}, alive={}, stderrBytes={}",
                process.exitCodeOrNull(),
                process.isAlive,
                stderr.length
            )
        } catch (error: Throwable) {
            closed.set(false)
            throw error
        }
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
            maxOutputBytes: Int? = null,
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
                    if (maxOutputBytes != null) {
                        add("--max-output-bytes")
                        add(maxOutputBytes.toString())
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
                    stderrBuffer = stderrBuffer,
                    diagnosticDirectory = diagnosticDirectoryOwner.path,
                    diagnosticDirectoryOwner = diagnosticDirectoryOwner,
                )
            } catch (error: Throwable) {
                runCatching { client.close() }.onFailure(error::addSuppressed)
                runCatching { stopFailedProcess(process) }.onFailure(error::addSuppressed)
                diagnosticDirectoryOwner.closeSuppressing(error)
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
            vararg arguments: String,
        ): InstalledCommandResult = runCommand(
            launcher = launcher,
            arguments = arguments.toList(),
            stdinBytes = null,
        )

        suspend fun runCommand(
            launcher: StdioLauncher,
            arguments: List<String>,
            stdinBytes: ByteArray?,
            closeStdin: Boolean = true,
            environment: Map<String, String> = emptyMap(),
        ): InstalledCommandResult {
            val processSpec = processSpec(launcher)
            val command = buildCommand(
                processSpec = processSpec,
                launcher = launcher,
                arguments = arguments,
            )
            logger.info("Starting command from {} command={}", processSpec.workingDirectory, command.joinToString(" "))

            val processBuilder = ProcessBuilder(command).apply {
                directory(processSpec.workingDirectory.toFile())
                useCurrentJvm(logger)
            }
            val diagnosticDirectoryOwner = processBuilder.useIsolatedDiagnosticDirectory(environment)
            var process: Process? = null
            var primaryFailure: Throwable? = null
            try {
                process = withContext(Dispatchers.IO) { processBuilder.start() }
                if (closeStdin || stdinBytes != null) {
                    withContext(Dispatchers.IO) {
                        process.outputStream.use { stdin ->
                            if (stdinBytes != null) stdin.write(stdinBytes)
                        }
                    }
                }
                val completed = withContext(Dispatchers.IO) {
                    process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                if (!closeStdin) runCatching { process.outputStream.close() }
                if (!completed) {
                    logger.warn("Installed command did not exit within {}s, forcing pid={}", COMMAND_TIMEOUT_SECONDS, process.pid())
                    stopInstalledProcessTree(process, logger, "installed command")
                }
                val stdout = process.inputStream.readTextSafely(logger, "installed command stdout")
                val stderr = process.errorStream.readTextSafely(logger, "installed command stderr")
                return InstalledCommandResult(command, process.exitCodeOrNull() ?: -1, stdout, stderr, !completed,
                    diagnosticDirectoryOwner.path)
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                process?.let {
                    if (it.isAlive) runCatching { stopInstalledProcessTree(it, logger, "installed command") }
                        .onFailure { cleanup -> primaryFailure?.addSuppressed(cleanup) ?: throw cleanup }
                    closeInstalledProcessStreams(it, logger, "installed command")
                }
                diagnosticDirectoryOwner.closeSuppressing(primaryFailure)
            }
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

internal fun ProcessBuilder.useIsolatedDiagnosticDirectory(
    explicitEnvironment: Map<String, String> = emptyMap(),
): DiagnosticDirectoryOwner {
    environment().putAll(explicitEnvironment)
    if (INTEGRATION_DIAGNOSTIC_ENV in explicitEnvironment) {
        return DiagnosticDirectoryOwner(Path.of(environment().getValue(INTEGRATION_DIAGNOSTIC_ENV)), owned = false)
    }
    val path = Files.createTempDirectory("hexana-mcp-integration-logs")
    environment()[INTEGRATION_DIAGNOSTIC_ENV] = path.toString()
    return DiagnosticDirectoryOwner(path, owned = true)
}

private const val INTEGRATION_DIAGNOSTIC_ENV = "HEXANA_MCP_LOG_DIR"

internal class DiagnosticDirectoryOwner internal constructor(val path: Path, private val owned: Boolean) {
    private val closed = AtomicBoolean()

    fun close() {
        if (!owned || !closed.compareAndSet(false, true)) return
        try {
            if (!Files.exists(path)) return
            Files.walk(path).use { entries ->
                entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        } catch (error: Throwable) {
            closed.set(false)
            throw error
        }
    }

    fun closeSuppressing(primaryFailure: Throwable?) {
        runCatching(::close).onFailure { cleanup ->
            if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
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
    val diagnosticDirectory: Path,
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

internal fun integrationInstallDir(launcher: StdioLauncher): Path {
    val rawPath = System.getProperty(launcher.installDirSystemProperty)
        ?: error("Missing `${launcher.installDirSystemProperty}` system property")
    return Path.of(rawPath)
}

internal fun assertNoPreObfuscationHexanaRuntimeJars(libDir: Path, label: String) {
    val jars = mutableListOf<Path>()
    Files.list(libDir).use { stream ->
        stream.forEach { path ->
            if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar")) {
                jars.add(path)
            }
        }
    }
    jars.sortBy { it.fileName.toString() }
    val jarNames = jars.map { it.fileName.toString() }

    assertTrue(
        OBFUSCATED_HEXANA_RUNTIME_JAR in jarNames,
        "$label must ship $OBFUSCATED_HEXANA_RUNTIME_JAR in $libDir. Jars: $jarNames"
    )
    assertTrue(
        SCRIPTING_RUNTIME_JAR !in jarNames,
        "$label must not ship pre-obfuscation $SCRIPTING_RUNTIME_JAR beside $OBFUSCATED_HEXANA_RUNTIME_JAR. Jars: $jarNames"
    )

    val preObfuscationHexanaJars = jars
        .filterNot { it.fileName.toString() == OBFUSCATED_HEXANA_RUNTIME_JAR }
        .filter { it.containsHexanaClasses() }
        .map { it.fileName.toString() }

    assertTrue(
        preObfuscationHexanaJars.isEmpty(),
        "$label must not ship pre-obfuscation Hexana runtime jars beside " +
            "$OBFUSCATED_HEXANA_RUNTIME_JAR. Found: $preObfuscationHexanaJars"
    )
}

private const val OBFUSCATED_HEXANA_RUNTIME_JAR = "hexana-runtime-obfuscated.jar"
private const val SCRIPTING_RUNTIME_JAR = "scripting.jar"

private fun Path.containsHexanaClasses(): Boolean =
    ZipFile(toFile()).use { zip ->
        zip.entries().asSequence().any { entry ->
            !entry.isDirectory &&
                entry.name.startsWith("org/jetbrains/hexana/") &&
                entry.name.endsWith(".class")
        }
    }

internal suspend inline fun <T> withInstalledStdioClient(
    logLevel: HexanaLogLevel = HexanaLogLevel.INFO,
    launcher: StdioLauncher = StdioLauncher.Server,
    toolProfile: HexanaMcpToolProfile? = null,
    allowedRoots: List<Path> = emptyList(),
    maxOutputBytes: Int? = null,
    crossinline block: suspend (HexanaInstalledStdioClient) -> T
): T {
    val server = HexanaInstalledStdioClient.start(
        logLevel = logLevel,
        launcher = launcher,
        toolProfile = toolProfile,
        allowedRoots = allowedRoots,
        maxOutputBytes = maxOutputBytes,
    )
    return try {
        block(server)
    } finally {
        server.close()
    }
}
