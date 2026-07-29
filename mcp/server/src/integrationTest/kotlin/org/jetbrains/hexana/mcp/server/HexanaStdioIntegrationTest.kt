package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HexanaStdioIntegrationTest {
    private val logger = LoggerFactory.getLogger(HexanaStdioIntegrationTest::class.java)

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installedServerVersionCommandExitsPromptly() = runBlocking {
        val result = HexanaInstalledStdioClient.runCommand(StdioLauncher.Server, "version")

        assertFalse(
            result.timedOut,
            "Version command timed out. stdout=${result.stdout}, stderr=${result.stderr}, command=${result.command}"
        )
        assertEquals(0, result.exitCode, "stderr=${result.stderr}")
        assertTrue(
            result.stdout.trim().matches(Regex("""hexana-mcp \S+""")),
            "Unexpected version output: ${result.stdout}"
        )
        assertFalse(Files.exists(result.diagnosticDirectory))
    }

    @Test
    fun ownedDiagnosticDirectoryReplacesInheritedStateAndDeletesRecursivelyOnce() {
        val inherited = Path.of("inherited-diagnostics")
        val process = ProcessBuilder().apply {
            environment()["HEXANA_MCP_LOG_DIR"] = inherited.toString()
        }
        val owner = process.useIsolatedDiagnosticDirectory()
        val actual = Path.of(process.environment().getValue("HEXANA_MCP_LOG_DIR"))
        Files.createDirectories(actual.resolve("nested"))
        Files.writeString(actual.resolve("nested/session.jsonl"), "{}\n")

        assertNotEquals(inherited, actual)
        owner.close()
        owner.close()
        assertFalse(Files.exists(actual))
    }

    @Test
    fun explicitDiagnosticDirectoryAndSentinelAreNeverOwned() {
        val explicit = temporaryFolder.newFolder("explicit-command-diagnostics").toPath()
        val sentinel = Files.writeString(explicit.resolve("sentinel.txt"), "keep")
        val process = ProcessBuilder()
        val owner = process.useIsolatedDiagnosticDirectory(mapOf("HEXANA_MCP_LOG_DIR" to explicit.toString()))

        owner.close()
        assertEquals(explicit, Path.of(process.environment().getValue("HEXANA_MCP_LOG_DIR")))
        assertTrue(Files.exists(sentinel))
    }

    @Test
    fun invalidConfigurationKeepsStdoutCleanAndWritesStructuredDiagnostic() = runBlocking {
        val logDirectory = temporaryFolder.newFolder("configuration-diagnostics").toPath()
        val result = HexanaInstalledStdioClient.runCommand(
            launcher = StdioLauncher.Server,
            arguments = listOf("stdio", "--log-level", "invalid"),
            stdinBytes = null,
            environment = mapOf("HEXANA_MCP_LOG_DIR" to logDirectory.toString()),
        )

        assertFalse(result.timedOut, result.toString())
        assertTrue(result.exitCode != 0, result.toString())
        assertTrue(result.stdout.isBlank(), "stdio stdout must stay protocol-only: ${result.stdout}")
        assertTrue(result.stderr.contains("[CONFIGURATION; id="), result.stderr)
        val diagnosticFiles = Files.list(logDirectory).use { it.toList() }
        assertEquals(1, diagnosticFiles.size)
        val diagnosticText = Files.readString(diagnosticFiles.single())
        assertTrue(diagnosticText.lineSequence().all { it.isBlank() || runCatching { Json.parseToJsonElement(it) }.isSuccess })
        assertTrue(diagnosticText.contains("\"category\":\"CONFIGURATION\""), diagnosticText)
    }

    @Test
    fun malformedStdioExitsNonzeroWithOneCompactStructuredFailure() = runBlocking {
        val logDirectory = temporaryFolder.newFolder("transport-diagnostics").toPath()
        val result = HexanaInstalledStdioClient.runCommand(
            launcher = StdioLauncher.Server,
            arguments = listOf("stdio", "--log-level", "quiet", "--telemetry-enabled", "false"),
            stdinBytes = "not-json\n".encodeToByteArray(),
            environment = mapOf("HEXANA_MCP_LOG_DIR" to logDirectory.toString()),
        )

        assertFalse(result.timedOut, result.toString())
        assertTrue(result.exitCode != 0, result.toString())
        assertTrue(result.stdout.isBlank(), "stdio stdout must stay protocol-only: ${result.stdout}")
        val stderr = result.unexpectedStderr
        assertEquals(1, stderr.lineSequence().count { it.isNotBlank() }, stderr)
        assertTrue(stderr.contains("[STDIO_TRANSPORT; id="), stderr)
        assertNoStackTrace(stderr)
        val diagnosticFiles = Files.list(logDirectory).use { it.toList() }
        assertEquals(1, diagnosticFiles.size)
        val diagnosticText = Files.readString(diagnosticFiles.single())
        assertEquals(1, Regex("\"event\":\"failure\"").findAll(diagnosticText).count(), diagnosticText)
        assertTrue(diagnosticText.contains("\"category\":\"STDIO_TRANSPORT\""), diagnosticText)
        assertTrue(diagnosticText.contains("\"stack_trace\""), diagnosticText)
    }

    @Test
    fun installedServerDoctorCommandReportsRuntimeAndLayout() = runBlocking {
        val result = HexanaInstalledStdioClient.runCommand(StdioLauncher.Server, "doctor")

        assertFalse(
            result.timedOut,
            "Doctor command timed out. stdout=${result.stdout}, stderr=${result.stderr}, command=${result.command}"
        )
        assertEquals(0, result.exitCode, "stdout=${result.stdout}, stderr=${result.stderr}")
        assertTrue(result.stdout.contains("Hexana MCP doctor"), result.stdout)
        assertTrue(result.stdout.contains("version:"), result.stdout)
        assertTrue(result.stdout.contains("runtime:"), result.stdout)
        assertTrue(result.stdout.contains("logs:"), result.stdout)
        assertTrue(result.stdout.contains("diagnostics:"), result.stdout)
        assertTrue(result.stdout.contains("structured JSONL diagnostics available"), result.stdout)
        assertTrue(result.stdout.contains("allowed roots:"), result.stdout)
        assertTrue(result.stdout.contains("installed layout:"), result.stdout)
        assertTrue(result.stdout.contains("JVM distribution detected"), result.stdout)
    }

    @Test
    fun removedDirectCommandsExitNonzeroWithNoSuchSubcommand() = runBlocking {
        val cases = listOf(
            "summarize-binary",
            "summarize-module",
            "wasm-to-wat",
            "run-script",
            "tools",
        )

        cases.forEach { command ->
            val result = HexanaInstalledStdioClient.runCommand(
                StdioLauncher.Server,
                command,
            )

            assertFalse(
                result.timedOut,
                "$command should exit promptly, not hang. stdout=${result.stdout}, stderr=${result.stderr}"
            )
            assertTrue(
                result.exitCode != 0,
                "$command should exit nonzero; exitCode=${result.exitCode}, stdout=${result.stdout}, stderr=${result.stderr}"
            )
            assertTrue(
                result.stdout.isBlank(),
                "$command stdout should be blank (protocol-only stdout); got: ${result.stdout}"
            )
            assertTrue(
                result.stderr.contains("no such subcommand"),
                "$command stderr should contain 'no such subcommand'; got: ${result.stderr}"
            )
            assertNoStackTrace(result.stderr)
        }
    }

    @Test
    fun installedServerTodoCommandIsHiddenByDefault() = runBlocking {
        val result = HexanaInstalledStdioClient.runCommand(StdioLauncher.Server, "list-enabled-features")

        assertFalse(result.timedOut, "Command timed out. stdout=${result.stdout}, stderr=${result.stderr}")
        assertEquals(1, result.exitCode, "stdout=${result.stdout}, stderr=${result.stderr}")
        assertTrue(result.stdout.isBlank(), result.stdout)
        assertTrue(result.stderr.contains("no such subcommand"), result.stderr)
        assertFalse(result.stderr.contains("not implemented"), result.stderr)
    }

    @Test
    fun installedServerSupportsListToolsAndToolCallsOverStdio() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        val fixtureDirectory = temporaryFolder.root.toPath()
        lateinit var diagnosticDirectory: Path
        withInstalledStdioClient(allowedRoots = listOf(fixtureDirectory)) { server ->
            diagnosticDirectory = server.diagnosticDirectory
            assertDefaultStdioSmoke(
                server = server,
                fixtureDirectory = fixtureDirectory,
                logger = logger,
                label = "installed"
            )
        }
        assertFalse(Files.exists(diagnosticDirectory))
    }

    @Test
    fun installedServerEnforcesGlobalEncodedResultBudget() = runBlocking {
        val fixtureDirectory = temporaryFolder.root.toPath()
        val wasm = fixtureDirectory.resolve("encoded-result-budget.wasm")
        Files.write(wasm, byteArrayOf(0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00))

        withInstalledStdioClient(
            allowedRoots = listOf(fixtureDirectory),
            maxOutputBytes = 100,
        ) { server ->
            val result = server.client.callTool(
                name = "summarize_module",
                arguments = mapOf("path" to wasm.toString()),
                meta = emptyMap<String, Any?>(),
            )

            assertEquals(true, result.isError)
            assertEquals(
                "RESOURCE_LIMIT_EXCEEDED",
                result.structuredContent?.get("code")?.jsonPrimitive?.content,
            )
            assertTrue((result.content.single() as TextContent).text.contains("maximum of 100 bytes"))
        }
    }

    @Test
    fun installedServerDrainsSingleResponseAfterFiniteStdinEof() = runBlocking {
        val result = HexanaInstalledStdioClient.runCommand(
            launcher = StdioLauncher.Server,
            arguments = listOf("stdio", "--telemetry-enabled", "false"),
            stdinBytes = stdioFrames(initializeRequest(id = 1)),
        )

        assertFiniteStdinStdioSucceeded(result)
        assertSuccessfulStdoutResponses(result.stdout, listOf("1"))
    }

    @Test
    fun installedServerDrainsMultipleResponsesAfterFiniteStdinEof() = runBlocking {
        val result = HexanaInstalledStdioClient.runCommand(
            launcher = StdioLauncher.Server,
            arguments = listOf("stdio", "--telemetry-enabled", "false"),
            stdinBytes = stdioFrames(
                initializeRequest(id = 1),
                """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
                """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
            ),
        )

        assertFiniteStdinStdioSucceeded(result)
        assertSuccessfulStdoutResponses(result.stdout, listOf("1", "2"))
    }

    @Test
    fun installedServerAcceptsLeadingBlankLineAndCrLfFrames() = runBlocking {
        val stdin = listOf(
            "",
            initializeRequest(id = 1),
            """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""",
        ).joinToString(separator = "\r\n", postfix = "\r\n").encodeToByteArray()
        val result = HexanaInstalledStdioClient.runCommand(
            launcher = StdioLauncher.Server,
            arguments = listOf("stdio", "--telemetry-enabled", "false"),
            stdinBytes = stdin,
        )

        assertFiniteStdinStdioSucceeded(result)
        assertSuccessfulStdoutResponses(result.stdout, listOf("1", "2"))
        assertFalse(result.stdout.lineSequence().filter { it.isNotBlank() }.any { line ->
            runCatching { Json.parseToJsonElement(line) }.isFailure
        }, "stdio stdout must contain protocol JSON only: ${result.stdout}")
        assertFalse(result.unexpectedStderr.contains("hexana-mcp error ["), result.stderr)
    }

    @Test
    fun installedServerScriptingProfileSupportsRunBinaryScriptToolCallOverStdio() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        val fixtureDirectory = temporaryFolder.root.toPath()
        val binary = fixtureDirectory.resolve("scripting-profile.bin")
        Files.write(binary, byteArrayOf(5, 6, 7, 8))

        withInstalledStdioClient(
            toolProfile = HexanaMcpToolProfile.SCRIPTING,
            allowedRoots = listOf(fixtureDirectory),
        ) { server ->
            val tools = server.client.listTools(ListToolsRequest())
            assertEquals(listOf("run_binary_script"), tools.tools.map { it.name }.sorted())

            val result = server.client.callTool(
                name = "run_binary_script",
                arguments = mapOf(
                    "path" to binary.toString(),
                    "script" to "print(binary.bytes.size); print(binary.bytes.get(2));",
                ),
                meta = emptyMap<String, Any?>(),
            )

            assertFalse(result.isError == true)
            val text = result.content.single() as TextContent
            assertEquals(result.structuredContent, Json.parseToJsonElement(text.text).jsonObject)
            val structured = result.structuredContent ?: error("Missing structured run_binary_script content")
            assertEquals(true, structured.getValue("success").jsonPrimitive.boolean)
            assertEquals("SUCCESS", structured.getValue("status").jsonPrimitive.content)
            assertEquals(
                listOf("4", "7"),
                structured.getValue("output")
                    .jsonObject
                    .getValue("lines")
                    .jsonArray
                    .map { it.jsonPrimitive.content },
            )
            assertTrue(server.stderr.contains("Starting stdio MCP transport"), server.stderr)
        }
    }

    @Test
    fun installedObfuscatedServerRuntimeShipsOnlyObfuscatedHexanaRuntimeJar() {
        val installDir = integrationInstallDir(StdioLauncher.Server)
        if (installDir.fileName.toString() != "server-r8") {
            return
        }

        assertNoPreObfuscationHexanaRuntimeJars(
            libDir = installDir.resolve("lib"),
            label = "standalone obfuscated MCP runtime",
        )
    }

    @Test
    fun installedServerAdvancedProfileExposesAllToolsOverStdio() = runBlocking {
        withInstalledStdioClient(toolProfile = HexanaMcpToolProfile.ADVANCED) { server ->
            val tools = server.client.listTools(ListToolsRequest())
            assertEquals(EXPECTED_ADVANCED_HEXANA_TOOL_NAMES, tools.tools.map { it.name }.sorted())
        }
    }

    private fun assertFiniteStdinStdioSucceeded(result: InstalledCommandResult) {
        assertFalse(
            result.timedOut,
            "Finite stdio command timed out. stdout=${result.stdout}, stderr=${result.stderr}, command=${result.command}"
        )
        assertEquals(0, result.exitCode, "stdout=${result.stdout}, stderr=${result.stderr}")
    }

    private fun assertSuccessfulStdoutResponses(stdout: String, expectedIds: List<String>) {
        val responses = stdout.lineSequence()
            .filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .toList()
        val ids = responses
            .map { message ->
                message["id"]?.jsonPrimitive?.content
                    ?: error("Unexpected id-less JSON-RPC message on stdout: $message")
            }
            .toList()

        assertEquals(expectedIds, ids, stdout)
        responses.forEach { response ->
            val id = response.getValue("id").jsonPrimitive.content
            assertFalse("error" in response, "Unexpected JSON-RPC error response for id=$id: $response")
            val result = response["result"]?.jsonObject
                ?: error("Missing JSON-RPC result for id=$id: $response")
            when (id) {
                "1" -> assertTrue("protocolVersion" in result, "Missing initialize result: $response")
                "2" -> assertEquals(
                    EXPECTED_DEFAULT_HEXANA_TOOL_NAMES,
                    result.getValue("tools")
                        .jsonArray
                        .map { tool -> tool.jsonObject.getValue("name").jsonPrimitive.content }
                        .sorted(),
                    response.toString()
                )
            }
        }
    }

    private fun stdioFrames(vararg frames: String): ByteArray =
        frames.joinToString(separator = "") { frame -> frame.trimIndent().trim() + "\n" }
            .encodeToByteArray()

    private fun initializeRequest(id: Int): String =
        """{"jsonrpc":"2.0","id":$id,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"hexana-finite-stdin-integration-test","version":"1.0"}}}"""

    private fun assertNoStackTrace(stderr: String) {
        assertFalse(Regex("""(?m)^\s+at\s""").containsMatchIn(stderr), stderr)
        assertFalse(stderr.contains("HexanaAnalysisException"), stderr)
    }
}
