package org.jetbrains.hexana.ui

import com.intellij.driver.client.Remote
import com.intellij.driver.client.impl.DriverCallException
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

@Tag("hexana")
class HexanaMcpPackagedSmokeTest {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Test
    fun packagedPluginExposesHexanaToolsOverGlobalMcp() {
        val context = HexanaUiDriverTestSupport
            .newContext(testName = "hexana-packaged-mcp-smoke")
            .let { HexanaUiDriverTestSupport.enableGlobalMcpServer(it) }

        context.runIdeWithDriver(runTimeout = 20.minutes) { withScreenRecording() }
            .useDriverAndCloseIde {
                waitForIndicators(5.minutes)
                val serverUrl = service(McpServerServiceRef::class).resolveServerUrl()

                HexanaUiDriverTestSupport.withGlobalMcpClient(context.resolvedProjectHome, serverUrl) { client ->
                    val toolNames = client.listTools(ListToolsRequest()).tools.map { it.name }.toSet()
                    assertTrue(
                        toolNames.contains("query_artifacts"),
                        "Expected query_artifacts in packaged MCP tools, got $toolNames"
                    )

                    val result = client.callTool(
                        CallToolRequest(
                            CallToolRequestParams(
                                name = "query_artifacts",
                                arguments = buildJsonObject {
                                    put("path", JsonPrimitive(HexanaUiDriverTestSupport.wasmFileName))
                                    put("operation", JsonPrimitive("overview"))
                                }
                            )
                        )
                    )

                    assertFalse(
                        result.isError == true,
                        "query_artifacts returned MCP error in packaged smoke: $result"
                    )
                    val textJson = json.parseToJsonElement(result.requireSingleText()).jsonObject
                    val structuredContent = result.structuredContent
                        ?: error("Expected structuredContent in packaged smoke result: $result")
                    val module = structuredContent
                        .getValue("modules")
                        .jsonArray
                        .single()
                        .jsonObject

                    assertEquals(textJson, structuredContent)
                    assertTrue(
                        module.fieldAsInt("moduleSize") > 0,
                        "Expected moduleSize > 0, got $structuredContent"
                    )
                    assertTrue(
                        module.fieldAsInt("functions") > 0,
                        "Expected functions > 0, got $structuredContent"
                    )
                }
            }
    }

    private fun io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.requireSingleText(): String {
        val textContent = content.singleOrNull() as? TextContent
            ?: error("Expected a single text payload but got $content")
        return textContent.text
    }

    private fun JsonObject.fieldAsInt(name: String): Int {
        return getValue(name).jsonPrimitive.int
    }

    private fun McpServerServiceRef.resolveServerUrl(): String {
        return try {
            serverStreamUrl
        } catch (streamFailure: DriverCallException) {
            try {
                serverSseUrl
            } catch (sseFailure: DriverCallException) {
                streamFailure.addSuppressed(sseFailure)
                throw streamFailure
            }
        }
    }

    @Remote("com.intellij.mcpserver.impl.McpServerService", plugin = "com.intellij.mcpServer")
    interface McpServerServiceRef {
        val serverStreamUrl: String
        val serverSseUrl: String
    }
}
