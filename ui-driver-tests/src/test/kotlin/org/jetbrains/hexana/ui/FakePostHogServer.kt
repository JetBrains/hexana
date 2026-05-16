package org.jetbrains.hexana.ui

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import org.junit.jupiter.api.Assertions.assertEquals

private val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(60)
private val POLL_INTERVAL: Duration = Duration.ofMillis(50)

internal class FakePostHogServer private constructor(
    private val server: HttpServer,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()

    val host: String = "127.0.0.1"
    val port: Int get() = server.address.port
    val url: String get() = "http://$host:$port"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.recordAndRespond()
        }
        server.start()
    }

    fun events(): List<RecordedEvent> =
        recordedRequests.flatMap { request -> request.bodyJson?.toRecordedEvents().orEmpty() }

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

    fun assertNoEvents(timeout: Duration = Duration.ofSeconds(3)) {
        val deadline = System.nanoTime() + timeout.toNanos()
        do {
            val currentEvents = events()
            if (currentEvents.isNotEmpty()) {
                failTest("Expected no PostHog events, but recorded: ${currentEvents.map { it.name }}")
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
        val requestBody = decodeBody(requestBodyBytes, requestHeaders["Content-Encoding"].orEmpty())
        recordedRequests += RecordedRequest(body = requestBody)

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

    private fun JsonElement.toRecordedEvents(): List<RecordedEvent> {
        val root = jsonObjectOrNull() ?: return emptyList()
        root["batch"]?.jsonArrayOrNull()?.let { batch ->
            return batch.mapNotNull { it.jsonObjectOrNull()?.toRecordedEvent() }
        }
        root["events"]?.jsonArrayOrNull()?.let { events ->
            return events.mapNotNull { it.jsonObjectOrNull()?.toRecordedEvent() }
        }
        return listOfNotNull(root.toRecordedEvent())
    }

    private fun JsonObject.toRecordedEvent(): RecordedEvent? {
        val name = stringField("event") ?: return null
        val distinctId = stringField("distinct_id") ?: stringField("distinctId")
        val properties = get("properties")?.jsonObjectOrNull().orEmpty()
        return RecordedEvent(name = name, distinctId = distinctId, properties = properties)
    }

    companion object {
        fun start(): FakePostHogServer {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            return FakePostHogServer(server)
        }
    }
}

private data class RecordedRequest(
    val body: String,
) {
    val bodyJson: JsonElement?
        get() = body.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) }
}

internal data class RecordedEvent(
    val name: String,
    val distinctId: String?,
    val properties: Map<String, JsonElement>,
) {
    fun requireDistinctId(): RecordedEvent = apply {
        if (distinctId.isNullOrBlank()) {
            failTest("Expected event `$name` to have distinct_id")
        }
    }

    fun assertProperty(key: String, expected: Any?): RecordedEvent = apply {
        val actual = properties[key] ?: failTest("Expected event `$name` to have property `$key`")
        assertJsonValueEquals(expected, actual, "Unexpected value for property `$key` in event `$name`")
    }

    fun assertPositiveNumberProperty(key: String): RecordedEvent = apply {
        val actual = properties[key]?.jsonPrimitive?.doubleOrNull
            ?: failTest("Expected event `$name` to have numeric property `$key`")
        if (actual <= 0.0) {
            failTest("Expected event `$name` property `$key` to be positive, but was $actual")
        }
    }
}

private fun ByteArray.isGzipEncoded(contentEncodings: List<String>): Boolean =
    contentEncodings.any { it.equals("gzip", ignoreCase = true) } ||
            size >= 2 && this[0] == 0x1f.toByte() && this[1] == 0x8b.toByte()

private fun JsonElement.jsonObjectOrNull(): JsonObject? =
    runCatching { jsonObject }.getOrNull()

private fun JsonElement.jsonArrayOrNull() =
    runCatching { jsonArray }.getOrNull()

private fun JsonObject.stringField(name: String): String? =
    get(name)?.jsonPrimitive?.contentOrNull

private fun assertJsonValueEquals(expected: Any?, actual: JsonElement, message: String) {
    val primitive = actual.jsonPrimitive
    when (expected) {
        null -> assertEquals(null, primitive.contentOrNull, message)
        is String -> assertEquals(expected, primitive.content, message)
        is Boolean -> assertEquals(expected, primitive.booleanOrNull, message)
        is Number -> assertEquals(expected.toDouble(), primitive.doubleOrNull, message)
        else -> assertEquals(expected.toString(), primitive.content, message)
    }
}

private fun failTest(message: String): Nothing {
    throw AssertionError(message)
}
