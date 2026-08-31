package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.CommonByteBuffer

/**
 * TLS content types as defined in RFC 8446 §5.1.
 */
object TlsContentType {
    const val CHANGE_CIPHER_SPEC: Int = 20
    const val ALERT: Int = 21
    const val HANDSHAKE: Int = 22
    const val APPLICATION_DATA: Int = 23
}

/**
 * TLS handshake message types (RFC 8446 §4).
 */
object TlsHandshakeType {
    const val CLIENT_HELLO: Int = 1
    const val SERVER_HELLO: Int = 2
    const val NEW_SESSION_TICKET: Int = 4
    const val ENCRYPTED_EXTENSIONS: Int = 8
    const val CERTIFICATE: Int = 11
    const val SERVER_KEY_EXCHANGE: Int = 12
    const val CERTIFICATE_REQUEST: Int = 13
    const val SERVER_HELLO_DONE: Int = 14
    const val CERTIFICATE_VERIFY: Int = 15
    const val CLIENT_KEY_EXCHANGE: Int = 16
    const val FINISHED: Int = 20

    fun name(type: Int): String = when (type) {
        CLIENT_HELLO -> "Client Hello"
        SERVER_HELLO -> "Server Hello"
        NEW_SESSION_TICKET -> "New Session Ticket"
        ENCRYPTED_EXTENSIONS -> "Encrypted Extensions"
        CERTIFICATE -> "Certificate"
        SERVER_KEY_EXCHANGE -> "Server Key Exchange"
        CERTIFICATE_REQUEST -> "Certificate Request"
        SERVER_HELLO_DONE -> "Server Hello Done"
        CERTIFICATE_VERIFY -> "Certificate Verify"
        CLIENT_KEY_EXCHANGE -> "Client Key Exchange"
        FINISHED -> "Finished"
        else -> "Unknown ($type)"
    }
}

/**
 * TLS alert descriptions (RFC 8446 §6.2).
 */
object TlsAlertDescription {
    fun name(desc: Int): String = when (desc) {
        0 -> "close_notify"
        10 -> "unexpected_message"
        20 -> "bad_record_mac"
        40 -> "handshake_failure"
        42 -> "bad_certificate"
        43 -> "unsupported_certificate"
        44 -> "certificate_revoked"
        45 -> "certificate_expired"
        46 -> "certificate_unknown"
        47 -> "illegal_parameter"
        48 -> "unknown_ca"
        49 -> "access_denied"
        50 -> "decode_error"
        51 -> "decrypt_error"
        70 -> "protocol_version"
        71 -> "insufficient_security"
        80 -> "internal_error"
        86 -> "inappropriate_fallback"
        90 -> "user_canceled"
        109 -> "missing_extension"
        112 -> "unrecognized_name"
        116 -> "certificate_required"
        120 -> "no_application_protocol"
        else -> "alert($desc)"
    }
}

/**
 * A parsed TLS record header.
 */
data class TlsRecordHeader(
    /** Content type (see [TlsContentType]). */
    val contentType: Int,
    /** Protocol version major (e.g. 3 for TLS). */
    val versionMajor: Int,
    /** Protocol version minor (e.g. 1 for TLS 1.0, 3 for TLS 1.2). */
    val versionMinor: Int,
    /** Length of the record payload (excluding the 5-byte header). */
    val length: Int,
) {
    /** Human-readable version string. */
    val versionString: String
        get() = when {
            versionMajor == 3 && versionMinor == 0 -> "SSL 3.0"
            versionMajor == 3 && versionMinor == 1 -> "TLS 1.0"
            versionMajor == 3 && versionMinor == 2 -> "TLS 1.1"
            versionMajor == 3 && versionMinor == 3 -> "TLS 1.2"
            versionMajor == 3 && versionMinor == 4 -> "TLS 1.3"
            else -> "$versionMajor.$versionMinor"
        }

    /** Content type name. */
    val contentTypeName: String
        get() = when (contentType) {
            TlsContentType.CHANGE_CIPHER_SPEC -> "Change Cipher Spec"
            TlsContentType.ALERT -> "Alert"
            TlsContentType.HANDSHAKE -> "Handshake"
            TlsContentType.APPLICATION_DATA -> "Application Data"
            else -> "Unknown ($contentType)"
        }
}

/**
 * Result of TLS-level dissection of a TCP payload.
 */
data class TlsDissectionResult(
    /** Protocol label for the Packets table (e.g. "TLSv1.2", "TLSv1.3"). */
    val protocol: String,
    /** Info string describing the TLS record(s) found. */
    val info: String,
    /** True if the payload contains encrypted application data that could be decrypted. */
    val hasEncryptedData: Boolean = false,
)

