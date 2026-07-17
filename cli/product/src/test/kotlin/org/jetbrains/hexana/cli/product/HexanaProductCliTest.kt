package org.jetbrains.hexana.cli.product

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class HexanaProductCliTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun commandHelpDoesNotReadStdinOrRequireSource() {
        listOf("-h", "--help").forEach { helpFlag ->
            val result = HexanaProductCli(HexanaCliProduct.STANDARD, FailOnExecuteExecutor).run(
                args = arrayOf("summarize-binary", helpFlag),
                stdin = FailOnReadInputStream,
                env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
            )

            assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
            assertTrue(result.stdout.startsWith("Usage: hexana-cli summarize-binary"), result.stdout)
        }
    }

    @Test
    fun implicitStdinDoesNotRequireAvailableBytes() {
        assumeTrue("Implicit stdin is disabled when a console is attached", System.console() == null)

        val executor = RecordingProductExecutor()
        val result = runStandardSummary(ZeroAvailableInputStream(MINIMAL_WASM_MODULE), executor)

        assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("\"format\":\"wasm\""), result.stdout)
        executor.singleRequest().assertStdinSource(MINIMAL_WASM_MODULE, slotName = "binary")
    }

    @Test
    fun implicitStdinWaitsForDelayedFirstByte() {
        assumeTrue("Implicit stdin is disabled when a console is attached", System.console() == null)

        val stdin = BlockingFirstReadInputStream(MINIMAL_WASM_MODULE)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val productExecutor = RecordingProductExecutor()
            val future = executor.submit(Callable { runStandardSummary(stdin, productExecutor) })

            assertTrue(stdin.readStarted.await(1, TimeUnit.SECONDS), "Expected CLI to start reading implicit stdin")
            assertFutureStillRunning(future, timeoutMillis = 150)
            stdin.release()

            val result = future.get(1, TimeUnit.SECONDS)
            assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
            assertTrue(result.stdout.contains("\"format\":\"wasm\""), result.stdout)
            productExecutor.singleRequest().assertStdinSource(MINIMAL_WASM_MODULE, slotName = "binary")
        } finally {
            stdin.release()
            executor.shutdownNow()
        }
    }

    @Test
    fun implicitStdinWaitsForSlowChunks() {
        assumeTrue("Implicit stdin is disabled when a console is attached", System.console() == null)

        val stdin = SlowChunkInputStream(MINIMAL_WASM_MODULE, firstChunkSize = 4)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val productExecutor = RecordingProductExecutor()
            val future = executor.submit(Callable { runStandardSummary(stdin, productExecutor) })

            assertTrue(stdin.firstChunkDelivered.await(1, TimeUnit.SECONDS), "Expected CLI to read the first chunk")
            assertTrue(stdin.nextChunkReadStarted.await(1, TimeUnit.SECONDS), "Expected CLI to keep reading until EOF")
            assertFutureStillRunning(future, timeoutMillis = 150)
            stdin.releaseNextChunks()

            val result = future.get(1, TimeUnit.SECONDS)
            assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
            assertTrue(result.stdout.contains("\"format\":\"wasm\""), result.stdout)
            productExecutor.singleRequest().assertStdinSource(MINIMAL_WASM_MODULE, slotName = "binary")
        } finally {
            stdin.releaseNextChunks()
            executor.shutdownNow()
        }
    }

    @Test
    fun implicitStdinPreservesInlineByteLimitFailures() {
        assumeTrue("Implicit stdin is disabled when a console is attached", System.console() == null)

        val result = HexanaProductCli(HexanaCliProduct.STANDARD, FailOnExecuteExecutor).run(
            args = arrayOf("summarize-binary", "--max-inline-bytes", "1"),
            stdin = ZeroAvailableInputStream(MINIMAL_WASM_MODULE),
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )

        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertTrue(result.stderr.contains("Inline bytes exceed maximum size of 1 bytes."), result.stderr)
    }

    @Test
    fun missingImplicitStdinSourceReportsUsageWhenStdinIsEmpty() {
        val result = HexanaProductCli(HexanaCliProduct.STANDARD, FailOnExecuteExecutor).run(
            args = arrayOf("summarize-binary"),
            stdin = ByteArrayInputStream(ByteArray(0)),
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )

        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertEquals(
            "Missing summarize-binary source. Provide a path, --stdin, or a base64 source option.\n",
            result.stderr,
        )
        assertEquals("", result.stdout)
    }

    @Test
    fun missingRunScriptSourceFailsBeforeReadingBinarySource() {
        val result = HexanaProductCli(HexanaCliProduct.SCRIPT, FailOnExecuteExecutor).run(
            args = arrayOf("run-script", "--stdin"),
            stdin = FailOnReadInputStream,
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )

        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertEquals("Exactly one of --script or --script-text is required.\n", result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun telemetryDoesNotConsumeStreamingResponseBody() {
        val home = temporaryFolder.newFolder("telemetry-home")
        withUserHome(home.absolutePath) {
            StreamingTelemetryServer.start().use { server ->
                val result = HexanaProductCli(HexanaCliProduct.SCRIPT, FailOnExecuteExecutor).run(
                    args = arrayOf("version"),
                    stdin = ByteArrayInputStream(ByteArray(0)),
                    env = mapOf(
                        "HEXANA_CLI_POSTHOG_HOST" to server.url,
                        "HEXANA_CLI_POSTHOG_KEY" to "test-key",
                    ),
                )

                assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
                server.assertRequestRecorded()
                server.assertResponseClosed()
            }
        }
    }
}

private fun runStandardSummary(
    stdin: InputStream,
    executor: RecordingProductExecutor,
): ProductCliResult =
    HexanaProductCli(HexanaCliProduct.STANDARD, executor).run(
        args = arrayOf("summarize-binary", "--format", "json"),
        stdin = stdin,
        env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
    )

private class RecordingProductExecutor(
    private val result: ProductCliResult = ProductCliResult(stdout = """{"format":"wasm"}"""),
) : HexanaProductToolExecutor {
    private val requests = mutableListOf<HexanaProductToolExecutionRequest>()

    override fun execute(request: HexanaProductToolExecutionRequest): ProductCliResult {
        requests += request
        return result
    }

    fun singleRequest(): HexanaProductToolExecutionRequest {
        assertEquals(1, requests.size, "Expected exactly one product executor request")
        return requests.single()
    }
}

private object FailOnExecuteExecutor : HexanaProductToolExecutor {
    override fun execute(request: HexanaProductToolExecutionRequest): ProductCliResult =
        error("CLI product test must not execute ${request.spec.commandName}")
}

private fun HexanaProductToolExecutionRequest.assertStdinSource(bytes: ByteArray, slotName: String) {
    val source = sourceSelection as? ToolInputSourceSelection.StdinBytes
        ?: fail("Expected stdin source, got $sourceSelection")
    assertEquals(slotName, source.slotName)
    assertContentEquals(bytes, source.bytes())
}

private fun assertFutureStillRunning(future: Future<*>, timeoutMillis: Long) {
    try {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        fail("Expected CLI to keep waiting for stdin")
    } catch (_: TimeoutException) {
    }
}

private inline fun withUserHome(value: String, block: () -> Unit) {
    val original = System.getProperty("user.home")
    try {
        System.setProperty("user.home", value)
        block()
    } finally {
        if (original == null) {
            System.clearProperty("user.home")
        } else {
            System.setProperty("user.home", original)
        }
    }
}

private class StreamingTelemetryServer private constructor(
    private val server: HttpServer,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val requestRecorded = CountDownLatch(1)
    private val responseClosed = CountDownLatch(1)

    val url: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.recordAndStreamBody()
        }
        server.start()
    }

    fun assertRequestRecorded() {
        assertTrue(requestRecorded.await(1, TimeUnit.SECONDS), "Expected telemetry request to be recorded")
    }

    fun assertResponseClosed() {
        assertTrue(
            responseClosed.await(1, TimeUnit.SECONDS),
            "Expected telemetry client to close the streaming response without reading the body",
        )
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun HttpExchange.recordAndStreamBody() {
        requestBody.use { it.readBytes() }
        requestRecorded.countDown()
        sendResponseHeaders(200, 1_000_000_000L)
        val chunk = """{"status":"ok"}""".toByteArray(Charsets.UTF_8)
        try {
            while (true) {
                responseBody.write(chunk)
                responseBody.flush()
                Thread.sleep(25)
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            responseClosed.countDown()
            runCatching { responseBody.close() }
        }
    }

    companion object {
        fun start(): StreamingTelemetryServer =
            StreamingTelemetryServer(HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0))
    }
}

