package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Lightweight packet dissector that extracts source/destination addresses,
 * protocol name, and a short info string from captured packet data.
 *
 * Supports Ethernet → IPv4/IPv6 → TCP/UDP/ICMP. Falls back gracefully
 * for unsupported or truncated packets.
 */
object PcapDissector {

    /**
     * Dissects a single packet record. Returns a [PcapDissectedPacket] with
     * whatever information could be extracted from the captured bytes.
     */
    fun dissect(buffer: CommonByteBuffer, packet: PcapPacketRecord, linkType: Long): PcapDissectedPacket {
        val dataOffset = (packet.fileOffset + 16).toInt() // skip 16-byte pcap record header
        val dataLen = packet.inclLen.toInt()
        if (dataLen <= 0 || dataOffset + dataLen > buffer.size) {
            return PcapDissectedPacket("", "", "Unknown", "")
        }

        return when (linkType.toInt()) {
            1 -> dissectEthernet(buffer, dataOffset, dataLen)
            101 -> dissectIp(buffer, dataOffset, dataLen) // Raw IP
            228 -> dissectIpv4(buffer, dataOffset, dataLen) // Raw IPv4
            229 -> dissectIpv6(buffer, dataOffset, dataLen) // Raw IPv6
            0 -> dissectNullLoopback(buffer, dataOffset, dataLen)
            113 -> dissectLinuxSll(buffer, dataOffset, dataLen)
            else -> PcapDissectedPacket("", "", "Link:$linkType", "${packet.inclLen} bytes")
        }
    }

    private fun dissectEthernet(buffer: CommonByteBuffer, offset: Int, len: Int): PcapDissectedPacket {
        if (len < 14) return PcapDissectedPacket("", "", "Ethernet", "truncated")

        val dstMac = readMac(buffer, offset)
        val srcMac = readMac(buffer, offset + 6)
        val etherType = readBigU16(buffer, offset + 12)

        var ipOffset = offset + 14
        var actualEtherType = etherType

        // Handle 802.1Q VLAN tag
        if (actualEtherType == 0x8100 && len >= 18) {
            actualEtherType = readBigU16(buffer, offset + 16)
            ipOffset = offset + 18
        }

        return when (actualEtherType) {
            0x0800 -> dissectIpv4(buffer, ipOffset, len - (ipOffset - offset), srcMac, dstMac)
            0x86DD -> dissectIpv6(buffer, ipOffset, len - (ipOffset - offset), srcMac, dstMac)
            0x0806 -> dissectArp(buffer, ipOffset, len - (ipOffset - offset), srcMac, dstMac)
            else -> PcapDissectedPacket(srcMac, dstMac, "Ethernet", "EtherType: 0x${actualEtherType.toString(16).padStart(4, '0')}")
        }
    }

    private fun dissectNullLoopback(buffer: CommonByteBuffer, offset: Int, len: Int): PcapDissectedPacket {
        if (len < 4) return PcapDissectedPacket("", "", "Loopback", "truncated")
        // BSD null/loopback: 4-byte family in host byte order (usually LE)
        val family = readLittleU32(buffer, offset)
        return when (family) {
            2L -> dissectIpv4(buffer, offset + 4, len - 4)
            24L, 28L, 30L -> dissectIpv6(buffer, offset + 4, len - 4)
            else -> PcapDissectedPacket("", "", "Loopback", "family=$family")
        }
    }

    private fun dissectLinuxSll(buffer: CommonByteBuffer, offset: Int, len: Int): PcapDissectedPacket {
        // Linux cooked capture: 16-byte header
        if (len < 16) return PcapDissectedPacket("", "", "Linux SLL", "truncated")
        val protocol = readBigU16(buffer, offset + 14)
        return when (protocol) {
            0x0800 -> dissectIpv4(buffer, offset + 16, len - 16)
            0x86DD -> dissectIpv6(buffer, offset + 16, len - 16)
            else -> PcapDissectedPacket("", "", "Linux SLL", "proto=0x${protocol.toString(16)}")
        }
    }

    private fun dissectIp(buffer: CommonByteBuffer, offset: Int, len: Int): PcapDissectedPacket {
        if (len < 1) return PcapDissectedPacket("", "", "IP", "truncated")
        val version = (readU8(buffer, offset) shr 4) and 0x0F
        return when (version) {
            4 -> dissectIpv4(buffer, offset, len)
            6 -> dissectIpv6(buffer, offset, len)
            else -> PcapDissectedPacket("", "", "IP", "version=$version")
        }
    }

