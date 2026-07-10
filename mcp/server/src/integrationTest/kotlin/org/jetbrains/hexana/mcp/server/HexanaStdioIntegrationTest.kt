package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
        assertTrue(result.unexpectedStderr.isBlank(), result.stderr)
    }

    @Test
    fun installedServerSummarizeModuleCommandReturnsJsonOnStdout() = runBlocking {
        val wasm = createWasmFile("installed-summary.wasm", "(module (memory 1) (func (export \"main\")))")

        val result = HexanaInstalledStdioClient.runCommand(
            StdioLauncher.Server,
            "summarize-module",
            wasm.toString(),
            "--format",
            "json",
        )

        assertInstalledCommandSucceeded(result)
        val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
        assertEquals("1", json.getValue("functions").jsonPrimitive.content)
        assertEquals("1", json.getValue("exports").jsonPrimitive.content)
        assertFalse("content" in json)
        assertTrue(result.unexpectedStderr.isBlank(), result.stderr)
    }

    @Test
    fun installedServerWasmToWatCommandReturnsRawWatOnStdout() = runBlocking {
        val wasm = createWasmFile("installed-wat.wasm", "(module (func (export \"main\")))")

        val result = HexanaInstalledStdioClient.runCommand(
            StdioLauncher.Server,
            "wasm-to-wat",
            wasm.toString(),
            "--format",
            "raw",
        )

        assertInstalledCommandSucceeded(result)
        assertTrue(result.stdout.contains("(module"), result.stdout)
        assertFalse(result.stdout.trimStart().startsWith("{"), result.stdout)
        assertTrue(result.unexpectedStderr.isBlank(), result.stderr)
    }

    @Test
    fun installedServerTodoCommandFailsOnStderr() = runBlocking {
        val result = HexanaInstalledStdioClient.runCommand(StdioLauncher.Server, "list-enabled-features")

        assertFalse(result.timedOut, "Command timed out. stdout=${result.stdout}, stderr=${result.stderr}")
        assertEquals(1, result.exitCode, "stdout=${result.stdout}, stderr=${result.stderr}")
        assertTrue(result.stdout.isBlank(), result.stdout)
        assertTrue(result.stderr.contains("list-enabled-features"), result.stderr)
        assertTrue(result.stderr.contains("not implemented"), result.stderr)
    }

    @Test
    fun installedServerSummarizeBinaryCommandReturnsJsonOnStdout() = runBlocking {
        val wasm = createWasmFile("installed-binary-summary.wasm", "(module (memory 1) (func (export \"main\")))")

        val result = HexanaInstalledStdioClient.runCommand(
            StdioLauncher.Server,
            "summarize-binary",
            wasm.toString(),
            "--format",
            "json",
        )

        assertInstalledCommandSucceeded(result)
        val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
        val module = json.getValue("module").jsonObject
        val counts = json.getValue("counts").jsonObject
        assertEquals("wasm", module.getValue("format").jsonPrimitive.content)
        assertEquals("1", counts.getValue("functions").jsonPrimitive.content)
        assertEquals("1", counts.getValue("exports").jsonPrimitive.content)
        assertTrue(json.getValue("warnings").jsonArray.isEmpty(), result.stdout)
        assertTrue(result.unexpectedStderr.isBlank(), result.stderr)
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
    fun installedExecutableToolInputFailuresStayConciseOnStderr() = runBlocking {
        val outsideWasm = createWasmFile("outside-root.wasm", "(module)")
        val allowedRoot = temporaryFolder.newFolder("allowed-root").toPath()
        val textFile = temporaryFolder.newFile("not-wasm.txt").toPath()
        Files.writeString(textFile, "not a wasm module")
        val directoryInput = temporaryFolder.newFolder("directory-input").toPath()
        val outputLimitedWasm = createWasmFile("output-limit.wasm", "(module (memory 1) (func (export \"main\")))")

        val cases = listOf(
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
            assertProductionStdioSmoke(
                server = server,
                fixtureDirectory = fixtureDirectory,
                logger = logger,
                label = "installed"
            )
        }
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
}
