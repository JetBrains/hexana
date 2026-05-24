package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaClaudePluginWrapperIntegrationTest {
    private val logger = LoggerFactory.getLogger(HexanaClaudePluginWrapperIntegrationTest::class.java)

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun assembledClaudePluginVersionCommandExitsPromptly() = runBlocking {
        assertVersionCommandExitsPromptly(StdioLauncher.ClaudePluginWrapper)
    }

    @Test
    fun assembledCodexPluginVersionCommandExitsPromptly() = runBlocking {
        assertVersionCommandExitsPromptly(StdioLauncher.CodexPluginWrapper)
    }

    @Test
    fun assembledCodexPluginMcpConfigPinsPluginRootWorkingDirectory() {
        val installDir = Path.of(
            System.getProperty(StdioLauncher.CodexPluginWrapper.installDirSystemProperty)
                ?: error("Missing `${StdioLauncher.CodexPluginWrapper.installDirSystemProperty}` system property")
        )
        val config = Json.parseToJsonElement(Files.readString(installDir.resolve(".mcp.json"))).jsonObject
        val hexanaServer = config.getValue("mcpServers").jsonObject.getValue("hexana").jsonObject

        assertEquals("./bin/hexana-mcp", hexanaServer.getValue("command").jsonPrimitive.content)
        assertEquals(".", hexanaServer.getValue("cwd").jsonPrimitive.content)
    }

    @Test
    fun assembledClaudePluginExposesAllToolsOverStdio() = runBlocking {
        assertWrapperExposesAllToolsOverStdio("Claude", StdioLauncher.ClaudePluginWrapper)
    }

    @Test
    fun assembledCodexPluginExposesAllToolsOverStdio() = runBlocking {
        assertWrapperExposesAllToolsOverStdio("Codex", StdioLauncher.CodexPluginWrapper)
    }

    private suspend fun assertVersionCommandExitsPromptly(launcher: StdioLauncher) {
        val result = HexanaInstalledStdioClient.runCommand(launcher, "version")
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

    private suspend fun assertWrapperExposesAllToolsOverStdio(clientLabel: String, launcher: StdioLauncher) {
        KotlinLoggingConfiguration.logStartupMessage = false
        withInstalledStdioClient(launcher = launcher) { server ->
            logger.info("Connected {} plugin wrapper stdio client", clientLabel)

            val tools = server.client.listTools(ListToolsRequest())
            val toolNames = tools.tools.map { it.name }.sorted()
            logger.info("{} wrapper tools: {}", clientLabel, toolNames)
            assertEquals(EXPECTED_HEXANA_TOOL_NAMES, toolNames)

            val wasm = temporaryFolder.newFile("claude-wrapper-summary.wasm").toPath().also {
                Files.write(it, wat2wasm(
                    """
                    (module
                      (memory 1)
                      (func (export "main")))
                    """.trimIndent()
                ))
            }

            val summary = server.client.callTool(
                name = "query_artifacts",
                arguments = mapOf("path" to wasm.toString(), "operation" to "overview"),
                meta = emptyMap()
            )
            assertFalse(summary.isError == true)
            val summaryText = summary.content.single() as TextContent
            logger.info("{} wrapper query_artifacts payload: {}", clientLabel, summaryText.text)
            assertEquals(
                1,
                summary.structuredContent
                    ?.get("modules")
                    ?.jsonArray
                    ?.first()
                    ?.jsonObject
                    ?.get("functions")
                    ?.jsonPrimitive
                    ?.int
            )

            assertTrue(
                server.stderr.contains("Starting stdio MCP transport"),
                "$clientLabel plugin wrapper should proxy stdio server logs on stderr. stderr was: ${server.stderr}"
            )
        }
    }
}
