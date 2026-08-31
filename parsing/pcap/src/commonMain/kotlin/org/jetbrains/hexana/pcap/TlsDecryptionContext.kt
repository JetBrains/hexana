package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.pcap.crypto.Digest
import org.jetbrains.hexana.pcap.crypto.Hmac
import org.jetbrains.hexana.pcap.crypto.Inflate
import org.jetbrains.hexana.pcap.crypto.Sha1Digest
import org.jetbrains.hexana.pcap.crypto.Sha256Digest
import org.jetbrains.hexana.pcap.crypto.Sha512Digest

/**
 * Pre-scanned TLS decryption context for a PCAP capture. Scans all packets
 * once to extract per-stream handshake metadata (cipher suite, server random,
 * TLS version, direction), derives the traffic keys for every stream that the
 * loaded [SslKeyLog] covers, and assigns each protected record its AEAD
 * sequence number. [tryDecrypt] is then a pure lookup plus one AEAD open.
 *
 * Everything is resolved up front deliberately: record sequence numbers are
 * a property of the capture, not of the order a UI happens to ask about rows.
 * Deriving them lazily made decryption depend on access order, so sorting or
 * searching the packet table silently broke every record reached out of turn.
 *
 * The same reasoning applies to the [tlsDecryptionByteBudget] ceiling the
 * browser targets need: the records that fall inside it are chosen during the
 * build scan, in capture order, rather than by whichever rows happen to be
 * read first. [isBeyondDecryptionBudget] tells them apart from records with no
 * key material, which is a different thing to show the user.
 *
 * Instances hold no mutable state of their own, so results no longer depend
 * on the order rows are asked about. Concurrent use is still bounded by
 * whatever the supplied [TlsDecryptor] allows.
 */