    private fun dissectIpv4(
        buffer: CommonByteBuffer, offset: Int, len: Int,
        fallbackSrc: String = "", fallbackDst: String = "",
    ): PcapDissectedPacket {
        if (len < 20) return PcapDissectedPacket(fallbackSrc, fallbackDst, "IPv4", "truncated")

        val versionIhl = readU8(buffer, offset)
        val ihl = (versionIhl and 0x0F) * 4
        val totalLen = readBigU16(buffer, offset + 2)
        val protocol = readU8(buffer, offset + 9)
        val srcIp = readIpv4(buffer, offset + 12)
        val dstIp = readIpv4(buffer, offset + 16)

        val transportOffset = offset + ihl
        val transportLen = len - ihl

        return dissectTransport(buffer, transportOffset, transportLen, protocol, srcIp, dstIp, totalLen)
    }

    private fun dissectIpv6(
        buffer: CommonByteBuffer, offset: Int, len: Int,
        fallbackSrc: String = "", fallbackDst: String = "",
    ): PcapDissectedPacket {
        if (len < 40) return PcapDissectedPacket(fallbackSrc, fallbackDst, "IPv6", "truncated")

        val payloadLen = readBigU16(buffer, offset + 4)
        val nextHeader = readU8(buffer, offset + 6)
        val srcIp = readIpv6(buffer, offset + 8)
        val dstIp = readIpv6(buffer, offset + 24)

        val transportOffset = offset + 40
        val transportLen = len - 40

        return dissectTransport(buffer, transportOffset, transportLen, nextHeader, srcIp, dstIp, payloadLen)
    }

    private fun dissectTransport(
        buffer: CommonByteBuffer, offset: Int, len: Int,
        protocol: Int, src: String, dst: String, ipLen: Int,
    ): PcapDissectedPacket {
        return when (protocol) {
            6 -> dissectTcp(buffer, offset, len, src, dst)
            17 -> dissectUdp(buffer, offset, len, src, dst)
            1 -> dissectIcmp(buffer, offset, len, src, dst)
            58 -> PcapDissectedPacket(src, dst, "ICMPv6", "$ipLen bytes")
            else -> PcapDissectedPacket(src, dst, ipProtocolName(protocol), "$ipLen bytes")
        }
    }

    private fun dissectTcp(
        buffer: CommonByteBuffer, offset: Int, len: Int,
        src: String, dst: String,
    ): PcapDissectedPacket {
        if (len < 20) return PcapDissectedPacket(src, dst, "TCP", "truncated")

        val srcPort = readBigU16(buffer, offset)
        val dstPort = readBigU16(buffer, offset + 2)
        val seqNum = readBigU32(buffer, offset + 4)
        val flags = readU8(buffer, offset + 13)
        val dataOff = ((readU8(buffer, offset + 12) shr 4) and 0x0F) * 4

        val payloadOffset = offset + dataOff
        val payloadLen = len - dataOff

        // Try HTTP detection on TCP payload
        if (payloadLen > 0) {
            val http = detectHttp(buffer, payloadOffset, payloadLen)
            if (http != null) {
                return PcapDissectedPacket(
                    src, dst, http.protocol, http.info,
                    httpBodyFileOffset = http.bodyFileOffset,
                    httpBodySize = http.bodySize,
                    httpContentType = http.contentType,
                )
            }
        }

        val flagStr = buildString {
            if (flags and 0x02 != 0) append("SYN ")
            if (flags and 0x10 != 0) append("ACK ")
            if (flags and 0x01 != 0) append("FIN ")
            if (flags and 0x04 != 0) append("RST ")
            if (flags and 0x08 != 0) append("PSH ")
        }.trimEnd()

        val portInfo = "$srcPort → $dstPort"
        val info = if (flagStr.isNotEmpty()) "[$flagStr] Seq=$seqNum $portInfo" else "$portInfo Seq=$seqNum"

        return PcapDissectedPacket(src, dst, "TCP", info)
    }

    private fun dissectUdp(
        buffer: CommonByteBuffer, offset: Int, len: Int,
        src: String, dst: String,
    ): PcapDissectedPacket {
        if (len < 8) return PcapDissectedPacket(src, dst, "UDP", "truncated")

        val srcPort = readBigU16(buffer, offset)
        val dstPort = readBigU16(buffer, offset + 2)
        val udpLen = readBigU16(buffer, offset + 4)

        val info = "$srcPort → $dstPort Len=$udpLen"
        // Detect well-known UDP protocols
        val proto = when {
            srcPort == 53 || dstPort == 53 -> "DNS"
            srcPort == 67 || dstPort == 67 || srcPort == 68 || dstPort == 68 -> "DHCP"
            else -> "UDP"
        }

        return PcapDissectedPacket(src, dst, proto, info)
    }

