package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.coroutines.runBlocking
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
    fun installedServerSupportsListToolsAndToolCallsOverStdio() = runBlocking {
        KotlinLoggingConfiguration.logStartupMessage = false
        withInstalledStdioClient { server ->
            logger.info("Connected stdio integration client")

            logger.info("Requesting tools/list over stdio")
            val tools = server.client.listTools(ListToolsRequest())
            logger.info("Received tools over stdio: {}", tools.tools.map { it.name }.sorted())
            assertEquals(EXPECTED_PRODUCTION_HEXANA_TOOL_NAMES, tools.tools.map { it.name }.sorted())

            val wasm = createWasmFile(
                "stdio-summary.wasm",
                """
                (module
                  (memory 1)
                  (func (export "main")))
                """.trimIndent()
            )
            logger.info("Created stdio wasm fixture at {}", wasm)

            logger.info("Requesting summarize_module over stdio")
            val overviewResult = server.client.callTool(
                name = "summarize_module",
                arguments = mapOf("path" to wasm.toString()),
                meta = emptyMap<String, Any?>()
            )
            logger.info(
                "Received summarize_module result over stdio isError={}, contentBlocks={}",
                overviewResult.isError,
                overviewResult.content.size
            )
            assertFalse(overviewResult.isError == true)
            val overviewText = overviewResult.content.single() as TextContent
            logger.info("Summarize module text payload over stdio: {}", overviewText.text)
            assertJsonTextMatchesStructured(overviewResult)
            assertEquals(
                1,
                overviewResult.structuredContent
                    ?.get("functions")
                    ?.jsonPrimitive
                    ?.int
            )

            logger.info("Requesting list_memory over stdio")
            val memoryResult = server.client.callTool(
                name = "list_memory",
                arguments = mapOf("path" to wasm.toString()),
                meta = emptyMap<String, Any?>()
            )
            logger.info(
                "Received list_memory result over stdio isError={}, contentBlocks={}",
                memoryResult.isError,
                memoryResult.content.size
            )
            assertFalse(memoryResult.isError == true)
            val memoryText = memoryResult.content.single() as TextContent
            logger.info("List memory text payload over stdio: {}", memoryText.text)
            assertJsonTextMatchesStructured(memoryResult)
            assertEquals(
                0,
                memoryResult.structuredContent
                    ?.get("memories")
                    ?.jsonArray
                    ?.first()
                    ?.jsonObject
                    ?.get("memoryIdx")
                    ?.jsonPrimitive
                    ?.int
            )
            assertTrue(
                server.stderr.contains("Starting stdio MCP transport"),
                "STDIO server logs should stay on stderr. stderr was: ${server.stderr}"
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

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
    }

    private fun assertJsonTextMatchesStructured(result: CallToolResult) {
        val textPayload = (result.content.single() as TextContent).text
        assertEquals(result.structuredContent, Json.parseToJsonElement(textPayload).jsonObject)
    }
}
