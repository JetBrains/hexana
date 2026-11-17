package org.jetbrains.hexana.pcap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The plaintext side of HTTP body extraction: what
 * [PcapDissector.httpBodyInPlaintext] can pull out of a decrypted TLS record,
 * whose bytes have no place in the capture file for the offset-based path to
 * slice.
 */
class PlaintextHttpBodyTest {

    private fun plaintext(text: String): ByteArray = text.encodeToByteArray()

    @Test
    fun `a response body is split off after the headers`() {
        val body = PcapDissector.httpBodyInPlaintext(
            plaintext(
                "HTTP/1.0 200 ok\r\n" +
                    "Content-type: text/html\r\n" +
                    "Content-length: 13\r\n" +
                    "\r\n" +
                    "<html></html>",
            ),
        )
        assertTrue(body != null, "an HTTP response with a body should yield one")
        assertEquals("<html></html>", body.bytes.decodeToString())
        assertEquals("text/html", body.contentType)
        assertNull(body.contentEncoding)
    }

    @Test
    fun `headers ended by bare newlines are recognised too`() {
        val body = PcapDissector.httpBodyInPlaintext(plaintext("HTTP/1.1 200 OK\nServer: t\n\npayload"))
        assertEquals("payload", body?.bytes?.decodeToString())
    }

    @Test
    fun `a chunked body is decoded, not handed over encoded`() {
        val body = PcapDissector.httpBodyInPlaintext(
            plaintext(
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Transfer-Encoding: chunked\r\n" +
                    "\r\n" +
                    "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n",
            ),
        )
        assertEquals("hello world", body?.bytes?.decodeToString())
        assertEquals("application/json", body?.contentType)
    }

    @Test
    fun `the content encoding is reported so the caller can decompress`() {
        val body = PcapDissector.httpBodyInPlaintext(
            plaintext("HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\ncompressed-bytes"),
        )
        assertEquals("gzip", body?.contentEncoding)
    }

    @Test
    fun `a headers-only response has no body`() {
        assertNull(PcapDissector.httpBodyInPlaintext(plaintext("HTTP/1.1 304 Not Modified\r\nAge: 1\r\n\r\n")))
    }

    @Test
    fun `a response whose headers never end has no body`() {
        assertNull(PcapDissector.httpBodyInPlaintext(plaintext("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n")))
    }

    @Test
    fun `a request carries no body, matching the cleartext path`() {
        assertNull(PcapDissector.httpBodyInPlaintext(plaintext("POST /u HTTP/1.1\r\nHost: h\r\n\r\na=1")))
    }

    @Test
    fun `plaintext that is not HTTP at all yields nothing`() {
        assertNull(PcapDissector.httpBodyInPlaintext(ByteArray(64) { it.toByte() }))
    }

    @Test
    fun `a body of raw bytes survives byte for byte`() {
        val raw = ByteArray(256) { it.toByte() }
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n".encodeToByteArray()
        val body = PcapDissector.httpBodyInPlaintext(head + raw)
        assertTrue(body != null)
        assertTrue(raw contentEquals body.bytes)
    }
}
