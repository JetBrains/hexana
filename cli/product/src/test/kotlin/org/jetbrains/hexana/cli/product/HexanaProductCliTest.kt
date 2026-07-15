package org.jetbrains.hexana.cli.product

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexanaProductCliTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun commandHelpDoesNotReadStdinOrRequireSource() {
        listOf("-h", "--help").forEach { helpFlag ->
            val result = HexanaProductCli(HexanaCliProduct.STANDARD).run(
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

        val result = HexanaProductCli(HexanaCliProduct.STANDARD).run(
            args = arrayOf("summarize-binary", "--format", "json"),
            stdin = ZeroAvailableInputStream(MINIMAL_WASM_MODULE),
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )

        assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("\"format\":\"wasm\""), result.stdout)
    }

    @Test
    fun implicitStdinPreservesInlineByteLimitFailures() {
        assumeTrue("Implicit stdin is disabled when a console is attached", System.console() == null)

        val result = HexanaProductCli(HexanaCliProduct.STANDARD).run(
            args = arrayOf("summarize-binary", "--max-inline-bytes", "1"),
            stdin = ZeroAvailableInputStream(MINIMAL_WASM_MODULE),
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )

        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertTrue(result.stderr.contains("Inline bytes exceed maximum size of 1 bytes."), result.stderr)
    }

    @Test
    fun missingRunScriptSourceFailsBeforeReadingBinarySource() {
        val result = HexanaProductCli(HexanaCliProduct.SCRIPT).run(
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
                val result = HexanaProductCli(HexanaCliProduct.SCRIPT).run(
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
