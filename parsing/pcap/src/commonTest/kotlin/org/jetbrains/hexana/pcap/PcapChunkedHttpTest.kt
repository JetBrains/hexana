package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.toCommonByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PcapChunkedHttpTest {

    @Test
    fun decodeChunkedBodySingleChunk() {
        val encoded = "5\r\nHello\r\n0\r\n\r\n".encodeToByteArray()
        val decoded = PcapDissector.decodeChunkedBody(encoded)
        assertContentEquals("Hello".encodeToByteArray(), decoded)
    }

    @Test
    fun decodeChunkedBodyMultipleChunks() {
        val encoded = "5\r\nHello\r\n7\r\n, World\r\n0\r\n\r\n".encodeToByteArray()
        val decoded = PcapDissector.decodeChunkedBody(encoded)
        assertContentEquals("Hello, World".encodeToByteArray(), decoded)
    }

    @Test
    fun decodeChunkedBodyWithExtensions() {
        // Chunk extensions after ';' should be ignored
        val encoded = "5;ext=val\r\nHello\r\n0\r\n\r\n".encodeToByteArray()
        val decoded = PcapDissector.decodeChunkedBody(encoded)
        assertContentEquals("Hello".encodeToByteArray(), decoded)
    }

    @Test
    fun decodeChunkedBodyHexSizes() {
        // Use hex chunk sizes (0xa = 10)
        val data = "ABCDEFGHIJ"
        val encoded = "a\r\n${data}\r\n0\r\n\r\n".encodeToByteArray()
        val decoded = PcapDissector.decodeChunkedBody(encoded)
        assertContentEquals(data.encodeToByteArray(), decoded)
    }

    @Test
    fun decodeChunkedBodyEmpty() {
        val encoded = "0\r\n\r\n".encodeToByteArray()
        val decoded = PcapDissector.decodeChunkedBody(encoded)
        assertEquals(0, decoded.size)
    }

    @Test
    fun dissectDetectsChunkedTransferEncoding() {
        // Build a minimal PCAP with an Ethernet+IPv4+TCP packet carrying a chunked HTTP response
        val httpResponse = "HTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: application/json\r\n" +
            "\r\n" +
            "5\r\nHello\r\n0\r\n\r\n"
        val pcapBytes = buildEthernetIpv4TcpPcap(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 80,
            dstPort = 12345,
            payload = httpResponse.encodeToByteArray(),
        )
        val buffer = pcapBytes.toCommonByteBuffer()
        val pcap = PcapParser.parse(buffer)
        assertNotNull(pcap)
        assertEquals(1, pcap.packets.size)

        val dissected = PcapDissector.dissect(buffer, pcap.packets[0], pcap.header.network)
        assertEquals("HTTP", dissected.protocol)
        assertTrue(dissected.isChunkedTransferEncoding)
        assertTrue(dissected.httpBodyFileOffset >= 0)
        assertEquals("application/json", dissected.httpContentType)
    }

    @Test
    fun reassembleChunkedBodyAcrossPackets() {
        // Build a PCAP with:
        // 1. HTTP response with chunked headers + first chunk
        // 2. TCP continuation with second chunk + terminal
        val httpResponse = "HTTP/1.1 200 OK\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "5\r\nHello\r\n"
        val continuation = "7\r\n, World\r\n0\r\n\r\n"

        val srcIp = byteArrayOf(10, 0, 0, 1)
        val dstIp = byteArrayOf(10, 0, 0, 2)
        val srcPort = 80
        val dstPort = 12345

        val pcapBytes = buildEthernetIpv4TcpPcap(
            srcIp = srcIp, dstIp = dstIp,
            srcPort = srcPort, dstPort = dstPort,
            payload = httpResponse.encodeToByteArray(),
            additionalPackets = listOf(
                TcpPacketSpec(srcIp, dstIp, srcPort, dstPort, continuation.encodeToByteArray()),
            ),
        )
        val buffer = pcapBytes.toCommonByteBuffer()
        val pcap = PcapParser.parse(buffer)
        assertNotNull(pcap)
        assertEquals(2, pcap.packets.size)

        val dissected = PcapDissector.dissect(buffer, pcap.packets[0], pcap.header.network)
        assertTrue(dissected.isChunkedTransferEncoding)

        val body = PcapDissector.reassembleHttpBody(
            buffer, pcap.packets, 0, pcap.header.network, dissected,
        )
        assertNotNull(body)
        assertContentEquals("Hello, World".encodeToByteArray(), body)
    }

    @Test
    fun reassembleNonChunkedMultiPacketBody() {
        // Non-chunked body split across two packets
        val httpResponse = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/plain\r\n" +
            "\r\n" +
            "Hello"
        val continuation = ", World"

        val srcIp = byteArrayOf(10, 0, 0, 1)
        val dstIp = byteArrayOf(10, 0, 0, 2)
        val srcPort = 80
        val dstPort = 12345

        val pcapBytes = buildEthernetIpv4TcpPcap(
            srcIp = srcIp, dstIp = dstIp,
            srcPort = srcPort, dstPort = dstPort,
            payload = httpResponse.encodeToByteArray(),
            additionalPackets = listOf(
                TcpPacketSpec(srcIp, dstIp, srcPort, dstPort, continuation.encodeToByteArray()),
            ),
        )
        val buffer = pcapBytes.toCommonByteBuffer()
        val pcap = PcapParser.parse(buffer)
        assertNotNull(pcap)

        val dissected = PcapDissector.dissect(buffer, pcap.packets[0], pcap.header.network)
        assertEquals("HTTP", dissected.protocol)

        val body = PcapDissector.reassembleHttpBody(
            buffer, pcap.packets, 0, pcap.header.network, dissected,
        )
        assertNotNull(body)
        assertContentEquals("Hello, World".encodeToByteArray(), body)
    }

    // ---- Test helpers ----

    private data class TcpPacketSpec(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    /**
     * Builds a minimal PCAP file with Ethernet/IPv4/TCP packets.
     */
    private fun buildEthernetIpv4TcpPcap(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
        additionalPackets: List<TcpPacketSpec> = emptyList(),
    ): ByteArray {
        val packets = mutableListOf<ByteArray>()
        packets.add(buildEthernetIpv4TcpPacket(srcIp, dstIp, srcPort, dstPort, payload))
        for (spec in additionalPackets) {
            packets.add(buildEthernetIpv4TcpPacket(spec.srcIp, spec.dstIp, spec.srcPort, spec.dstPort, spec.payload))
        }

        val globalHeader = leGlobalHeader()
        val result = mutableListOf<Byte>()
        result.addAll(globalHeader.toList())
        for (pkt in packets) {
            val pktHeader = lePacketHeader(tsSec = 1000, tsUsec = 0, inclLen = pkt.size.toLong(), origLen = pkt.size.toLong())
            result.addAll(pktHeader.toList())
            result.addAll(pkt.toList())
        }
        return result.toByteArray()
    }

    private fun buildEthernetIpv4TcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        // Ethernet header (14 bytes): dst MAC + src MAC + EtherType 0x0800
        val eth = ByteArray(14)
        // dst/src MACs: all zeros (doesn't matter for dissection)
        eth[12] = 0x08.toByte(); eth[13] = 0x00.toByte() // IPv4

        // IPv4 header (20 bytes, no options)
        val tcpHeaderLen = 20
        val ipTotalLen = 20 + tcpHeaderLen + payload.size
        val ipHeader = ByteArray(20)
        ipHeader[0] = 0x45.toByte() // version=4, IHL=5
        ipHeader[2] = (ipTotalLen shr 8).toByte()
        ipHeader[3] = (ipTotalLen and 0xFF).toByte()
        ipHeader[9] = 6 // TCP
        srcIp.copyInto(ipHeader, 12)
        dstIp.copyInto(ipHeader, 16)

        // TCP header (20 bytes, no options)
        val tcpHeader = ByteArray(tcpHeaderLen)
        tcpHeader[0] = (srcPort shr 8).toByte()
        tcpHeader[1] = (srcPort and 0xFF).toByte()
        tcpHeader[2] = (dstPort shr 8).toByte()
        tcpHeader[3] = (dstPort and 0xFF).toByte()
        // Data offset = 5 (20 bytes), flags = PSH+ACK
        tcpHeader[12] = 0x50.toByte() // data offset = 5 words
        tcpHeader[13] = 0x18.toByte() // PSH + ACK

        return eth + ipHeader + tcpHeader + payload
    }

    private fun leGlobalHeader(network: Long = 1): ByteArray {
        val buf = ByteArray(24)
        buf[0] = 0xD4.toByte(); buf[1] = 0xC3.toByte()
        buf[2] = 0xB2.toByte(); buf[3] = 0xA1.toByte()
        buf[4] = 2; buf[5] = 0; buf[6] = 4; buf[7] = 0
        putU32LE(buf, 16, 65535)
        putU32LE(buf, 20, network)
        return buf
    }

    private fun lePacketHeader(tsSec: Long, tsUsec: Long, inclLen: Long, origLen: Long): ByteArray {
        val buf = ByteArray(16)
        putU32LE(buf, 0, tsSec)
        putU32LE(buf, 4, tsUsec)
        putU32LE(buf, 8, inclLen)
        putU32LE(buf, 12, origLen)
        return buf
    }

    private fun putU32LE(buf: ByteArray, off: Int, value: Long) {
        buf[off] = (value and 0xFF).toByte()
        buf[off + 1] = ((value shr 8) and 0xFF).toByte()
        buf[off + 2] = ((value shr 16) and 0xFF).toByte()
        buf[off + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
