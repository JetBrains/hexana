package org.jetbrains.hexana.mcp.server

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

class HexanaHttpIntegrationTest {
    private val logger = LoggerFactory.getLogger(HexanaHttpIntegrationTest::class.java)

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installedServerSupportsListToolsAndToolCallsOverHttp() = runBlocking {
        withInstalledServer { server ->
            logger.info("Server reported ready at {}", server.baseUrl)
            KotlinLoggingConfiguration.logStartupMessage = false
            HttpClient(CIO).use { httpClient ->
                logger.info("Requesting {}", "${server.origin}/healthz")
                val healthResponse = httpClient.get("${server.origin}/healthz")
                val healthBody = healthResponse.bodyAsText()
                logger.info("Received /healthz status={} body={}", healthResponse.status, healthBody)
                assertEquals(HttpStatusCode.OK, healthResponse.status)
                assertEquals("OK", healthBody)

                logger.info("Connecting MCP client to {}", server.baseUrl)
                val client = httpClient.mcpStreamableHttp(server.baseUrl)
                try {
                    logger.info("Requesting tools/list")
                    val tools = client.listTools(ListToolsRequest())
                    logger.info("Received tools: {}", tools.tools.map { it.name }.sorted())
                    assertEquals(
                        listOf(
                            "disassemble_function",
                            "functions_for_indices",
                            "get_globals_for_indices",
                            "get_instructions_for_functions",
                            "get_locals_for_functions",
                            "get_memory_for_indices",
                            "get_types_for_indices",
                            "list_code_section_offsets",
                            "list_custom_sections",
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
                            "list_value_types",
                            "resolve_function_signature",
                            "search_exports",
                            "search_functions_by_name",
                            "summarize_module"
                        ),
                        tools.tools.map { it.name }.sorted()
                    )

                    val wasm = createWasmFile(
                        "summary.wasm",
                        """
                        (module
                          (memory 1)
                          (func (export "main")))
                        """.trimIndent()
                    )
                    logger.info("Created wasm fixture at {}", wasm)
                    val wasmBytes = Files.readAllBytes(wasm).map { it.toInt() and 0xFF }

                    logger.info("Requesting tools/call summarize_module")
                    val result = client.callTool(
                        name = "summarize_module",
                        arguments = mapOf("path" to wasm.toString()),
                        meta = emptyMap<String, Any?>()
                    )
                    logger.info(
                        "Received tools/call result isError={}, contentBlocks={}",
                        result.isError,
                        result.content.size
                    )

                    assertFalse(result.isError == true)
                    assertTrue(server.stdout.isBlank(), "HTTP mode must keep stdout empty, but was: ${server.stdout}")
                    val text = result.content.single() as TextContent
                    logger.info("Result text payload: {}", text.text)
                    assertJsonTextMatchesStructured(result)
                    assertEquals(1, result.structuredContent?.get("functions")?.jsonPrimitive?.int)

                    logger.info("Requesting tools/call summarize_module with inline bytes")
                    val inlineResult = client.callTool(
                        name = "summarize_module",
                        arguments = mapOf("bytes" to wasmBytes),
                        meta = emptyMap<String, Any?>()
                    )
                    logger.info(
                        "Received inline summarize_module result isError={}, contentBlocks={}",
                        inlineResult.isError,
                        inlineResult.content.size
                    )
                    assertFalse(inlineResult.isError == true)
                    val inlineText = inlineResult.content.single() as TextContent
                    logger.info("Inline result text payload: {}", inlineText.text)
                    assertJsonTextMatchesStructured(inlineResult)
                    assertEquals(1, inlineResult.structuredContent?.get("functions")?.jsonPrimitive?.int)

                    logger.info("Requesting tools/call list_memory")
                    val memoryResult = client.callTool(
                        name = "list_memory",
                        arguments = mapOf("path" to wasm.toString()),
                        meta = emptyMap<String, Any?>()
                    )
                    logger.info(
                        "Received list_memory result isError={}, contentBlocks={}",
                        memoryResult.isError,
                        memoryResult.content.size
                    )
                    assertFalse(memoryResult.isError == true)
                    val memoryText = memoryResult.content.single() as TextContent
                    logger.info("List memory text payload: {}", memoryText.text)
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

                    logger.info("Requesting tools/call functions_for_indices")
                    val functionsResult = client.callTool(
                        name = "functions_for_indices",
                        arguments = mapOf("path" to wasm.toString(), "indices" to listOf(0)),
                        meta = emptyMap<String, Any?>()
                    )
                    logger.info(
                        "Received functions_for_indices result isError={}, contentBlocks={}",
                        functionsResult.isError,
                        functionsResult.content.size
                    )
                    assertFalse(functionsResult.isError == true)
                    val functionsText = functionsResult.content.single() as TextContent
                    logger.info("Functions for indices text payload: {}", functionsText.text)
                    assertJsonTextMatchesStructured(functionsResult)
                    assertEquals(
                        0,
                        functionsResult.structuredContent
                            ?.get("functions")
                            ?.jsonArray
                            ?.first()
                            ?.jsonObject
                            ?.get("funcidx")
                            ?.jsonPrimitive
                            ?.int
                    )
                } finally {
                    logger.info("Closing MCP client")
                    client.close()
                }
            }
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
