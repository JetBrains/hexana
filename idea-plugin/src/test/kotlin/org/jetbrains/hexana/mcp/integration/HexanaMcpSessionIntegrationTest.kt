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

        val requiredGranularTools = setOf(
            "summarize_module",
            "list_imports",
            "list_exports",
            "list_functions",
            "list_memory",
            "list_globals",
            "list_element_segments",
            "list_data_segments",
            "audit_data_payload_policy",
        )
        assertTrue(
            "Authorized session tools missing expected granular tools. tools=$toolNames",
            toolNames.containsAll(requiredGranularTools),
        )
        assertFalse("query_artifacts should not be exposed by IDEA: $toolNames", "query_artifacts" in toolNames)
        assertFalse("query_binary should not be exposed by IDEA: $toolNames", "query_binary" in toolNames)
        assertFalse("list_binary_functions should not be exposed by IDEA: $toolNames", "list_binary_functions" in toolNames)
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
        val module = structuredContent
        assertEquals(1, module.int("functions"))
        assertEquals(1, module.int("exports"))
        assertTrue(module.int("moduleSize") > 0)
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
