package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.pcap.crypto.AesCbc
import org.jetbrains.hexana.pcap.crypto.AesGcm
import org.jetbrains.hexana.pcap.crypto.AesKeySchedule
import org.jetbrains.hexana.pcap.crypto.Digest
import org.jetbrains.hexana.pcap.crypto.Hmac
import org.jetbrains.hexana.pcap.crypto.Sha1Digest
import org.jetbrains.hexana.pcap.crypto.Sha256Digest
import org.jetbrains.hexana.pcap.crypto.Sha512Digest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end TLS decryption over synthetic captures, one fixture per protocol
 * version. The records are sealed with the same pure-Kotlin AES-GCM the
 * decryptor opens them with, and the traffic keys come from the platform key
 * deriver, so this exercises the whole path: handshake scan, key derivation,
 * record numbering, nonce construction, AAD construction and the AEAD open.
 */
class TlsDecryptionContextTest {

    @Test
    fun decryptsTls13ApplicationData() {
        val capture = buildTls13Capture(
            listOf(
                Message(fromClient = true, text = "GET /one HTTP/1.1"),
                Message(fromClient = false, text = "HTTP/1.1 200 OK"),
                Message(fromClient = true, text = "GET /two HTTP/1.1"),
            ),
        )
        val context = capture.buildContext()

        for ((index, message) in capture.messages.withIndex()) {
            val plaintext = context.tryDecrypt(
                capture.buffer, capture.applicationDataPackets[index], LINK_TYPE_ETHERNET,
            )
            assertNotNull(plaintext, "record $index should decrypt")
            assertEquals(message.text, plaintext.decodeToString())
        }
    }

    /**
     * The regression guard for the bug this replaced: sequence numbers used to
     * be handed out in the order rows were rendered, so reading the table out
     * of order — a sort, a search, a jump to the middle — silently broke every
     * record after the first. Reading backwards must give the same answers.
     */
    @Test
    fun decryptionIsIndependentOfAccessOrder() {
        val messages = (0 until 8).map { Message(fromClient = it % 3 != 2, text = "record number $it") }
        val capture = buildTls13Capture(messages)

        val forwards = capture.buildContext().let { context ->
            capture.applicationDataPackets.map {
                context.tryDecrypt(capture.buffer, it, LINK_TYPE_ETHERNET)?.decodeToString()
            }
        }
        assertEquals(messages.map { it.text }, forwards)

        // Backwards, on a fresh context.
        val backwardsContext = capture.buildContext()
        val backwards = capture.applicationDataPackets.indices.reversed().map { index ->
            backwardsContext.tryDecrypt(
                capture.buffer, capture.applicationDataPackets[index], LINK_TYPE_ETHERNET,
            )?.decodeToString()
        }.reversed()
        assertEquals(messages.map { it.text }, backwards)

        // And a scattered order, on another fresh context.
        val scatteredContext = capture.buildContext()
        val scattered = intArrayOf(5, 0, 7, 2, 6, 1, 4, 3).map { index ->
            index to scatteredContext.tryDecrypt(
                capture.buffer, capture.applicationDataPackets[index], LINK_TYPE_ETHERNET,
            )?.decodeToString()
        }.sortedBy { it.first }.map { it.second }
        assertEquals(messages.map { it.text }, scattered)
    }

    /** Re-reading the same record must not advance anything. */
    @Test
    fun decryptionIsRepeatable() {
        val capture = buildTls13Capture(listOf(Message(fromClient = true, text = "hello there")))
        val context = capture.buildContext()
        val packet = capture.applicationDataPackets[0]
        repeat(5) {
            assertEquals(
                "hello there",
                context.tryDecrypt(capture.buffer, packet, LINK_TYPE_ETHERNET)?.decodeToString(),
            )
        }
    }

    @Test
    fun withoutMatchingKeysNothingDecrypts() {
        val capture = buildTls13Capture(listOf(Message(fromClient = true, text = "secret payload")))
        // A key log for a different session: right shape, wrong client random.
        val otherRandom = ByteArray(32) { 0x5a }
        val keyLog = SslKeyLog.parse(
            "CLIENT_TRAFFIC_SECRET_0 ${hex(otherRandom)} ${hex(capture.clientSecret)}\n" +
                "SERVER_TRAFFIC_SECRET_0 ${hex(otherRandom)} ${hex(capture.serverSecret)}\n",
        )
        assertTrue(keyLog.isNotEmpty)
        val context = capture.buildContext(keyLog)
        assertNull(context.tryDecrypt(capture.buffer, capture.applicationDataPackets[0], LINK_TYPE_ETHERNET))
    }

    @Test
    fun handshakePacketsAreNotTreatedAsApplicationData() {
        val capture = buildTls13Capture(listOf(Message(fromClient = true, text = "payload")))
        val context = capture.buildContext()
        for (packet in capture.handshakePackets) {
            assertNull(context.tryDecrypt(capture.buffer, packet, LINK_TYPE_ETHERNET))
        }
    }

