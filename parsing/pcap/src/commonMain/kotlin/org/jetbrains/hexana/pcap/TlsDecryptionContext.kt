package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * Pre-scanned TLS decryption context for a PCAP capture. Scans all packets
 * once to extract per-stream handshake metadata (cipher suite, server random,
 * TLS version, direction) and then provides [tryDecrypt] to decrypt individual
 * Application Data records using the loaded [SslKeyLog].
 *
 * Thread-safety: instances are NOT thread-safe due to mutable sequence counters.
 * Create one per dissection pass (e.g. per Packets tab build).
 */
class TlsDecryptionContext private constructor(
    private val sslKeyLog: SslKeyLog,
    private val tlsSessionMap: Map<TcpStreamKey, String>,
    private val decryptor: TlsDecryptor,
    private val keyDeriver: TlsKeyDeriver,
    private val streamInfoMap: Map<TcpStreamKey, StreamInfo>,
) {
    /**
     * Per-stream TLS handshake metadata extracted during the pre-scan.
     */
    internal data class StreamInfo(
        val cipherSuiteId: Int,
        val isTls13: Boolean,
        val serverRandom: ByteArray?,
        /** The IP:port of the server (the side that sent ServerHello). */
        val serverSrcKey: String,
    ) {
        override fun equals(other: Any?): Boolean =
            other is StreamInfo && cipherSuiteId == other.cipherSuiteId &&
                isTls13 == other.isTls13 &&
                (serverRandom contentEquals other.serverRandom) &&
                serverSrcKey == other.serverSrcKey

        override fun hashCode(): Int {
            var h = cipherSuiteId
            h = 31 * h + isTls13.hashCode()
            h = 31 * h + (serverRandom?.contentHashCode() ?: 0)
            h = 31 * h + serverSrcKey.hashCode()
            return h
        }
    }

    /**
     * Mutable per-direction sequence counter for TLS 1.3 AEAD nonce construction.
     * TLS 1.3 XORs the 64-bit sequence number with the static IV to form the
     * per-record nonce (RFC 8446 §5.3).
     */
    private val sequenceCounters = HashMap<String, Long>() // key: "canonical_stream:direction"

    private fun seqKey(streamKey: TcpStreamKey, isFromServer: Boolean): String =
        "${streamKey.canonical}:${if (isFromServer) "s" else "c"}"

    private fun nextSeqNum(streamKey: TcpStreamKey, isFromServer: Boolean): Long {
        val key = seqKey(streamKey, isFromServer)
        val seq = sequenceCounters.getOrPut(key) { 0L }
        sequenceCounters[key] = seq + 1
        return seq
    }

    /**
     * Attempts to decrypt TLS Application Data from the given packet.
     * Returns the decrypted plaintext bytes, or `null` if decryption is not
     * possible (no key material, unsupported cipher, not Application Data, etc.).
     */
    fun tryDecrypt(
        buffer: CommonByteBuffer,
        packet: PcapPacketRecord,
        linkType: Long,
    ): ByteArray? {
        val tcpInfo = extractTcpInfo(buffer, packet, linkType) ?: return null
        if (tcpInfo.payloadLen < TlsRecordParser.RECORD_HEADER_SIZE + 1) return null

        // Check for TLS Application Data record
        val hdr = TlsRecordParser.parseRecordHeader(buffer, tcpInfo.payloadOffset, tcpInfo.payloadLen)
            ?: return null
        if (hdr.contentType != TlsContentType.APPLICATION_DATA) return null

        val canonical = tcpInfo.streamKey.canonical
        val streamInfo = streamInfoMap[canonical] ?: return null
        val clientRandomHex = tlsSessionMap[canonical] ?: return null
        val secrets = sslKeyLog.lookup(clientRandomHex)
        if (secrets.isEmpty()) return null

        val cipherSuite = TlsCipherSuite.fromId(streamInfo.cipherSuiteId) ?: return null

        // Determine direction: is this packet from the server?
        val senderKey = "${tcpInfo.streamKey.srcIp}:${tcpInfo.streamKey.srcPort}"
        val isFromServer = senderKey == streamInfo.serverSrcKey

        val recordPayloadOffset = tcpInfo.payloadOffset + TlsRecordParser.RECORD_HEADER_SIZE
        val recordPayloadLen = minOf(hdr.length, tcpInfo.payloadLen - TlsRecordParser.RECORD_HEADER_SIZE)
        if (recordPayloadLen <= 0) return null

        val ciphertext = ByteArray(recordPayloadLen)
        buffer.position(recordPayloadOffset)
        for (i in 0 until recordPayloadLen) {
            ciphertext[i] = buffer.get()
        }

        return if (streamInfo.isTls13) {
            decryptTls13(ciphertext, secrets, cipherSuite, canonical, isFromServer, hdr)
        } else {
            decryptTls12(ciphertext, secrets, cipherSuite, streamInfo, canonical, isFromServer, hdr)
        }
    }

    private fun decryptTls13(
        ciphertext: ByteArray,
        secrets: List<SslKeyLogParser.KeyLogEntry>,
        cipherSuite: TlsCipherSuite,
        streamKey: TcpStreamKey,
        isFromServer: Boolean,
        hdr: TlsRecordHeader,
    ): ByteArray? {
        // For TLS 1.3, use the appropriate traffic secret based on direction
        val label = if (isFromServer) "SERVER_TRAFFIC_SECRET_0" else "CLIENT_TRAFFIC_SECRET_0"
        val trafficSecret = secrets.firstOrNull { it.label == label }?.secret ?: return null

        val keyIv = keyDeriver.deriveTrafficKeyAndIv(trafficSecret, cipherSuite) ?: return null
        val (key, staticIv) = keyIv

        // Construct per-record nonce: XOR sequence number with static IV (RFC 8446 §5.3)
        val seqNum = nextSeqNum(streamKey, isFromServer)
        val nonce = staticIv.copyOf()
        for (i in 0 until 8) {
            nonce[nonce.size - 8 + i] = (nonce[nonce.size - 8 + i].toInt() xor
                ((seqNum shr (56 - 8 * i)) and 0xFF).toInt()).toByte()
        }

        // AAD for TLS 1.3: the 5-byte record header
        val aad = ByteArray(5)
        aad[0] = hdr.contentType.toByte()
        aad[1] = hdr.versionMajor.toByte()
        aad[2] = hdr.versionMinor.toByte()
        aad[3] = (hdr.length shr 8).toByte()
        aad[4] = (hdr.length and 0xFF).toByte()

        val plaintext = decryptor.decrypt(ciphertext, key, nonce, aad, cipherSuite) ?: return null

        // TLS 1.3: the last byte of plaintext is the inner content type;
        // strip trailing zeros and the content type byte
        if (plaintext.isEmpty()) return null
        var end = plaintext.size - 1
        while (end > 0 && plaintext[end] == 0.toByte()) end--
        if (end < 0) return null
        // plaintext[end] is the inner content type
        return plaintext.copyOfRange(0, end)
    }

    private fun decryptTls12(
        ciphertext: ByteArray,
        secrets: List<SslKeyLogParser.KeyLogEntry>,
        cipherSuite: TlsCipherSuite,
        streamInfo: StreamInfo,
        streamKey: TcpStreamKey,
        isFromServer: Boolean,
        hdr: TlsRecordHeader,
    ): ByteArray? {
        val masterSecret = secrets.firstOrNull { it.label == "CLIENT_RANDOM" }?.secret ?: return null
        val serverRandom = streamInfo.serverRandom ?: return null
        val clientRandomHex = tlsSessionMap[streamKey.canonical] ?: return null
        val clientRandom = hexToBytes(clientRandomHex) ?: return null

        val keyMaterial = keyDeriver.deriveTls12Keys(masterSecret, serverRandom, clientRandom, cipherSuite)
            ?: return null

        val writeKey = if (isFromServer) keyMaterial.serverWriteKey else keyMaterial.clientWriteKey
        val writeIv = if (isFromServer) keyMaterial.serverWriteIv else keyMaterial.clientWriteIv

        // TLS 1.2 GCM: the nonce is implicit_iv(4) + explicit_nonce(8)
        // The explicit nonce is the first 8 bytes of the ciphertext
        if (ciphertext.size < 8) return null
        val explicitNonce = ciphertext.copyOfRange(0, 8)
        val encryptedPayload = ciphertext.copyOfRange(8, ciphertext.size)
        val nonce = writeIv + explicitNonce

        // AAD for TLS 1.2: seq_num(8) + content_type(1) + version(2) + length(2)
        val seqNum = nextSeqNum(streamKey, isFromServer)
        val plaintextLen = encryptedPayload.size - cipherSuite.tagLength
        if (plaintextLen < 0) return null
        val aad = ByteArray(13)
        for (i in 0 until 8) {
            aad[i] = ((seqNum shr (56 - 8 * i)) and 0xFF).toByte()
        }
        aad[8] = hdr.contentType.toByte()
        aad[9] = hdr.versionMajor.toByte()
        aad[10] = hdr.versionMinor.toByte()
        aad[11] = (plaintextLen shr 8).toByte()
        aad[12] = (plaintextLen and 0xFF).toByte()

        return decryptor.decrypt(encryptedPayload, writeKey, nonce, aad, cipherSuite)
    }

    companion object {
        /**
         * Builds a decryption context by scanning all packets in the capture
         * to extract per-stream TLS handshake metadata.
         */
        fun build(
            buffer: CommonByteBuffer,
            packets: List<PcapPacketRecord>,
            linkType: Long,
            sslKeyLog: SslKeyLog,
            tlsSessionMap: Map<TcpStreamKey, String>,
            decryptor: TlsDecryptor,
            keyDeriver: TlsKeyDeriver,
        ): TlsDecryptionContext {
            val streamInfoMap = scanHandshakes(buffer, packets, linkType)
            return TlsDecryptionContext(sslKeyLog, tlsSessionMap, decryptor, keyDeriver, streamInfoMap)
        }

        /**
         * Scans all packets to find ServerHello messages and extract cipher
         * suite, TLS version, server random, and direction for each stream.
         */
        private fun scanHandshakes(
            buffer: CommonByteBuffer,
            packets: List<PcapPacketRecord>,
            linkType: Long,
        ): Map<TcpStreamKey, StreamInfo> {
            val result = mutableMapOf<TcpStreamKey, StreamInfo>()
            for (packet in packets) {
                val tcpInfo = extractTcpInfo(buffer, packet, linkType) ?: continue
                if (tcpInfo.payloadLen < TlsRecordParser.RECORD_HEADER_SIZE + 1) continue

                if (!TlsRecordParser.isTlsRecord(buffer, tcpInfo.payloadOffset, tcpInfo.payloadLen)) continue
                val hdr = TlsRecordParser.parseRecordHeader(buffer, tcpInfo.payloadOffset, tcpInfo.payloadLen)
                    ?: continue
                if (hdr.contentType != TlsContentType.HANDSHAKE) continue

                val hsOffset = tcpInfo.payloadOffset + TlsRecordParser.RECORD_HEADER_SIZE
                val hsAvailable = tcpInfo.payloadLen - TlsRecordParser.RECORD_HEADER_SIZE
                if (hsAvailable < 1) continue

                // Read handshake type
                buffer.position(hsOffset)
                val hsType = buffer.get().toInt() and 0xFF
                if (hsType != TlsHandshakeType.SERVER_HELLO) continue

                val canonical = tcpInfo.streamKey.canonical
                if (canonical in result) continue // already have info for this stream

                val cipherSuiteId = TlsRecordParser.extractCipherSuite(buffer, hsOffset, hsAvailable) ?: continue
                val serverRandom = TlsRecordParser.extractServerRandom(buffer, hsOffset, hsAvailable)
                val isTls13 = TlsRecordParser.isServerHelloTls13(buffer, hsOffset, hsAvailable)

                // The sender of ServerHello is the server
                val serverSrcKey = "${tcpInfo.streamKey.srcIp}:${tcpInfo.streamKey.srcPort}"

                result[canonical] = StreamInfo(
                    cipherSuiteId = cipherSuiteId,
                    isTls13 = isTls13,
                    serverRandom = serverRandom,
                    serverSrcKey = serverSrcKey,
                )
            }
            return result
        }
    }
}
