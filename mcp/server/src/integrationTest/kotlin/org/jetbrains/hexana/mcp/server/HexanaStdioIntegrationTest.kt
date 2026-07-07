package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.nio.file.Files
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
        val result = HexanaInstalledStdioClient.runCommand(StdioLauncher.Server, "summarize-binary", "app.wasm")

        assertFalse(result.timedOut, "Command timed out. stdout=${result.stdout}, stderr=${result.stderr}")
        assertEquals(1, result.exitCode, "stdout=${result.stdout}, stderr=${result.stderr}")
        assertTrue(result.stdout.isBlank(), result.stdout)
        assertTrue(result.stderr.contains("summarize-binary"), result.stderr)
        assertTrue(result.stderr.contains("not implemented"), result.stderr)
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

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
    }
}