    /**
     * ChaCha20-Poly1305 has no pure-Kotlin implementation, so on the web
     * targets it must report as unsupported rather than appear to work.
     */
    @Test
    fun unsupportedSuitesAreReportedNotSilentlyDropped() {
        val capture = buildTls13Capture(
            listOf(Message(fromClient = true, text = "payload")),
            cipherSuite = TlsCipherSuite.CHACHA20_POLY1305_SHA256,
        )
        val context = capture.buildContext()
        val expectedUndecryptable =
            if (isTlsDecryptionSupported(TlsCipherSuite.CHACHA20_POLY1305_SHA256)) {
                emptySet<TlsCipherSuite>()
            } else {
                setOf(TlsCipherSuite.CHACHA20_POLY1305_SHA256)
            }
        assertEquals(expectedUndecryptable, context.undecryptableCipherSuites)
    }

    /**
     * The browser targets cap how much ciphertext one context will open. Which
     * records fall inside that cap has to be a property of the capture: if it
     * followed read order, a sort would move the plaintext around and the same
     * row would decrypt or not depending on how the user got to it.
     */
    @Test
    fun byteBudgetClipsInCaptureOrderRegardlessOfAccessOrder() {
        val messages = (0 until 6).map { Message(fromClient = it % 2 == 0, text = "record number $it") }
        val capture = buildTls13Capture(messages)

        // Enough for the first two records and no more: each record's
        // ciphertext is the text plus the inner content type and the tag, and
        // the budget is charged before the record that crosses it is added.
        val recordSize = messages[0].text.length + 1 + AEAD_TAG_SIZE
        val context = capture.buildContext(byteBudget = (recordSize + 1).toLong())

        assertEquals(messages.size, context.decryptableRecordCount)
        assertEquals(messages.size - 2, context.recordsBeyondBudgetCount)

        // Read scattered: the first two records must still be the decrypted
        // ones, and the rest must report the budget rather than returning
        // plaintext or looking like they had no keys.
        val order = intArrayOf(4, 1, 5, 0, 3, 2)
        for (index in order) {
            val packet = capture.applicationDataPackets[index]
            val plaintext = context.tryDecrypt(capture.buffer, packet, LINK_TYPE_ETHERNET)
            if (index < 2) {
                assertEquals(messages[index].text, plaintext?.decodeToString(), "record $index")
                assertFalse(context.isBeyondDecryptionBudget(packet), "record $index")
            } else {
                assertNull(plaintext, "record $index should be left to the budget")
                assertTrue(context.isBeyondDecryptionBudget(packet), "record $index")
            }
        }
    }

    /** A budget of zero still opens one record: something is better than a wall of padlocks. */
    @Test
    fun aZeroByteBudgetStillDecryptsTheFirstRecord() {
        val capture = buildTls13Capture(
            listOf(
                Message(fromClient = true, text = "first"),
                Message(fromClient = true, text = "second"),
            ),
        )
        val context = capture.buildContext(byteBudget = 0)
        assertEquals(
            "first",
            context.tryDecrypt(
                capture.buffer, capture.applicationDataPackets[0], LINK_TYPE_ETHERNET,
            )?.decodeToString(),
        )
        assertNull(context.tryDecrypt(capture.buffer, capture.applicationDataPackets[1], LINK_TYPE_ETHERNET))
        assertEquals(1, context.recordsBeyondBudgetCount)
    }

    /** With no ceiling nothing is reported as skipped, which is the JVM's case. */
    @Test
    fun anUnlimitedBudgetSkipsNothing() {
        val capture = buildTls13Capture((0 until 4).map { Message(fromClient = true, text = "record $it") })
        val context = capture.buildContext()
        assertEquals(4, context.decryptableRecordCount)
        assertEquals(0, context.recordsBeyondBudgetCount)
        assertFalse(context.isBeyondDecryptionBudget(capture.applicationDataPackets[3]))
    }

    @Test
    fun supportedSuitesAreNotFlaggedAsUndecryptable() {
        val capture = buildTls13Capture(listOf(Message(fromClient = true, text = "payload")))
        assertEquals(emptySet<TlsCipherSuite>(), capture.buildContext().undecryptableCipherSuites)
    }