    private fun dissectIcmp(
        buffer: CommonByteBuffer, offset: Int, len: Int,
        src: String, dst: String,
    ): PcapDissectedPacket {
        if (len < 4) return PcapDissectedPacket(src, dst, "ICMP", "truncated")

        val type = readU8(buffer, offset)
        val code = readU8(buffer, offset + 1)
        val info = when (type) {
            0 -> "Echo Reply"
            3 -> "Destination Unreachable (code=$code)"
            8 -> "Echo Request"
            11 -> "Time Exceeded"
            else -> "type=$type code=$code"
        }

        return PcapDissectedPacket(src, dst, "ICMP", info)
    }

    private fun dissectArp(
        buffer: CommonByteBuffer, offset: Int, len: Int,
        srcMac: String, dstMac: String,
    ): PcapDissectedPacket {
        if (len < 28) return PcapDissectedPacket(srcMac, dstMac, "ARP", "truncated")

        val opcode = readBigU16(buffer, offset + 6)
        val senderIp = readIpv4(buffer, offset + 14)
        val targetIp = readIpv4(buffer, offset + 24)

        val info = when (opcode) {
            1 -> "Who has $targetIp? Tell $senderIp"
            2 -> "$senderIp is at $srcMac"
            else -> "opcode=$opcode"
        }

        return PcapDissectedPacket(srcMac, dstMac, "ARP", info)
    }

    // ---- low-level readers (always big-endian / network byte order) ----

    private fun readU8(buffer: CommonByteBuffer, offset: Int): Int {
        buffer.position(offset)
        return buffer.get().toInt() and 0xFF
    }

    private fun readBigU16(buffer: CommonByteBuffer, offset: Int): Int {
        buffer.position(offset)
        val b0 = buffer.get().toInt() and 0xFF
        val b1 = buffer.get().toInt() and 0xFF
        return (b0 shl 8) or b1
    }

