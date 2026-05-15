package org.jetbrains.hexana.analytics.testing

import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import org.jetbrains.hexana.analytics.AnalyticsClient
import kotlin.js.Promise
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

private const val DEFAULT_TIMEOUT_MS = 5_000L
private const val POLL_INTERVAL_MS = 25L

private fun nodeRequire(module: String): dynamic =
    js("eval('require')(module)")

class FakePostHogNodeServer private constructor(
    private val server: dynamic,
    val port: Int,
) {
    private val recordedRequests = mutableListOf<RecordedNodeRequest>()

    val host: String = "127.0.0.1"
    val url: String get() = "http://$host:$port"
    val requests: List<RecordedNodeRequest> get() = recordedRequests.toList()

    fun events(): List<RecordedNodeEvent> {
        return requests.flatMap { request -> request.toRecordedEvents() }
    }

    suspend fun awaitEvents(count: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<RecordedNodeEvent> {
        return awaitAtLeast(count, timeoutMs, ::events, "PostHog events")
    }

    suspend fun awaitEvent(name: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): RecordedNodeEvent {
        val deadline = currentTimeMillis() + timeoutMs
        do {
            events().firstOrNull { it.name == name }?.let { return it }
            delay(POLL_INTERVAL_MS)
        } while (currentTimeMillis() < deadline)

        fail(
            "Expected PostHog event `$name`, but recorded events were: " +
                events().joinToString(prefix = "[", postfix = "]") { it.name }
        )
    }

    suspend fun assertNoRequests(timeoutMs: Long = 250L) {
        val deadline = currentTimeMillis() + timeoutMs
        do {
            assertFalse(
                requests.isNotEmpty(),
                "Expected no PostHog requests, but recorded: $requests"
            )
            delay(POLL_INTERVAL_MS)
        } while (currentTimeMillis() < deadline)
    }

    suspend fun close() {
        Promise<Unit> { resolve, reject ->
            server.close { error: dynamic ->
                if (error == null) {
                    resolve(Unit)
                } else {
                    reject(error)
                }
            }
        }.await()
    }

    private fun record(request: dynamic, body: String) {
        recordedRequests += RecordedNodeRequest(
            method = request.method as String,
            path = request.url as String,
            headers = request.headers,
            body = body,
        )
    }

    private fun RecordedNodeRequest.toRecordedEvents(): List<RecordedNodeEvent> {
        if (body.isBlank()) return emptyList()
        val parsed = JSON.parse<dynamic>(body)
        val batch = parsed.batch
        if (isDefined(batch)) {
            return dynamicArrayToList(batch).mapNotNull { event ->
                toRecordedEvent(event, this)
            }
        }

        val events = parsed.events
        if (isDefined(events)) {
            return dynamicArrayToList(events).mapNotNull { event ->
                toRecordedEvent(event, this)
            }
        }

        return listOfNotNull(toRecordedEvent(parsed, this))
    }

    private fun toRecordedEvent(eventPayload: dynamic, request: RecordedNodeRequest): RecordedNodeEvent? {
        val eventName = eventPayload.event as? String ?: return null
        return RecordedNodeEvent(
            name = eventName,
            distinctId = (eventPayload["distinct_id"] ?: eventPayload.distinctId) as? String,
            properties = eventPayload.properties ?: js("{}"),
            request = request,
        )
    }

    companion object {
        suspend fun start(): FakePostHogNodeServer {
            val http = nodeRequire("http")
            val zlib = nodeRequire("zlib")
            lateinit var fake: FakePostHogNodeServer

            val server = http.createServer { request: dynamic, response: dynamic ->
                val chunks = js("[]")
                request.on("data") { chunk: dynamic ->
                    chunks.push(chunk)
                }
                request.on("end") {
                    val bufferModule = nodeRequire("buffer")
                    val requestBuffer = bufferModule.Buffer.concat(chunks)
                    val encoding = request.headers["content-encoding"] as? String
                    val decodedBuffer = if (encoding.equals("gzip", ignoreCase = true)) {
                        zlib.gunzipSync(requestBuffer)
                    } else {
                        requestBuffer
                    }
                    fake.record(request, decodedBuffer.toString("utf8") as String)

                    response.statusCode = 200
                    response.setHeader("Content-Type", "application/json")
                    response.end("""{"status":"ok"}""")
                }
            }

            return Promise<FakePostHogNodeServer> { resolve, reject ->
                server.on("error") { error: dynamic ->
                    reject(error)
                }
                server.listen(0, "127.0.0.1") {
                    val port = server.address().port as Int
                    fake = FakePostHogNodeServer(server, port)
                    resolve(fake)
                }
            }.await()
        }
    }
}

data class RecordedNodeRequest(
    val method: String,
    val path: String,
    val headers: dynamic,
    val body: String,
)

data class RecordedNodeEvent(
    val name: String,
    val distinctId: String?,
    val properties: dynamic,
    val request: RecordedNodeRequest,
) {
    fun requireDistinctId(expected: String? = null): RecordedNodeEvent = apply {
        val actual = distinctId ?: fail("Expected event `$name` to have distinct_id")
        if (expected != null) {
            assertEquals(expected, actual, "Unexpected distinct_id for event `$name`")
        }
    }

    fun assertProperty(key: String, expected: Any?): RecordedNodeEvent = apply {
        val actual = properties[key]
        assertDynamicValueEquals(expected, actual, "Unexpected value for property `$key` in event `$name`")
    }
}

fun List<RecordedNodeEvent>.singleEvent(name: String): RecordedNodeEvent {
    val matches = filter { it.name == name }
    assertEquals(
        1,
        matches.size,
        "Expected exactly one PostHog event `$name`, but recorded: " +
            joinToString(prefix = "[", postfix = "]") { it.name }
    )
    return matches.single()
}

suspend fun flushUntilEvents(client: AnalyticsClient, server: FakePostHogNodeServer, expectedCount: Int) {
    val deadline = currentTimeMillis() + DEFAULT_TIMEOUT_MS
    do {
        client.flush()
        if (server.events().size >= expectedCount) {
            return
        }
        delay(POLL_INTERVAL_MS)
    } while (currentTimeMillis() < deadline)

    server.awaitEvents(expectedCount, timeoutMs = 0)
}

private suspend fun <T> awaitAtLeast(
    count: Int,
    timeoutMs: Long,
    snapshot: () -> List<T>,
    label: String,
): List<T> {
    val deadline = currentTimeMillis() + timeoutMs
    do {
        val current = snapshot()
        if (current.size >= count) {
            return current
        }
        delay(POLL_INTERVAL_MS)
    } while (currentTimeMillis() < deadline)

    val current = snapshot()
    fail("Expected at least $count $label, but recorded ${current.size}: $current")
}

private fun dynamicArrayToList(array: dynamic): List<dynamic> {
    val result = mutableListOf<dynamic>()
    val length = array.length as Int
    for (index in 0 until length) {
        result.add(array[index])
    }
    return result
}

private fun assertDynamicValueEquals(expected: Any?, actual: dynamic, message: String) {
    when (expected) {
        null -> assertEquals(null, actual, message)
        is Number -> assertEquals(expected.toDouble(), dynamicToDouble(actual), message)
        else -> assertEquals(expected, actual, message)
    }
}

private fun dynamicToDouble(value: dynamic): Double =
    js("Number(value)") as Double

private fun isDefined(value: dynamic): Boolean =
    js("value !== undefined && value !== null") as Boolean

private fun currentTimeMillis(): Double =
    js("Date.now()") as Double