    /**
     * The regression guard for the capture that started this: a real TLS 1.2
     * conversation whose Application Data read "encrypted" with the key log
     * loaded, because the encrypted Finished's sequence number was never
     * counted. Wireshark decrypted the same file.
     */
    @Test
    fun decryptsTls12ApplicationData() {
        val messages = listOf(
            Message(fromClient = true, text = "GET / HTTP/1.0"),
            Message(fromClient = false, text = "HTTP/1.0 200 ok"),
            Message(fromClient = true, text = "GET /again HTTP/1.0"),
            Message(fromClient = false, text = "HTTP/1.0 404 nope"),
        )
        val capture = buildTls12Capture(messages)
        val context = capture.buildContext()

        assertEquals(messages.size, context.decryptableRecordCount)
        for ((index, message) in messages.withIndex()) {
            val plaintext = context.tryDecrypt(
                capture.buffer, capture.applicationDataPackets[index], LINK_TYPE_ETHERNET,
            )
            assertNotNull(plaintext, "TLS 1.2 record $index should decrypt")
            assertEquals(message.text, plaintext.decodeToString())
        }
        assertFalse(context.streamDiagnostics.hasFailures)
        assertEquals(1, context.streamDiagnostics.decryptableStreams)
    }

    /** TLS 1.2 decryption must not depend on read order either. */
    @Test
    fun tls12DecryptionIsIndependentOfAccessOrder() {
        val messages = (0 until 6).map { Message(fromClient = it % 2 == 0, text = "line $it") }
        val capture = buildTls12Capture(messages)
        val context = capture.buildContext()
        val scattered = intArrayOf(3, 5, 0, 4, 1, 2).map { index ->
            index to context.tryDecrypt(
                capture.buffer, capture.applicationDataPackets[index], LINK_TYPE_ETHERNET,
            )?.decodeToString()
        }.sortedBy { it.first }.map { it.second }
        assertEquals(messages.map { it.text }, scattered)
    }

    /**
     * The ChangeCipherSpec packet also carries the encrypted Finished, but its
     * first record is not Application Data, so the table must not offer it as
     * decrypted payload.
     */
    @Test
    fun tls12HandshakePacketsAreNotOfferedAsPlaintext() {
        val capture = buildTls12Capture(listOf(Message(fromClient = true, text = "payload")))
        val context = capture.buildContext()
        for (packet in capture.handshakePackets) {
            assertNull(context.tryDecrypt(capture.buffer, packet, LINK_TYPE_ETHERNET))
        }
    }

    /**
     * A cipher suite this build has no entry for must be named, not silently
     * dropped: "encrypted" with a key log loaded reads as a broken key log,
     * while the real reason may be a suite — 3DES, RC4, Camellia — whose bulk
     * cipher this record layer does not implement.
     */
    @Test
    fun anUnknownCipherSuiteIsReportedById() {
        val tripleDesSuiteId = 0x000A // TLS_RSA_WITH_3DES_EDE_CBC_SHA
        val capture = buildTls12Capture(
            listOf(Message(fromClient = true, text = "payload")),
            serverHelloCipherSuiteId = tripleDesSuiteId,
        )
        val diagnostics = capture.buildContext().streamDiagnostics
        assertEquals(setOf(tripleDesSuiteId), diagnostics.unsupportedCipherSuiteIds)
        assertEquals(0, diagnostics.decryptableStreams)
        assertTrue(diagnostics.hasFailures)
    }

    /**
     * The MAC-then-encrypt suites, which make up most of what a capture from an
     * older server carries. Each of the three MAC algorithms gets a run,
     * because the digest choice also sizes the key block.
     */
    @Test
    fun tls12CbcRecordsDecrypt() {
        val suites = listOf(
            TlsCipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
            TlsCipherSuite.TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256,
            TlsCipherSuite.TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384,
        )
        for (suite in suites) {
            val messages = listOf(
                Message(fromClient = true, text = "GET / HTTP/1.0"),
                Message(fromClient = false, text = "HTTP/1.0 200 ok"),
                Message(fromClient = true, text = "second request over ${suite.displayName}"),
            )
            val capture = buildTls12Capture(messages, cipherSuite = suite)
            val context = capture.buildContext()
            assertEquals(1, context.streamDiagnostics.decryptableStreams, suite.displayName)
            assertEquals(0, context.streamDiagnostics.unauthenticatedStreams, suite.displayName)
            val decrypted = capture.applicationDataPackets.map {
                context.tryDecrypt(capture.buffer, it, LINK_TYPE_ETHERNET)?.decodeToString()
            }
            assertEquals(messages.map { it.text }, decrypted, suite.displayName)
        }
    }

    /**
     * A CBC record whose MAC does not check out must come back null rather than
     * as padding-shaped garbage — the MAC is the only thing standing between a
     * wrong key log and a table full of nonsense.
     */
    @Test
    fun tls12CbcRecordsWithTheWrongKeyDoNotDecrypt() {
        val capture = buildTls12Capture(
            listOf(Message(fromClient = true, text = "secret payload")),
            cipherSuite = TlsCipherSuite.TLS_RSA_WITH_AES_128_CBC_SHA,
        )
        val keyLog = SslKeyLog.parse("CLIENT_RANDOM ${hex(capture.clientRandom)} ${hex(ByteArray(48) { 0x7f })}\n")
        val context = capture.buildContext(keyLog)
        assertNull(context.tryDecrypt(capture.buffer, capture.applicationDataPackets[0], LINK_TYPE_ETHERNET))
        assertEquals(1, context.streamDiagnostics.unauthenticatedStreams)
    }