/**
 * Utilities for detecting and parsing TLS records in TCP payloads.
 */
object TlsRecordParser {

    /** TLS record header size. */
    const val RECORD_HEADER_SIZE = 5

    /**
     * Checks whether the bytes at [offset] look like a TLS record.
     * A valid TLS record starts with a content type byte (20–23),
     * followed by a version (0x0300–0x0304), and a 2-byte length ≤ 16384+256.
     */
    fun isTlsRecord(buffer: CommonByteBuffer, offset: Int, available: Int): Boolean {
        if (available < RECORD_HEADER_SIZE) return false
        val contentType = readU8(buffer, offset)
        if (contentType !in 20..23) return false
        val vMajor = readU8(buffer, offset + 1)
        val vMinor = readU8(buffer, offset + 2)
        if (vMajor != 3 || vMinor > 4) return false
        val length = readBigU16(buffer, offset + 3)
        return length in 0..16640 // max TLS record = 16384 + 256 overhead
    }

    /**
     * Parses a TLS record header at [offset].
     * Returns `null` if the data doesn't look like a valid TLS record.
     */
    fun parseRecordHeader(buffer: CommonByteBuffer, offset: Int, available: Int): TlsRecordHeader? {
        if (!isTlsRecord(buffer, offset, available)) return null
        return TlsRecordHeader(
            contentType = readU8(buffer, offset),
            versionMajor = readU8(buffer, offset + 1),
            versionMinor = readU8(buffer, offset + 2),
            length = readBigU16(buffer, offset + 3),
        )
    }

    /**
     * Dissects TLS records in a TCP payload, producing a summary for the
     * Packets table. Returns `null` if the payload doesn't start with a
     * valid TLS record.
     */
    fun dissect(buffer: CommonByteBuffer, offset: Int, len: Int): TlsDissectionResult? {
        val first = parseRecordHeader(buffer, offset, len) ?: return null

        val records = mutableListOf<TlsRecordHeader>()
        var pos = offset
        var remaining = len
        while (remaining >= RECORD_HEADER_SIZE) {
            val hdr = parseRecordHeader(buffer, pos, remaining) ?: break
            records.add(hdr)
            val advance = RECORD_HEADER_SIZE + hdr.length
            if (advance > remaining) break
            pos += advance
            remaining -= advance
        }

        if (records.isEmpty()) return null

        // Determine the highest TLS version seen
        val maxVersion = records.maxByOrNull { it.versionMajor * 256 + it.versionMinor } ?: first
        val protocol = "TLS" + maxVersion.versionString.removePrefix("TLS").removePrefix("SSL")

        val hasEncrypted = records.any { it.contentType == TlsContentType.APPLICATION_DATA }

        val info = buildString {
            val summaries = records.map { hdr ->
                when (hdr.contentType) {
                    TlsContentType.HANDSHAKE -> {
                        // Try to read the handshake type byte
                        val hsOffset = offset + (records.takeWhile { it !== hdr }
                            .sumOf { RECORD_HEADER_SIZE + it.length })
                        val payloadOffset = hsOffset + RECORD_HEADER_SIZE
                        if (payloadOffset < offset + len) {
                            val hsType = readU8(buffer, payloadOffset)
                            TlsHandshakeType.name(hsType)
                        } else {
                            "Handshake"
                        }
                    }
                    TlsContentType.CHANGE_CIPHER_SPEC -> "Change Cipher Spec"
                    TlsContentType.ALERT -> {
                        val payloadOffset = offset + (records.takeWhile { it !== hdr }
                            .sumOf { RECORD_HEADER_SIZE + it.length }) + RECORD_HEADER_SIZE
                        if (payloadOffset + 1 < offset + len) {
                            val level = readU8(buffer, payloadOffset)
                            val desc = readU8(buffer, payloadOffset + 1)
                            val levelStr = if (level == 1) "Warning" else "Fatal"
                            "$levelStr: ${TlsAlertDescription.name(desc)}"
                        } else {
                            "Alert"
                        }
                    }
                    TlsContentType.APPLICATION_DATA -> "Application Data"
                    else -> hdr.contentTypeName
                }
            }
            append(summaries.joinToString(", "))
        }

        return TlsDissectionResult(protocol, info, hasEncrypted)
    }

    /**
     * Extracts the Client Random from a ClientHello message.
     * The ClientHello starts at [offset] in the buffer (pointing to the
     * handshake type byte). Returns 32 bytes or `null` if not available.
     */
    fun extractClientRandom(buffer: CommonByteBuffer, offset: Int, available: Int): ByteArray? {
        // Handshake header: type(1) + length(3) + client_version(2) + random(32)
        if (available < 1 + 3 + 2 + 32) return null
        val hsType = readU8(buffer, offset)
        if (hsType != TlsHandshakeType.CLIENT_HELLO) return null
        val randomOffset = offset + 1 + 3 + 2 // skip type + length(3) + version(2)
        val random = ByteArray(32)
        buffer.position(randomOffset)
        for (i in 0 until 32) {
            random[i] = buffer.get()
        }
        return random
    }

