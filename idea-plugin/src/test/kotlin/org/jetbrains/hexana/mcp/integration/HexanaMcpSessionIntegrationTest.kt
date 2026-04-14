package org.jetbrains.hexana.mcp.integration

import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.util.network.McpServerConnectionAddressProvider
import com.intellij.mcpserver.stdio.IJ_MCP_SERVER_PROJECT_PATH
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.hexana.mcp.direct.HexanaMcpToolsetDirectTestBase

class HexanaMcpSessionIntegrationTest : HexanaMcpToolsetDirectTestBase() {
    fun testAuthorizedSessionListsExpectedHexanaTools() {
        val toolNames = withMcpClient { client ->
            client.listTools(ListToolsRequest()).tools.map { it.name }.toSet()
        }

        assertTrue(
            "Missing Hexana MCP tools from authorized session. Expected at least $EXPECTED_HEXANA_TOOLS but got $toolNames",
            toolNames.containsAll(EXPECTED_HEXANA_TOOLS)
        )
    }

    fun testSummarizeModuleWorksOverAuthorizedSession() {
        val fixture = createWasmFixture(
            "fixtures/integration/session/summary.wasm",
            """
                (module
                  (memory 1)
                  (func (export "main") (result i32)
                    i32.const 42))
            """.trimIndent()
        )

        val result = callToolViaSession("summarize_module", jsonPath(fixture.projectRelativePath))

        assertFalse("summarize_module returned MCP error over session: $result", result.isError == true)
        val textJson = json.parseToJsonElement(result.requireSingleText()) as JsonObject
        val structuredContent = result.requireStructuredObject()

        assertEquals(textJson, structuredContent)
        assertEquals(1, structuredContent.int("functions"))
        assertEquals(1, structuredContent.int("exports"))
        assertTrue(structuredContent.int("moduleSize") > 0)
    }

    fun testListExportedFunctionsWorksOverAuthorizedSession() {
        val fixture = createWasmFixture(
            "fixtures/integration/session/exports.wasm",
            """
                (module
                  (func (export "a") (result i32)
                    i32.const 1)
                  (func (export "b") (result i32)
                    i32.const 2))
            """.trimIndent()
        )

        val result = callToolViaSession(
            "list_exported_functions",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(0))
                    put("to", JsonPrimitive(0))
                })
            }
        )

        assertFalse("list_exported_functions returned MCP error over session: $result", result.isError == true)
        val textJson = json.parseToJsonElement(result.requireSingleText()) as JsonObject
        val structuredContent = result.requireStructuredObject()

        assertEquals(textJson, structuredContent)
        assertEquals(0, structuredContent.int("from"))
        assertEquals(0, structuredContent.int("to"))
        assertEquals(2, structuredContent.int("total"))
        assertEquals(1, structuredContent.array("returned").size)
        assertEquals("a", structuredContent.array("returned").obj(0).text("name"))
    }

    fun testValidationErrorReturnsToolErrorOverAuthorizedSession() {
        val fixture = createWasmFixture(
            "fixtures/integration/session/invalid_range.wasm",
            """
                (module
                  (memory 1)
                  (data (i32.const 0) "Hi"))
            """.trimIndent()
        )

        val result = callToolViaSession(
            "list_data",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("entriesRange", buildJsonObject {
                    put("from", JsonPrimitive(5))
                    put("to", JsonPrimitive(1))
                })
            }
        )

        assertEquals(true, result.isError)
        assertEquals("Invalid segmentRange `to` must be >= `from`", result.requireSingleText())
    }

    private fun callToolViaSession(toolName: String, input: JsonObject): CallToolResult {
        return withMcpClient { client ->
            client.callTool(CallToolRequest(CallToolRequestParams(name = toolName, arguments = input)))
        }
    }

    private fun <T> withMcpClient(action: suspend (Client) -> T): T = runBlocking {
        var result: Result<T>? = null
        val projectBasePath = project.basePath ?: error("Project base path is not available")

        McpServerService.getInstance().authorizedSession(
            McpServerService.McpSessionOptions(
                commandExecutionMode = McpServerService.AskCommandExecutionMode.DONT_ASK,
            )
        ) { port, authTokenName, authTokenValue ->
            val transportUrl =
                McpServerConnectionAddressProvider.getInstanceOrNull()?.httpUrl("/stream", portOverride = port)
                    ?: "http://localhost:$port/stream"
            val httpClient = HttpClient(CIO) {
                install(SSE)
            }
            val transport = StreamableHttpClientTransport(httpClient, transportUrl) {
                header(IJ_MCP_SERVER_PROJECT_PATH, projectBasePath)
                header(authTokenName, authTokenValue)
            }
            val client = Client(Implementation(name = "hexana-test-client", version = "1.0"))

            try {
                client.connect(transport)
                result = runCatching { action(client) }
            } finally {
                transport.close()
                httpClient.close()
            }
        }

        result?.getOrThrow() ?: error("Authorized MCP session finished without producing a client result")
    }

    private fun CallToolResult.requireSingleText(): String {
        val textContent: TextContent = content.singleOrNull() as? TextContent
            ?: error("Expected a single text payload but got $content")
        return textContent.text
    }

    private fun CallToolResult.requireStructuredObject(): JsonObject {
        return structuredContent ?: error("Expected structuredContent to be present but it was null. Result: $this")
    }

    private companion object {
        val EXPECTED_HEXANA_TOOLS = setOf(
            "list_imports",
            "list_functions",
            "list_data",
            "list_exports",
            "list_exported_functions",
            "functions_for_indices",
            "list_globals",
            "list_memory",
            "get_globals_for_indices",
            "list_data_segments",
            "get_memory_for_indices",
            "list_types",
            "get_types_for_indices",
            "get_locals_for_functions",
            "get_instructions_for_functions",
            "list_element_segments",
            "summarize_module"
        )
    }
}
