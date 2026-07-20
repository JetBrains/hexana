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
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue(result.stdout.contains("allowed roots:"), result.stdout)
        assertTrue(result.stdout.contains("installed layout:"), result.stdout)
        assertTrue(result.stdout.contains("JVM distribution detected"), result.stdout)
    }

    @Test
    fun installedServerToolsCommandReportsCliMetadata() = runBlocking {
        val result = HexanaInstalledStdioClient.runCommand(StdioLauncher.Server, "tools")

        assertInstalledCommandSucceeded(result)
        assertTrue(result.stdout.startsWith("Hexana MCP CLI tools\n"), result.stdout)
        assertTrue(result.stdout.contains("summarize-module\n"), result.stdout)
        assertTrue(result.stdout.contains("wasm-to-wat\n"), result.stdout)
        assertFalse(result.stdout.contains("list-enabled-features\n"), result.stdout)
        assertTrue(result.unexpectedStderr.isBlank(), result.stderr)
    }

    @Test
    fun installedServerSummarizeModuleCommandSupportsInstalledInputSources() = runBlocking {
        val wasm = createWasmFile("installed-summary.wasm", "(module (memory 1) (func (export \"main\")))")
        val wasmBytes = Files.readAllBytes(wasm)

        assertInstalledToolSourceModes(
            command = "summarize-module",
            path = wasm,
            stdinBytes = wasmBytes,
            base64Option = "--wasm-base64",
            format = "json",
            assertStdout = ::assertSummarizeModuleJson,
        )
    }

    @Test
    fun installedServerWasmToWatCommandSupportsInstalledInputSources() = runBlocking {
        val wasm = createWasmFile("installed-wat.wasm", "(module (func (export \"main\")))")
        val wasmBytes = Files.readAllBytes(wasm)

        assertInstalledToolSourceModes(
            command = "wasm-to-wat",
            path = wasm,
            stdinBytes = wasmBytes,
            base64Option = "--wasm-base64",
            format = "raw",
            assertStdout = ::assertRawWat,
        )
    }

    @Test
    fun installedExecutableToolAutoStdinDoesNotWaitForOpenEmptyStdin() = runBlocking {
        val result = HexanaInstalledStdioClient.runCommand(
            launcher = StdioLauncher.Server,
            arguments = listOf("summarize-module", "--format", "json"),
            stdinBytes = null,
            closeStdin = false,
        )

        assertFalse(
            result.timedOut,
            "Command waited for open empty stdin. stdout=${result.stdout}, stderr=${result.stderr}"
        )
        assertTrue(result.exitCode != 0, "stdout=${result.stdout}, stderr=${result.stderr}")
        assertTrue(result.stderr.contains("Missing summarize-module source"), result.stderr)
        assertNoStackTrace(result.stderr)
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
    fun installedServerSummarizeBinaryCommandSupportsInstalledInputSources() = runBlocking {
        val wasm = createWasmFile("installed-binary-summary.wasm", "(module (memory 1) (func (export \"main\")))")
        val wasmBytes = Files.readAllBytes(wasm)

        assertInstalledToolSourceModes(
            command = "summarize-binary",
            path = wasm,
            stdinBytes = wasmBytes,
            base64Option = "--binary-base64",
            format = "json",
            assertStdout = ::assertSummarizeBinaryWasmJson,
            extraBase64Options = listOf("--bytes-base64"),
        )
    }

    @Test
    fun installedServerSummarizeBinaryCommandReturnsNativeJsonOnStdout() = runBlocking {
        val elf = nativeFixture("elf/sample.o")

        val result = HexanaInstalledStdioClient.runCommand(
            StdioLauncher.Server,
            "summarize-binary",
            elf.toString(),
            "--format",
            "json",
        )

        assertInstalledCommandSucceeded(result)
        val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
        val module = json.getValue("module").jsonObject
        val counts = json.getValue("counts").jsonObject
        assertEquals("elf", module.getValue("format").jsonPrimitive.content)
        assertTrue(module.getValue("architecture").jsonPrimitive.content.isNotBlank(), result.stdout)
        assertTrue(counts.getValue("sections").jsonPrimitive.content.toInt() > 0, result.stdout)
        assertTrue(counts.getValue("symbols").jsonPrimitive.content.toInt() > 0, result.stdout)
        assertTrue(result.unexpectedStderr.isBlank(), result.stderr)
    }

    @Test
    fun installedServerRunScriptCommandReturnsScriptOutput() = runBlocking {
        val binary = temporaryFolder.newFile("run-script-success.bin").toPath()
        Files.write(binary, byteArrayOf(1, 2, 3))

        val result = HexanaInstalledStdioClient.runCommand(
            StdioLauncher.Server,
            "run-script",
            binary.toString(),
            "--script-text",
            "print(binary.bytes.size); print(binary.bytes.get(1));",
            "--format",
            "raw",
        )

        assertInstalledCommandSucceeded(result)
        assertEquals("3\n2", result.stdout)
        assertTrue(result.unexpectedStderr.isBlank(), result.stderr)
    }

    @Test
    fun installedServerRunScriptRawCanEmitExactlyMaxOutputBytes() = runBlocking {
        val binary = temporaryFolder.newFile("run-script-exact-output-budget.bin").toPath()
        Files.write(binary, byteArrayOf(1, 2, 3))

        val result = HexanaInstalledStdioClient.runCommand(
            StdioLauncher.Server,
            "run-script",
            binary.toString(),
            "--script-text",
            "print('abcd');",
            "--format",
            "raw",
            "--max-output-bytes",
            "4",
        )

        assertInstalledCommandSucceeded(result)
        assertEquals("abcd", result.stdout)
        assertEquals(4, result.stdout.toByteArray(StandardCharsets.UTF_8).size)
        assertTrue(result.unexpectedStderr.isBlank(), result.stderr)
    }

    @Test
    fun installedServerRunScriptFailuresExitNonZero() = runBlocking {
        val binary = temporaryFolder.newFile("run-script-failure.bin").toPath()
        Files.write(binary, byteArrayOf(1, 2, 3))
        val script = temporaryFolder.newFile("run-script-failure.js").toPath()
        Files.writeString(script, "print(1);")
        val allowedRoot = temporaryFolder.newFolder("run-script-allowed-root").toPath()
        val allowedBinary = allowedRoot.resolve("inside.bin")
        Files.write(allowedBinary, byteArrayOf(4, 5, 6))
        val outsideScript = temporaryFolder.newFile("run-script-outside.js").toPath()
        Files.writeString(outsideScript, "print(1);")

        val cases = listOf(
            listOf(
                "run-script",
                binary.toString(),
                "--script-text",
                "throw new Error('boom');",
                "--format",
                "raw",
            ) to listOf("RUNTIME_ERROR", "boom"),
            listOf(
                "run-script",
                binary.toString(),
                "--format",
                "raw",
            ) to listOf("INVALID_INPUT", "Exactly one of --script or --script-text is required"),
            listOf(
                "run-script",
                binary.toString(),
                "--script",
                script.toString(),
                "--script-text",
                "print(1);",
                "--format",
                "raw",
            ) to listOf("INVALID_INPUT", "Provide exactly one of --script or --script-text"),
            listOf(
                "run-script",
                allowedBinary.toString(),
                "--script",
                outsideScript.toString(),
                "--format",
                "raw",
                "--allowed-root",
                allowedRoot.toString(),
            ) to listOf("INVALID_INPUT", "outside allowed roots"),
        )

        cases.forEach { (arguments, expectedMessages) ->
            val result = HexanaInstalledStdioClient.runCommand(StdioLauncher.Server, *arguments.toTypedArray())

            assertFalse(result.timedOut, "Command timed out. stdout=${result.stdout}, stderr=${result.stderr}")
            assertEquals(1, result.exitCode, "stdout=${result.stdout}, stderr=${result.stderr}")
            assertTrue(result.stdout.isBlank(), "Expected stdout to stay empty, got: ${result.stdout}")
            expectedMessages.forEach { expected ->
                assertTrue(result.stderr.contains(expected), result.stderr)
            }
            assertNoStackTrace(result.stderr)
        }
    }

    @Test
    fun installedExecutableToolInputFailuresStayConciseOnStderr() = runBlocking {
        val outsideWasm = createWasmFile("outside-root.wasm", "(module)")
        val allowedRoot = temporaryFolder.newFolder("allowed-root").toPath()
        val textFile = temporaryFolder.newFile("not-wasm.txt").toPath()
        Files.writeString(textFile, "not a wasm module")
        val directoryInput = temporaryFolder.newFolder("directory-input").toPath()
        val outputLimitedWasm = createWasmFile("output-limit.wasm", "(module (memory 1) (func (export \"main\")))")

        val cases = listOf(
            listOf("summarize-binary", missingPath("missing.bin").toString(), "--format", "json") to "File not found",
            listOf("summarize-module", missingPath("missing.wasm").toString(), "--format", "json") to "File not found",
            listOf(
                "summarize-module",
                outsideWasm.toString(),
                "--format",
                "json",
                "--allowed-root",
                allowedRoot.toString(),
            ) to "outside allowed roots",
            listOf("summarize-module", directoryInput.toString(), "--format", "json") to "File not found",
            listOf("wasm-to-wat", textFile.toString(), "--format", "raw") to "Not a .wasm file",
            listOf(
                "summarize-module",
                outputLimitedWasm.toString(),
                "--format",
                "json",
                "--max-output-bytes",
                "4",
            ) to "maxOutputBytes=4",
        )

        cases.forEach { (arguments, expectedMessage) ->
            val result = HexanaInstalledStdioClient.runCommand(StdioLauncher.Server, *arguments.toTypedArray())

            assertFalse(result.timedOut, "Command timed out. stdout=${result.stdout}, stderr=${result.stderr}")
            assertEquals(1, result.exitCode, "stdout=${result.stdout}, stderr=${result.stderr}")
            assertTrue(result.stdout.isBlank(), "Expected stdout to stay empty, got: ${result.stdout}")
            assertTrue(result.stderr.contains(expectedMessage), result.stderr)
            assertTrue(result.stderr.contains("Hexana MCP ${arguments.first()} failed"), result.stderr)
            assertNoStackTrace(result.stderr)
            assertFalse(result.stderr.contains("See server logs"), result.stderr)
        }
    }

    @Test
    fun installedExecutableToolCanEmitExactlyMaxOutputBytes() = runBlocking {
        val binary = temporaryFolder.newFile("exact-output-budget.bin").toPath()
        Files.write(binary, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val initial = HexanaInstalledStdioClient.runCommand(
            StdioLauncher.Server,
            "summarize-binary",
            binary.toString(),
            "--format",
            "json",
        )
        assertInstalledCommandSucceeded(initial)
        assertFalse(initial.stdout.endsWith("\n"), "Executable CLI output must not append a trailing newline")

        val renderedStdout = initial.stdout.trimEnd('\r', '\n')
        val maxOutputBytes = renderedStdout.toByteArray(StandardCharsets.UTF_8).size
        val exactBudget = HexanaInstalledStdioClient.runCommand(
            StdioLauncher.Server,
            "summarize-binary",
            binary.toString(),
            "--format",
            "json",
            "--max-output-bytes",
            maxOutputBytes.toString(),
        )

        assertInstalledCommandSucceeded(exactBudget)
        assertEquals(renderedStdout, exactBudget.stdout)
        assertEquals(maxOutputBytes, exactBudget.stdout.toByteArray(StandardCharsets.UTF_8).size)
        assertTrue(exactBudget.unexpectedStderr.isBlank(), exactBudget.stderr)
    }

    @Test
    fun installedServerSupportsListToolsAndToolCallsOverStdio() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        val fixtureDirectory = temporaryFolder.root.toPath()
        withInstalledStdioClient(allowedRoots = listOf(fixtureDirectory)) { server ->
            assertDefaultStdioSmoke(
                server = server,
                fixtureDirectory = fixtureDirectory,
                logger = logger,
                label = "installed"
            )
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

    private fun assertInstalledCommandSucceeded(result: InstalledCommandResult) {
        assertFalse(
            result.timedOut,
            "Command timed out. stdout=${result.stdout}, stderr=${result.stderr}, command=${result.command}"
        )
        assertEquals(0, result.exitCode, "stdout=${result.stdout}, stderr=${result.stderr}")
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

    private suspend fun assertInstalledToolSourceModes(
        command: String,
        path: Path,
        stdinBytes: ByteArray,
        base64Option: String,
        format: String,
        extraBase64Options: List<String> = emptyList(),
        assertStdout: (String, String) -> Unit,
    ) {
        val bytesBase64 = Base64.getEncoder().encodeToString(stdinBytes)
        val cases = listOf(
            InstalledToolSourceCase(
                label = "path",
                arguments = listOf(command, path.toString(), "--format", format),
            ),
            InstalledToolSourceCase(
                label = "-",
                arguments = listOf(command, "-", "--format", format),
                stdinBytes = stdinBytes,
            ),
            InstalledToolSourceCase(
                label = "--stdin",
                arguments = listOf(command, "--stdin", "--format", format),
                stdinBytes = stdinBytes,
            ),
            InstalledToolSourceCase(
                label = "auto-stdin",
                arguments = listOf(command, "--format", format),
                stdinBytes = stdinBytes,
            ),
            InstalledToolSourceCase(
                label = base64Option,
                arguments = listOf(command, base64Option, bytesBase64, "--format", format),
            ),
        ) + extraBase64Options.map { optionName ->
            InstalledToolSourceCase(
                label = optionName,
                arguments = listOf(command, optionName, bytesBase64, "--format", format),
            )
        }

        cases.forEach { case ->
            val result = HexanaInstalledStdioClient.runCommand(
                launcher = StdioLauncher.Server,
                arguments = case.arguments,
                stdinBytes = case.stdinBytes,
            )

            assertInstalledCommandSucceeded(result)
            assertStdout(result.stdout, case.label)
            assertTrue(result.unexpectedStderr.isBlank(), "${case.label}: ${result.stderr}")
        }
    }

    private fun assertSummarizeModuleJson(stdout: String, sourceLabel: String) {
        val json = Json.parseToJsonElement(stdout.trim()).jsonObject
        assertEquals("1", json.getValue("functions").jsonPrimitive.content, sourceLabel)
        assertEquals("1", json.getValue("exports").jsonPrimitive.content, sourceLabel)
        assertFalse("content" in json, sourceLabel)
    }

    private fun assertSummarizeBinaryWasmJson(stdout: String, sourceLabel: String) {
        val json = Json.parseToJsonElement(stdout.trim()).jsonObject
        val module = json.getValue("module").jsonObject
        val counts = json.getValue("counts").jsonObject
        assertEquals("wasm", module.getValue("format").jsonPrimitive.content, sourceLabel)
        assertEquals("1", counts.getValue("functions").jsonPrimitive.content, sourceLabel)
        assertEquals("1", counts.getValue("exports").jsonPrimitive.content, sourceLabel)
        assertTrue(json.getValue("warnings").jsonArray.isEmpty(), "$sourceLabel: $stdout")
    }

    private fun assertRawWat(stdout: String, sourceLabel: String) {
        assertTrue(stdout.contains("(module"), "$sourceLabel: $stdout")
        assertTrue(stdout.contains("\"main\""), "$sourceLabel: $stdout")
        assertFalse(stdout.trimStart().startsWith("{"), "$sourceLabel: $stdout")
    }

    private fun assertNoStackTrace(stderr: String) {
        assertFalse(Regex("""(?m)^\s+at\s""").containsMatchIn(stderr), stderr)
        assertFalse(stderr.contains("HexanaAnalysisException"), stderr)
    }

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
    }

    private fun missingPath(name: String): Path = temporaryFolder.root.toPath().resolve(name)

    private fun nativeFixture(relativePath: String): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (true) {
            val candidate = current.resolve("parsing/native-fixtures/test-data").resolve(relativePath)
            if (Files.exists(candidate)) return candidate
            current = current.parent ?: break
        }
        error("Cannot find parsing/native-fixtures/test-data/$relativePath from ${System.getProperty("user.dir")}")
    }

    private data class InstalledToolSourceCase(
        val label: String,
        val arguments: List<String>,
        val stdinBytes: ByteArray? = null,
    )
}