    private fun readBigU32(buffer: CommonByteBuffer, offset: Int): Long {
        buffer.position(offset)
        val b0 = buffer.get().toLong() and 0xFF
        val b1 = buffer.get().toLong() and 0xFF
        val b2 = buffer.get().toLong() and 0xFF
        val b3 = buffer.get().toLong() and 0xFF
        return (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun readLittleU32(buffer: CommonByteBuffer, offset: Int): Long {
        buffer.position(offset)
        val b0 = buffer.get().toLong() and 0xFF
        val b1 = buffer.get().toLong() and 0xFF
        val b2 = buffer.get().toLong() and 0xFF
        val b3 = buffer.get().toLong() and 0xFF
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readMac(buffer: CommonByteBuffer, offset: Int): String {
        buffer.position(offset)
        return buildString {
            for (i in 0 until 6) {
                if (i > 0) append(':')
                append((buffer.get().toInt() and 0xFF).toString(16).padStart(2, '0'))
            }
        }
    }

    private fun readIpv4(buffer: CommonByteBuffer, offset: Int): String {
        buffer.position(offset)
        return buildString {
            for (i in 0 until 4) {
                if (i > 0) append('.')
                append(buffer.get().toInt() and 0xFF)
            }
        }
    }

    private fun readIpv6(buffer: CommonByteBuffer, offset: Int): String {
        buffer.position(offset)
        val groups = IntArray(8)
        for (i in 0 until 8) {
            val b0 = buffer.get().toInt() and 0xFF
            val b1 = buffer.get().toInt() and 0xFF
            groups[i] = (b0 shl 8) or b1
        }
        // Simple compressed format: find longest run of zeros
        var bestStart = -1
        var bestLen = 0
        var curStart = -1
        var curLen = 0
        for (i in 0 until 8) {
            if (groups[i] == 0) {
                if (curStart == -1) curStart = i
                curLen++
                if (curLen > bestLen) { bestStart = curStart; bestLen = curLen }
            } else {
                curStart = -1; curLen = 0
            }
        }
        if (bestLen < 2) bestStart = -1 // only compress runs of 2+

        return buildString {
            var i = 0
            while (i < 8) {
                if (i == bestStart) {
                    append(if (i == 0) "::" else ":")
                    i += bestLen
                } else {
                    if (i > 0 && i - 1 != bestStart + bestLen - 1) append(':')
                    else if (i > 0 && bestStart == -1) append(':')
                    append(groups[i].toString(16))
                    i++
                }
            }
        }
    }

    // ---- HTTP detection ----

    private class HttpInfo(
        val protocol: String,
        val info: String,
        val bodyFileOffset: Long,
        val bodySize: Int,
        val contentType: String?,
    )

    private val HTTP_METHODS = listOf("GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE ")

    private fun detectHttp(buffer: CommonByteBuffer, offset: Int, len: Int): HttpInfo? {
        if (len < 16) return null

        // Read first line to check for HTTP request or response
        val previewLen = minOf(len, 4096)
        val preview = readAscii(buffer, offset, previewLen)

        val firstLineEnd = preview.indexOf('\n')
        if (firstLineEnd < 0) return null
        val firstLine = preview.substring(0, firstLineEnd).trimEnd('\r')

        val isResponse = firstLine.startsWith("HTTP/")
        val isRequest = !isResponse && HTTP_METHODS.any { firstLine.startsWith(it) }

        if (!isRequest && !isResponse) return null

        // Find headers end (\r\n\r\n or \n\n)
        val headersEnd = findHeadersEnd(preview)
        val bodyStart = if (headersEnd >= 0) headersEnd else -1

        // Parse Content-Type from headers
        val contentType = parseHeaderValue(preview, "content-type")

        val bodyFileOffset: Long
        val bodySize: Int
        if (bodyStart >= 0 && bodyStart < len) {
            bodyFileOffset = (offset + bodyStart).toLong()
            bodySize = len - bodyStart
        } else {
            bodyFileOffset = -1
            bodySize = 0
        }

        return if (isResponse) {
            // e.g. "HTTP/1.1 200 OK"
            val statusInfo = buildString {
                append(firstLine)
                if (contentType != null) append("  ($contentType)")
            }
            HttpInfo("HTTP", statusInfo, bodyFileOffset, bodySize, contentType)
        } else {
            // e.g. "GET /index.html HTTP/1.1"
            HttpInfo("HTTP", firstLine, -1, 0, null)
        }
    }

    private fun findHeadersEnd(text: String): Int {
        val crlf = text.indexOf("\r\n\r\n")
        if (crlf >= 0) return crlf + 4
        val lf = text.indexOf("\n\n")
        if (lf >= 0) return lf + 2
        return -1
    }

    private fun parseHeaderValue(headers: String, headerName: String): String? {
        var i = 0
        while (i < headers.length) {
            val lineEnd = headers.indexOf('\n', i)
            if (lineEnd < 0) break
            val line = headers.substring(i, lineEnd).trimEnd('\r')
            if (line.isEmpty()) break // end of headers
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim()
                if (name.equals(headerName, ignoreCase = true)) {
                    return line.substring(colon + 1).trim()
                }
            }
            i = lineEnd + 1
        }
        return null
    }

    private fun readAscii(buffer: CommonByteBuffer, offset: Int, len: Int): String {
        buffer.position(offset)
        return buildString(len) {
            for (i in 0 until len) {
                val b = buffer.get().toInt() and 0xFF
                append(if (b in 0x20..0x7E || b == 0x0D || b == 0x0A || b == 0x09) b.toChar() else '.')
            }
        }
    }

    private fun ipProtocolName(protocol: Int): String = when (protocol) {
        1 -> "ICMP"
        2 -> "IGMP"
        6 -> "TCP"
        17 -> "UDP"
        41 -> "IPv6-in-IPv4"
        47 -> "GRE"
        50 -> "ESP"
        51 -> "AH"
        58 -> "ICMPv6"
        89 -> "OSPF"
        132 -> "SCTP"
        else -> "IP Proto $protocol"
    }
}

/**
 * Result of dissecting a single packet's captured data.
 */
data class PcapDissectedPacket(
    /** Source address (IP, MAC, or empty if unknown). */
    val source: String,
    /** Destination address (IP, MAC, or empty if unknown). */
    val destination: String,
    /** Protocol name (e.g. "TCP", "UDP", "ARP", "ICMP"). */
    val protocol: String,
    /** Short info string similar to Wireshark's Info column. */
    val info: String,
    /**
     * Absolute byte offset of the HTTP body within the PCAP file,
     * or `-1` when this packet does not carry an HTTP body.
     */
    val httpBodyFileOffset: Long = -1,
    /** Size of the HTTP body in bytes, or `0` when not applicable. */
    val httpBodySize: Int = 0,
    /** HTTP Content-Type header value, or `null` when not available. */
    val httpContentType: String? = null,
)