    /**
     * Extracts the Server Random from a ServerHello message.
     */
    fun extractServerRandom(buffer: CommonByteBuffer, offset: Int, available: Int): ByteArray? {
        if (available < 1 + 3 + 2 + 32) return null
        val hsType = readU8(buffer, offset)
        if (hsType != TlsHandshakeType.SERVER_HELLO) return null
        val randomOffset = offset + 1 + 3 + 2
        val random = ByteArray(32)
        buffer.position(randomOffset)
        for (i in 0 until 32) {
            random[i] = buffer.get()
        }
        return random
    }

    /**
     * Extracts the cipher suite from a ServerHello message.
     * ServerHello layout: type(1) + length(3) + version(2) + random(32) +
     * session_id_length(1) + session_id(variable) + cipher_suite(2).
     */
    fun extractCipherSuite(buffer: CommonByteBuffer, offset: Int, available: Int): Int? {
        // Minimum: type(1) + length(3) + version(2) + random(32) + session_id_len(1) + cipher(2) = 41
        if (available < 41) return null
        val hsType = readU8(buffer, offset)
        if (hsType != TlsHandshakeType.SERVER_HELLO) return null
        val sessionIdLenOffset = offset + 1 + 3 + 2 + 32
        val sessionIdLen = readU8(buffer, sessionIdLenOffset)
        val cipherSuiteOffset = sessionIdLenOffset + 1 + sessionIdLen
        if (cipherSuiteOffset + 2 > offset + available) return null
        return readBigU16(buffer, cipherSuiteOffset)
    }

    /**
     * Extracts the compression method a ServerHello selected. `0` is null
     * compression; `1` is DEFLATE (RFC 3749), which puts a connection-long
     * compressed stream underneath the record protection and so has to be
     * undone after decryption before the payload means anything.
     *
     * Layout is [extractCipherSuite]'s, plus the compression byte that follows
     * the cipher suite.
     */
    fun extractCompressionMethod(buffer: CommonByteBuffer, offset: Int, available: Int): Int? {
        if (available < 42) return null
        val hsType = readU8(buffer, offset)
        if (hsType != TlsHandshakeType.SERVER_HELLO) return null
        val sessionIdLenOffset = offset + 1 + 3 + 2 + 32
        val sessionIdLen = readU8(buffer, sessionIdLenOffset)
        val compressionOffset = sessionIdLenOffset + 1 + sessionIdLen + 2
        if (compressionOffset + 1 > offset + available) return null
        return readU8(buffer, compressionOffset)
    }

    /**
     * Detects whether a ServerHello indicates TLS 1.3 by checking for the
     * `supported_versions` extension with value 0x0304. In TLS 1.3 the
     * record-layer version stays at 0x0303 (TLS 1.2) and the real version
     * is negotiated via this extension.
     */
    fun isServerHelloTls13(buffer: CommonByteBuffer, offset: Int, available: Int): Boolean {
        // ServerHello: type(1) + length(3) + version(2) + random(32) +
        //   session_id_len(1) + session_id(var) + cipher(2) + compression(1) + extensions_len(2) + extensions
        if (available < 41) return false
        val hsType = readU8(buffer, offset)
        if (hsType != TlsHandshakeType.SERVER_HELLO) return false
        val sessionIdLenOffset = offset + 1 + 3 + 2 + 32
        val sessionIdLen = readU8(buffer, sessionIdLenOffset)
        // After cipher_suite(2) + compression_method(1) come extensions
        val extensionsLenOffset = sessionIdLenOffset + 1 + sessionIdLen + 2 + 1
        if (extensionsLenOffset + 2 > offset + available) return false
        val extensionsLen = readBigU16(buffer, extensionsLenOffset)
        val extensionsStart = extensionsLenOffset + 2
        val extensionsEnd = minOf(extensionsStart + extensionsLen, offset + available)
        // Scan extensions for supported_versions (type 0x002B)
        var pos = extensionsStart
        while (pos + 4 <= extensionsEnd) {
            val extType = readBigU16(buffer, pos)
            val extLen = readBigU16(buffer, pos + 2)
            if (extType == 0x002B && extLen >= 2 && pos + 4 + extLen <= extensionsEnd) {
                val version = readBigU16(buffer, pos + 4)
                if (version == 0x0304) return true
            }
            pos += 4 + extLen
        }
        return false
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
}