    /**
     * TLS-level DEFLATE (RFC 3749) runs before record protection and keeps one
     * compressed stream per direction for the whole connection, so a record's
     * plaintext depends on every record before it — including the encrypted
     * Finished. Decrypting one in isolation is not enough.
     */
    @Test
    fun tls12DeflateCompressedRecordsDecompress() {
        val messages = (0 until 6).map { Message(fromClient = it % 2 == 0, text = "compressed record $it") }
        val capture = buildTls12Capture(messages, compressionMethod = 1)
        val context = capture.buildContext()
        assertEquals(0, context.streamDiagnostics.compressionFailures)
        val decrypted = capture.applicationDataPackets.map {
            context.tryDecrypt(capture.buffer, it, LINK_TYPE_ETHERNET)?.decodeToString()
        }
        assertEquals(messages.map { it.text }, decrypted)
    }

    /** Compression methods other than none and DEFLATE are counted, not guessed at. */
    @Test
    fun anUnknownCompressionMethodIsCounted() {
        val capture = buildTls12Capture(
            listOf(Message(fromClient = true, text = "payload")),
            compressionMethod = 64,
        )
        val context = capture.buildContext()
        assertEquals(1, context.streamDiagnostics.compressionFailures)
        assertEquals(0, context.streamDiagnostics.decryptableStreams)
        assertTrue(context.streamDiagnostics.hasFailures)
        assertNull(context.tryDecrypt(capture.buffer, capture.applicationDataPackets[0], LINK_TYPE_ETHERNET))
    }

    /**
     * The point of decrypting in the table: a record that opens into HTTP has
     * to be described as HTTP, the way Wireshark does, instead of keeping the
     * "Application Data" envelope it arrived in.
     */
    @Test
    fun decryptedRecordsAreDescribedByTheirPlaintext() {
        val capture = buildTls12Capture(
            listOf(
                Message(fromClient = true, text = "GET / HTTP/1.0\r\nHost: example\r\n\r\n"),
                Message(fromClient = false, text = "HTTP/1.0 200 ok\r\nContent-type: text/html\r\n\r\n<html/>"),
            ),
        )
        val context = capture.buildContext()

        val request = PcapDissector.dissect(
            capture.buffer, capture.applicationDataPackets[0], LINK_TYPE_ETHERNET, context,
        )
        assertEquals("HTTP", request.protocol)
        assertEquals("GET / HTTP/1.0", request.info)

        val response = PcapDissector.dissect(
            capture.buffer, capture.applicationDataPackets[1], LINK_TYPE_ETHERNET, context,
        )
        assertEquals("HTTP", response.protocol)
        assertEquals("HTTP/1.0 200 ok  (text/html)", response.info)
        assertEquals("text/html", response.httpContentType)
        // The plaintext lives only in memory, so there is no file range to open.
        assertEquals(-1L, response.httpBodyFileOffset)
    }

    /** A record whose plaintext is not an HTTP head keeps its TLS description. */
    @Test
    fun decryptedNonHttpRecordsKeepTheirTlsDescription() {
        val capture = buildTls12Capture(listOf(Message(fromClient = true, text = "not an HTTP message at all")))
        val row = PcapDissector.dissect(
            capture.buffer, capture.applicationDataPackets[0], LINK_TYPE_ETHERNET, capture.buildContext(),
        )
        assertEquals("TLS 1.2", row.protocol)
        assertTrue(row.info.contains("Application Data"))
        assertNotNull(row.decryptedData)
    }

    /** A capture whose sessions the key log does not cover says so. */
    @Test
    fun aSessionMissingFromTheKeyLogIsCounted() {
        val capture = buildTls12Capture(listOf(Message(fromClient = true, text = "payload")))
        val otherRandom = ByteArray(32) { 0x11 }
        val keyLog = SslKeyLog.parse("CLIENT_RANDOM ${hex(otherRandom)} ${hex(ByteArray(48))}\n")
        val diagnostics = capture.buildContext(keyLog).streamDiagnostics
        assertEquals(1, diagnostics.streamsWithoutKeys)
        assertEquals(0, diagnostics.decryptableStreams)
    }

    // ---- Synthetic capture construction ----

    private class Message(val fromClient: Boolean, val text: String)

