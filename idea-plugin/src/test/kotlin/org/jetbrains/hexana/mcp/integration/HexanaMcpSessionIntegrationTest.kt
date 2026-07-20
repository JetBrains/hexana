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
import org.jetbrains.hexana.mcp.HexanaToolset
import org.jetbrains.hexana.mcp.direct.HexanaMcpToolsetDirectTestBase

class HexanaMcpSessionIntegrationTest : HexanaMcpToolsetDirectTestBase() {
    fun testAuthorizedSessionListsExpectedHexanaTools() {
        val toolNames = withMcpClient { client ->
            client.listTools(ListToolsRequest()).tools.map { it.name }.toSet()
        }

        val expectedHexanaTools = HexanaToolset().getTools().mapTo(linkedSetOf()) { it.descriptor.name }
        val sessionHexanaTools = toolNames.intersect(knownHexanaToolNames)

        assertEquals(expectedHexanaTools, sessionHexanaTools)
        assertEquals(29, sessionHexanaTools.size)
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

    private companion object {
        val knownHexanaToolNames = setOf(
            "summarize_binary", "list_binary_functions", "search_binary_functions", "query_binary",
            "run_binary_script", "wasm_to_wat", "summarize_module", "list_enabled_features",
            "get_entrypoint_policy", "list_required_proposals", "diff_required_proposals",
            "check_runtime_compatibility", "check_component_compatibility", "resolve_component_dependencies",
            "list_component_surface", "plan_abi_binding_regeneration", "check_memory_contract",
            "audit_global_state_policy", "audit_local_stack_budget", "list_custom_sections", "list_imports",
            "list_exports", "search_exports", "list_exported_functions", "list_functions",
            "list_code_section_offsets", "functions_for_indices", "resolve_function_signature",
            "disassemble_function", "get_function_call_graph", "search_functions_by_name", "resolve_wasm_frames",
            "resolve_function_symbols", "resolve_source_locations", "get_locals_for_functions",
            "get_instructions_for_functions", "list_memory", "get_memory_for_indices", "list_globals",
            "get_globals_for_indices", "list_types", "list_value_types", "list_reference_types",
            "get_types_for_indices", "list_element_segments", "list_data", "list_data_segments",
            "audit_data_payload_policy", "query_artifacts",
        )
    }
}
