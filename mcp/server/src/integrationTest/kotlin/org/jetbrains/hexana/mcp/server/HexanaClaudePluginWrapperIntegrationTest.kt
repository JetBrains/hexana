package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.nio.file.Files
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
            assertEquals(
                listOf(
                    "functions_for_indices",
                    "get_globals_for_indices",
                    "get_instructions_for_functions",
                    "get_locals_for_functions",
                    "get_memory_for_indices",
                    "get_types_for_indices",
                    "list_data",
                    "list_data_segments",
                    "list_element_segments",
                    "list_exported_functions",
                    "list_exports",
                    "list_functions",
                    "list_globals",
                    "list_imports",
                    "list_memory",
                    "list_types",
                    "summarize_module"
                ),
                toolNames
            )

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
                name = "summarize_module",
                arguments = mapOf("path" to wasm.toString()),
                meta = emptyMap()
            )
            assertFalse(summary.isError == true)
            val summaryText = summary.content.single() as TextContent
            logger.info("{} wrapper summarize_module payload: {}", clientLabel, summaryText.text)
            assertEquals(1, summary.structuredContent?.get("functions")?.jsonPrimitive?.int)

            assertTrue(
                server.stderr.contains("Starting stdio MCP transport"),
                "$clientLabel plugin wrapper should proxy stdio server logs on stderr. stderr was: ${server.stderr}"
            )
        }
    }
}
