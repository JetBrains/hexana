package org.jetbrains.hexana.mcp.server

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
import kotlin.test.assertEquals
import kotlin.test.fail

private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val POLL_INTERVAL: Duration = Duration.ofMillis(25)

internal class FakePostHogServer private constructor(
    private val server: HttpServer,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()

    val host: String = "127.0.0.1"
    val port: Int get() = server.address.port
    val url: String get() = "http://$host:$port"

    val requests: List<RecordedRequest>
        get() = recordedRequests.toList()

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.recordAndRespond()
        }
        server.start()
    }

    fun events(): List<RecordedEvent> {
        return requests.flatMap { request ->
            request.bodyJson?.toRecordedEvents(request).orEmpty()
        }
    }

    fun awaitEvents(count: Int, timeout: Duration = DEFAULT_TIMEOUT): List<RecordedEvent> {
        return awaitAtLeast(count, timeout, ::events, "PostHog events")
    }

    fun assertNoRequests(timeout: Duration = Duration.ofMillis(250)) {
        val deadline = System.nanoTime() + timeout.toNanos()
        do {
            val currentRequests = requests
            if (currentRequests.isNotEmpty()) {
                fail("Expected no PostHog requests, but recorded: $currentRequests")
            }
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
        root["events"]?.asJsonArrayOrNull()?.let { events ->
            return events.mapNotNull { it.asJsonObjectOrNull()?.toRecordedEvent(request) }
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

internal data class RecordedRequest(
    val method: String,
    val path: String,
    val query: String?,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    val bodyJson: JsonElement?
        get() = body.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it) }
}

internal data class RecordedEvent(
    val name: String,
    val distinctId: String?,
    val properties: Map<String, JsonElement>,
    val request: RecordedRequest,
) {
    fun requireDistinctId(): RecordedEvent = apply {
        if (distinctId == null) {
            fail("Expected event `$name` to have distinct_id")
        }
    }

    fun assertProperty(key: String, expected: Any?): RecordedEvent = apply {
        val actual = properties[key] ?: fail(
            "Expected event `$name` to have property `$key`, but properties were: ${properties.keys.sorted()}"
        )
        assertJsonValueEquals(expected, actual, "Unexpected value for property `$key` in event `$name`")
    }
}

internal fun List<RecordedEvent>.singleEvent(name: String): RecordedEvent {
    val matches = filter { it.name == name }
    assertEquals(
        1,
        matches.size,
        "Expected exactly one PostHog event `$name`, but recorded: " +
            joinToString(prefix = "[", postfix = "]") { it.name }
    )
    return matches.single()
}

private fun ByteArray.isGzipEncoded(contentEncodings: List<String>): Boolean {
    return contentEncodings.any { it.equals("gzip", ignoreCase = true) } ||
        size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()
}

private fun <T> awaitAtLeast(
    count: Int,
    timeout: Duration,
    snapshot: () -> List<T>,
    label: String,
): List<T> {
    val deadline = System.nanoTime() + timeout.toNanos()
    do {
        val current = snapshot()
        if (current.size >= count) {
            return current
        }
        Thread.sleep(POLL_INTERVAL.toMillis())
    } while (System.nanoTime() < deadline)

    val current = snapshot()
    fail("Expected at least $count $label, but recorded ${current.size}: $current")
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
        null -> assertEquals(true, actual.isJsonNull, message)
        is String -> assertEquals(expected, actual.asString, message)
        is Boolean -> assertEquals(expected, actual.asBoolean, message)
        is Number -> {
            val expectedNumber = BigDecimal(expected.toString())
            val actualPrimitive = actual.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            if (actualPrimitive?.isNumber != true) {
                fail("$message. Expected numeric JSON value, but was $actual")
            }
            val actualNumber = actualPrimitive.asBigDecimal
            assertEquals(0, actualNumber.compareTo(expectedNumber), message)
        }
        is JsonElement -> assertEquals(expected, actual, message)
        else -> fail("Unsupported expected JSON value type: ${expected::class.simpleName}")
    }
}
