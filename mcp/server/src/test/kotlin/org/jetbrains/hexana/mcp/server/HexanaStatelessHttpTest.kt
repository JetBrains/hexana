package org.jetbrains.hexana.mcp.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class HexanaStatelessHttpTest {
    @Test
    fun statelessHttpRequestClosesServerAfterSuccessfulJsonRpcResponse() = runBlocking {
        val closeCount = AtomicInteger()
        val closed = CompletableDeferred<Unit>()

        withLifecycleHttpServer(
            serverFactory = {
                lifecycleTestServer {
                    closeCount.incrementAndGet()
                    closed.complete(Unit)
                }
            }
        ) { port ->
            val response = postJsonRpc(
                port = port,
                body = initializeRequest(),
                accept = "application/json, text/event-stream",
            )

            assertEquals(200, response.statusCode)
            withTimeout(2_000) { closed.await() }
            assertEquals(1, closeCount.get())
        }
    }

    @Test
    fun statelessHttpRequestClosesServerAfterProtocolValidationFailure() = runBlocking {
        val closeCount = AtomicInteger()
        val closed = CompletableDeferred<Unit>()

        withLifecycleHttpServer(
            serverFactory = {
                lifecycleTestServer {
                    closeCount.incrementAndGet()
                    closed.complete(Unit)
                }
            }
        ) { port ->
            val response = postJsonRpc(
                port = port,
                body = initializeRequest(),
                accept = "application/json",
            )

            assertEquals(406, response.statusCode)
            withTimeout(2_000) { closed.await() }
            assertEquals(1, closeCount.get())
        }
    }

    private suspend fun withLifecycleHttpServer(
        serverFactory: () -> Server,
        block: suspend (Int) -> Unit,
    ) {
        val port = freePort()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            hexanaStatelessMcpStreamableHttp(
                path = "/mcp",
                enableDnsRebindingProtection = true,
                allowedHosts = listOf("127.0.0.1", "127.0.0.1:$port"),
                allowedOrigins = null,
                eventStore = null,
            ) {
                serverFactory()
            }
        }.start(wait = false)

        try {
            block(port)
        } finally {
            server.stop()
        }
    }

    private fun lifecycleTestServer(onClose: () -> Unit): Server {
        return Server(
            serverInfo = Implementation("hexana-lifecycle-test", "1.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false)
                )
            )
        ).also { server ->
            server.onClose(onClose)
        }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun postJsonRpc(
        port: Int,
        body: String,
        accept: String,
    ): RawHttpResponse {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val request = buildString {
            append("POST /mcp HTTP/1.1\r\n")
            append("Host: 127.0.0.1:").append(port).append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Accept: ").append(accept).append("\r\n")
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)

        return Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), RAW_HTTP_SOCKET_TIMEOUT_MILLIS)
            socket.soTimeout = RAW_HTTP_SOCKET_TIMEOUT_MILLIS

            socket.getOutputStream().apply {
                write(request)
                write(bodyBytes)
                flush()
            }
            socket.shutdownOutput()

            val rawResponse = socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            val statusLine = rawResponse.lineSequence().firstOrNull()
                ?: error("Empty HTTP response")
            RawHttpResponse(
                statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                    ?: error("Unexpected HTTP status line: $statusLine"),
            )
        }
    }

    private fun initializeRequest(): String = """
        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"hexana-lifecycle-test","version":"1.0"}}}
    """.trimIndent()

    private data class RawHttpResponse(
        val statusCode: Int,
    )

    private companion object {
        const val RAW_HTTP_SOCKET_TIMEOUT_MILLIS = 5_000
    }
}
