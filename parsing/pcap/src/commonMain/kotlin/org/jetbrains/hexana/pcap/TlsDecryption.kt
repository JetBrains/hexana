package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Represents a TCP stream identified by its 4-tuple (src IP, dst IP, src port, dst port).
 * The [canonical] form ensures the same stream is identified regardless of direction.
 */
data class TcpStreamKey(
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int,
    val dstPort: Int,
) {
    /** Canonical form: lower IP:port first, so both directions map to the same key. */
    val canonical: TcpStreamKey
        get() {
            val srcKey = "$srcIp:$srcPort"
            val dstKey = "$dstIp:$dstPort"
            return if (srcKey <= dstKey) this else TcpStreamKey(dstIp, srcIp, dstPort, srcPort)
        }
}

/**
 * Extracts TCP stream metadata from a packet for TLS session tracking.
 * Returns `null` if the packet is not TCP or is truncated.
 */
fun extractTcpInfo(
    buffer: CommonByteBuffer,
    packet: PcapPacketRecord,
    linkType: Long,
): TcpStreamInfo? {
    val dataOffset = packet.dataOffset.toInt()
    val dataLen = packet.inclLen.toInt()
    if (dataLen <= 0 || dataOffset + dataLen > buffer.size) return null

    // Navigate through link layer to IP layer
    val ipInfo = when (linkType.toInt()) {
        1 -> extractIpFromEthernet(buffer, dataOffset, dataLen)
        101 -> extractIpDirect(buffer, dataOffset, dataLen)
        228 -> extractIpv4Info(buffer, dataOffset, dataLen)
        229 -> extractIpv6Info(buffer, dataOffset, dataLen)
        0 -> extractIpFromLoopback(buffer, dataOffset, dataLen)
        113 -> extractIpFromSll(buffer, dataOffset, dataLen)
        else -> null
    } ?: return null

    // Check it's TCP (protocol 6)
    if (ipInfo.protocol != 6) return null
    val tcpOffset = ipInfo.transportOffset
    val tcpLen = ipInfo.transportLen
    if (tcpLen < 20) return null

    val srcPort = readBigU16(buffer, tcpOffset)
    val dstPort = readBigU16(buffer, tcpOffset + 2)
    val dataOff = ((readU8(buffer, tcpOffset + 12) shr 4) and 0x0F) * 4
    val payloadOffset = tcpOffset + dataOff
    val payloadLen = tcpLen - dataOff

    return TcpStreamInfo(
        streamKey = TcpStreamKey(ipInfo.srcIp, ipInfo.dstIp, srcPort, dstPort),
        payloadOffset = payloadOffset,
        payloadLen = payloadLen,
    )
}

/**
 * TCP stream info extracted from a packet.
 */
data class TcpStreamInfo(
    val streamKey: TcpStreamKey,
    val payloadOffset: Int,
    val payloadLen: Int,
)

/**
 * Scans all packets in a PCAP file to find TLS Client Hello messages and
 * extract their client randoms. Returns a map from canonical [TcpStreamKey]
 * to the hex-encoded client random for that TLS session.
 */
fun buildTlsSessionMap(
    buffer: CommonByteBuffer,
    packets: List<PcapPacketRecord>,
    linkType: Long,
): Map<TcpStreamKey, String> {
    val sessionMap = mutableMapOf<TcpStreamKey, String>()
    for (packet in packets) {
        val tcpInfo = extractTcpInfo(buffer, packet, linkType) ?: continue
        if (tcpInfo.payloadLen < 6) continue // need at least TLS record header + handshake type

        // Check for TLS Handshake record containing ClientHello
        if (!TlsRecordParser.isTlsRecord(buffer, tcpInfo.payloadOffset, tcpInfo.payloadLen)) continue
        val hdr = TlsRecordParser.parseRecordHeader(buffer, tcpInfo.payloadOffset, tcpInfo.payloadLen) ?: continue
        if (hdr.contentType != TlsContentType.HANDSHAKE) continue

        val hsOffset = tcpInfo.payloadOffset + 5 // skip TLS record header
        val hsAvailable = tcpInfo.payloadLen - 5
        val clientRandom = TlsRecordParser.extractClientRandom(buffer, hsOffset, hsAvailable) ?: continue

        sessionMap[tcpInfo.streamKey.canonical] = clientRandom.toHexString()
    }
    return sessionMap
}

/**
 * Result of attempting to decrypt a TLS Application Data record.
 */
data class DecryptedTlsData(
    /** The decrypted plaintext bytes. */
    val plaintext: ByteArray,
    /** The inner content type (for TLS 1.3 where it's appended to the plaintext). */
    val innerContentType: Int = TlsContentType.APPLICATION_DATA,
) {
    override fun equals(other: Any?): Boolean =
        other is DecryptedTlsData && plaintext.contentEquals(other.plaintext) &&
            innerContentType == other.innerContentType

    override fun hashCode(): Int = 31 * plaintext.contentHashCode() + innerContentType
}

/**
 * TLS record decryption interface. On JVM this is implemented using
 * javax.crypto; on other platforms a no-op implementation returns null.
 */
interface TlsDecryptor {
    fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        iv: ByteArray,
        aad: ByteArray?,
        cipherSuite: TlsCipherSuite,
    ): ByteArray?

    companion object {
        /** No-op decryptor for platforms without crypto support. */
        val NOOP: TlsDecryptor = object : TlsDecryptor {
            override fun decrypt(
                ciphertext: ByteArray, key: ByteArray, iv: ByteArray,
                aad: ByteArray?, cipherSuite: TlsCipherSuite,
            ): ByteArray? = null
        }
    }
}

