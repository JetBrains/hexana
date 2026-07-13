package org.jetbrains.hexana.cli.product

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HexanaProductCliTest {
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
