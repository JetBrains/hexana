package org.jetbrains.hexana.ui.framework

import com.intellij.driver.client.Remote
import com.intellij.driver.client.impl.DriverCallException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val MCP_SERVER_PLUGIN_ID = "com.intellij.mcpServer"
private const val PROJECT_PATH_HEADER = "IJ_MCP_SERVER_PROJECT_PATH"
private val mcpJson = Json { ignoreUnknownKeys = true }

internal fun <T> HexanaIde.withGlobalMcpClient(action: suspend (HexanaMcpClient) -> T): T =
    withGlobalMcpClient(context.resolvedProjectHome, mcpServerUrl(), action)

private fun HexanaIde.mcpServerUrl(): String {
    val server = driver.service(McpServerServiceRef::class)
    return try {
        server.serverStreamUrl
    } catch (streamFailure: DriverCallException) {
        try {
            server.serverSseUrl
        } catch (sseFailure: DriverCallException) {
            streamFailure.addSuppressed(sseFailure)
            throw streamFailure
        }
    }
}

internal class HexanaMcpClient(private val client: Client) {
    suspend fun toolNames(): Set<String> =
        client.listTools(ListToolsRequest()).tools.map { it.name }.toSet()

    suspend fun callStructuredTool(name: String, arguments: JsonObject): JsonObject {
        val result = callTool(name, arguments)
        check(result.isError != true) {
            "$name returned MCP error in packaged smoke: $result"
        }

        val structuredContent = result.structuredContent
            ?: error("Expected structuredContent for $name in packaged smoke result: $result")
        val textJson = mcpJson.parseToJsonElement(result.requireSingleText())
        check(textJson == structuredContent) {
            "Expected text JSON to match structuredContent for $name, got text=$textJson structured=$structuredContent"
        }
        return structuredContent
    }

    suspend fun callToolExpectingError(name: String, arguments: JsonObject): CallToolResult {
        val result = callTool(name, arguments)
        check(result.isError == true) {
            "Expected $name to return MCP error in packaged smoke, got $result"
        }
        return result
    }

    private suspend fun callTool(name: String, arguments: JsonObject): CallToolResult =
        client.callTool(
            CallToolRequest(
                CallToolRequestParams(
                    name = name,
                    arguments = arguments,
                )
            )
        )

    private fun CallToolResult.requireSingleText(): String {
        val textContent = content.singleOrNull() as? TextContent
            ?: error("Expected a single text payload but got $content")
        return textContent.text
    }
}

private fun <T> withGlobalMcpClient(projectDir: Path, serverUrl: String, action: suspend (HexanaMcpClient) -> T): T =
    runBlocking {
        val deadline = TimeSource.Monotonic.markNow() + 2.minutes
        var lastFailure: Throwable? = null

        while (deadline.hasNotPassedNow()) {
            val httpClient = HttpClient(CIO) {
                install(SSE)
            }
            val transport = createTransport(httpClient, serverUrl, projectDir)
            val client = Client(Implementation(name = "hexana-ui-driver-mcp-client", version = "1.0"))

            try {
                client.connect(transport)
                client.listTools(ListToolsRequest())
            } catch (t: Throwable) {
                lastFailure = t
                transport.close()
                httpClient.close()
                delay(1.seconds)
                continue
            }

            try {
                return@runBlocking action(HexanaMcpClient(client))
            } finally {
                transport.close()
                httpClient.close()
            }
        }

        error("Failed to connect to IDE MCP server at $serverUrl for project $projectDir: ${lastFailure?.message}")
    }

private fun createTransport(httpClient: HttpClient, serverUrl: String, projectDir: Path): Transport {
    val requestBuilder: HttpRequestBuilder.() -> Unit = {
        header(PROJECT_PATH_HEADER, projectDir.toString())
    }
    return if (serverUrl.substringBefore('?').endsWith("/sse")) {
        SseClientTransport(httpClient, serverUrl, requestBuilder = requestBuilder)
    } else {
        StreamableHttpClientTransport(httpClient, serverUrl, requestBuilder = requestBuilder)
    }
}

@Remote("com.intellij.mcpserver.impl.McpServerService", plugin = MCP_SERVER_PLUGIN_ID)
private interface McpServerServiceRef {
    val serverStreamUrl: String
    val serverSseUrl: String
}