/**
 * Supported TLS cipher suites for decryption.
 */
enum class TlsCipherSuite(
    val id: Int,
    val keyLength: Int,
    val ivLength: Int,
    val tagLength: Int,
    val displayName: String,
) {
    AES_128_GCM_SHA256(0x1301, 16, 12, 16, "TLS_AES_128_GCM_SHA256"),
    AES_256_GCM_SHA384(0x1302, 32, 12, 16, "TLS_AES_256_GCM_SHA384"),
    CHACHA20_POLY1305_SHA256(0x1303, 32, 12, 16, "TLS_CHACHA20_POLY1305_SHA256"),
    // TLS 1.2 cipher suites
    TLS_RSA_WITH_AES_128_GCM_SHA256(0x009C, 16, 4, 16, "TLS_RSA_WITH_AES_128_GCM_SHA256"),
    TLS_RSA_WITH_AES_256_GCM_SHA384(0x009D, 32, 4, 16, "TLS_RSA_WITH_AES_256_GCM_SHA384"),
    TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256(0xC02F, 16, 4, 16, "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"),
    TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384(0xC030, 32, 4, 16, "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"),
    ;

    companion object {
        fun fromId(id: Int): TlsCipherSuite? = entries.firstOrNull { it.id == id }
    }
}

// ---- Internal helpers for IP extraction ----

internal data class IpLayerInfo(
    val srcIp: String,
    val dstIp: String,
    val protocol: Int,
    val transportOffset: Int,
    val transportLen: Int,
)

private fun extractIpFromEthernet(buffer: CommonByteBuffer, offset: Int, len: Int): IpLayerInfo? {
    if (len < 14) return null
    var etherType = readBigU16(buffer, offset + 12)
    var ipOffset = offset + 14
    if (etherType == 0x8100 && len >= 18) {
        etherType = readBigU16(buffer, offset + 16)
        ipOffset = offset + 18
    }
    return when (etherType) {
        0x0800 -> extractIpv4Info(buffer, ipOffset, len - (ipOffset - offset))
        0x86DD -> extractIpv6Info(buffer, ipOffset, len - (ipOffset - offset))
        else -> null
    }
}

private fun extractIpFromLoopback(buffer: CommonByteBuffer, offset: Int, len: Int): IpLayerInfo? {
    if (len < 4) return null
    val family = readLittleU32(buffer, offset)
    return when (family) {
        2L -> extractIpv4Info(buffer, offset + 4, len - 4)
        24L, 28L, 30L -> extractIpv6Info(buffer, offset + 4, len - 4)
        else -> null
    }
}

private fun extractIpFromSll(buffer: CommonByteBuffer, offset: Int, len: Int): IpLayerInfo? {
    if (len < 16) return null
    val protocol = readBigU16(buffer, offset + 14)
    return when (protocol) {
        0x0800 -> extractIpv4Info(buffer, offset + 16, len - 16)
        0x86DD -> extractIpv6Info(buffer, offset + 16, len - 16)
        else -> null
    }
}

private fun extractIpDirect(buffer: CommonByteBuffer, offset: Int, len: Int): IpLayerInfo? {
    if (len < 1) return null
    val version = (readU8(buffer, offset) shr 4) and 0x0F
    return when (version) {
        4 -> extractIpv4Info(buffer, offset, len)
        6 -> extractIpv6Info(buffer, offset, len)
        else -> null
    }
}

private fun extractIpv4Info(buffer: CommonByteBuffer, offset: Int, len: Int): IpLayerInfo? {
    if (len < 20) return null
    val ihl = (readU8(buffer, offset) and 0x0F) * 4
    val protocol = readU8(buffer, offset + 9)
    val srcIp = readIpv4String(buffer, offset + 12)
    val dstIp = readIpv4String(buffer, offset + 16)
    return IpLayerInfo(srcIp, dstIp, protocol, offset + ihl, len - ihl)
}

private fun extractIpv6Info(buffer: CommonByteBuffer, offset: Int, len: Int): IpLayerInfo? {
    if (len < 40) return null
    val nextHeader = readU8(buffer, offset + 6)
    val srcIp = readIpv6String(buffer, offset + 8)
    val dstIp = readIpv6String(buffer, offset + 24)
    return IpLayerInfo(srcIp, dstIp, nextHeader, offset + 40, len - 40)
}

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

private fun readLittleU32(buffer: CommonByteBuffer, offset: Int): Long {
    buffer.position(offset)
    val b0 = buffer.get().toLong() and 0xFF
    val b1 = buffer.get().toLong() and 0xFF
    val b2 = buffer.get().toLong() and 0xFF
    val b3 = buffer.get().toLong() and 0xFF
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}

private fun readIpv4String(buffer: CommonByteBuffer, offset: Int): String {
    buffer.position(offset)
    return buildString {
        for (i in 0 until 4) {
            if (i > 0) append('.')
            append(buffer.get().toInt() and 0xFF)
        }
    }
}

private fun readIpv6String(buffer: CommonByteBuffer, offset: Int): String {
    buffer.position(offset)
    return buildString {
        for (i in 0 until 8) {
            if (i > 0) append(':')
            val b0 = buffer.get().toInt() and 0xFF
            val b1 = buffer.get().toInt() and 0xFF
            append(((b0 shl 8) or b1).toString(16))
        }
    }
}
