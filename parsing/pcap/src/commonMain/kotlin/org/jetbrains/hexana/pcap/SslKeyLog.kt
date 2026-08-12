package org.jetbrains.hexana.pcap

/**
 * Parser for the NSS Key Log Format used by browsers and curl to export
 * TLS session secrets. This is the same format that Wireshark reads via
 * the `SSLKEYLOGFILE` environment variable.
 *
 * Format (one line per secret):
 * ```
 * <label> <client_random_hex> <secret_hex>
 * ```
 *
 * Supported labels:
 * - `CLIENT_RANDOM` — TLS 1.2 master secret
 * - `CLIENT_HANDSHAKE_TRAFFIC_SECRET` — TLS 1.3
 * - `SERVER_HANDSHAKE_TRAFFIC_SECRET` — TLS 1.3
 * - `CLIENT_TRAFFIC_SECRET_0` — TLS 1.3
 * - `SERVER_TRAFFIC_SECRET_0` — TLS 1.3
 * - `EXPORTER_SECRET` — TLS 1.3
 *
 * Reference: https://www.ietf.org/archive/id/draft-thomson-tls-keylogfile-00.html
 */
object SslKeyLogParser {

    /**
     * A single entry from an SSLKEYLOG file.
     */
    data class KeyLogEntry(
        /** The label identifying the secret type. */
        val label: String,
        /** The 32-byte client random that identifies the TLS session. */
        val clientRandom: ByteArray,
        /** The secret value (master secret or traffic secret). */
        val secret: ByteArray,
    ) {
        /** Hex-encoded client random for use as a lookup key. */
        val clientRandomHex: String get() = clientRandom.toHexString()

        override fun equals(other: Any?): Boolean =
            other is KeyLogEntry && label == other.label &&
                clientRandom.contentEquals(other.clientRandom) &&
                secret.contentEquals(other.secret)

        override fun hashCode(): Int {
            var h = label.hashCode()
            h = 31 * h + clientRandom.contentHashCode()
            h = 31 * h + secret.contentHashCode()
            return h
        }
    }

    /** Known labels from the NSS Key Log Format. */
    private val KNOWN_LABELS = setOf(
        "CLIENT_RANDOM",
        "CLIENT_HANDSHAKE_TRAFFIC_SECRET",
        "SERVER_HANDSHAKE_TRAFFIC_SECRET",
        "CLIENT_TRAFFIC_SECRET_0",
        "SERVER_TRAFFIC_SECRET_0",
        "EXPORTER_SECRET",
        "EARLY_TRAFFIC_SECRET",
    )

    /**
     * Parses the content of an SSLKEYLOG file into a list of entries.
     * Lines that are blank, start with `#`, or have unrecognised labels
     * are silently skipped.
     */
    fun parse(content: String): List<KeyLogEntry> {
        val entries = mutableListOf<KeyLogEntry>()
        for (line in content.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            val parts = trimmed.split(' ', limit = 3)
            if (parts.size != 3) continue
            val label = parts[0]
            if (label !in KNOWN_LABELS) continue
            val clientRandom = hexToBytes(parts[1]) ?: continue
            val secret = hexToBytes(parts[2]) ?: continue
            if (clientRandom.size != 32) continue
            entries.add(KeyLogEntry(label, clientRandom, secret))
        }
        return entries
    }

    /**
     * Builds a lookup index from parsed entries, keyed by hex-encoded
     * client random. Each key maps to all entries sharing that client random
     * (there are typically multiple labels per session in TLS 1.3).
     */
    fun buildIndex(entries: List<KeyLogEntry>): Map<String, List<KeyLogEntry>> {
        return entries.groupBy { it.clientRandomHex }
    }
}

/**
 * Indexed collection of TLS session secrets parsed from an SSLKEYLOG file.
 * Provides fast lookup by client random.
 */
class SslKeyLog(
    /** All parsed entries. */
    val entries: List<SslKeyLogParser.KeyLogEntry>,
) {
    /** Entries indexed by hex-encoded client random. */
    private val index: Map<String, List<SslKeyLogParser.KeyLogEntry>> =
        SslKeyLogParser.buildIndex(entries)

    /** Returns all secrets for the given client random, or empty list. */
    fun lookup(clientRandom: ByteArray): List<SslKeyLogParser.KeyLogEntry> =
        index[clientRandom.toHexString()] ?: emptyList()

    /** Returns all secrets for the given hex-encoded client random, or empty list. */
    fun lookup(clientRandomHex: String): List<SslKeyLogParser.KeyLogEntry> =
        index[clientRandomHex.lowercase()] ?: emptyList()

    /** True if this key log contains any entries. */
    val isNotEmpty: Boolean get() = entries.isNotEmpty()

    /** Number of unique sessions (distinct client randoms). */
    val sessionCount: Int get() = index.size

    companion object {
        val EMPTY = SslKeyLog(emptyList())

        /** Parses an SSLKEYLOG file content into an indexed [SslKeyLog]. */
        fun parse(content: String): SslKeyLog {
            val entries = SslKeyLogParser.parse(content)
            return SslKeyLog(entries)
        }
    }
}

// ---- Hex utilities ----

internal fun hexToBytes(hex: String): ByteArray? {
    val clean = hex.lowercase()
    if (clean.length % 2 != 0) return null
    return try {
        ByteArray(clean.length / 2) { i ->
            val hi = hexDigit(clean[i * 2]) ?: return null
            val lo = hexDigit(clean[i * 2 + 1]) ?: return null
            ((hi shl 4) or lo).toByte()
        }
    } catch (_: Exception) {
        null
    }
}

private fun hexDigit(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    else -> null
}

internal fun ByteArray.toHexString(): String = buildString(size * 2) {
    for (b in this@toHexString) {
        val v = b.toInt() and 0xFF
        append(HEX_CHARS[v shr 4])
        append(HEX_CHARS[v and 0x0F])
    }
}

private val HEX_CHARS = "0123456789abcdef".toCharArray()