private class BlockingFirstReadInputStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    private val release = CountDownLatch(1)

    val readStarted = CountDownLatch(1)

    fun release() {
        release.countDown()
    }

    override fun available(): Int = 0

    override fun read(): Int {
        waitForRelease()
        return delegate.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        waitForRelease()
        return delegate.read(buffer, offset, length)
    }

    private fun waitForRelease() {
        readStarted.countDown()
        try {
            if (!release.await(1, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting to release stdin")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting to release stdin", error)
        }
    }
}

private class SlowChunkInputStream(
    private val bytes: ByteArray,
    private val firstChunkSize: Int,
) : InputStream() {
    private val releaseNextChunks = CountDownLatch(1)
    private var position = 0

    val firstChunkDelivered = CountDownLatch(1)
    val nextChunkReadStarted = CountDownLatch(1)

    init {
        require(firstChunkSize in 1..bytes.size)
    }

    fun releaseNextChunks() {
        releaseNextChunks.countDown()
    }

    override fun available(): Int = 0

    override fun read(): Int {
        val singleByte = ByteArray(1)
        val read = read(singleByte, 0, singleByte.size)
        return if (read < 0) -1 else singleByte[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) {
            return -1
        }
        if (position == 0) {
            val count = firstChunkSize.coerceAtMost(length)
            bytes.copyInto(buffer, destinationOffset = offset, startIndex = position, endIndex = position + count)
            position += count
            firstChunkDelivered.countDown()
            return count
        }

        nextChunkReadStarted.countDown()
        waitForNextChunks()
        val count = (bytes.size - position).coerceAtMost(length)
        bytes.copyInto(buffer, destinationOffset = offset, startIndex = position, endIndex = position + count)
        position += count
        return count
    }

    private fun waitForNextChunks() {
        try {
            if (!releaseNextChunks.await(1, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting to release stdin chunks")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting to release stdin chunks", error)
        }
    }
}

private object FailOnReadInputStream : InputStream() {
    override fun read(): Int =
        error("help must not read stdin")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        error("help must not read stdin")
}

private class ZeroAvailableInputStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    private var firstBulkRead = true

    override fun available(): Int = 0

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (firstBulkRead) {
            firstBulkRead = false
            Thread.sleep(10)
        }
        return delegate.read(buffer, offset, length)
    }
}

private val MINIMAL_WASM_MODULE = byteArrayOf(
    0x00, 0x61, 0x73, 0x6d,
    0x01, 0x00, 0x00, 0x00,
)
