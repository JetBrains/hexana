package org.jetbrains.hexana.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HexanaStdioServerTransportTest {

    @Test
    fun deliversValidJsonRpcLine() = runBlocking {
        val transport = HexanaStdioServerTransport(
            inputStream = ChunkedRawSource(
                listOf("""{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n")
            ).buffered(),
            outputStream = Buffer(),
        )
        val received = CompletableDeferred<JSONRPCMessage>()

        transport.onMessage { received.complete(it) }
        transport.start()

        val message = withTimeout(5.seconds) { received.await() }
        val notification = assertIs<JSONRPCNotification>(message)
        assertEquals("notifications/initialized", notification.method)

        transport.close()
    }

    @Test
    fun closesAndReportsApprovedErrorWhenInboundMessageExceedsMaximumSize() = runBlocking {
        assertInboundMessageLimit(byte = 'x'.code.toByte())
    }

    @Test
    fun closesAndReportsApprovedErrorWhenBareCarriageReturnsExceedMaximumSize() = runBlocking {
        assertInboundMessageLimit(byte = '\r'.code.toByte())
    }

    @Test
    fun acceptsCrlfTerminatorWithoutCountingCarriageReturnWithinChunk() {
        val transport = testTransport()
        transport.setBufferedLineBytesForTest(HEXANA_STDIO_MAX_MESSAGE_BYTES)

        assertTrue(transport.checkMessageSizeForTest("\r\n".encodeToByteArray()))
    }

    @Test
    fun acceptsCrlfTerminatorWithoutCountingCarriageReturnAcrossChunks() {
        val transport = testTransport()
        transport.setBufferedLineBytesForTest(HEXANA_STDIO_MAX_MESSAGE_BYTES)

        assertFalse(transport.checkMessageSizeForTest(byteArrayOf('\r'.code.toByte())))
        assertTrue(transport.checkMessageSizeForTest(byteArrayOf('\n'.code.toByte())))
    }

    @Test
    fun backpressuresInboundReadQueueAtBoundedCapacity() = runBlocking {
        val releaseHandler = CompletableDeferred<Unit>()
        val source = ChunkedRawSource(
            List(200) { """{"jsonrpc":"2.0","method":"notifications/initialized"}""" + "\n" }
        )
        val transport = HexanaStdioServerTransport(
            inputStream = source.buffered(),
            outputStream = Buffer(),
        )
        val firstMessageInHandler = CompletableDeferred<Unit>()

        transport.onMessage {
            firstMessageInHandler.complete(Unit)
            releaseHandler.await()
        }
        transport.start()
        withTimeout(5.seconds) { firstMessageInHandler.await() }
        withTimeout(5.seconds) {
            while (source.readCount < 130) {
                delay(10)
            }
        }
        delay(100)

        assertTrue(
            source.readCount <= 130,
            "Expected bounded read channel to apply backpressure around 128 queued chunks, read ${source.readCount}"
        )

        releaseHandler.complete(Unit)
        transport.close()
    }

    @Test
    fun closesAndReportsErrorWhenOutboundWriteQueueOverflows() = runBlocking {
        val transport = HexanaStdioServerTransport(
            inputStream = ChunkedRawSource(emptyList()).buffered(),
            outputStream = Buffer(),
        )
        val reportedError = CompletableDeferred<Throwable>()

        transport.onError { reportedError.complete(it) }
        repeat(1024) {
            transport.send(JSONRPCNotification("notifications/progress"))
        }

        val thrown = assertFailsWith<IllegalStateException> {
            transport.send(JSONRPCNotification("notifications/progress"))
        }
        assertEquals("Hexana stdio MCP transport write queue exceeded capacity of 1024 messages.", thrown.message)
        assertEquals(thrown, withTimeout(5.seconds) { reportedError.await() })

        transport.close()
    }

    @Test
    fun sendOnClosedTransportDoesNotReportQueueOverflow() = runBlocking {
        val transport = HexanaStdioServerTransport(
            inputStream = ChunkedRawSource(emptyList()).buffered(),
            outputStream = Buffer(),
        )
        val reportedError = CompletableDeferred<Throwable>()

        transport.onError { reportedError.complete(it) }
        transport.start()
        transport.close()

        val thrown = assertFailsWith<IllegalStateException> {
            transport.send(JSONRPCNotification("notifications/progress"))
        }
        assertFalse(reportedError.isCompleted, "Closed transports must not be reported as queue overflows")
        assertFalse(thrown.message.orEmpty().contains("write queue exceeded capacity"))
    }

    private fun testTransport(): HexanaStdioServerTransport =
        HexanaStdioServerTransport(
            inputStream = ChunkedRawSource(emptyList()).buffered(),
            outputStream = Buffer(),
        )

    private fun HexanaStdioServerTransport.checkMessageSizeForTest(bytes: ByteArray): Boolean {
        val method = HexanaStdioServerTransport::class.java.getDeclaredMethod("checkMessageSize", ByteArray::class.java)
        method.isAccessible = true
        return method.invoke(this, bytes) as Boolean
    }

    private fun HexanaStdioServerTransport.setBufferedLineBytesForTest(value: Long) {
        val field = HexanaStdioServerTransport::class.java.getDeclaredField("bufferedLineBytes")
        field.isAccessible = true
        field.setLong(this, value)
    }

    private class ChunkedRawSource(chunks: List<String>) : RawSource {
        private val chunks = ArrayDeque(chunks.map { it.encodeToByteArray() })
        @Volatile
        private var closed = false
        @Volatile
        var readCount = 0
            private set

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            while (!closed) {
                val chunk = synchronized(chunks) { chunks.removeFirstOrNull() }
                if (chunk != null) {
                    require(chunk.size <= byteCount) { "Test chunk is larger than requested byteCount=$byteCount" }
                    sink.write(chunk)
                    readCount += 1
                    return chunk.size.toLong()
                }
                Thread.sleep(10)
            }
            return -1
        }

        override fun close() {
            closed = true
        }
    }

    private suspend fun assertInboundMessageLimit(byte: Byte) {
        val totalBytes = if (byte == '\r'.code.toByte()) {
            HEXANA_STDIO_MAX_MESSAGE_BYTES + 2
        } else {
            HEXANA_STDIO_MAX_MESSAGE_BYTES + 1
        }
        val transport = HexanaStdioServerTransport(
            inputStream = RepeatingRawSource(byte = byte, totalBytes = totalBytes).buffered(),
            outputStream = Buffer(),
        )
        val reportedError = CompletableDeferred<Throwable>()

        transport.onError { reportedError.complete(it) }
        transport.start()

        val error = withTimeout(10.seconds) { reportedError.await() }
        assertEquals(HEXANA_STDIO_MESSAGE_LIMIT_ERROR_MESSAGE, error.message)

        transport.close()
    }

    private class RepeatingRawSource(private val byte: Byte, private val totalBytes: Long) : RawSource {
        private val chunk = ByteArray(8192) { byte }
        private var emittedBytes = 0L
        @Volatile
        private var closed = false

        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
            if (closed) return -1
            if (emittedBytes >= totalBytes) {
                while (!closed) {
                    Thread.sleep(10)
                }
                return -1
            }

            val bytesToEmit = minOf(byteCount, chunk.size.toLong(), totalBytes - emittedBytes).toInt()
            sink.write(chunk, endIndex = bytesToEmit)
            emittedBytes += bytesToEmit
            return bytesToEmit.toLong()
        }

        override fun close() {
            closed = true
        }
    }
}