    private class Capture(
        val buffer: org.jetbrains.hexana.io.CommonByteBuffer,
        val pcap: PcapFile,
        val messages: List<Message>,
        val handshakePackets: List<PcapPacketRecord>,
        val applicationDataPackets: List<PcapPacketRecord>,
        val clientRandom: ByteArray,
        /** The key log a capture of this shape would really ship with. */
        val defaultKeyLogText: String,
        /** TLS 1.3 traffic secrets; empty for a TLS 1.2 capture. */
        val clientSecret: ByteArray = ByteArray(0),
        val serverSecret: ByteArray = ByteArray(0),
    ) {
        fun defaultKeyLog(): SslKeyLog = SslKeyLog.parse(defaultKeyLogText)

        fun buildContext(
            keyLog: SslKeyLog = defaultKeyLog(),
            byteBudget: Long = Long.MAX_VALUE,
        ): TlsDecryptionContext =
            TlsDecryptionContext.build(
                buffer = buffer,
                packets = pcap.packets,
                linkType = LINK_TYPE_ETHERNET,
                sslKeyLog = keyLog,
                tlsSessionMap = buildTlsSessionMap(buffer, pcap.packets, LINK_TYPE_ETHERNET),
                byteBudget = byteBudget,
            )
    }

    private fun buildTls13Capture(
        messages: List<Message>,
        cipherSuite: TlsCipherSuite = TlsCipherSuite.AES_128_GCM_SHA256,
    ): Capture {
        val clientRandom = ByteArray(32) { (it + 1).toByte() }
        val serverRandom = ByteArray(32) { (0x40 + it).toByte() }
        val clientSecret = ByteArray(32) { (it * 3 + 7).toByte() }
        val serverSecret = ByteArray(32) { (it * 5 + 11).toByte() }

        val keyDeriver = platformTlsKeyDeriver()
        val clientKeyIv = keyDeriver.deriveTrafficKeyAndIv(clientSecret, cipherSuite)
        val serverKeyIv = keyDeriver.deriveTrafficKeyAndIv(serverSecret, cipherSuite)
        checkNotNull(clientKeyIv)
        checkNotNull(serverKeyIv)

        val payloads = mutableListOf<TcpPayload>()
        payloads += TcpPayload(fromClient = true, bytes = clientHelloRecord(clientRandom))
        payloads += TcpPayload(
            fromClient = false,
            bytes = serverHelloRecord(serverRandom, cipherSuite.id, tls13 = true),
        )

        // Sequence numbers restart per direction and count records in order.
        var clientSeq = 0L
        var serverSeq = 0L
        for (message in messages) {
            val (key, staticIv) = if (message.fromClient) clientKeyIv else serverKeyIv
            val sequence = if (message.fromClient) clientSeq++ else serverSeq++
            payloads += TcpPayload(
                fromClient = message.fromClient,
                bytes = applicationDataRecord(message.text, key, staticIv, sequence),
            )
        }

        val bytes = buildPcap(payloads)
        val buffer = bytes.toCommonByteBuffer()
        val pcap = checkNotNull(PcapParser.parse(buffer)) { "synthetic capture failed to parse" }
        assertEquals(payloads.size, pcap.packets.size)

        return Capture(
            buffer = buffer,
            pcap = pcap,
            messages = messages,
            handshakePackets = pcap.packets.take(2),
            applicationDataPackets = pcap.packets.drop(2),
            clientRandom = clientRandom,
            defaultKeyLogText =
                "CLIENT_TRAFFIC_SECRET_0 ${hex(clientRandom)} ${hex(clientSecret)}\n" +
                    "SERVER_TRAFFIC_SECRET_0 ${hex(clientRandom)} ${hex(serverSecret)}\n",
            clientSecret = clientSecret,
            serverSecret = serverSecret,
        )
    }

