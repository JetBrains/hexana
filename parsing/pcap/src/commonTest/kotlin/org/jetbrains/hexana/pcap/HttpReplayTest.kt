package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpReplayTest {

    private val client = byteArrayOf(10, 0, 0, 1)
    private val server = byteArrayOf(10, 0, 0, 2)

    @Test
    fun parsesRequestWithContentLengthBody() {
        val raw = ("POST /api/items?x=1 HTTP/1.1\r\n" +
            "Host: example.test:8080\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: 13\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n" +
            "{\"name\":\"a\"}\n").encodeToByteArray()
        val request = assertNotNull(HttpReplay.parseRequest(raw, "http", "10.0.0.2", 8080))
        assertEquals("POST", request.method)
        assertEquals("http://example.test:8080/api/items?x=1", request.url)
        assertEquals("HTTP/1.1", request.httpVersion)
        assertEquals("{\"name\":\"a\"}\n", request.body.decodeToString())
        assertTrue(request.bodyComplete)
        assertEquals(
            listOf("Host", "Content-Type"),
            HttpReplay.replayHeaders(request).map { it.name },
        )
    }

    @Test
    fun reportsTruncatedBody() {
        val raw = "PUT /x HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\nabc".encodeToByteArray()
        val request = assertNotNull(HttpReplay.parseRequest(raw, "http", "10.0.0.2", 80))
        assertEquals("abc", request.body.decodeToString())
        assertFalse(request.bodyComplete)
    }

    @Test
    fun decodesChunkedBody() {
        val raw = ("POST /u HTTP/1.1\r\nHost: h\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "5\r\nHello\r\n7\r\n, World\r\n0\r\n\r\n").encodeToByteArray()
        val request = assertNotNull(HttpReplay.parseRequest(raw, "http", "10.0.0.2", 80))
        assertEquals("Hello, World", request.body.decodeToString())
        assertTrue(request.bodyComplete)
        assertTrue(HttpReplay.replayHeaders(request).none { it.name.equals("Transfer-Encoding", true) })
    }

    @Test
    fun fallsBackToServerAddressWithoutHost() {
        val raw = "GET /a HTTP/1.0\r\n\r\n".encodeToByteArray()
        assertEquals("https://10.0.0.2/a", HttpReplay.parseRequest(raw, "https", "10.0.0.2", 443)?.url)
        assertEquals("http://[fe80::1]:8000/a", HttpReplay.parseRequest(raw, "http", "fe80::1", 8000)?.url)
    }

    @Test
    fun keepsAbsoluteFormTarget() {
        val raw = "GET http://proxied.test/p HTTP/1.1\r\nHost: proxied.test\r\n\r\n".encodeToByteArray()
        assertEquals("http://proxied.test/p", HttpReplay.parseRequest(raw, "http", "10.0.0.2", 3128)?.url)
    }

    @Test
    fun rejectsResponsesAndTunnels() {
        assertNull(HttpReplay.parseRequest("HTTP/1.1 200 OK\r\n\r\n".encodeToByteArray(), "http", "h", 80))
        assertNull(HttpReplay.parseRequest("CONNECT h:443 HTTP/1.1\r\n\r\n".encodeToByteArray(), "http", "h", 80))
        assertNull(HttpReplay.parseRequest("OPTIONS * HTTP/1.1\r\n\r\n".encodeToByteArray(), "http", "h", 80))
    }

    @Test
    fun headerBlockRoundTrips() {
        val headers = listOf(HttpHeader("Accept", "*/*"), HttpHeader("X-Token", "a:b"))
        assertEquals(headers, HttpReplay.parseHeaders(HttpReplay.formatHeaders(headers) + "\n\n"))
        val error = assertFailsWith<IllegalArgumentException> { HttpReplay.parseHeaders("Accept: */*\nbogus") }
        assertTrue(error.message!!.contains("Line 2"))
    }

    @Test
    fun rendersHttpClientRequest() {
        val raw = ("POST /api HTTP/1.1\r\nHost: example.test\r\nContent-Length: 7\r\n" +
            "Content-Type: text/plain\r\n\r\nhi\r\nyou").encodeToByteArray()
        val request = assertNotNull(HttpReplay.parseRequest(raw, "https", "10.0.0.2", 443))
        val text = HttpReplay.toHttpClientFile(request, "Replay of packet #4", listOf("10.0.0.1:5000 → 10.0.0.2:443"))
        assertEquals(
            "### Replay of packet #4\n" +
                "# 10.0.0.1:5000 → 10.0.0.2:443\n" +
                "POST https://example.test/api HTTP/1.1\n" +
                "Host: example.test\n" +
                "Content-Type: text/plain\n" +
                "\n" +
                "hi\nyou\n",
            text,
        )
    }

    @Test
    fun binaryOrAmbiguousBodiesGoThroughAFile() {
        val binary = assertNotNull(
            HttpReplay.parseRequest(
                "POST /b HTTP/1.1\r\nHost: h\r\nContent-Length: 3\r\n\r\n".encodeToByteArray() +
                    byteArrayOf(0x1F, 0x8B.toByte(), 0x00),
                "http", "h", 80,
            ),
        )
        assertTrue(HttpReplay.needsBodyFile(binary))
        assertTrue(HttpReplay.toHttpClientFile(binary, "t", bodyFilePath = "/tmp/b.bin").endsWith("\n< /tmp/b.bin\n"))
        assertFailsWith<IllegalArgumentException> { HttpReplay.toHttpClientFile(binary, "t") }

        val separator = assertNotNull(
            HttpReplay.parseRequest(
                "POST /b HTTP/1.1\r\nHost: h\r\nContent-Length: 5\r\n\r\n###\nx".encodeToByteArray(), "http", "h", 80,
            ),
        )
        assertTrue(HttpReplay.needsBodyFile(separator))
    }

    @Test
    fun reassemblesRequestAcrossSegmentsSkippingRetransmissions() {
        val head = "POST /upload HTTP/1.1\r\nHost: h\r\nContent-Length: 10\r\n\r\n01234"
        val pcap = buildCapture(
            Segment(client, server, 40000, 80, seq = 100, payload = head),
            // The server's ACK is in the other direction and must not be taken.
            Segment(server, client, 80, 40000, seq = 900, payload = "HTTP/1.1 100 Continue\r\n\r\n"),
            Segment(client, server, 40000, 80, seq = 100, payload = head),
            Segment(client, server, 40000, 80, seq = 100L + head.length, payload = "56789"),
            Segment(client, server, 40000, 80, seq = 200, payload = "GET /next HTTP/1.1\r\n\r\n"),
        )
        val buffer = pcap.toCommonByteBuffer()
        val parsed = assertNotNull(PcapParser.parse(buffer))
        val raw = assertNotNull(PcapDissector.reassembleHttpRequest(buffer, parsed.packets, 0, parsed.header.network))
        val endpoints = assertNotNull(PcapDissector.tcpEndpoints(buffer, parsed.packets[0], parsed.header.network))
        assertEquals(TcpEndpoints("10.0.0.1", 40000, "10.0.0.2", 80), endpoints)

        val request = assertNotNull(
            HttpReplay.parseRequest(raw, "http", endpoints.destinationAddress, endpoints.destinationPort),
        )
        assertContentEquals("0123456789".encodeToByteArray(), request.body)
        assertTrue(request.bodyComplete)
    }

    @Test
    fun responsePacketIsNotAReplayableRequest() {
        val pcap = buildCapture(Segment(server, client, 80, 40000, seq = 1, payload = "HTTP/1.1 200 OK\r\n\r\n12345678"))
        val buffer = pcap.toCommonByteBuffer()
        val parsed = assertNotNull(PcapParser.parse(buffer))
        assertNull(PcapDissector.reassembleHttpRequest(buffer, parsed.packets, 0, parsed.header.network))
    }

    private class Segment(
        val src: ByteArray,
        val dst: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val seq: Long,
        val payload: String,
    )

    /** A classic little-endian Ethernet capture of TCP segments with real sequence numbers. */
    private fun buildCapture(vararg segments: Segment): ByteArray {
        val out = mutableListOf<Byte>()
        out += ByteArray(24).also {
            putU32(it, 0, 0xA1B2C3D4L)
            it[4] = 2; it[6] = 4
            putU32(it, 16, 65535)
            putU32(it, 20, 1)
        }.toList()
        for (s in segments) {
            val payload = s.payload.encodeToByteArray()
            val eth = ByteArray(14).also { it[12] = 0x08 }
            val ip = ByteArray(20).also {
                it[0] = 0x45
                val total = 40 + payload.size
                it[2] = (total shr 8).toByte(); it[3] = total.toByte()
                it[9] = 6
                s.src.copyInto(it, 12); s.dst.copyInto(it, 16)
            }
            val tcp = ByteArray(20).also {
                it[0] = (s.srcPort shr 8).toByte(); it[1] = s.srcPort.toByte()
                it[2] = (s.dstPort shr 8).toByte(); it[3] = s.dstPort.toByte()
                it[4] = (s.seq shr 24).toByte(); it[5] = (s.seq shr 16).toByte()
                it[6] = (s.seq shr 8).toByte(); it[7] = s.seq.toByte()
                it[12] = 0x50; it[13] = 0x18
            }
            val frame = eth + ip + tcp + payload
            out += ByteArray(16).also {
                putU32(it, 0, 1000); putU32(it, 8, frame.size.toLong()); putU32(it, 12, frame.size.toLong())
            }.toList()
            out += frame.toList()
        }
        return out.toByteArray()
    }

    private fun putU32(buf: ByteArray, off: Int, value: Long) {
        for (i in 0 until 4) buf[off + i] = (value shr (8 * i)).toByte()
    }
}
