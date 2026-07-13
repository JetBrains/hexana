package org.jetbrains.hexana.cli.hexanacliscript

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class HexanaCliScriptIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installedScriptProductExposesOnlyApprovedCommands() {
        val help = runScriptCli()
        assertEquals(0, help.exitCode, help.stderr)
        assertTrue(help.stdout.startsWith("hexana-cli-script <command> [options]\n"), help.stdout)
        listOf("run-script", "tools", "version", "help").forEach {
            assertTrue(help.stdout.contains(it), help.stdout)
        }
        listOf("summarize-binary", "summarize-module", "wasm-to-wat", "stdio", "http", "doctor").forEach {
            assertFalse(help.stdout.contains(it), help.stdout)
            val rejected = runScriptCli(it)
            assertEquals(2, rejected.exitCode, "stdout=${rejected.stdout}, stderr=${rejected.stderr}")
            assertTrue(rejected.stderr.contains("no such subcommand"), rejected.stderr)
        }

        val tools = runScriptCli("tools")
        assertEquals(0, tools.exitCode, tools.stderr)
        assertTrue(tools.stdout.startsWith("hexana-cli-script tools\n"), tools.stdout)
        assertTrue(tools.stdout.contains("run-script\n"), tools.stdout)
        assertFalse(tools.stdout.contains("summarize-binary\n"), tools.stdout)

        val helpCommand = runScriptCli("help", "run-script")
        assertEquals(0, helpCommand.exitCode, helpCommand.stderr)
        assertTrue(helpCommand.stdout.startsWith("Usage: hexana-cli-script run-script"), helpCommand.stdout)
    }

    @Test
    fun installedRunScriptSupportsPathBase64StdinFailuresAndRawOutput() {
        val binary = temporaryFolder.newFile("input.bin").toPath()
        Files.write(binary, byteArrayOf(1, 2, 3))

        val path = runScriptCli(
            "run-script",
            binary.toString(),
            "--script-text",
            "print(binary.bytes.size); print(binary.bytes.get(1));",
            "--format",
            "raw",
        )
        assertEquals(0, path.exitCode, path.stderr)
        assertEquals("3\n2", path.stdout)

        val base64 = runScriptCli(
            "run-script",
            "--binary-base64",
            Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6)),
            "--script-text",
            "print(binary.bytes.get(2));",
            "--format",
            "raw",
        )
        assertEquals(0, base64.exitCode, base64.stderr)
        assertEquals("6", base64.stdout)

        val stdin = runScriptCli(
            "run-script",
            "--stdin",
            "--script-text",
            "print(binary.bytes.size);",
            "--format",
            "json",
            stdin = byteArrayOf(7, 8, 9),
        )
        assertEquals(0, stdin.exitCode, stdin.stderr)
        assertEquals(1, trailingNewlineCount(stdin.stdout), stdin.stdout)
        assertTrue(stdin.stdout.contains("\"success\":true"), stdin.stdout)

        val failure = runScriptCli(
            "run-script",
            binary.toString(),
            "--script-text",
            "throw new Error('boom');",
            "--format",
            "raw",
        )
        assertEquals(1, failure.exitCode, failure.stderr)
        assertTrue(failure.stderr.contains("RUNTIME_ERROR"), failure.stderr)
        assertFalse(failure.stderr.contains(binary.toString()), failure.stderr)

        val usage = runScriptCli("run-script", binary.toString(), "--format", "pretty")
        assertEquals(2, usage.exitCode, usage.stderr)

        val allowedRoot = temporaryFolder.newFolder("allowed-root").toPath()
        val allowedBinary = allowedRoot.resolve("input.bin")
        Files.write(allowedBinary, byteArrayOf(1, 2, 3))
        val allowed = runScriptCli(
            "run-script",
            allowedBinary.toString(),
            "--script-text",
            "print(binary.bytes.size);",
            env = mapOf("HEXANA_CLI_ALLOWED_ROOTS" to allowedRoot.toString()),
        )
        assertEquals(0, allowed.exitCode, allowed.stderr)

        val flagAllowedRoot = temporaryFolder.newFolder("flag-allowed-root").toPath()
        val additive = runScriptCli(
            "run-script",
            allowedBinary.toString(),
            "--allowed-root",
            flagAllowedRoot.toString(),
            "--script-text",
            "print(binary.bytes.size);",
            env = mapOf("HEXANA_CLI_ALLOWED_ROOTS" to allowedRoot.toString()),
        )
        assertEquals(0, additive.exitCode, additive.stderr)

        val denied = runScriptCli(
            "run-script",
            binary.toString(),
            "--script-text",
            "print(binary.bytes.size);",
            env = mapOf("HEXANA_CLI_ALLOWED_ROOTS" to allowedRoot.toString()),
        )
        assertEquals(1, denied.exitCode, denied.stderr)
        assertTrue(denied.stderr.contains("outside allowed roots"), denied.stderr)
    }

    @Test
    fun telemetryDefaultsToEnabledSupportsOptOutAndUsesSharedDistinctId() {
        val home = temporaryFolder.newFolder("telemetry-home").toPath()
        FakePostHogServer.start().use { server ->
            val telemetryEnv = mapOf(
                "HEXANA_CLI_POSTHOG_HOST" to server.url,
                "HEXANA_CLI_POSTHOG_KEY" to "test-key",
                "HOME" to home.toString(),
                "JAVA_TOOL_OPTIONS" to "-Duser.home=$home",
            )
            val standardVersion = runStandardCli("version", env = telemetryEnv)
            assertEquals(0, standardVersion.exitCode, standardVersion.stderr)
            val scriptVersion = runScriptCli("version", env = telemetryEnv)
            assertEquals(0, scriptVersion.exitCode, scriptVersion.stderr)

            val events = server.awaitEvents(2)
            val standardEvent = events.single { it.property("product_id") == "hexana-cli" }
            val scriptEvent = events.single { it.property("product_id") == "hexana-cli-script" }
            assertEquals("version", standardEvent.property("command_name"))
            assertEquals("version", scriptEvent.property("command_name"))
            assertEquals(setOf("product_id", "command_name"), standardEvent.customPropertyKeys())
            assertEquals(setOf("product_id", "command_name"), scriptEvent.customPropertyKeys())
            assertEquals(standardEvent.distinctId, scriptEvent.distinctId)
            assertTrue(Files.exists(home.resolve(".hexana/analytics-id")))
        }

        FakePostHogServer.start().use { server ->
            val disabled = runScriptCli(
                "version",
                env = mapOf(
                    "HEXANA_CLI_POSTHOG_HOST" to server.url,
                    "HEXANA_CLI_TELEMETRY_ENABLED" to "false",
                    "HOME" to home.toString(),
                    "JAVA_TOOL_OPTIONS" to "-Duser.home=$home",
                )
            )
            assertEquals(0, disabled.exitCode, disabled.stderr)
            server.assertNoRequests()
        }
    }

    @Test
    fun versionsAreSharedAcrossProductsAndMcpEnvIsIgnored() {
        val standard = runStandardCli("version")
        val script = runScriptCli("version")
        assertEquals(0, standard.exitCode, standard.stderr)
        assertEquals(0, script.exitCode, script.stderr)
        assertEquals(standard.stdout.substringAfter(' '), script.stdout.substringAfter(' '))

        val binary = temporaryFolder.newFile("mcp-ignored.bin").toPath()
        Files.write(binary, byteArrayOf(1, 2, 3))
        val mcpIgnored = runScriptCli(
            "run-script",
            binary.toString(),
            "--script-text",
            "print(binary.bytes.size);",
            "--format",
            "raw",
            env = mapOf("HEXANA_MCP_MAX_OUTPUT_BYTES" to "1"),
        )
        assertEquals(0, mcpIgnored.exitCode, mcpIgnored.stderr)
        assertEquals("3", mcpIgnored.stdout)
    }

    private fun runScriptCli(
        vararg args: String,
        stdin: ByteArray? = null,
        env: Map<String, String> = emptyMap(),
    ): ProcessResult = runInstalled(
        installDir = Path.of(requireNotNull(System.getProperty("hexana.cli.script.integration.installDir"))),
        executableName = "hexana-cli-script",
        args = args.toList(),
        stdin = stdin,
        env = env.withTelemetryDefaultDisabled(),
    )

    private fun runStandardCli(
        vararg args: String,
        stdin: ByteArray? = null,
        env: Map<String, String> = emptyMap(),
    ): ProcessResult = runInstalled(
        installDir = Path.of(requireNotNull(System.getProperty("hexana.cli.integration.installDir"))),
        executableName = "hexana-cli",
        args = args.toList(),
        stdin = stdin,
        env = env.withTelemetryDefaultDisabled(),
    )

    private fun Map<String, String>.withTelemetryDefaultDisabled(): Map<String, String> =
        if (keys.any { it == "HEXANA_CLI_POSTHOG_HOST" || it == "HEXANA_CLI_TELEMETRY_ENABLED" }) {
            this
        } else {
            this + ("HEXANA_CLI_TELEMETRY_ENABLED" to "false")
        }
}