    /**
     * A TLS 1.2 capture shaped like a real one: a full handshake, then a packet
     * per direction holding three records — the last unencrypted handshake
     * message, the ChangeCipherSpec, and the *encrypted* Finished — and only
     * then Application Data.
     *
     * That Finished is the whole point of the fixture. TLS 1.2 resets the
     * sequence number when the cipher state activates (RFC 5246 §6.1), so the
     * Finished holds sequence number 0 and the first Application Data record
     * holds 1. The sequence number is part of the GCM AAD, so counting only
     * Application Data records shifts every nonce and tag by one and the whole
     * conversation reads as "encrypted" — which is exactly what shipped.
     */
    private fun buildTls12Capture(
        messages: List<Message>,
        cipherSuite: TlsCipherSuite = TlsCipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        serverHelloCipherSuiteId: Int = cipherSuite.id,
        compressionMethod: Int = 0,
    ): Capture {
        val clientRandom = ByteArray(32) { (it + 3).toByte() }
        val serverRandom = ByteArray(32) { (0x20 + it).toByte() }
        val masterSecret = ByteArray(48) { (it * 7 + 5).toByte() }

        val material = checkNotNull(
            platformTlsKeyDeriver().deriveTls12Keys(masterSecret, serverRandom, clientRandom, cipherSuite),
        ) { "TLS 1.2 key derivation failed" }

        val payloads = mutableListOf<TcpPayload>()
        payloads += TcpPayload(fromClient = true, bytes = clientHelloRecord(clientRandom))
        payloads += TcpPayload(
            fromClient = false,
            bytes = serverHelloRecord(
                serverRandom, serverHelloCipherSuiteId, tls13 = false, compressionMethod = compressionMethod,
            ),
        )
        // DEFLATE runs before record protection, and one compressed stream spans
        // the whole direction, so a fragment has to be produced in record order.
        val deflate = compressionMethod == 1
        fun fragment(plaintext: ByteArray): ByteArray = if (deflate) storedDeflateBlock(plaintext) else plaintext

        fun seal(fromClient: Boolean, contentType: Int, plaintext: ByteArray, sequence: Long): ByteArray {
            val key = if (fromClient) material.clientWriteKey else material.serverWriteKey
            val iv = if (fromClient) material.clientWriteIv else material.serverWriteIv
            val macKey = if (fromClient) material.clientMacKey else material.serverMacKey
            return if (cipherSuite.isCbc) {
                tls12CbcRecord(contentType, fragment(plaintext), key, macKey, sequence, cipherSuite)
            } else {
                tls12Record(contentType, fragment(plaintext), key, iv, sequence, cipherSuite.tagLength)
            }
        }

        // ClientKeyExchange + ChangeCipherSpec + encrypted Finished, one packet.
        payloads += TcpPayload(
            fromClient = true,
            bytes = plainHandshakeRecord(TlsHandshakeType.CLIENT_KEY_EXCHANGE) +
                changeCipherSpecRecord() +
                seal(true, TlsContentType.HANDSHAKE, finishedMessage(), 0),
        )
        // NewSessionTicket + ChangeCipherSpec + encrypted Finished, one packet.
        payloads += TcpPayload(
            fromClient = false,
            bytes = plainHandshakeRecord(TlsHandshakeType.NEW_SESSION_TICKET) +
                changeCipherSpecRecord() +
                seal(false, TlsContentType.HANDSHAKE, finishedMessage(), 0),
        )

        // The Finished took sequence number 0 in each direction.
        var clientSeq = 1L
        var serverSeq = 1L
        for (message in messages) {
            val sequence = if (message.fromClient) clientSeq++ else serverSeq++
            payloads += TcpPayload(
                fromClient = message.fromClient,
                bytes = seal(
                    message.fromClient,
                    TlsContentType.APPLICATION_DATA,
                    message.text.encodeToByteArray(),
                    sequence,
                ),
            )
        }

        val buffer = buildPcap(payloads).toCommonByteBuffer()
        val pcap = checkNotNull(PcapParser.parse(buffer)) { "synthetic capture failed to parse" }
        assertEquals(payloads.size, pcap.packets.size)

        return Capture(
            buffer = buffer,
            pcap = pcap,
            messages = messages,
            handshakePackets = pcap.packets.take(TLS12_HANDSHAKE_PACKETS),
            applicationDataPackets = pcap.packets.drop(TLS12_HANDSHAKE_PACKETS),
            clientRandom = clientRandom,
            defaultKeyLogText = "CLIENT_RANDOM ${hex(clientRandom)} ${hex(masterSecret)}\n",
        )
    }

    /** An unencrypted handshake record, trimmed to a header and filler. */
    private fun plainHandshakeRecord(handshakeType: Int): ByteArray {
        val body = ByteArray(8) { (it + 1).toByte() }
        return tlsRecord(
            TlsContentType.HANDSHAKE,
            byteArrayOf(handshakeType.toByte(), 0, 0, body.size.toByte()) + body,
        )
    }

    private fun changeCipherSpecRecord(): ByteArray =
        tlsRecord(TlsContentType.CHANGE_CIPHER_SPEC, byteArrayOf(0x01))

    /** A Finished message: handshake header plus 12 bytes of verify_data. */
    private fun finishedMessage(): ByteArray =
        byteArrayOf(TlsHandshakeType.FINISHED.toByte(), 0, 0, 12) + ByteArray(12) { (it + 0x30).toByte() }

    /**
     * Seals [plaintext] as a TLS 1.2 AEAD record: the record body is the
     * 8-byte explicit nonce followed by the ciphertext and tag, the full GCM
     * nonce is the 4-byte implicit write IV plus that explicit nonce, and the
     * AAD is seq_num(8) + type + version + *plaintext* length (RFC 5246 §6.2.3.3).
     */
    private fun tls12Record(
        contentType: Int,
        plaintext: ByteArray,
        key: ByteArray,
        implicitIv: ByteArray,
        sequence: Long,
        tagLength: Int,
    ): ByteArray {
        val explicitNonce = ByteArray(8) { ((sequence shr (56 - 8 * it)) and 0xFF).toByte() }
        val aad = ByteArray(13)
        for (i in 0 until 8) {
            aad[i] = ((sequence shr (56 - 8 * i)) and 0xFF).toByte()
        }
        aad[8] = contentType.toByte()
        aad[9] = 0x03
        aad[10] = 0x03
        aad[11] = (plaintext.size shr 8).toByte()
        aad[12] = (plaintext.size and 0xFF).toByte()

        val sealed = AesGcm.encrypt(plaintext, AesKeySchedule(key), implicitIv + explicitNonce, aad, tagLength)
        return tlsRecord(contentType, explicitNonce + sealed)
    }

