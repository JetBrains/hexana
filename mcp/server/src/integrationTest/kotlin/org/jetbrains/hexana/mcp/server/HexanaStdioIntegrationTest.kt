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
            assertEquals(listOf("query_artifacts"), tools.tools.map { it.name }.sorted())

            val wasm = createWasmFile(
                "stdio-summary.wasm",
                """
                (module
                  (memory 1)
                  (func (export "main")))
                """.trimIndent()
            )
            logger.info("Created stdio wasm fixture at {}", wasm)

            logger.info("Requesting query_artifacts overview over stdio")
            val overviewResult = server.client.callTool(
                name = "query_artifacts",
                arguments = mapOf("path" to wasm.toString(), "operation" to "overview"),
                meta = emptyMap<String, Any?>()
            )
            logger.info(
                "Received query_artifacts overview result over stdio isError={}, contentBlocks={}",
                overviewResult.isError,
                overviewResult.content.size
            )
            assertFalse(overviewResult.isError == true)
            val overviewText = overviewResult.content.single() as TextContent
            logger.info("Query artifacts overview text payload over stdio: {}", overviewText.text)
            assertJsonTextMatchesStructured(overviewResult)
            assertEquals(
                1,
                overviewResult.structuredContent
                    ?.get("modules")
                    ?.jsonArray
                    ?.first()
                    ?.jsonObject
                    ?.get("functions")
                    ?.jsonPrimitive
                    ?.int
            )

            logger.info("Requesting query_artifacts memory find over stdio")
            val memoryResult = server.client.callTool(
                name = "query_artifacts",
                arguments = mapOf(
                    "path" to wasm.toString(),
                    "operation" to "find",
                    "entityTypes" to listOf("memory"),
                    "limit" to 5,
                ),
                meta = emptyMap<String, Any?>()
            )
            logger.info(
                "Received query_artifacts memory result over stdio isError={}, contentBlocks={}",
                memoryResult.isError,
                memoryResult.content.size
            )
            assertFalse(memoryResult.isError == true)
            val memoryText = memoryResult.content.single() as TextContent
            logger.info("Query artifacts memory text payload over stdio: {}", memoryText.text)
            assertJsonTextMatchesStructured(memoryResult)
            assertEquals(
                0,
                memoryResult.structuredContent
                    ?.get("records")
                    ?.jsonArray
                    ?.first()
                    ?.jsonObject
                    ?.get("index")
                    ?.jsonPrimitive
                    ?.int
            )
            assertTrue(
                server.stderr.contains("Starting stdio MCP transport"),
                "STDIO server logs should stay on stderr. stderr was: ${server.stderr}"
            )
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