private data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

private fun runInstalled(
    installDir: Path,
    executableName: String,
    args: List<String>,
    stdin: ByteArray?,
    env: Map<String, String>,
): ProcessResult {
    val executable = installedLauncher(installDir, executableName)
    val process = ProcessBuilder(listOf(executable.toString()) + args)
        .redirectErrorStream(false)
        .apply {
            environment().keys.removeAll { it.startsWith("HEXANA_CLI_") || it.startsWith("HEXANA_MCP_") }
            environment().putAll(env)
            environment()["JAVA_HOME"] = System.getProperty("java.home")
        }
        .start()
    if (stdin != null) {
        process.outputStream.use { it.write(stdin) }
    } else {
        process.outputStream.close()
    }
    val executor = Executors.newFixedThreadPool(2)
    try {
        val stdout = executor.readAsync { process.inputStream.readBytes() }
        val stderr = executor.readAsync { process.errorStream.readBytes() }
        check(process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Timed out running $executableName ${args.joinToString(" ")}"
        }
        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = stdout.get().toString(Charsets.UTF_8),
            stderr = stderr.get().toString(Charsets.UTF_8),
        )
    } finally {
        executor.shutdownNow()
    }
}

private fun installedLauncher(installDir: Path, executableName: String): Path =
    installDir.resolve("bin").resolve(if (isWindows()) "$executableName.bat" else executableName)

private fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private fun ExecutorService.readAsync(block: () -> ByteArray): CompletableFuture<ByteArray> =
    CompletableFuture.supplyAsync(block, this)

private class FakePostHogServer private constructor(
    private val server: HttpServer,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val recordedRequests = CopyOnWriteArrayList<String>()

    val url: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.recordAndRespond()
        }
        server.start()
    }

    fun awaitEvents(count: Int): List<RecordedEvent> {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        do {
            val events = events()
            if (events.size >= count) return events
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
        fail("Expected at least $count telemetry events, got ${events()} from requests $recordedRequests")
    }

    fun assertNoRequests() {
        val deadline = System.nanoTime() + Duration.ofMillis(250).toNanos()
        do {
            if (recordedRequests.isNotEmpty()) {
                fail("Expected no telemetry requests, got $recordedRequests")
            }
            Thread.sleep(25)
        } while (System.nanoTime() < deadline)
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun events(): List<RecordedEvent> =
        recordedRequests.flatMap { body ->
            val root = JsonParser.parseString(body).asJsonObject
            root["batch"]?.asJsonArray?.mapNotNull { it.asJsonObjectOrNull()?.toRecordedEvent() }
                ?: listOfNotNull(root.toRecordedEvent())
        }

    private fun HttpExchange.recordAndRespond() {
        val raw = requestBody.use { it.readBytes() }
        val body = if (requestHeaders["Content-Encoding"].orEmpty().any { it.equals("gzip", ignoreCase = true) }) {
            GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        } else {
            raw
        }
        recordedRequests += body.toString(Charsets.UTF_8)
        val response = """{"status":"ok"}""".toByteArray(Charsets.UTF_8)
        sendResponseHeaders(200, response.size.toLong())
        responseBody.use { it.write(response) }
    }

    companion object {
        fun start(): FakePostHogServer =
            FakePostHogServer(HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0))
    }
}

private data class RecordedEvent(
    val name: String,
    val distinctId: String,
    val properties: JsonObject,
) {
    fun property(name: String): String? = properties[name]?.asString

    fun customPropertyKeys(): Set<String> =
        properties.keySet().filterNot { it.startsWith("$") || it == "token" }.toSet()
}

private fun JsonObject.toRecordedEvent(): RecordedEvent? {
    if (this["event"]?.asString != "cli_command_invoked") return null
    return RecordedEvent(
        name = this["event"].asString,
        distinctId = this["distinct_id"]?.asString ?: this["distinctId"]?.asString ?: "",
        properties = this["properties"].asJsonObject,
    )
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
    if (isJsonObject) asJsonObject else null

private fun trailingNewlineCount(value: String): Int =
    value.reversed().takeWhile { it == '\n' }.length