    /**
     * Seals [fragment] as a TLS 1.2 MAC-then-encrypt record: HMAC over
     * `seq ‖ type ‖ version ‖ len ‖ fragment`, TLS padding out to the block
     * size, then CBC with a fresh explicit IV prefixed to the body
     * (RFC 5246 §6.2.3.2).
     */
    private fun tls12CbcRecord(
        contentType: Int,
        fragment: ByteArray,
        key: ByteArray,
        macKey: ByteArray,
        sequence: Long,
        cipherSuite: TlsCipherSuite,
    ): ByteArray {
        val header = ByteArray(13)
        for (i in 0 until 8) {
            header[i] = ((sequence shr (56 - 8 * i)) and 0xFF).toByte()
        }
        header[8] = contentType.toByte()
        header[9] = 0x03
        header[10] = 0x03
        header[11] = (fragment.size shr 8).toByte()
        header[12] = (fragment.size and 0xFF).toByte()

        val newDigest: () -> Digest = when (cipherSuite.macAlgorithm) {
            TlsMacAlgorithm.SHA1 -> ::Sha1Digest
            TlsMacAlgorithm.SHA256 -> ::Sha256Digest
            TlsMacAlgorithm.SHA384 -> { { Sha512Digest(48) } }
            TlsMacAlgorithm.AEAD -> error("${cipherSuite.displayName} is not a MAC-then-encrypt suite")
        }
        val hmac = Hmac(newDigest, macKey)
        hmac.update(header)
        hmac.update(fragment)
        val mac = hmac.doFinal()

        val unpadded = fragment + mac
        val padLength = AesCbc.BLOCK_SIZE - 1 - (unpadded.size % AesCbc.BLOCK_SIZE)
        val padded = unpadded + ByteArray(padLength + 1) { padLength.toByte() }

        val explicitIv = ByteArray(AesCbc.BLOCK_SIZE) { ((sequence + it) and 0xFF).toByte() }
        val sealed = checkNotNull(AesCbc.encrypt(padded, AesKeySchedule(key), explicitIv))
        return tlsRecord(contentType, explicitIv + sealed)
    }

    /**
     * Wraps [data] in a DEFLATE stored (uncompressed) block — the one encoding
     * that can be emitted without a compressor, and enough to drive the
     * per-direction inflater across a whole stream. Huffman-coded blocks are
     * covered by the canned vectors in `CryptoVectorTest`.
     */
    private fun storedDeflateBlock(data: ByteArray): ByteArray =
        byteArrayOf(
            0x00, // BFINAL = 0, BTYPE = 00 (stored), rest of the byte is the align padding
            (data.size and 0xFF).toByte(), (data.size shr 8).toByte(),
            (data.size.inv() and 0xFF).toByte(), ((data.size.inv() shr 8) and 0xFF).toByte(),
        ) + data

    /**
     * A ClientHello, trimmed to what [TlsRecordParser.extractClientRandom]
     * reads: handshake type, length, legacy version and the random.
     */
    private fun clientHelloRecord(clientRandom: ByteArray): ByteArray {
        val body = byteArrayOf(0x03, 0x03) + clientRandom
        val handshake = byteArrayOf(
            TlsHandshakeType.CLIENT_HELLO.toByte(),
            0, 0, body.size.toByte(),
        ) + body
        return tlsRecord(TlsContentType.HANDSHAKE, handshake)
    }

    /**
     * A ServerHello carrying the cipher suite and, for TLS 1.3, a
     * `supported_versions` extension of 0x0304 — which is how TLS 1.3 is
     * actually signalled, the record-layer version staying at 0x0303.
     * [cipherSuiteId] is a raw id so that a suite this build does not know
     * about can be put on the wire.
     */
    private fun serverHelloRecord(
        serverRandom: ByteArray,
        cipherSuiteId: Int,
        tls13: Boolean,
        compressionMethod: Int = 0,
    ): ByteArray {
        val supportedVersions = if (tls13) {
            byteArrayOf(
                0x00, 0x2B, // extension type: supported_versions
                0x00, 0x02, // extension length
                0x03, 0x04, // TLS 1.3
            )
        } else {
            ByteArray(0)
        }
        val body = byteArrayOf(0x03, 0x03) + serverRandom +
            byteArrayOf(0x00) + // session_id_length
            byteArrayOf((cipherSuiteId shr 8).toByte(), (cipherSuiteId and 0xFF).toByte()) +
            byteArrayOf(compressionMethod.toByte()) +
            byteArrayOf((supportedVersions.size shr 8).toByte(), supportedVersions.size.toByte()) +
            supportedVersions
        val handshake = byteArrayOf(
            TlsHandshakeType.SERVER_HELLO.toByte(),
            0, 0, body.size.toByte(),
        ) + body
        return tlsRecord(TlsContentType.HANDSHAKE, handshake)
    }