class TlsDecryptionContext private constructor(
    private val decryptor: TlsDecryptor,
    private val streamKeys: Map<TcpStreamKey, StreamKeys>,
    /** Packet file offset -> AEAD sequence number for that record's direction. */
    private val recordSequenceNumbers: Map<Long, Long>,
    /** File offsets of decryptable records left unopened by the byte budget. */
    private val recordsBeyondBudget: Set<Long>,
    /**
     * Plaintext for records of streams that negotiated TLS-level compression,
     * decompressed during the build scan. A DEFLATE stream spans a whole
     * connection, so unlike the AEAD layer it cannot be entered at an
     * arbitrary record and has to be resolved up front.
     */
    private val decompressedRecords: Map<Long, ByteArray>,
    private val decryptableRecords: Int,
    private val diagnostics: Diagnostics,
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
        /** The ServerHello's compression method: 0 for none, 1 for DEFLATE. */
        val compressionMethod: Int = COMPRESSION_NULL,
    ) {
        override fun equals(other: Any?): Boolean =
            other is StreamInfo && cipherSuiteId == other.cipherSuiteId &&
                isTls13 == other.isTls13 &&
                (serverRandom contentEquals other.serverRandom) &&
                serverSrcKey == other.serverSrcKey &&
                compressionMethod == other.compressionMethod

        override fun hashCode(): Int {
            var h = cipherSuiteId
            h = 31 * h + isTls13.hashCode()
            h = 31 * h + (serverRandom?.contentHashCode() ?: 0)
            h = 31 * h + serverSrcKey.hashCode()
            h = 31 * h + compressionMethod
            return h
        }
    }

    /**
     * Traffic keys for one TLS stream, derived once at build time rather than
     * once per record. For TLS 1.3 the `iv` fields hold the static per-direction
     * IV that the sequence number is XORed into; for TLS 1.2 they hold the
     * 4-byte implicit write IV that the record's explicit nonce is appended to.
     */
    internal class StreamKeys(
        val cipherSuite: TlsCipherSuite,
        val isTls13: Boolean,
        val clientKey: ByteArray,
        val clientIv: ByteArray,
        val serverKey: ByteArray,
        val serverIv: ByteArray,
        /** The IP:port of the server, used to work out a record's direction. */
        val serverSrcKey: String,
        /** Whether the connection negotiated TLS-level DEFLATE compression. */
        val usesDeflate: Boolean = false,
        /** MAC-then-encrypt keys; empty for the AEAD suites, which have no separate MAC. */
        val clientMacKey: ByteArray = ByteArray(0),
        val serverMacKey: ByteArray = ByteArray(0),
    )

    /**
     * Why the streams this capture could *not* decrypt were given up on.
     * Without this the packet table can only say "encrypted", which reads as
     * "you loaded the wrong key log" even when the real reason is a cipher
     * suite this build has never heard of.
     */
    class Diagnostics internal constructor(
        /** Streams whose keys were derived and whose records are numbered. */
        val decryptableStreams: Int,
        /** Streams whose TLS session is absent from the loaded key log. */
        val streamsWithoutKeys: Int,
        /**
         * Cipher suite ids seen in a ServerHello that [TlsCipherSuite] does not
         * cover — every non-AEAD suite, since this record layer has no
         * MAC-then-encrypt path, plus any AEAD suite not yet listed.
         */
        val unsupportedCipherSuiteIds: Set<Int>,
        /** Streams whose key material was present but would not derive. */
        val keyDerivationFailures: Int,
        /**
         * Streams with usable keys where a direction's records never
         * authenticated, so no sequence number could be pinned for it. A wrong
         * key log entry, or a capture that lost a record and left the
         * direction un-alignable.
         */
        val unauthenticatedStreams: Int,
        /**
         * Streams that negotiated TLS-level compression this build cannot
         * undo: a compression method other than DEFLATE, or a DEFLATE stream
         * that stopped decoding partway through the capture.
         */
        val compressionFailures: Int,
    ) {
        /** Whether anything at all went wrong worth telling the user about. */
        val hasFailures: Boolean
            get() = streamsWithoutKeys > 0 || unsupportedCipherSuiteIds.isNotEmpty() ||
                keyDerivationFailures > 0 || unauthenticatedStreams > 0 || compressionFailures > 0
    }

    /** What kept this capture's TLS streams from being decrypted, if anything. */
    val streamDiagnostics: Diagnostics get() = diagnostics

    /** Cipher suites seen in this capture that this platform cannot decrypt. */
    val undecryptableCipherSuites: Set<TlsCipherSuite>
        get() = streamKeys.values
            .map { it.cipherSuite }
            .filterNot { isTlsDecryptionSupported(it) }
            .toSet()

    /**
     * Application Data records this capture has key material for: the ones
     * [tryDecrypt] could open if the byte budget were unlimited.
     */
    val decryptableRecordCount: Int get() = decryptableRecords

    /**
     * How many of [decryptableRecordCount] the byte budget left unopened.
     * Zero on platforms with no ceiling, and on captures small enough to fit.
     */
    val recordsBeyondBudgetCount: Int get() = recordsBeyondBudget.size

    /**
     * Whether [packet] holds a record that this capture has keys for but that
     * fell outside [tlsDecryptionByteBudget]. Such records read as *not
     * decrypted* rather than *encrypted*: the keys are there, the platform
     * just declined to spend the time.
     */
    fun isBeyondDecryptionBudget(packet: PcapPacketRecord): Boolean =
        packet.fileOffset in recordsBeyondBudget

    /**
     * Attempts to decrypt TLS Application Data from the given packet.
     * Returns the decrypted plaintext bytes, or `null` if decryption is not
     * possible (no key material, unsupported cipher, not Application Data,
     * beyond the byte budget, etc.).
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
        val keys = streamKeys[canonical] ?: return null
        val seqNum = recordSequenceNumbers[packet.fileOffset] ?: return null
        if (packet.fileOffset in recordsBeyondBudget) return null

        // Compressed streams were resolved during the build scan; a record's
        // plaintext depends on every record before it, so there is nothing to
        // open here.
        if (keys.usesDeflate) return decompressedRecords[packet.fileOffset]

        // Determine direction: is this packet from the server?
        val senderKey = "${tcpInfo.streamKey.srcIp}:${tcpInfo.streamKey.srcPort}"
        val isFromServer = senderKey == keys.serverSrcKey

        val recordPayloadOffset = tcpInfo.payloadOffset + TlsRecordParser.RECORD_HEADER_SIZE
        val recordPayloadLen = minOf(hdr.length, tcpInfo.payloadLen - TlsRecordParser.RECORD_HEADER_SIZE)
        if (recordPayloadLen <= 0) return null

        val ciphertext = readBytes(buffer, recordPayloadOffset, recordPayloadLen)
        return openRecord(decryptor, ciphertext, keys, isFromServer, seqNum, hdr)
    }

    companion object {
        /**
         * How many leading protected records of a direction may be tried before
         * giving up on finding the one that carries sequence number zero. In
         * TLS 1.3 the encrypted handshake flight travels as content type 23
         * under *handshake* traffic keys, which the key log's
         * `*_TRAFFIC_SECRET_0` entries cannot open; those records have to be
         * stepped over without consuming an application sequence number.
         * EncryptedExtensions, Certificate, CertificateVerify and Finished is
         * four, so eight leaves room to spare.
         */
        private const val MAX_ALIGNMENT_SKIP = 8

        /**
         * How far past zero the first openable record's sequence number may be
         * searched for. Non-zero only when the capture lost a record — one
         * spanning two TCP segments, say, which this per-packet record layer
         * cannot reassemble.
         */
        private const val MAX_ALIGNMENT_SEQ = 2

        /** ServerHello compression_method values (RFC 3749 §2). */
        private const val CBC_BLOCK_SIZE = 16
        private const val SHA384_DIGEST_SIZE = 48
        private const val COMPRESSION_NULL = 0
        private const val COMPRESSION_DEFLATE = 1

        /**
         * Builds a decryption context using this platform's TLS crypto
         * implementation. Prefer this overload: it keeps callers free of
         * platform-specific wiring and works from common code.
         */
        fun build(
            buffer: CommonByteBuffer,
            packets: List<PcapPacketRecord>,
            linkType: Long,
            sslKeyLog: SslKeyLog,
            tlsSessionMap: Map<TcpStreamKey, String>,
            byteBudget: Long = tlsDecryptionByteBudget,
        ): TlsDecryptionContext = build(
            buffer = buffer,
            packets = packets,
            linkType = linkType,
            sslKeyLog = sslKeyLog,
            tlsSessionMap = tlsSessionMap,
            decryptor = platformTlsDecryptor(),
            keyDeriver = platformTlsKeyDeriver(),
            byteBudget = byteBudget,
        )

        /**
         * Builds a decryption context with an explicit [decryptor] and
         * [keyDeriver]. Scans all packets in the capture to extract per-stream
         * TLS handshake metadata, derives traffic keys, and numbers records.
         *
         * [byteBudget] caps the total ciphertext the context will open; tests
         * set it explicitly rather than depending on the platform's value.
         */
        fun build(
            buffer: CommonByteBuffer,
            packets: List<PcapPacketRecord>,
            linkType: Long,
            sslKeyLog: SslKeyLog,
            tlsSessionMap: Map<TcpStreamKey, String>,
            decryptor: TlsDecryptor,
            keyDeriver: TlsKeyDeriver,
            byteBudget: Long = tlsDecryptionByteBudget,
        ): TlsDecryptionContext {
            val streamInfoMap = scanHandshakes(buffer, packets, linkType)
            val derived = deriveStreamKeys(streamInfoMap, sslKeyLog, tlsSessionMap, keyDeriver)
            val numbering = numberProtectedRecords(
                buffer, packets, linkType, derived.keys, decryptor, byteBudget,
            )
            return TlsDecryptionContext(
                decryptor = decryptor,
                streamKeys = derived.keys,
                recordSequenceNumbers = numbering.sequenceNumbers,
                recordsBeyondBudget = numbering.beyondBudget,
                decompressedRecords = numbering.decompressed,
                decryptableRecords = numbering.candidateCount,
                diagnostics = Diagnostics(
                    decryptableStreams = derived.keys.size - numbering.unauthenticatedStreams,
                    streamsWithoutKeys = derived.streamsWithoutKeys,
                    unsupportedCipherSuiteIds = derived.unsupportedCipherSuiteIds,
                    keyDerivationFailures = derived.keyDerivationFailures,
                    unauthenticatedStreams = numbering.unauthenticatedStreams,
                    compressionFailures = derived.compressionFailures + numbering.compressionFailures,
                ),
            )
        }

        /** What one build scan learned about the capture's protected records. */
        private class RecordNumbering(
            val sequenceNumbers: Map<Long, Long>,
            val beyondBudget: Set<Long>,
            /** Plaintext of the compressed streams' records, keyed by packet file offset. */
            val decompressed: Map<Long, ByteArray>,
            /** Compressed streams whose DEFLATE state stopped decoding. */
            val compressionFailures: Int,
            /** Streams where at least one direction would not authenticate. */
            val unauthenticatedStreams: Int,
            /** Application Data records in streams this capture holds keys for. */
            val candidateCount: Int,
        )

        /** Traffic keys, plus why the other streams did not get any. */
        private class DerivedKeys(
            val keys: Map<TcpStreamKey, StreamKeys>,
            val streamsWithoutKeys: Int,
            val unsupportedCipherSuiteIds: Set<Int>,
            val keyDerivationFailures: Int,
            /** Streams using a compression method other than none or DEFLATE. */
            val compressionFailures: Int,
        )

        /**
         * Derives the traffic keys for every stream whose TLS session appears
         * in [sslKeyLog]. Streams with no key material, an unknown cipher suite
         * or a key derivation failure carry no keys, so they are dropped — but
         * counted, because "encrypted" on its own tells the user nothing about
         * which of those three happened.
         */
        private fun deriveStreamKeys(
            streamInfoMap: Map<TcpStreamKey, StreamInfo>,
            sslKeyLog: SslKeyLog,
            tlsSessionMap: Map<TcpStreamKey, String>,
            keyDeriver: TlsKeyDeriver,
        ): DerivedKeys {
            val result = mutableMapOf<TcpStreamKey, StreamKeys>()
            val unsupportedSuites = mutableSetOf<Int>()
            var withoutKeys = 0
            var derivationFailures = 0
            var compressionFailures = 0
            for ((canonical, info) in streamInfoMap) {
                val clientRandomHex = tlsSessionMap[canonical]
                if (clientRandomHex == null) {
                    withoutKeys++
                    continue
                }
                val secrets = sslKeyLog.lookup(clientRandomHex)
                if (secrets.isEmpty()) {
                    withoutKeys++
                    continue
                }
                val cipherSuite = TlsCipherSuite.fromId(info.cipherSuiteId)
                if (cipherSuite == null) {
                    unsupportedSuites += info.cipherSuiteId
                    continue
                }
                // Compression sits between the plaintext and the record
                // protection, so a method this build cannot undo leaves the
                // stream unreadable however good the keys are.
                if (info.compressionMethod != COMPRESSION_NULL &&
                    info.compressionMethod != COMPRESSION_DEFLATE
                ) {
                    compressionFailures++
                    continue
                }
                val usesDeflate = info.compressionMethod == COMPRESSION_DEFLATE

                val keys = if (info.isTls13) {
                    val clientSecret = secrets.firstOrNull { it.label == "CLIENT_TRAFFIC_SECRET_0" }?.secret
                    val serverSecret = secrets.firstOrNull { it.label == "SERVER_TRAFFIC_SECRET_0" }?.secret
                    if (clientSecret == null || serverSecret == null) {
                        withoutKeys++
                        continue
                    }
                    val client = keyDeriver.deriveTrafficKeyAndIv(clientSecret, cipherSuite)
                    val server = keyDeriver.deriveTrafficKeyAndIv(serverSecret, cipherSuite)
                    if (client == null || server == null) {
                        derivationFailures++
                        continue
                    }
                    StreamKeys(
                        cipherSuite = cipherSuite,
                        isTls13 = true,
                        clientKey = client.first,
                        clientIv = client.second,
                        serverKey = server.first,
                        serverIv = server.second,
                        serverSrcKey = info.serverSrcKey,
                        usesDeflate = usesDeflate,
                    )
                } else {
                    val masterSecret = secrets.firstOrNull { it.label == "CLIENT_RANDOM" }?.secret
                    val clientRandom = hexToBytes(clientRandomHex)
                    if (masterSecret == null) {
                        withoutKeys++
                        continue
                    }
                    val serverRandom = info.serverRandom
                    if (serverRandom == null || clientRandom == null) {
                        derivationFailures++
                        continue
                    }
                    val material = keyDeriver.deriveTls12Keys(
                        masterSecret, serverRandom, clientRandom, cipherSuite,
                    )
                    if (material == null) {
                        derivationFailures++
                        continue
                    }
                    StreamKeys(
                        cipherSuite = cipherSuite,
                        isTls13 = false,
                        clientKey = material.clientWriteKey,
                        clientIv = material.clientWriteIv,
                        serverKey = material.serverWriteKey,
                        serverIv = material.serverWriteIv,
                        serverSrcKey = info.serverSrcKey,
                        usesDeflate = usesDeflate,
                        clientMacKey = material.clientMacKey,
                        serverMacKey = material.serverMacKey,
                    )
                }
                result[canonical] = keys
            }
            return DerivedKeys(result, withoutKeys, unsupportedSuites, derivationFailures, compressionFailures)
        }

        /** One AEAD-protected record, as the numbering pass sees it. */
        private class ProtectedRecord(
            /** Position in the whole-capture record walk, so the budget can be spent in capture order. */
            val captureIndex: Int,
            val packetFileOffset: Long,
            /** Only the first record of a packet is reachable from [tryDecrypt]. */
            val isFirstInPacket: Boolean,
            val header: TlsRecordHeader,
            val bodyOffset: Int,
            val bodyLen: Int,
        )

        /** A stream direction: the two directions have separate sequence spaces. */
        private data class DirectionKey(val stream: TcpStreamKey, val fromServer: Boolean)

        /**
         * Walks the capture in order and assigns each protected record its AEAD
         * sequence number: the count of records already sent in that stream
         * direction under the same keys.
         *
         * Which records count is version-specific, and getting it wrong shifts
         * every nonce and AAD in the direction by one, which fails the tag and
         * looks exactly like a missing key:
         *
         * * TLS 1.2 resets the sequence number when a cipher state is activated
         *   (RFC 5246 §6.1), so counting starts at the ChangeCipherSpec and
         *   *includes* the encrypted Finished that immediately follows it. The
         *   Finished owns sequence number 0; the first Application Data record
         *   is 1. Alerts and renegotiation records count too.
         * * TLS 1.3 hides its handshake flight in content type 23 records under
         *   handshake traffic keys, which the key log's `*_TRAFFIC_SECRET_0`
         *   entries cannot open and which have their own sequence space. Those
         *   have to be skipped without consuming a number, while a
         *   post-handshake NewSessionTicket does consume one.
         *
         * Since a record's keys cannot be told from its header, the start of
         * each direction is found by opening candidates until one authenticates
         * — see [MAX_ALIGNMENT_SKIP]. A 128-bit tag makes a false positive not
         * worth reasoning about.
         *
         * The same walk spends [byteBudget]. Records are numbered whatever
         * happens — a sequence number is a fact about the capture — but once
         * the running ciphertext total passes the budget the rest are marked
         * beyond it. Deciding here rather than at read time is what keeps the
         * set of decrypted records the same no matter which rows a sort or a
         * search reaches first.
         */
        private fun numberProtectedRecords(
            buffer: CommonByteBuffer,
            packets: List<PcapPacketRecord>,
            linkType: Long,
            streamKeys: Map<TcpStreamKey, StreamKeys>,
            decryptor: TlsDecryptor,
            byteBudget: Long,
        ): RecordNumbering {
            if (streamKeys.isEmpty()) return RecordNumbering(emptyMap(), emptySet(), emptyMap(), 0, 0, 0)
            val perDirection = collectProtectedRecords(buffer, packets, linkType, streamKeys)

            // Spend the budget first, over the records tryDecrypt can reach, in
            // capture order rather than per-direction order — so the rows that
            // decrypt are the first ones in the file whichever row a sort or a
            // search asks about first. This does not depend on the alignment
            // below, which is what lets the alignment be skipped for the
            // directions that have nothing left to spend on.
            val candidates = perDirection.values
                .flatten()
                .filter { it.isFirstInPacket && it.header.contentType == TlsContentType.APPLICATION_DATA }
                .sortedBy { it.captureIndex }
            val beyondBudget = HashSet<Long>()
            var spent = 0L
            for (record in candidates) {
                if (spent > byteBudget) {
                    beyondBudget += record.packetFileOffset
                } else {
                    // Charged after the check, and strictly greater, so the
                    // record that crosses the line is still decrypted and no
                    // budget — not even zero — decrypts nothing at all. The
                    // overshoot is one record, at most 16 KiB.
                    spent += record.bodyLen.toLong()
                }
            }

            val sequenceNumbers = HashMap<Long, Long>()
            val decompressed = HashMap<Long, ByteArray>()
            val unauthenticated = HashSet<TcpStreamKey>()
            var compressionFailures = 0
            for ((direction, records) in perDirection) {
                val keys = streamKeys[direction.stream] ?: continue
                val worthAligning = records.any {
                    it.isFirstInPacket &&
                        it.header.contentType == TlsContentType.APPLICATION_DATA &&
                        it.packetFileOffset !in beyondBudget
                }
                if (!worthAligning) continue
                val start = alignSequenceNumbers(buffer, records, keys, direction.fromServer, decryptor)
                if (start == null) {
                    unauthenticated += direction.stream
                    continue
                }
                for (i in start.recordIndex until records.size) {
                    val record = records[i]
                    if (!record.isFirstInPacket) continue
                    if (record.header.contentType != TlsContentType.APPLICATION_DATA) continue
                    sequenceNumbers[record.packetFileOffset] = start.sequenceNumber + (i - start.recordIndex)
                }
                if (keys.usesDeflate) {
                    val complete = decompressDirection(
                        buffer, records, start, keys, direction.fromServer,
                        decryptor, beyondBudget, decompressed,
                    )
                    if (!complete) compressionFailures++
                }
            }
            return RecordNumbering(
                sequenceNumbers = sequenceNumbers,
                beyondBudget = beyondBudget,
                decompressed = decompressed,
                compressionFailures = compressionFailures,
                unauthenticatedStreams = unauthenticated.size,
                candidateCount = candidates.size,
            )
        }

        /**
         * Runs one direction's records through a single DEFLATE stream and
         * files the plaintext of the ones [tryDecrypt] can reach into
         * [into], keyed by packet file offset.
         *
         * Every protected record has to go through the inflater, not just the
         * Application Data ones: TLS compresses the connection, so the
         * encrypted Finished and any alert contribute to the LZ77 history that
         * later records back-reference. Skipping one would corrupt everything
         * after it.
         *
         * Stops at the first record the byte budget excluded — there is no way
         * to resume a DEFLATE stream past a gap — and reports whether it got
         * all the way to the end. Returns true when it stopped only because of
         * the budget, since that is the caller's decision rather than a
         * failure of the capture.
         */
        @Suppress("LongParameterList")
        private fun decompressDirection(
            buffer: CommonByteBuffer,
            records: List<ProtectedRecord>,
            start: SequenceStart,
            keys: StreamKeys,
            fromServer: Boolean,
            decryptor: TlsDecryptor,
            beyondBudget: Set<Long>,
            into: MutableMap<Long, ByteArray>,
        ): Boolean {
            val inflate = Inflate()
            for (i in start.recordIndex until records.size) {
                val record = records[i]
                val reachable = record.isFirstInPacket &&
                    record.header.contentType == TlsContentType.APPLICATION_DATA
                if (reachable && record.packetFileOffset in beyondBudget) return true
                val ciphertext = readBytes(buffer, record.bodyOffset, record.bodyLen)
                val sequence = start.sequenceNumber + (i - start.recordIndex)
                val fragment = openRecord(decryptor, ciphertext, keys, fromServer, sequence, record.header)
                    ?: return false
                val plaintext = inflate.push(fragment) ?: return false
                if (reachable) into[record.packetFileOffset] = plaintext
            }
            return true
        }

        /**
         * Groups every AEAD-protected record of every decryptable stream by
         * direction, in capture order. Records that precede the change of
         * cipher state are not protected and are left out, apart from the
         * ChangeCipherSpec itself, which is the marker TLS 1.2 needs.
         */
        private fun collectProtectedRecords(
            buffer: CommonByteBuffer,
            packets: List<PcapPacketRecord>,
            linkType: Long,
            streamKeys: Map<TcpStreamKey, StreamKeys>,
        ): Map<DirectionKey, List<ProtectedRecord>> {
            val result = LinkedHashMap<DirectionKey, MutableList<ProtectedRecord>>()
            val cipherStateActive = HashSet<DirectionKey>()
            var captureIndex = 0
            for (packet in packets) {
                val tcpInfo = extractTcpInfo(buffer, packet, linkType) ?: continue
                val keys = streamKeys[tcpInfo.streamKey.canonical] ?: continue
                val senderKey = "${tcpInfo.streamKey.srcIp}:${tcpInfo.streamKey.srcPort}"
                val direction = DirectionKey(tcpInfo.streamKey.canonical, senderKey == keys.serverSrcKey)

                var offset = tcpInfo.payloadOffset
                var remaining = tcpInfo.payloadLen
                var first = true
                while (remaining >= TlsRecordParser.RECORD_HEADER_SIZE + 1) {
                    val hdr = TlsRecordParser.parseRecordHeader(buffer, offset, remaining) ?: break
                    val bodyLen = minOf(hdr.length, remaining - TlsRecordParser.RECORD_HEADER_SIZE)
                    if (bodyLen <= 0) break

                    val protected = if (keys.isTls13) {
                        // The TLS 1.3 ChangeCipherSpec is a compatibility relic
                        // sent in the clear, so content type is the whole test.
                        hdr.contentType == TlsContentType.APPLICATION_DATA
                    } else if (hdr.contentType == TlsContentType.CHANGE_CIPHER_SPEC) {
                        cipherStateActive += direction
                        false
                    } else {
                        direction in cipherStateActive
                    }
                    if (protected) {
                        result.getOrPut(direction) { mutableListOf() } += ProtectedRecord(
                            captureIndex = captureIndex++,
                            packetFileOffset = packet.fileOffset,
                            isFirstInPacket = first,
                            header = hdr,
                            bodyOffset = offset + TlsRecordParser.RECORD_HEADER_SIZE,
                            bodyLen = bodyLen,
                        )
                    }

                    val advance = TlsRecordParser.RECORD_HEADER_SIZE + bodyLen
                    offset += advance
                    remaining -= advance
                    first = false
                }
            }
            return result
        }

        /** Where a direction's sequence space starts, once a record has proved it. */
        private class SequenceStart(val recordIndex: Int, val sequenceNumber: Long)

        /**
         * Finds the first record of [records] that authenticates, and the
         * sequence number it authenticates under. Bounded by
         * [MAX_ALIGNMENT_SKIP] and [MAX_ALIGNMENT_SEQ], so a stream whose keys
         * do not match costs a fixed handful of AEAD opens rather than one per
         * record.
         */
        private fun alignSequenceNumbers(
            buffer: CommonByteBuffer,
            records: List<ProtectedRecord>,
            keys: StreamKeys,
            fromServer: Boolean,
            decryptor: TlsDecryptor,
        ): SequenceStart? {
            val limit = minOf(records.size, MAX_ALIGNMENT_SKIP)
            for (index in 0 until limit) {
                val record = records[index]
                val ciphertext = readBytes(buffer, record.bodyOffset, record.bodyLen)
                for (seq in 0L..MAX_ALIGNMENT_SEQ) {
                    val opened = openRecord(decryptor, ciphertext, keys, fromServer, seq, record.header)
                    if (opened != null) return SequenceStart(index, seq)
                }
            }
            return null
        }

        private fun readBytes(buffer: CommonByteBuffer, offset: Int, len: Int): ByteArray {
            val bytes = ByteArray(len)
            buffer.position(offset)
            for (i in 0 until len) {
                bytes[i] = buffer.get()
            }
            return bytes
        }

        private fun openRecord(
            decryptor: TlsDecryptor,
            ciphertext: ByteArray,
            keys: StreamKeys,
            isFromServer: Boolean,
            seqNum: Long,
            hdr: TlsRecordHeader,
        ): ByteArray? = when {
            keys.isTls13 -> openTls13(decryptor, ciphertext, keys, isFromServer, seqNum, hdr)
            keys.cipherSuite.isCbc -> openTls12Cbc(decryptor, ciphertext, keys, isFromServer, seqNum, hdr)
            else -> openTls12(decryptor, ciphertext, keys, isFromServer, seqNum, hdr)
        }

        private fun openTls13(
            decryptor: TlsDecryptor,
            ciphertext: ByteArray,
            keys: StreamKeys,
            isFromServer: Boolean,
            seqNum: Long,
            hdr: TlsRecordHeader,
        ): ByteArray? {
            val key = if (isFromServer) keys.serverKey else keys.clientKey
            val staticIv = if (isFromServer) keys.serverIv else keys.clientIv

            // Construct per-record nonce: XOR sequence number with static IV (RFC 8446 §5.3)
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

            val plaintext = decryptor.decrypt(ciphertext, key, nonce, aad, keys.cipherSuite) ?: return null

            // TLS 1.3: the last byte of plaintext is the inner content type;
            // strip trailing zeros and the content type byte
            if (plaintext.isEmpty()) return null
            var end = plaintext.size - 1
            while (end > 0 && plaintext[end] == 0.toByte()) end--
            if (end < 0) return null
            // plaintext[end] is the inner content type
            return plaintext.copyOfRange(0, end)
        }

        private fun openTls12(
            decryptor: TlsDecryptor,
            ciphertext: ByteArray,
            keys: StreamKeys,
            isFromServer: Boolean,
            seqNum: Long,
            hdr: TlsRecordHeader,
        ): ByteArray? {
            val writeKey = if (isFromServer) keys.serverKey else keys.clientKey
            val writeIv = if (isFromServer) keys.serverIv else keys.clientIv

            // TLS 1.2 GCM: the nonce is implicit_iv(4) + explicit_nonce(8)
            // The explicit nonce is the first 8 bytes of the ciphertext
            if (ciphertext.size < 8) return null
            val explicitNonce = ciphertext.copyOfRange(0, 8)
            val encryptedPayload = ciphertext.copyOfRange(8, ciphertext.size)
            val nonce = writeIv + explicitNonce

            // AAD for TLS 1.2: seq_num(8) + content_type(1) + version(2) + length(2)
            val plaintextLen = encryptedPayload.size - keys.cipherSuite.tagLength
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

            return decryptor.decrypt(encryptedPayload, writeKey, nonce, aad, keys.cipherSuite)
        }

        /**
         * Opens one MAC-then-encrypt CBC record (RFC 5246 §6.2.3.2).
         *
         * Only the TLS 1.1+ layout is handled: there the record carries its own
         * explicit IV, so each record is independent. TLS 1.0 chains the IV from
         * the previous record's last ciphertext block, which the sequence-number
         * alignment scan has no way to bootstrap; those records are reported as
         * undecryptable rather than decoded into garbage.
         *
         * The MAC is verified because it is the only signal that the derived keys
         * and the sequence number are right — [alignSequenceNumbers] relies on it
         * exactly as it relies on the AEAD tag. A plain comparison is fine: this
         * reads a capture that was recorded long ago, so there is no live peer to
         * leak timing to.
         */
        private fun openTls12Cbc(
            decryptor: TlsDecryptor,
            ciphertext: ByteArray,
            keys: StreamKeys,
            isFromServer: Boolean,
            seqNum: Long,
            hdr: TlsRecordHeader,
        ): ByteArray? {
            if (hdr.versionMajor != 3 || hdr.versionMinor < 2) return null
            val macLength = keys.cipherSuite.macAlgorithm.macLength
            if (macLength == 0) return null
            if (ciphertext.size < 2 * CBC_BLOCK_SIZE || ciphertext.size % CBC_BLOCK_SIZE != 0) return null

            val iv = ciphertext.copyOfRange(0, CBC_BLOCK_SIZE)
            val body = ciphertext.copyOfRange(CBC_BLOCK_SIZE, ciphertext.size)
            val writeKey = if (isFromServer) keys.serverKey else keys.clientKey
            val plain = decryptor.decryptCbc(body, writeKey, iv) ?: return null
            if (plain.isEmpty()) return null

            // TLS padding: padLen repeated padLen + 1 times, all of it verified.
            val padLength = plain[plain.size - 1].toInt() and 0xFF
            val contentEnd = plain.size - padLength - 1 - macLength
            if (contentEnd < 0) return null
            for (i in plain.size - padLength - 1 until plain.size) {
                if ((plain[i].toInt() and 0xFF) != padLength) return null
            }

            val content = plain.copyOfRange(0, contentEnd)
            val mac = plain.copyOfRange(contentEnd, contentEnd + macLength)
            val macKey = if (isFromServer) keys.serverMacKey else keys.clientMacKey
            if (macKey.isEmpty()) return null
            val expected = recordMac(keys.cipherSuite.macAlgorithm, macKey, seqNum, hdr, content)
                ?: return null
            if (!expected.contentEquals(mac)) return null
            return content
        }

        /** HMAC over `seq_num ‖ type ‖ version ‖ length ‖ fragment` (RFC 5246 §6.2.3.1). */
        private fun recordMac(
            algorithm: TlsMacAlgorithm,
            macKey: ByteArray,
            seqNum: Long,
            hdr: TlsRecordHeader,
            content: ByteArray,
        ): ByteArray? {
            val newDigest: () -> Digest = when (algorithm) {
                TlsMacAlgorithm.SHA1 -> ::Sha1Digest
                TlsMacAlgorithm.SHA256 -> ::Sha256Digest
                TlsMacAlgorithm.SHA384 -> { { Sha512Digest(SHA384_DIGEST_SIZE) } }
                TlsMacAlgorithm.AEAD -> return null
            }
            val header = ByteArray(13)
            for (i in 0 until 8) {
                header[i] = ((seqNum shr (56 - 8 * i)) and 0xFF).toByte()
            }
            header[8] = hdr.contentType.toByte()
            header[9] = hdr.versionMajor.toByte()
            header[10] = hdr.versionMinor.toByte()
            header[11] = (content.size shr 8).toByte()
            header[12] = (content.size and 0xFF).toByte()

            val hmac = Hmac(newDigest, macKey)
            hmac.update(header)
            hmac.update(content)
            return hmac.doFinal()
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
                    compressionMethod = TlsRecordParser.extractCompressionMethod(
                        buffer, hsOffset, hsAvailable,
                    ) ?: COMPRESSION_NULL,
                )
            }
            return result
        }
    }
}
