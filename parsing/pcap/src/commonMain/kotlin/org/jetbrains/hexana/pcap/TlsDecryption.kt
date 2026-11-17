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

    /**
     * Decrypts a whole number of AES-CBC blocks for the MAC-then-encrypt
     * suites, chaining from [iv] and removing no padding — TLS pads its own
     * way and checks it together with the record MAC.
     *
     * Defaulted so an implementation that only does AEAD stays valid.
     */
    fun decryptCbc(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? = null

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
 * Key derivation interface for TLS traffic keys. On JVM this is implemented
 * using HMAC-based HKDF; on other platforms a no-op returns null.
 */
interface TlsKeyDeriver {
    /** Derives TLS 1.3 traffic key and IV from a traffic secret. */
    fun deriveTrafficKeyAndIv(
        trafficSecret: ByteArray,
        cipherSuite: TlsCipherSuite,
    ): Pair<ByteArray, ByteArray>?

    /** Derives TLS 1.2 key material from the master secret. */
    fun deriveTls12Keys(
        masterSecret: ByteArray,
        serverRandom: ByteArray,
        clientRandom: ByteArray,
        cipherSuite: TlsCipherSuite,
    ): Tls12KeyMaterial?

    companion object {
        val NOOP: TlsKeyDeriver = object : TlsKeyDeriver {
            override fun deriveTrafficKeyAndIv(
                trafficSecret: ByteArray, cipherSuite: TlsCipherSuite,
            ): Pair<ByteArray, ByteArray>? = null

            override fun deriveTls12Keys(
                masterSecret: ByteArray, serverRandom: ByteArray,
                clientRandom: ByteArray, cipherSuite: TlsCipherSuite,
            ): Tls12KeyMaterial? = null
        }
    }
}

/**
 * TLS 1.2 key material derived from the master secret.
 */
data class Tls12KeyMaterial(
    val clientWriteKey: ByteArray,
    val clientWriteIv: ByteArray,
    val serverWriteKey: ByteArray,
    val serverWriteIv: ByteArray,
    /** Empty for the AEAD suites, which authenticate inside the cipher. */
    val clientMacKey: ByteArray = ByteArray(0),
    val serverMacKey: ByteArray = ByteArray(0),
) {
    override fun equals(other: Any?): Boolean =
        other is Tls12KeyMaterial &&
            clientWriteKey.contentEquals(other.clientWriteKey) &&
            clientWriteIv.contentEquals(other.clientWriteIv) &&
            serverWriteKey.contentEquals(other.serverWriteKey) &&
            serverWriteIv.contentEquals(other.serverWriteIv) &&
            clientMacKey.contentEquals(other.clientMacKey) &&
            serverMacKey.contentEquals(other.serverMacKey)

    override fun hashCode(): Int {
        var h = clientWriteKey.contentHashCode()
        h = 31 * h + clientWriteIv.contentHashCode()
        h = 31 * h + serverWriteKey.contentHashCode()
        h = 31 * h + serverWriteIv.contentHashCode()
        h = 31 * h + clientMacKey.contentHashCode()
        h = 31 * h + serverMacKey.contentHashCode()
        return h
    }
}

/**
 * How a cipher suite authenticates a record. AEAD suites do it inside the
 * cipher; the older suites append an HMAC to the plaintext before encrypting.
 */
enum class TlsMacAlgorithm(val macLength: Int) {
    AEAD(0),
    SHA1(20),
    SHA256(32),
    SHA384(48),
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
    /**
     * Whether the suite's key schedule — HKDF in TLS 1.3, the PRF in TLS 1.2 —
     * runs on SHA-384 instead of SHA-256. Carried here so that adding a suite
     * is one line rather than an edit to every `when` over the entries.
     */
    val usesSha384: Boolean = false,
    /** Whether records are protected with ChaCha20-Poly1305 rather than AES-GCM. */
    val isChaCha20Poly1305: Boolean = false,
    /**
     * How records are authenticated: [TlsMacAlgorithm.AEAD] for the suites
     * whose cipher does it, or the HMAC a MAC-then-encrypt suite uses.
     */
    val macAlgorithm: TlsMacAlgorithm = TlsMacAlgorithm.AEAD,
) {

    AES_128_GCM_SHA256(0x1301, 16, 12, 16, "TLS_AES_128_GCM_SHA256"),
    AES_256_GCM_SHA384(0x1302, 32, 12, 16, "TLS_AES_256_GCM_SHA384", usesSha384 = true),
    CHACHA20_POLY1305_SHA256(
        0x1303, 32, 12, 16, "TLS_CHACHA20_POLY1305_SHA256", isChaCha20Poly1305 = true,
    ),
    // TLS 1.2 cipher suites: AES-GCM and AES-CBC. TLS 1.2 ChaCha20-Poly1305 is
    // absent because it numbers its nonce the TLS 1.3 way (RFC 7905) rather
    // than with an explicit per-record nonce, so it needs its own record path.
    TLS_RSA_WITH_AES_128_GCM_SHA256(0x009C, 16, 4, 16, "TLS_RSA_WITH_AES_128_GCM_SHA256"),
    TLS_RSA_WITH_AES_256_GCM_SHA384(0x009D, 32, 4, 16, "TLS_RSA_WITH_AES_256_GCM_SHA384", usesSha384 = true),
    TLS_DHE_RSA_WITH_AES_128_GCM_SHA256(0x009E, 16, 4, 16, "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"),
    TLS_DHE_RSA_WITH_AES_256_GCM_SHA384(0x009F, 32, 4, 16, "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384", usesSha384 = true),
    TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256(0xC02B, 16, 4, 16, "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"),
    TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384(
        0xC02C, 32, 4, 16, "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", usesSha384 = true,
    ),
    TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256(0xC02F, 16, 4, 16, "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"),
    TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384(
        0xC030, 32, 4, 16, "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384", usesSha384 = true,
    ),

    // TLS 1.2 AEAD suites. The GCM nonce is the 4-byte implicit write IV from
    // the key block followed by the record's 8-byte explicit nonce, so
    // ivLength is 4 here where the TLS 1.3 suites carry a full 12.
    TLS_DHE_DSS_WITH_AES_128_GCM_SHA256(0x00A2, 16, 4, 16, "TLS_DHE_DSS_WITH_AES_128_GCM_SHA256"),
    TLS_DHE_DSS_WITH_AES_256_GCM_SHA384(0x00A3, 32, 4, 16, "TLS_DHE_DSS_WITH_AES_256_GCM_SHA384", usesSha384 = true),
    TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256(0xC02D, 16, 4, 16, "TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256"),
    TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384(
        0xC02E, 32, 4, 16, "TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384", usesSha384 = true,
    ),
    TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256(0xC031, 16, 4, 16, "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256"),
    TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384(
        0xC032, 32, 4, 16, "TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384", usesSha384 = true,
    ),

    // TLS 1.2 MAC-then-encrypt suites: AES-CBC with an explicit per-record IV
    // (TLS 1.1 and later). No fixed IV or AEAD tag in the key block, so both
    // lengths are zero and the MAC key length comes from [macAlgorithm].
    // AES only — 3DES, RC4, Camellia, SEED and ARIA are not implemented, and
    // are named by id in the packet tab header when a capture uses them.
    TLS_RSA_WITH_AES_128_CBC_SHA(
        0x002F, 16, 0, 0, "TLS_RSA_WITH_AES_128_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_DHE_DSS_WITH_AES_128_CBC_SHA(
        0x0032, 16, 0, 0, "TLS_DHE_DSS_WITH_AES_128_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_DHE_RSA_WITH_AES_128_CBC_SHA(
        0x0033, 16, 0, 0, "TLS_DHE_RSA_WITH_AES_128_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_RSA_WITH_AES_256_CBC_SHA(
        0x0035, 32, 0, 0, "TLS_RSA_WITH_AES_256_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_DHE_DSS_WITH_AES_256_CBC_SHA(
        0x0038, 32, 0, 0, "TLS_DHE_DSS_WITH_AES_256_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_DHE_RSA_WITH_AES_256_CBC_SHA(
        0x0039, 32, 0, 0, "TLS_DHE_RSA_WITH_AES_256_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_RSA_WITH_AES_128_CBC_SHA256(
        0x003C, 16, 0, 0, "TLS_RSA_WITH_AES_128_CBC_SHA256", macAlgorithm = TlsMacAlgorithm.SHA256,
    ),
    TLS_RSA_WITH_AES_256_CBC_SHA256(
        0x003D, 32, 0, 0, "TLS_RSA_WITH_AES_256_CBC_SHA256", macAlgorithm = TlsMacAlgorithm.SHA256,
    ),
    TLS_DHE_DSS_WITH_AES_128_CBC_SHA256(
        0x0040, 16, 0, 0, "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256", macAlgorithm = TlsMacAlgorithm.SHA256,
    ),
    TLS_DHE_RSA_WITH_AES_128_CBC_SHA256(
        0x0067, 16, 0, 0, "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256", macAlgorithm = TlsMacAlgorithm.SHA256,
    ),
    TLS_DHE_DSS_WITH_AES_256_CBC_SHA256(
        0x006A, 32, 0, 0, "TLS_DHE_DSS_WITH_AES_256_CBC_SHA256", macAlgorithm = TlsMacAlgorithm.SHA256,
    ),
    TLS_DHE_RSA_WITH_AES_256_CBC_SHA256(
        0x006B, 32, 0, 0, "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256", macAlgorithm = TlsMacAlgorithm.SHA256,
    ),
    TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA(
        0xC004, 16, 0, 0, "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA(
        0xC005, 32, 0, 0, "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA(
        0xC009, 16, 0, 0, "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA(
        0xC00A, 32, 0, 0, "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_ECDH_RSA_WITH_AES_128_CBC_SHA(
        0xC00E, 16, 0, 0, "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_ECDH_RSA_WITH_AES_256_CBC_SHA(
        0xC00F, 32, 0, 0, "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA(
        0xC013, 16, 0, 0, "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA(
        0xC014, 32, 0, 0, "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA", macAlgorithm = TlsMacAlgorithm.SHA1,
    ),
    TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256(
        0xC023, 16, 0, 0, "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", macAlgorithm = TlsMacAlgorithm.SHA256,
    ),
    TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384(
        0xC024, 32, 0, 0, "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
        usesSha384 = true, macAlgorithm = TlsMacAlgorithm.SHA384,
    ),
    TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256(
        0xC025, 16, 0, 0, "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256", macAlgorithm = TlsMacAlgorithm.SHA256,
    ),
    TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384(
        0xC026, 32, 0, 0, "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384",
        usesSha384 = true, macAlgorithm = TlsMacAlgorithm.SHA384,
    ),
    TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256(
        0xC027, 16, 0, 0, "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256", macAlgorithm = TlsMacAlgorithm.SHA256,
    ),
    TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384(
        0xC028, 32, 0, 0, "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
        usesSha384 = true, macAlgorithm = TlsMacAlgorithm.SHA384,
    ),
    TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256(
        0xC029, 16, 0, 0, "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256", macAlgorithm = TlsMacAlgorithm.SHA256,
    ),
    TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384(
        0xC02A, 32, 0, 0, "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384",
        usesSha384 = true, macAlgorithm = TlsMacAlgorithm.SHA384,
    ),
    ;

    /**
     * Whether records are AES-CBC with a separate HMAC rather than AEAD. Such
     * a record carries its own IV, its plaintext is padded to the block size,
     * and the MAC is what tells a right key from a wrong one.
     */
    val isCbc: Boolean get() = macAlgorithm != TlsMacAlgorithm.AEAD

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