    /**
     * Seals [text] as a TLS 1.3 Application Data record. The inner plaintext
     * carries the real content type as its last byte (RFC 8446 §5.2), and the
     * AAD is the record header — including the length, which is why the
     * ciphertext size has to be known before the header is written.
     */
    private fun applicationDataRecord(
        text: String,
        key: ByteArray,
        staticIv: ByteArray,
        sequence: Long,
    ): ByteArray {
        val innerPlaintext = text.encodeToByteArray() +
            byteArrayOf(TlsContentType.APPLICATION_DATA.toByte())
        val recordLength = innerPlaintext.size + AEAD_TAG_SIZE

        val nonce = staticIv.copyOf()
        for (i in 0 until 8) {
            nonce[nonce.size - 8 + i] = (nonce[nonce.size - 8 + i].toInt() xor
                ((sequence shr (56 - 8 * i)) and 0xFF).toInt()).toByte()
        }
        val aad = byteArrayOf(
            TlsContentType.APPLICATION_DATA.toByte(), 0x03, 0x03,
            (recordLength shr 8).toByte(), (recordLength and 0xFF).toByte(),
        )
        val sealed = AesGcm.encrypt(innerPlaintext, AesKeySchedule(key), nonce, aad, AEAD_TAG_SIZE)
        assertEquals(recordLength, sealed.size)
        return tlsRecord(TlsContentType.APPLICATION_DATA, sealed)
    }

    private fun tlsRecord(contentType: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            contentType.toByte(), 0x03, 0x03,
            (payload.size shr 8).toByte(), (payload.size and 0xFF).toByte(),
        ) + payload

    private class TcpPayload(val fromClient: Boolean, val bytes: ByteArray)

    private fun buildPcap(payloads: List<TcpPayload>): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(globalHeader().toList())
        for ((index, payload) in payloads.withIndex()) {
            val packet = ethernetIpv4TcpPacket(payload)
            out.addAll(packetHeader(tsSec = 1000 + index.toLong(), length = packet.size).toList())
            out.addAll(packet.toList())
        }
        return out.toByteArray()
    }

    private fun ethernetIpv4TcpPacket(payload: TcpPayload): ByteArray {
        val ethernet = ByteArray(14).also {
            it[12] = 0x08
            it[13] = 0x00 // IPv4
        }
        val ipTotalLength = 20 + 20 + payload.bytes.size
        val ip = ByteArray(20)
        ip[0] = 0x45 // version 4, header length 5 words
        ip[2] = (ipTotalLength shr 8).toByte()
        ip[3] = (ipTotalLength and 0xFF).toByte()
        ip[9] = 6 // TCP
        val (srcIp, dstIp) = if (payload.fromClient) {
            byteArrayOf(10, 0, 0, 1) to byteArrayOf(10, 0, 0, 2)
        } else {
            byteArrayOf(10, 0, 0, 2) to byteArrayOf(10, 0, 0, 1)
        }
        srcIp.copyInto(ip, 12)
        dstIp.copyInto(ip, 16)

        val (srcPort, dstPort) = if (payload.fromClient) CLIENT_PORT to SERVER_PORT else SERVER_PORT to CLIENT_PORT
        val tcp = ByteArray(20)
        tcp[0] = (srcPort shr 8).toByte()
        tcp[1] = (srcPort and 0xFF).toByte()
        tcp[2] = (dstPort shr 8).toByte()
        tcp[3] = (dstPort and 0xFF).toByte()
        tcp[12] = 0x50 // data offset = 5 words
        tcp[13] = 0x18 // PSH + ACK

        return ethernet + ip + tcp + payload.bytes
    }

    private fun globalHeader(): ByteArray {
        val buf = ByteArray(24)
        buf[0] = 0xD4.toByte(); buf[1] = 0xC3.toByte()
        buf[2] = 0xB2.toByte(); buf[3] = 0xA1.toByte()
        buf[4] = 2; buf[6] = 4
        putU32LE(buf, 16, 65535)
        putU32LE(buf, 20, LINK_TYPE_ETHERNET)
        return buf
    }

    private fun packetHeader(tsSec: Long, length: Int): ByteArray {
        val buf = ByteArray(16)
        putU32LE(buf, 0, tsSec)
        putU32LE(buf, 4, 0)
        putU32LE(buf, 8, length.toLong())
        putU32LE(buf, 12, length.toLong())
        return buf
    }

    private fun putU32LE(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private companion object {
        const val LINK_TYPE_ETHERNET = 1L
        const val CLIENT_PORT = 54321
        const val SERVER_PORT = 443
        const val AEAD_TAG_SIZE = 16

        /** ClientHello, ServerHello, and one ChangeCipherSpec packet per direction. */
        const val TLS12_HANDSHAKE_PACKETS = 4

        fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
