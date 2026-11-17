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
     * Lightweight protocol identification that navigates link/IP/TCP headers
     * without building address strings, flag descriptions, or info text.
     * Returns a short protocol name (e.g. "TCP", "UDP", "TLS", "HTTP", "DNS")
     * or `null` when the packet cannot be parsed at all.
     */
    fun protocolInfo(buffer: CommonByteBuffer, packet: PcapPacketRecord, linkType: Long): String? {
        val dataOffset = packet.dataOffset.toInt()
        val dataLen = packet.inclLen.toInt()
        if (dataLen <= 0 || dataOffset + dataLen > buffer.size) return null

        return when (linkType.toInt()) {
            1 -> protocolEthernet(buffer, dataOffset, dataLen)
            101 -> protocolIp(buffer, dataOffset, dataLen)
            228 -> protocolIpv4(buffer, dataOffset, dataLen)
            229 -> protocolIpv6(buffer, dataOffset, dataLen)
            0 -> protocolNullLoopback(buffer, dataOffset, dataLen)
            113 -> protocolLinuxSll(buffer, dataOffset, dataLen)
            else -> "Link:$linkType"
        }
    }

    private fun protocolEthernet(buffer: CommonByteBuffer, offset: Int, len: Int): String? {
        if (len < 14) return "Ethernet"
        var etherType = readBigU16(buffer, offset + 12)
        var ipOffset = offset + 14
        if (etherType == 0x8100 && len >= 18) {
            etherType = readBigU16(buffer, offset + 16)
            ipOffset = offset + 18
        }
        return when (etherType) {
            0x0800 -> protocolIpv4(buffer, ipOffset, len - (ipOffset - offset))
            0x86DD -> protocolIpv6(buffer, ipOffset, len - (ipOffset - offset))
            0x0806 -> "ARP"
            else -> "Ethernet"
        }
    }

    private fun protocolNullLoopback(buffer: CommonByteBuffer, offset: Int, len: Int): String? {
        if (len < 4) return "Loopback"
        val family = readLittleU32(buffer, offset)
        return when (family) {
            2L -> protocolIpv4(buffer, offset + 4, len - 4)
            24L, 28L, 30L -> protocolIpv6(buffer, offset + 4, len - 4)
            else -> "Loopback"
        }
    }

    private fun protocolLinuxSll(buffer: CommonByteBuffer, offset: Int, len: Int): String? {
        if (len < 16) return "Linux SLL"
        val protocol = readBigU16(buffer, offset + 14)
        return when (protocol) {
            0x0800 -> protocolIpv4(buffer, offset + 16, len - 16)
            0x86DD -> protocolIpv6(buffer, offset + 16, len - 16)
            else -> "Linux SLL"
        }
    }

    private fun protocolIp(buffer: CommonByteBuffer, offset: Int, len: Int): String? {
        if (len < 1) return null
        val version = (readU8(buffer, offset) shr 4) and 0x0F
        return when (version) {
            4 -> protocolIpv4(buffer, offset, len)
            6 -> protocolIpv6(buffer, offset, len)
            else -> "IP"
        }
    }

    private fun protocolIpv4(buffer: CommonByteBuffer, offset: Int, len: Int): String? {
        if (len < 20) return "IPv4"
        val ihl = (readU8(buffer, offset) and 0x0F) * 4
        val protocol = readU8(buffer, offset + 9)
        return protocolTransport(buffer, offset + ihl, len - ihl, protocol)
    }

    private fun protocolIpv6(buffer: CommonByteBuffer, offset: Int, len: Int): String? {
        if (len < 40) return "IPv6"
        val nextHeader = readU8(buffer, offset + 6)
        return protocolTransport(buffer, offset + 40, len - 40, nextHeader)
    }

    private fun protocolTransport(buffer: CommonByteBuffer, offset: Int, len: Int, protocol: Int): String {
        return when (protocol) {
            6 -> protocolTcp(buffer, offset, len)
            17 -> protocolUdp(buffer, offset, len)
            1 -> "ICMP"
            58 -> "ICMPv6"
            else -> ipProtocolName(protocol)
        }
    }

    private fun protocolTcp(buffer: CommonByteBuffer, offset: Int, len: Int): String {
        if (len < 20) return "TCP"
        val dataOff = ((readU8(buffer, offset + 12) shr 4) and 0x0F) * 4
        val payloadOffset = offset + dataOff
        val payloadLen = len - dataOff
        if (payloadLen > 0) {
            if (isHttpPayload(buffer, payloadOffset, payloadLen)) return "HTTP"
            if (TlsRecordParser.isTlsRecord(buffer, payloadOffset, payloadLen)) return "TLS"
        }
        return "TCP"
    }

    private fun protocolUdp(buffer: CommonByteBuffer, offset: Int, len: Int): String {
        if (len < 8) return "UDP"
        val srcPort = readBigU16(buffer, offset)
        val dstPort = readBigU16(buffer, offset + 2)
        return when {
            srcPort == 53 || dstPort == 53 -> "DNS"
            srcPort == 67 || dstPort == 67 || srcPort == 68 || dstPort == 68 -> "DHCP"
            else -> "UDP"
        }
    }

    /**
     * Dissects a single packet record, attempting TLS decryption when a
     * [decryptionContext] is provided. Returns a [PcapDissectedPacket] with
     * whatever information could be extracted, including decrypted plaintext
     * when decryption succeeds.
     */
    fun dissect(
        buffer: CommonByteBuffer,
        packet: PcapPacketRecord,
        linkType: Long,
        decryptionContext: TlsDecryptionContext?,
    ): PcapDissectedPacket {
        val base = dissect(buffer, packet, linkType)
        if (decryptionContext == null) return base
        // Only attempt decryption for TLS Application Data packets
        if (!base.protocol.startsWith("TLS") || !base.info.contains("Application Data")) return base
        val decrypted = try {
            decryptionContext.tryDecrypt(buffer, packet, linkType)
        } catch (_: Exception) {
            null
        }
        if (decrypted == null) return base
        // Once a record opens, describe what it actually carries rather than the
        // envelope it arrived in - the same thing Wireshark does when a key log
        // turns an "Application Data" row into "GET / HTTP/1.0".
        val http = detectHttpInPlaintext(decrypted)
        return PcapDissectedPacket(
            source = base.source,
            destination = base.destination,
            protocol = http?.protocol ?: base.protocol,
            info = http?.info ?: base.info,
            // The plaintext exists only in memory, so there is no file range to
            // open; [decryptedData] is how consumers reach the body.
            httpBodyFileOffset = base.httpBodyFileOffset,
            httpBodySize = base.httpBodySize,
            httpContentType = http?.contentType ?: base.httpContentType,
            isChunkedTransferEncoding = http?.isChunkedTransferEncoding
                ?: base.isChunkedTransferEncoding,
            httpContentEncoding = http?.contentEncoding ?: base.httpContentEncoding,
            decryptedData = decrypted,
        )
    }

    /**
     * Dissects a single packet record. Returns a [PcapDissectedPacket] with
     * whatever information could be extracted from the captured bytes.
     */
    fun dissect(buffer: CommonByteBuffer, packet: PcapPacketRecord, linkType: Long): PcapDissectedPacket {
        val dataOffset = packet.dataOffset.toInt()
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
                    isChunkedTransferEncoding = http.isChunkedTransferEncoding,
                    httpContentEncoding = http.contentEncoding,
                )
            }

            // Try TLS detection on TCP payload
            val tls = TlsRecordParser.dissect(buffer, payloadOffset, payloadLen)
            if (tls != null) {
                return PcapDissectedPacket(src, dst, tls.protocol, tls.info)
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

    /**
     * Reassembles the full HTTP response body for the packet at [packetIndex].
     *
     * For non-chunked responses whose body fits in a single packet, this simply
     * returns the body bytes from that packet. For chunked transfer-encoded
     * responses (or bodies that span multiple TCP segments), this collects TCP
     * payloads from subsequent packets in the same stream and, when chunked,
     * decodes the chunked encoding to produce the raw body.
     *
     * Returns `null` if the packet is not an HTTP response with a body.
     */
    fun reassembleHttpBody(
        buffer: CommonByteBuffer,
        packets: List<PcapPacketRecord>,
        packetIndex: Int,
        linkType: Long,
        dissected: PcapDissectedPacket,
    ): ByteArray? {
        if (dissected.httpBodyFileOffset < 0 || dissected.httpBodySize <= 0) return null

        // Extract TCP stream identity from the response packet
        val streamId = extractTcpStreamId(buffer, packets[packetIndex], linkType) ?: return null

        // Collect the body bytes from the initial response packet
        val rawChunks = mutableListOf<ByteArray>()
        rawChunks.add(buffer.slice(dissected.httpBodyFileOffset.toInt(), dissected.httpBodySize).toByteArray())

        // Walk subsequent packets in the same TCP stream (same direction:
        // server→client) and collect their TCP payloads until we see a new
        // HTTP response, a FIN/RST, or run out of packets.
        for (i in (packetIndex + 1) until packets.size) {
            val pkt = packets[i]
            val tcp = extractTcpPayloadInfo(buffer, pkt, linkType) ?: continue
            // Same stream, same direction (server → client)
            if (tcp.srcIp != streamId.srcIp || tcp.dstIp != streamId.dstIp ||
                tcp.srcPort != streamId.srcPort || tcp.dstPort != streamId.dstPort) continue

            // Stop on FIN or RST
            if (tcp.fin || tcp.rst) break

            if (tcp.payloadLen <= 0) continue

            // Stop if this payload starts a new HTTP response
            val previewLen = minOf(tcp.payloadLen, 16)
            val preview = readAscii(buffer, tcp.payloadOffset, previewLen)
            if (preview.startsWith("HTTP/")) break

            rawChunks.add(buffer.slice(tcp.payloadOffset, tcp.payloadLen).toByteArray())
        }

        val concatenated = concatenateByteArrays(rawChunks)

        return if (dissected.isChunkedTransferEncoding) {
            decodeChunkedBody(concatenated)
        } else {
            concatenated
        }
    }

    /**
     * The TCP endpoints of [packet], or `null` when it is not a TCP segment
     * this dissector can reach. Replay uses the destination as the server a
     * request was sent to.
     */
    fun tcpEndpoints(buffer: CommonByteBuffer, packet: PcapPacketRecord, linkType: Long): TcpEndpoints? {
        val info = extractTcpPayloadInfo(buffer, packet, linkType) ?: return null
        return TcpEndpoints(info.srcIp, info.srcPort, info.dstIp, info.dstPort)
    }

    /**
     * The raw bytes of the cleartext HTTP request that starts in the packet at
     * [packetIndex] - request line, headers and body - or `null` when that
     * packet does not start one.
     *
     * Later segments of the same stream and direction are placed by TCP
     * sequence number until the body announced by `Content-Length` or chunked
     * framing is complete or the stream closes: retransmitted bytes are
     * dropped, overlaps trimmed, and segments that arrive ahead of a gap wait
     * for it to fill. What comes back stops at the first gap the capture never
     * filled; [HttpReplay.parseRequest] reports that as an incomplete body.
     */
    fun reassembleHttpRequest(
        buffer: CommonByteBuffer,
        packets: List<PcapPacketRecord>,
        packetIndex: Int,
        linkType: Long,
    ): ByteArray? {
        val first = extractTcpPayloadInfo(buffer, packets[packetIndex], linkType) ?: return null
        if (first.payloadLen <= 0 || !isHttpPayload(buffer, first.payloadOffset, first.payloadLen)) return null
        val head = readAscii(buffer, first.payloadOffset, minOf(first.payloadLen, HTTP_PREVIEW_LIMIT))
        if (head.startsWith("HTTP/") || HTTP_METHODS.none { head.startsWith(it) }) return null

        val assembly = HttpRequestAssembly()
        assembly.append(buffer.slice(first.payloadOffset, first.payloadLen).toByteArray())
        var nextSeq = (first.seq + first.payloadLen) and SEQ_MASK
        // Segments past a gap, by sequence number, until the gap is filled.
        val ahead = mutableListOf<Pair<Long, ByteArray>>()
        var aheadBytes = 0L

        /** Appends what of [bytes], starting at [seq], lies at or past [nextSeq]; `false` when it starts past it. */
        fun place(seq: Long, bytes: ByteArray): Boolean {
            val offset = seqDelta(seq, nextSeq)
            if (offset > 0) return false
            val skip = -offset
            if (skip < bytes.size) {
                assembly.append(if (skip == 0L) bytes else bytes.copyOfRange(skip.toInt(), bytes.size))
                nextSeq = (nextSeq + bytes.size - skip) and SEQ_MASK
            }
            return true
        }

        var closed = first.fin || first.rst
        var i = packetIndex + 1
        while (!closed && i < packets.size && !assembly.isComplete && assembly.size <= MAX_REPLAY_REQUEST_BYTES) {
            val tcp = extractTcpPayloadInfo(buffer, packets[i++], linkType) ?: continue
            if (tcp.srcIp != first.srcIp || tcp.dstIp != first.dstIp ||
                tcp.srcPort != first.srcPort || tcp.dstPort != first.dstPort) continue
            closed = tcp.fin || tcp.rst
            if (tcp.payloadLen <= 0) continue
            val bytes = buffer.slice(tcp.payloadOffset, tcp.payloadLen).toByteArray()
            if (!place(tcp.seq, bytes)) {
                if (aheadBytes + bytes.size > MAX_REPLAY_REQUEST_BYTES) break
                ahead += tcp.seq to bytes
                aheadBytes += bytes.size
                continue
            }
            // The segment may have filled a gap that waiting segments were behind.
            do {
                val placed = ahead.indexOfFirst { (seq, held) -> place(seq, held) }
                if (placed >= 0) aheadBytes -= ahead.removeAt(placed).second.size
            } while (placed >= 0)
        }
        return assembly.toByteArray()
    }

    /** [seq] minus [base] in 32-bit sequence space, negative when [seq] comes first. */
    private fun seqDelta(seq: Long, base: Long): Long = ((seq - base) and SEQ_MASK).toInt().toLong()

    private const val SEQ_MASK = 0xFFFF_FFFFL

    /** Upper bound on a reassembled request, so a runaway upload cannot fill the heap. */
    private const val MAX_REPLAY_REQUEST_BYTES = 64 * 1024 * 1024

    /**
     * The HTTP body carried by a decrypted TLS record, or `null` when the
     * plaintext is not an HTTP response with a body.
     *
     * Decrypted bytes were never in the capture file, so
     * [PcapDissectedPacket.httpBodyFileOffset] cannot point at them and
     * [reassembleHttpBody] has no range to slice; this is the plaintext
     * equivalent. Only what this one record holds comes back - a body split
     * over several records is not reassembled, and neither is a request body,
     * matching what the cleartext path offers.
     */
    fun httpBodyInPlaintext(plaintext: ByteArray): PlaintextHttpBody? {
        val http = detectHttpInPlaintext(plaintext) ?: return null
        val start = http.bodyStart
        if (start < 0 || start >= plaintext.size) return null
        val raw = plaintext.copyOfRange(start, plaintext.size)
        val body = if (http.isChunkedTransferEncoding) decodeChunkedBody(raw) else raw
        if (body.isEmpty()) return null
        return PlaintextHttpBody(body, http.contentType, http.contentEncoding)
    }

    /**
     * Decodes an HTTP chunked transfer-encoded body.
     * Format: `<hex-size>\r\n<data>\r\n` repeated, terminated by `0\r\n\r\n`.
     */
    internal fun decodeChunkedBody(encoded: ByteArray): ByteArray {
        val result = mutableListOf<ByteArray>()
        var pos = 0
        while (pos < encoded.size) {
            // Find the end of the chunk-size line
            val lineEnd = findCrLf(encoded, pos)
            if (lineEnd < 0) break

            // Parse hex chunk size (ignore chunk extensions after ';')
            val sizeLine = encoded.decodeToString(pos, lineEnd)
            val sizeStr = sizeLine.substringBefore(';').trim()
            if (sizeStr.isEmpty()) break
            val chunkSize = sizeStr.toLongOrNull(16) ?: break
            if (chunkSize == 0L) break // terminal chunk

            pos = lineEnd + 2 // skip \r\n after size line
            val end = minOf(pos + chunkSize.toInt(), encoded.size)
            if (pos < end) {
                result.add(encoded.copyOfRange(pos, end))
            }
            pos = end + 2 // skip \r\n after chunk data
        }
        return concatenateByteArrays(result)
    }

    private fun findCrLf(data: ByteArray, from: Int): Int {
        var i = from
        while (i < data.size - 1) {
            if (data[i] == 0x0D.toByte() && data[i + 1] == 0x0A.toByte()) return i
            i++
        }
        return -1
    }

    internal fun concatenateByteArrays(arrays: List<ByteArray>): ByteArray {
        if (arrays.size == 1) return arrays[0]
        val total = arrays.sumOf { it.size }
        val result = ByteArray(total)
        var offset = 0
        for (arr in arrays) {
            arr.copyInto(result, offset)
            offset += arr.size
        }
        return result
    }

    private class TcpStreamId(
        val srcIp: String, val dstIp: String,
        val srcPort: Int, val dstPort: Int,
    )

    private class TcpPayloadInfo(
        val srcIp: String, val dstIp: String,
        val srcPort: Int, val dstPort: Int,
        val payloadOffset: Int, val payloadLen: Int,
        val fin: Boolean, val rst: Boolean,
        val seq: Long = 0,
    )

    private fun extractTcpStreamId(
        buffer: CommonByteBuffer, packet: PcapPacketRecord, linkType: Long,
    ): TcpStreamId? {
        val info = extractTcpPayloadInfo(buffer, packet, linkType) ?: return null
        return TcpStreamId(info.srcIp, info.dstIp, info.srcPort, info.dstPort)
    }

    /**
     * Extracts TCP-level metadata (IPs, ports, payload location, flags) from
     * a packet without performing HTTP/TLS detection.
     */
    private fun extractTcpPayloadInfo(
        buffer: CommonByteBuffer, packet: PcapPacketRecord, linkType: Long,
    ): TcpPayloadInfo? {
        val dataOffset = packet.dataOffset.toInt()
        val dataLen = packet.inclLen.toInt()
        if (dataLen <= 0 || dataOffset + dataLen > buffer.size) return null

        // Navigate through link layer to IP layer
        val ipResult = when (linkType.toInt()) {
            1 -> findIpInEthernet(buffer, dataOffset, dataLen)
            101 -> findIpRaw(buffer, dataOffset, dataLen)
            228 -> IpLocation(dataOffset, dataLen, isV6 = false)
            229 -> IpLocation(dataOffset, dataLen, isV6 = true)
            0 -> findIpInNullLoopback(buffer, dataOffset, dataLen)
            113 -> findIpInLinuxSll(buffer, dataOffset, dataLen)
            else -> null
        } ?: return null

        // Parse IP header to get addresses and find TCP
        return if (ipResult.isV6) {
            extractTcpFromIpv6(buffer, ipResult.offset, ipResult.len)
        } else {
            extractTcpFromIpv4(buffer, ipResult.offset, ipResult.len)
        }
    }

    private class IpLocation(val offset: Int, val len: Int, val isV6: Boolean)

    private fun findIpInEthernet(buffer: CommonByteBuffer, offset: Int, len: Int): IpLocation? {
        if (len < 14) return null
        var etherType = readBigU16(buffer, offset + 12)
        var ipOffset = offset + 14
        if (etherType == 0x8100 && len >= 18) {
            etherType = readBigU16(buffer, offset + 16)
            ipOffset = offset + 18
        }
        return when (etherType) {
            0x0800 -> IpLocation(ipOffset, len - (ipOffset - offset), isV6 = false)
            0x86DD -> IpLocation(ipOffset, len - (ipOffset - offset), isV6 = true)
            else -> null
        }
    }

    private fun findIpRaw(buffer: CommonByteBuffer, offset: Int, len: Int): IpLocation? {
        if (len < 1) return null
        val version = (readU8(buffer, offset) shr 4) and 0x0F
        return when (version) {
            4 -> IpLocation(offset, len, isV6 = false)
            6 -> IpLocation(offset, len, isV6 = true)
            else -> null
        }
    }

    private fun findIpInNullLoopback(buffer: CommonByteBuffer, offset: Int, len: Int): IpLocation? {
        if (len < 4) return null
        val family = readLittleU32(buffer, offset)
        return when (family) {
            2L -> IpLocation(offset + 4, len - 4, isV6 = false)
            24L, 28L, 30L -> IpLocation(offset + 4, len - 4, isV6 = true)
            else -> null
        }
    }

    private fun findIpInLinuxSll(buffer: CommonByteBuffer, offset: Int, len: Int): IpLocation? {
        if (len < 16) return null
        val protocol = readBigU16(buffer, offset + 14)
        return when (protocol) {
            0x0800 -> IpLocation(offset + 16, len - 16, isV6 = false)
            0x86DD -> IpLocation(offset + 16, len - 16, isV6 = true)
            else -> null
        }
    }

    private fun extractTcpFromIpv4(buffer: CommonByteBuffer, offset: Int, len: Int): TcpPayloadInfo? {
        if (len < 20) return null
        val versionIhl = readU8(buffer, offset)
        val ihl = (versionIhl and 0x0F) * 4
        val protocol = readU8(buffer, offset + 9)
        if (protocol != 6) return null // not TCP
        val srcIp = readIpv4(buffer, offset + 12)
        val dstIp = readIpv4(buffer, offset + 16)
        val tcpOffset = offset + ihl
        val tcpLen = len - ihl
        return extractTcpFields(buffer, tcpOffset, tcpLen, srcIp, dstIp)
    }

    private fun extractTcpFromIpv6(buffer: CommonByteBuffer, offset: Int, len: Int): TcpPayloadInfo? {
        if (len < 40) return null
        val nextHeader = readU8(buffer, offset + 6)
        if (nextHeader != 6) return null // not TCP
        val srcIp = readIpv6(buffer, offset + 8)
        val dstIp = readIpv6(buffer, offset + 24)
        val tcpOffset = offset + 40
        val tcpLen = len - 40
        return extractTcpFields(buffer, tcpOffset, tcpLen, srcIp, dstIp)
    }

    private fun extractTcpFields(
        buffer: CommonByteBuffer, offset: Int, len: Int,
        srcIp: String, dstIp: String,
    ): TcpPayloadInfo? {
        if (len < 20) return null
        val srcPort = readBigU16(buffer, offset)
        val dstPort = readBigU16(buffer, offset + 2)
        val flags = readU8(buffer, offset + 13)
        val dataOff = ((readU8(buffer, offset + 12) shr 4) and 0x0F) * 4
        val payloadOffset = offset + dataOff
        val payloadLen = len - dataOff
        return TcpPayloadInfo(
            srcIp, dstIp, srcPort, dstPort,
            payloadOffset, payloadLen,
            fin = flags and 0x01 != 0,
            rst = flags and 0x04 != 0,
            seq = readBigU32(buffer, offset + 4),
        )
    }

    // ---- HTTP detection ----

    private class HttpInfo(
        val protocol: String,
        val info: String,
        val bodyFileOffset: Long,
        val bodySize: Int,
        val contentType: String?,
        val isChunkedTransferEncoding: Boolean = false,
        val contentEncoding: String? = null,
        /**
         * Where the body starts within the payload, or `-1` when there is none.
         * Payload-relative rather than file-relative, so it is also the answer
         * for plaintext that was never in the file.
         */
        val bodyStart: Int = -1,
    )

    /**
     * Quick check whether the TCP payload starts with an HTTP method or response.
     * Does not allocate strings — reads only the first few bytes.
     */
    private fun isHttpPayload(buffer: CommonByteBuffer, offset: Int, len: Int): Boolean {
        if (len < 4) return false
        // Check for "HTTP/" response
        if (matchAscii(buffer, offset, len, 'H', 'T', 'T', 'P')) return true
        // Check common request methods by first two bytes
        val b0 = readU8(buffer, offset)
        val b1 = readU8(buffer, offset + 1)
        return when {
            b0 == 0x47 && b1 == 0x45 -> true // GE(T)
            b0 == 0x50 && (b1 == 0x4F || b1 == 0x55 || b1 == 0x41) -> true // PO(ST), PU(T), PA(TCH)
            b0 == 0x44 && b1 == 0x45 -> true // DE(LETE)
            b0 == 0x48 && b1 == 0x45 -> true // HE(AD)
            b0 == 0x4F && b1 == 0x50 -> true // OP(TIONS)
            b0 == 0x43 && b1 == 0x4F -> true // CO(NNECT)
            b0 == 0x54 && b1 == 0x52 -> true // TR(ACE)
            else -> false
        }
    }

    private fun matchAscii(buffer: CommonByteBuffer, offset: Int, len: Int, vararg chars: Char): Boolean {
        if (len < chars.size) return false
        for (i in chars.indices) {
            if (readU8(buffer, offset + i) != chars[i].code) return false
        }
        return true
    }

    private val HTTP_METHODS = listOf("GET ", "POST ", "PUT ", "DELETE ", "HEAD ", "OPTIONS ", "PATCH ", "CONNECT ", "TRACE ")

    /** Shortest payload that can hold a request line and a blank line. */
    private const val MIN_HTTP_PAYLOAD = 16

    /** How much of a payload is read as text to find the end of the headers. */
    private const val HTTP_PREVIEW_LIMIT = 4096

    private fun detectHttp(buffer: CommonByteBuffer, offset: Int, len: Int): HttpInfo? {
        if (len < MIN_HTTP_PAYLOAD) return null
        val preview = readAscii(buffer, offset, minOf(len, HTTP_PREVIEW_LIMIT))
        return parseHttpHead(preview, len, offset.toLong())
    }

    /**
     * The same detection over decrypted TLS plaintext. Those bytes were never in
     * the capture file, so no body offset can be reported for them.
     */
    private fun detectHttpInPlaintext(plaintext: ByteArray): HttpInfo? {
        if (plaintext.size < MIN_HTTP_PAYLOAD) return null
        val preview = asciiPreview(plaintext, minOf(plaintext.size, HTTP_PREVIEW_LIMIT))
        return parseHttpHead(preview, plaintext.size, payloadFileOffset = -1)
    }

    /**
     * Parses an HTTP request or response head out of an ASCII [preview] of a
     * [len]-byte payload. [payloadFileOffset] is where that payload starts in
     * the capture file, or `-1` when it has no place there.
     */
    private fun parseHttpHead(preview: String, len: Int, payloadFileOffset: Long): HttpInfo? {
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
        val transferEncoding = parseHeaderValue(preview, "transfer-encoding")
        val isChunked = transferEncoding?.lowercase()?.contains("chunked") == true
        val contentEncoding = parseHeaderValue(preview, "content-encoding")

        val hasBody = bodyStart in 0 until len
        val bodyFileOffset: Long
        val bodySize: Int
        if (hasBody && payloadFileOffset >= 0) {
            bodyFileOffset = payloadFileOffset + bodyStart
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
            HttpInfo(
                "HTTP", statusInfo, bodyFileOffset, bodySize, contentType, isChunked, contentEncoding,
                bodyStart = if (hasBody) bodyStart else -1,
            )
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
            for (i in 0 until len) append(asciiChar(buffer.get().toInt() and 0xFF))
        }
    }

    private fun asciiPreview(bytes: ByteArray, len: Int): String =
        buildString(len) {
            for (i in 0 until len) append(asciiChar(bytes[i].toInt() and 0xFF))
        }

    /** Printable characters and the whitespace HTTP heads are made of; the rest becomes a dot. */
    private fun asciiChar(b: Int): Char =
        if (b in 0x20..0x7E || b == 0x0D || b == 0x0A || b == 0x09) b.toChar() else '.'


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

/** Addresses and ports of a TCP segment, as the dissector prints them. */
data class TcpEndpoints(
    val sourceAddress: String,
    val sourcePort: Int,
    val destinationAddress: String,
    val destinationPort: Int,
)

/**
 * An HTTP body read straight out of decrypted TLS plaintext, with the header
 * values a consumer needs to decompress it and name the file it opens as.
 */
class PlaintextHttpBody(
    /** Body bytes, de-chunked when the head said `Transfer-Encoding: chunked`, still compressed. */
    val bytes: ByteArray,
    /** HTTP Content-Type header value, or `null` when the head carried none. */
    val contentType: String?,
    /** HTTP Content-Encoding header value (e.g. "gzip"), or `null` when not compressed. */
    val contentEncoding: String?,
)

/**
 * Result of dissecting a single packet's captured data.
 */
data class PcapDissectedPacket(
    /** Source address (IP, MAC, or empty if unknown). */
    val source: String,
    /** Destination address (IP, MAC, or empty if unknown). */
    val destination: String,
    /** Protocol name (e.g. "TCP", "UDP", "ARP", "ICMP", "TLSv1.2"). */
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
    /** `true` when the HTTP response uses chunked transfer encoding. */
    val isChunkedTransferEncoding: Boolean = false,
    /** HTTP Content-Encoding header value (e.g. "gzip", "deflate", "br"), or `null` when not compressed. */
    val httpContentEncoding: String? = null,
    /**
     * Decrypted TLS application data plaintext, or `null` when the packet
     * is not encrypted or decryption was not possible (no key / unsupported cipher).
     */
    val decryptedData: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcapDissectedPacket) return false
        return source == other.source && destination == other.destination &&
            protocol == other.protocol && info == other.info &&
            httpBodyFileOffset == other.httpBodyFileOffset &&
            httpBodySize == other.httpBodySize &&
            httpContentType == other.httpContentType &&
            isChunkedTransferEncoding == other.isChunkedTransferEncoding &&
            httpContentEncoding == other.httpContentEncoding &&
            (decryptedData contentEquals other.decryptedData)
    }

    override fun hashCode(): Int {
        var h = source.hashCode()
        h = 31 * h + destination.hashCode()
        h = 31 * h + protocol.hashCode()
        h = 31 * h + info.hashCode()
        h = 31 * h + httpBodyFileOffset.hashCode()
        h = 31 * h + httpBodySize
        h = 31 * h + (httpContentType?.hashCode() ?: 0)
        h = 31 * h + isChunkedTransferEncoding.hashCode()
        h = 31 * h + (httpContentEncoding?.hashCode() ?: 0)
        h = 31 * h + (decryptedData?.contentHashCode() ?: 0)
        return h
    }
}
