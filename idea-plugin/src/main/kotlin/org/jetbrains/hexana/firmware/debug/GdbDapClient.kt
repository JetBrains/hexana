package org.jetbrains.hexana.firmware.debug

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import java.io.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal DAP-over-stdio client for `gdb -i=dap`.
 *
 * Framing: `Content-Length: N\r\n\r\n<json>` in both directions.
 * The writer serializes requests with monotonically increasing seq.
 * The reader thread parses frames, correlates responses by request_seq,
 * queues events separately. All waits are bounded (default 15 s).
 *
 * **Stream ownership:** callers must pass [input] and [output] taken directly from
 * `process.inputStream` / `process.outputStream` BEFORE calling
 * `KillableProcessHandler.startNotify()`. If `startNotify()` is called first, the
 * handler's internal reader threads will consume the stream before the DAP reader can.
 *
 * Log every frame at DEBUG via [LOG].
 *
 * Must be created and used off the EDT.
 */
class GdbDapClient(
    input: InputStream,
    output: OutputStream,
    private val defaultTimeoutMs: Long = 15_000L,
) : Closeable {

    private val seq = AtomicInteger(1)
    private val pendingResponses = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val eventQueue = LinkedBlockingDeque<JsonObject>(256)

    private val writer: OutputStream = BufferedOutputStream(output)
    private val bufferedInput: BufferedInputStream = BufferedInputStream(input)

    private val readerThread: Thread = Thread(::readLoop, "gdb-dap-reader").also { t ->
        t.isDaemon = true
        t.start()
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sends a DAP request and blocks until the corresponding response arrives.
     *
     * @throws TimeoutException if no response is received within [defaultTimeoutMs].
     * @throws IOException if GDB terminates before responding.
     */
    fun request(command: String, args: JsonObject = buildJsonObject {}): JsonObject {
        val (_, future) = send(command, args)
        return future.get(defaultTimeoutMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Sends a DAP request without blocking for the response.
     *
     * Returns the request seq and a [CompletableFuture] that will be completed when the
     * response arrives. Use [awaitResponse] to retrieve it later.
     *
     * Needed for GDB DAP's `attach` quirk: the `attach` response is only delivered AFTER
     * `configurationDone`, so the caller must fire `attach`, send subsequent configuration
     * requests, then await the `attach` future.
     */
    fun send(command: String, args: JsonObject = buildJsonObject {}): Pair<Int, CompletableFuture<JsonObject>> {
        val id = seq.getAndIncrement()
        val frame = buildJsonObject {
            put("seq", id)
            put("type", "request")
            put("command", command)
            put("arguments", args)
        }
        val future = CompletableFuture<JsonObject>()
        pendingResponses[id] = future
        writeFrame(frame)
        return id to future
    }

    /**
     * Awaits the response future from [send], with [timeoutMs] bound.
     *
     * @throws TimeoutException if no response arrives within [timeoutMs].
     */
    fun awaitResponse(future: CompletableFuture<JsonObject>, timeoutMs: Long = defaultTimeoutMs): JsonObject =
        future.get(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Re-queues [event] at the front of the event queue so a subsequent [waitForEvent] call
     * sees it. Used by consumers that peek an event and decide not to consume it.
     */
    fun requeueEvent(event: JsonObject) {
        eventQueue.offerFirst(event)
    }

    /**
     * Blocks until an event whose `event` field is one of [eventNames] arrives,
     * or [timeoutMs] elapses. Returns `null` on timeout.
     *
     * Unmatched events are drained to a side buffer first and re-queued at the end
     * so no events are lost but the scan makes forward progress.
     */
    fun waitForEvent(vararg eventNames: String, timeoutMs: Long = defaultTimeoutMs): JsonObject? {
        val target = setOf(*eventNames)
        val deadline = System.currentTimeMillis() + timeoutMs
        val skipped = mutableListOf<JsonObject>()
        try {
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return null
                val obj = eventQueue.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
                val name = obj["event"]?.jsonPrimitive?.contentOrNull
                if (name != null && name in target) return obj
                skipped.add(obj)
            }
        } finally {
            // Re-queue skipped events at the front in original order so future waitForEvent
            // calls see them. Use addFirst to reverse them back to original order.
            for (i in skipped.indices.reversed()) {
                eventQueue.offerFirst(skipped[i])
            }
        }
    }

    override fun close() {
        readerThread.interrupt()
        readerThread.join(2_000)
    }

    // -------------------------------------------------------------------------
    // Internal framing
    // -------------------------------------------------------------------------

    /**
     * Encodes [json] as a Content-Length framed DAP message.
     * Exposed as `internal` so [GdbDapFramingTest] can verify encoding in isolation.
     */
    internal fun encodeFrame(json: JsonObject): ByteArray {
        val body = json.toString().toByteArray(Charsets.UTF_8)
        val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.UTF_8)
        return header + body
    }

    private fun writeFrame(json: JsonObject) {
        LOG.debug("DAP >> $json")
        val bytes = encodeFrame(json)
        synchronized(writer) {
            writer.write(bytes)
            writer.flush()
        }
    }

    private fun readLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val json = readFrame(bufferedInput) ?: break
                LOG.debug("DAP << $json")
                dispatch(json)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: EOFException) {
            LOG.info("GDB DAP stream closed")
        } catch (e: Exception) {
            LOG.warn("GDB DAP reader error", e)
        } finally {
            // Fail any outstanding futures so callers don't hang.
            pendingResponses.values.forEach { it.completeExceptionally(IOException("DAP stream closed")) }
        }
    }

    /** Reads exactly one Content-Length framed JSON object from [input], or returns `null` on EOF. */
    private fun readFrame(input: InputStream): JsonObject? {
        // Read the header line by line until the blank separator.
        var contentLength = -1
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength < 0) return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) throw EOFException("DAP stream ended mid-frame")
            read += n
        }
        return Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
    }

    /**
     * Reads a CRLF- or LF-terminated line from [input].
     * Returns `null` on EOF, empty string for the blank separator line.
     */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.endsWith('\r')) sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
        }
    }

    private fun dispatch(json: JsonObject) {
        when (json["type"]?.jsonPrimitive?.contentOrNull) {
            "response" -> {
                val reqSeq = json["request_seq"]?.jsonPrimitive?.intOrNull ?: return
                pendingResponses.remove(reqSeq)?.complete(json)
            }
            "event" -> {
                if (!eventQueue.offer(json)) {
                    LOG.warn("DAP event queue full — dropped event: ${json["event"]}")
                }
            }
            else -> LOG.debug("DAP unhandled message type: ${json["type"]}")
        }
    }

    companion object {
        private val LOG = Logger.getInstance(GdbDapClient::class.java)

        /**
         * Decodes one or more complete Content-Length framed JSON objects from [buffer].
         * Exposed as `internal` for use by [GdbDapFramingTest].
         */
        internal fun decodeFrames(buffer: ByteArray): List<JsonObject> {
            val results = mutableListOf<JsonObject>()
            val input = BufferedInputStream(ByteArrayInputStream(buffer))
            val scratch = FrameParser(input)
            while (true) {
                val obj = scratch.readFrame() ?: break
                results.add(obj)
            }
            return results
        }

        /** Encodes [json] as a Content-Length framed byte array (stateless, for tests). */
        internal fun encodeFrameStatic(json: JsonObject): ByteArray {
            val body = json.toString().toByteArray(Charsets.UTF_8)
            val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.UTF_8)
            return header + body
        }

        /** Reusable parser used by [decodeFrames]. */
        private class FrameParser(private val input: InputStream) {
            fun readFrame(): JsonObject? {
                var contentLength = -1
                while (true) {
                    val line = readLine() ?: return null
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                }
                if (contentLength < 0) return null
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(body, read, contentLength - read)
                    if (n < 0) return null
                    read += n
                }
                return try {
                    Json.parseToJsonElement(body.toString(Charsets.UTF_8)).jsonObject
                } catch (_: Exception) {
                    null
                }
            }

            fun readLine(): String? {
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                    if (b == '\n'.code) {
                        if (sb.endsWith('\r')) sb.deleteCharAt(sb.length - 1)
                        return sb.toString()
                    }
                    sb.append(b.toChar())
                }
            }
        }
    }
}
