package org.jetbrains.hexana.mcp.server

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpStatusCode
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal suspend fun assertDefaultHttpSmoke(
    server: HexanaInstalledServer,
    logger: Logger,
    label: String,
) {
    HttpClient(CIO).use { httpClient ->
        logger.info("Requesting {} /healthz", label)
        val healthResponse = httpClient.get("${server.origin}/healthz")
        val healthBody = healthResponse.bodyAsText()
        logger.info("Received {} /healthz status={} body={}", label, healthResponse.status, healthBody)
        assertEquals(HttpStatusCode.OK, healthResponse.status)
        assertEquals("OK", healthBody)

        logger.info("Connecting {} MCP client to {}", label, server.baseUrl)
        val client = httpClient.mcpStreamableHttp(server.baseUrl)
        try {
            logger.info("Requesting {} tools/list", label)
            val tools = client.listTools(ListToolsRequest())
            logger.info("Received {} tools: {}", label, tools.tools.map { it.name }.sorted())
            assertEquals(EXPECTED_DEFAULT_HEXANA_TOOL_NAMES, tools.tools.map { it.name }.sorted())
        } finally {
            logger.info("Closing {} MCP client", label)
            client.close()
        }
    }

    assertTrue(server.stdout.isBlank(), "$label HTTP mode must keep stdout empty, but was: ${server.stdout}")
}

internal suspend fun assertLocalHttpProtectionSmoke(
    server: HexanaInstalledServer,
    logger: Logger,
    label: String,
) {
    val invalidOriginStatus = postRawMcpInitialize(
        server = server,
        hostHeader = "127.0.0.1:${server.port}",
        originHeader = "http://evil.example"
    )
    logger.info("Received {} invalid Origin status={}", label, invalidOriginStatus)
    assertEquals(403, invalidOriginStatus)

    val invalidHostStatus = postRawMcpInitialize(
        server = server,
        hostHeader = "evil.example:${server.port}",
        originHeader = server.origin
    )
    logger.info("Received {} invalid Host status={}", label, invalidHostStatus)
    assertTrue(
        invalidHostStatus in 400..499,
        "$label DNS rebinding protection should reject invalid Host with 4xx, got $invalidHostStatus"
    )
}

private suspend fun postRawMcpInitialize(
    server: HexanaInstalledServer,
    hostHeader: String,
    originHeader: String,
): Int = withContext(Dispatchers.IO) {
    val body = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"hexana-http-protection-test","version":"1.0"}}}
    """.trimIndent()
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val request = buildString {
        append("POST ").append(server.endpoint).append(" HTTP/1.1\r\n")
        append("Host: ").append(hostHeader).append("\r\n")
        append("Origin: ").append(originHeader).append("\r\n")
        append("Content-Type: application/json\r\n")
        append("Accept: application/json, text/event-stream\r\n")
        append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }.toByteArray(StandardCharsets.UTF_8)

    Socket("127.0.0.1", server.port).use { socket ->
        val output = socket.getOutputStream()
        output.write(request)
        output.write(bodyBytes)
        output.flush()
        socket.shutdownOutput()

        val statusLine = socket.getInputStream()
            .bufferedReader(StandardCharsets.UTF_8)
            .readLine()
            ?: error("Empty HTTP response")
        statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: error("Unexpected HTTP status line: $statusLine")
    }
}
