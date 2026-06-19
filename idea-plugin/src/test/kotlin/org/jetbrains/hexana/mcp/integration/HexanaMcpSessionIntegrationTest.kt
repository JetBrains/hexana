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

        assertTrue("query_artifacts missing from authorized session tools: $toolNames", "query_artifacts" in toolNames)
        val requiredGranularTools = setOf(
            "summarize_module",
            "list_imports",
            "list_exports",
            "list_functions",
            "list_memory",
            "list_globals",
            "list_types",
            "list_element_segments",
            "list_data_segments",
            "audit_data_payload_policy",
        )
        assertTrue(
            "Authorized session tools missing expected granular tools. tools=$toolNames",
            toolNames.containsAll(requiredGranularTools),
        )
    }

    fun testQueryArtifactsWorksOverAuthorizedSession() {
        val fixture = createWasmFixture(
            "fixtures/integration/session/summary.wasm",
            """
                (module
                  (memory 1)
                  (func (export "main") (result i32)
                    i32.const 42))
            """.trimIndent()
        )

        val result = callToolViaSession("query_artifacts", jsonPath(fixture.projectRelativePath))

        assertFalse("query_artifacts returned MCP error over session: $result", result.isError == true)
        val textJson = json.parseToJsonElement(result.requireSingleText()) as JsonObject
        val structuredContent = result.requireStructuredObject()

        assertEquals(textJson, structuredContent)
        val module = structuredContent.array("modules").obj(0)
        assertEquals(1, module.int("functions"))
        assertEquals(1, module.int("exports"))
        assertTrue(module.int("moduleSize") > 0)
    }

    fun testQueryArtifactsFindsExportsOverAuthorizedSession() {
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
            "query_artifacts",
            buildJsonObject {
                put("path", JsonPrimitive(fixture.projectRelativePath))
                put("operation", JsonPrimitive("find"))
                put("entityTypes", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive("export"))
                })
                put("query", kotlinx.serialization.json.buildJsonArray {
                    add(JsonPrimitive("a"))
                })
            }
        )

        assertFalse("query_artifacts returned MCP error over session: $result", result.isError == true)
        val textJson = json.parseToJsonElement(result.requireSingleText()) as JsonObject
        val structuredContent = result.requireStructuredObject()

        assertEquals(textJson, structuredContent)
        val record = structuredContent.array("records").obj(0)
        assertEquals("export", record.text("entityType"))
        assertEquals("a", record.text("name"))
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
}
