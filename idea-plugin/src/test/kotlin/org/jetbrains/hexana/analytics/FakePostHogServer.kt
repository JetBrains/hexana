package org.jetbrains.hexana.analytics

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val POLL_INTERVAL: Duration = Duration.ofMillis(25)

class FakePostHogServer private constructor(
    private val server: HttpServer,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()

    val host: String = "127.0.0.1"
    val port: Int get() = server.address.port
    val url: String get() = "http://$host:$port"
    val requests: List<RecordedRequest> get() = recordedRequests.toList()

    init {
        server.executor = executor
        server.createContext("/") { exchange -> exchange.recordAndRespond() }
        server.start()
    }

    fun events(): List<RecordedEvent> {
        return requests.flatMap { request ->
            request.bodyJson?.toRecordedEvents(request).orEmpty()
        }
    }

    fun awaitEvent(name: String, timeout: Duration = DEFAULT_TIMEOUT): RecordedEvent {
        val deadline = System.nanoTime() + timeout.toNanos()
        do {
            events().firstOrNull { it.name == name }?.let { return it }
            Thread.sleep(POLL_INTERVAL.toMillis())
        } while (System.nanoTime() < deadline)

        failTest(
            "Expected PostHog event `$name`, but recorded events were: " +
                events().joinToString(prefix = "[", postfix = "]") { it.name }
        )
    }

    fun assertNoRequests(timeout: Duration = Duration.ofMillis(250)) {
        val deadline = System.nanoTime() + timeout.toNanos()
        do {
            assertFalse(
                "Expected no PostHog requests, but recorded: $requests",
                requests.isNotEmpty(),
            )
            Thread.sleep(POLL_INTERVAL.toMillis())
        } while (System.nanoTime() < deadline)
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun HttpExchange.recordAndRespond() {
        val requestBodyBytes = requestBody.use { it.readBytes() }
        recordedRequests += RecordedRequest(
            method = requestMethod,
            path = requestURI.rawPath,
            query = requestURI.rawQuery,
            headers = requestHeaders.mapValues { (_, values) -> values.toList() },
            body = decodeBody(requestBodyBytes, requestHeaders["Content-Encoding"].orEmpty()),
        )

        val response = """{"status":"ok"}""".toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, response.size.toLong())
        responseBody.use { it.write(response) }
    }

    private fun decodeBody(bytes: ByteArray, contentEncodings: List<String>): String {
        val decodedBytes = if (bytes.isGzipEncoded(contentEncodings)) {
            GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
        } else {
            bytes
        }
        return decodedBytes.toString(Charsets.UTF_8)
    }

    private fun JsonElement.toRecordedEvents(request: RecordedRequest): List<RecordedEvent> {
        val root = asJsonObjectOrNull() ?: return emptyList()
        root["batch"]?.asJsonArrayOrNull()?.let { batch ->
            return batch.mapNotNull { it.asJsonObjectOrNull()?.toRecordedEvent(request) }
        }
        return listOfNotNull(root.toRecordedEvent(request))
    }

    private fun JsonObject.toRecordedEvent(request: RecordedRequest): RecordedEvent? {
        val name = stringField("event") ?: return null
        return RecordedEvent(
            name = name,
            distinctId = stringField("distinct_id") ?: stringField("distinctId"),
            properties = get("properties")?.asJsonObjectOrNull()?.asMap().orEmpty(),
            request = request,
        )
    }

    companion object {
        fun start(): FakePostHogServer {
            return FakePostHogServer(HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0))
        }
    }
}

data class RecordedRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    val bodyJson: JsonElement?
        get() = body.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it) }
}

data class RecordedEvent(
    val name: String,
    val distinctId: String?,
    val properties: Map<String, JsonElement>,
    val request: RecordedRequest,
) {
    fun requireDistinctId(expected: String? = null): RecordedEvent = apply {
        val actual = distinctId ?: failTest("Expected event `$name` to have distinct_id")
        if (expected != null) {
            assertEquals("Unexpected distinct_id for event `$name`", expected, actual)
        }
    }

    fun assertProperty(key: String, expected: Any?): RecordedEvent = apply {
        val actual = properties[key] ?: failTest(
            "Expected event `$name` to have property `$key`, but properties were: ${properties.keys.sorted()}"
        )
        assertJsonValueEquals(expected, actual, "Unexpected value for property `$key` in event `$name`")
    }
}

private fun ByteArray.isGzipEncoded(contentEncodings: List<String>): Boolean {
    return contentEncodings.any { it.equals("gzip", ignoreCase = true) } ||
        size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()
}

private fun JsonObject.stringField(name: String): String? {
    return get(name)
        ?.takeIf { it.isJsonPrimitive }
        ?.asJsonPrimitive
        ?.asString
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject

private fun JsonElement.asJsonArrayOrNull() = takeIf { it.isJsonArray }?.asJsonArray

private fun assertJsonValueEquals(expected: Any?, actual: JsonElement, message: String) {
    when (expected) {
        null -> assertEquals(message, true, actual.isJsonNull)
        is String -> assertEquals(message, expected, actual.asString)
        is Boolean -> assertEquals(message, expected, actual.asBoolean)
        is Number -> {
            val expectedNumber = BigDecimal(expected.toString())
            val actualPrimitive = actual.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            if (actualPrimitive?.isNumber != true) {
                failTest("$message. Expected numeric JSON value, but was $actual")
            }
            val actualNumber = actualPrimitive.asBigDecimal
            assertEquals(message, 0, actualNumber.compareTo(expectedNumber))
        }
        is JsonElement -> assertEquals(message, expected, actual)
        else -> failTest("Unsupported expected JSON value type: ${expected::class.simpleName}")
    }
}

private fun failTest(message: String): Nothing {
    throw AssertionError(message)
}
