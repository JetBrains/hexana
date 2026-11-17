package org.jetbrains.hexana.ui.pcap

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.pcap.HttpHeader
import org.jetbrains.hexana.ui.decompressHttpBody
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Sends a replay to a local server and checks what arrives: the edited
 * method, headers and body, without the captured framing headers, and with a
 * redirect reported rather than followed.
 */
class HttpReplaySenderTest : BasePlatformTestCase() {

    private lateinit var server: HttpServer
    private val received = mutableMapOf<String, String>()

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/echo") { exchange ->
            received["method"] = exchange.requestMethod
            received["query"] = exchange.requestURI.rawQuery.orEmpty()
            received["x-replay"] = exchange.requestHeaders.getFirst("X-Replay").orEmpty()
            received["content-length"] = exchange.requestHeaders.getFirst("Content-Length").orEmpty()
            received["keep-alive"] = exchange.requestHeaders.getFirst("Keep-Alive").orEmpty()
            received["body"] = exchange.requestBody.readBytes().decodeToString()
            val answer = "pong".encodeToByteArray()
            exchange.responseHeaders.add("X-Answer", "1")
            exchange.sendResponseHeaders(201, answer.size.toLong())
            exchange.responseBody.use { it.write(answer) }
        }
        server.createContext("/big") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                val chunk = ByteArray(1024 * 1024) { 'a'.code.toByte() }
                repeat(HttpReplaySender.MAX_RESPONSE_BYTES / chunk.size + 1) { out.write(chunk) }
            }
        }
        server.createContext("/moved") { exchange ->
            exchange.responseHeaders.add("Location", "/echo")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.start()
    }

    override fun tearDown() {
        try {
            server.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private val base get() = "http://127.0.0.1:${server.address.port}"

    fun testSendsEditedRequestWithoutFramingHeaders() {
        val response = runBlocking {
            HttpReplaySender.send(
                method = "PATCH",
                url = "$base/echo?id=7",
                headers = listOf(
                    HttpHeader("X-Replay", "yes"),
                    HttpHeader("Content-Length", "999"),
                    HttpHeader("Keep-Alive", "timeout=5"),
                    HttpHeader("Host", "elsewhere.test"),
                ),
                body = "edited".encodeToByteArray(),
            )
        }
        assertEquals(201, response.statusCode)
        assertEquals("pong", response.body.decodeToString())
        assertTrue(response.headers.any { it.name.equals("X-Answer", true) && it.value == "1" })

        assertEquals("PATCH", received["method"])
        assertEquals("id=7", received["query"])
        assertEquals("yes", received["x-replay"])
        assertEquals("edited", received["body"])
        assertEquals("6", received["content-length"])
        assertEquals("", received["keep-alive"])
    }

    fun testReportsRedirectInsteadOfFollowingIt() {
        val response = runBlocking { HttpReplaySender.send("GET", "$base/moved", emptyList(), ByteArray(0)) }
        assertEquals(302, response.statusCode)
        assertNull(received["method"])
    }

    fun testKeepsOnlyTheStartOfAHugeResponse() {
        val response = runBlocking { HttpReplaySender.send("GET", "$base/big", emptyList(), ByteArray(0)) }
        assertEquals(HttpReplaySender.MAX_RESPONSE_BYTES, response.body.size)
        assertTrue(response.bodyTruncated)

        val small = runBlocking { HttpReplaySender.send("GET", "$base/echo", emptyList(), ByteArray(0)) }
        assertFalse(small.bodyTruncated)
    }

    fun testDecompressionStopsAtTheLimit() {
        val zeros = ByteArray(1024 * 1024)
        val gzip = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(zeros) }
        }.toByteArray()
        val deflate = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.DeflaterOutputStream(out).use { it.write(zeros) }
        }.toByteArray()
        assertEquals(1000, decompressHttpBody(gzip, "gzip", maxBytes = 1000).size)
        assertEquals(1000, decompressHttpBody(deflate, "deflate", maxBytes = 1000).size)
        assertEquals(zeros.size, decompressHttpBody(gzip, "gzip").size)
    }

    fun testRejectsNonHttpUrls() {
        val error = runCatching {
            runBlocking { HttpReplaySender.send("GET", "file:///etc/hosts", emptyList(), ByteArray(0)) }
        }.exceptionOrNull()
        assertInstanceOf(error, IllegalArgumentException::class.java)
    }
}
