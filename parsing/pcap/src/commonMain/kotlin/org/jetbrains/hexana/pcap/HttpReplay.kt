package org.jetbrains.hexana.pcap

import org.jetbrains.hexana.io.CommonByteBuffer

/** One HTTP header line, in the order and spelling the client sent it. */
data class HttpHeader(val name: String, val value: String)

/**
 * A captured HTTP/1.x request in the shape needed to send it again.
 *
 * The body is the payload with transfer framing undone - chunked encoding is
 * decoded - but content coding kept, so a gzip upload is replayed as gzip
 * together with its `Content-Encoding` header.
 */
class HttpReplayRequest(
    val method: String,
    /** Absolute URL, rebuilt from the request target, the `Host` header and the server the capture saw. */
    val url: String,
    /** Version from the request line, e.g. `HTTP/1.1`. */
    val httpVersion: String,
    /** Every header as captured, hop-by-hop ones included; see [HttpReplay.replayHeaders]. */
    val headers: List<HttpHeader>,
    val body: ByteArray,
    /**
     * `false` when the capture holds less body than the request announced -
     * missed segments, a snap length that cut packets short, or a body that
     * continued past the one TLS record that was decrypted.
     */
    val bodyComplete: Boolean,
)

/**
 * A replayable request found in one row of a capture, with what a replay UI
 * shows about where it came from.
 */
class CapturedHttpRequest(
    val request: HttpReplayRequest,
    /** One-based, matching the Packets table's "No." column. */
    val packetNumber: Int,
    /** e.g. `Captured 10.0.0.1:5000 → 10.0.0.2:80, decrypted from TLS`. */
    val origin: String,
) {
    /** Title of the request in a `.http` file and in a replay window. */
    val title: String get() = "Replay of packet #$packetNumber"

    /** Name for the `.http` file a host writes the request into. */
    val httpFileName: String get() = "hexana-replay-packet-$packetNumber.http"

    /** Name for the file holding a body that cannot be inlined; see [HttpReplay.needsBodyFile]. */
    val bodyFileName: String get() = "hexana-replay-packet-$packetNumber-body.bin"
}

/**
 * Turns captured HTTP requests into something that can be sent again, either
 * by a host's own client or as an IntelliJ HTTP Client `.http` file.
 *
 * Only HTTP/1.x is understood, matching what [PcapDissector] recognises.
 */
object HttpReplay {

    /**
     * Headers that describe one connection rather than the request (RFC 9110
     * §7.6.1), plus the framing headers a client recomputes for whatever body
     * it ends up sending. Replaying any of them verbatim would be wrong as soon
     * as the body is edited.
     */
    private val CONNECTION_SPECIFIC_HEADERS = setOf(
        "connection", "keep-alive", "proxy-connection", "transfer-encoding",
        "te", "trailer", "upgrade", "content-length",
    )

    /** `true` for a header a replay must not copy from the capture; see [CONNECTION_SPECIFIC_HEADERS]. */
    fun isConnectionSpecific(name: String): Boolean = name.lowercase() in CONNECTION_SPECIFIC_HEADERS

    /** The headers of [request] worth sending again. */
    fun replayHeaders(request: HttpReplayRequest): List<HttpHeader> =
        request.headers.filterNot { isConnectionSpecific(it.name) }

    /**
     * Headers a client library sets itself and refuses, or ignores, when a
     * caller passes them: the JDK client and Node's `fetch` both own `Host`
     * (it follows the URL) and `Expect`.
     */
    private val CLIENT_OWNED_HEADERS = setOf("host", "expect")

    /**
     * The subset of [headers] a host's HTTP client can actually send. A
     * request that needs a different virtual host has to change the URL.
     */
    fun sendableHeaders(headers: List<HttpHeader>): List<HttpHeader> =
        headers.filterNot { isConnectionSpecific(it.name) || it.name.lowercase() in CLIENT_OWNED_HEADERS }

    /**
     * Parses the raw bytes of a request - [PcapDissector.reassembleHttpRequest]
     * output, or decrypted TLS plaintext - sent to [serverAddress]:[serverPort]
     * over [scheme] (`http` or `https`).
     *
     * Returns `null` when the bytes do not start with an HTTP/1.x request line
     * whose target can be turned into a URL: `CONNECT` and `OPTIONS *` name no
     * resource to replay.
     */
    fun parseRequest(raw: ByteArray, scheme: String, serverAddress: String, serverPort: Int): HttpReplayRequest? {
        val headEnd = headEnd(raw)
        val head = latin1(raw, if (headEnd >= 0) headEnd else raw.size)
        val lines = head.split('\n').map { it.trimEnd('\r') }
        val requestLine = lines.firstOrNull()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
        if (requestLine.size != 3 || !requestLine[2].startsWith("HTTP/1.")) return null
        val (method, target, version) = requestLine
        if (method.isEmpty() || method.any { !it.isLetter() }) return null

        val headers = lines.drop(1)
            .takeWhile { it.isNotEmpty() }
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else HttpHeader(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
            }
        val url = buildUrl(target, headers, scheme, serverAddress, serverPort) ?: return null

        if (headEnd < 0) {
            // The head itself was cut off; there is a request but nothing after it.
            return HttpReplayRequest(method, url, version, headers, ByteArray(0), bodyComplete = false)
        }
        val payload = raw.copyOfRange(headEnd, raw.size)
        val chunked = headers.any {
            it.name.equals("transfer-encoding", ignoreCase = true) && it.value.lowercase().contains("chunked")
        }
        val contentLength = headers
            .firstOrNull { it.name.equals("content-length", ignoreCase = true) }
            ?.value?.toLongOrNull()
        val (body, complete) = when {
            chunked -> dechunk(payload)
            contentLength != null -> {
                val available = minOf(payload.size.toLong(), contentLength).toInt()
                payload.copyOfRange(0, available) to (payload.size >= contentLength)
            }
            // A request with neither framing header has no body (RFC 9112 §6.3).
            else -> ByteArray(0) to true
        }
        return HttpReplayRequest(method, url, version, headers, body, complete)
    }

    /**
     * The request that starts in packet [index], or `null` when that row holds
     * none. Cleartext requests are reassembled across segments; otherwise
     * [decryptedPlaintext] is asked for the row's decrypted TLS record, whose
     * request is replayed over `https` and holds only what that one record
     * carries.
     */
    fun capturedRequest(
        buffer: CommonByteBuffer,
        packets: List<PcapPacketRecord>,
        index: Int,
        linkType: Long,
        decryptedPlaintext: () -> ByteArray?,
    ): CapturedHttpRequest? {
        val endpoints = PcapDissector.tcpEndpoints(buffer, packets[index], linkType) ?: return null
        val cleartext = PcapDissector.reassembleHttpRequest(buffer, packets, index, linkType)
        val raw = cleartext ?: decryptedPlaintext() ?: return null
        val scheme = if (cleartext != null) "http" else "https"
        val request = parseRequest(raw, scheme, endpoints.destinationAddress, endpoints.destinationPort)
            ?: return null
        val origin = buildString {
            append("Captured ${endpoints.sourceAddress}:${endpoints.sourcePort} → ")
            append("${endpoints.destinationAddress}:${endpoints.destinationPort}")
            if (scheme == "https") append(", decrypted from TLS")
        }
        return CapturedHttpRequest(request, index + 1, origin)
    }

    /**
     * A cheap answer to whether a row can hold a replayable request, for
     * deciding whether to offer replay in a menu: a cleartext request line
     * the dissector already saw, or decrypted [plaintext] that starts one.
     */
    fun mayHoldRequest(packet: PcapDissectedPacket, plaintext: () -> ByteArray?): Boolean {
        if (packet.protocol == "HTTP" && !packet.info.startsWith("HTTP/") && packet.decryptedData == null) return true
        val bytes = plaintext() ?: return false
        return parseRequest(bytes, "https", "", 443) != null
    }

    /**
     * Rebuilds an absolute URL from the request target. An absolute-form
     * target (a request to a proxy) is already one; an origin-form target is
     * resolved against `Host`, falling back to the address the capture saw.
     */
    private fun buildUrl(
        target: String,
        headers: List<HttpHeader>,
        scheme: String,
        serverAddress: String,
        serverPort: Int,
    ): String? {
        if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
            return target
        }
        if (!target.startsWith("/")) return null
        val host = headers.firstOrNull { it.name.equals("host", ignoreCase = true) }?.value?.takeIf { it.isNotEmpty() }
        val authority = host ?: buildString {
            append(if (':' in serverAddress) "[$serverAddress]" else serverAddress)
            val defaultPort = if (scheme == "https") 443 else 80
            if (serverPort != defaultPort) append(':').append(serverPort)
        }
        return "$scheme://$authority$target"
    }

    /** Header block as one `Name: value` per line, the form the replay editors use. */
    fun formatHeaders(headers: List<HttpHeader>): String =
        headers.joinToString("\n") { "${it.name}: ${it.value}" }

    /**
     * Parses a header block edited by the user. Blank lines are skipped; a line
     * without a `Name:` part is rejected with its one-based line number, since
     * silently dropping it would send a different request than the one shown.
     */
    fun parseHeaders(text: String): List<HttpHeader> =
        text.lines().mapIndexedNotNull { index, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@mapIndexedNotNull null
            val colon = line.indexOf(':')
            val name = if (colon > 0) line.substring(0, colon).trim() else ""
            require(name.isNotEmpty() && name.none { it.isWhitespace() }) {
                "Line ${index + 1} is not a \"Name: value\" header: $line"
            }
            HttpHeader(name, line.substring(colon + 1).trim())
        }

    /**
     * The body as text when it can be shown and edited as such: valid UTF-8
     * with no control characters other than tab and line breaks. `null` means
     * it has to travel as bytes.
     */
    fun bodyAsText(body: ByteArray): String? {
        val text = try {
            body.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            return null
        }
        return text.takeIf { t -> t.none { it.isISOControl() && it != '\t' && it != '\n' && it != '\r' } }
    }

    /**
     * `true` when the body of [request] cannot be written inline in a `.http`
     * file and has to be referenced from a file instead: it is binary, or a
     * line of it would read as HTTP Client syntax - a request separator, a
     * file include or a response handler.
     */
    fun needsBodyFile(request: HttpReplayRequest): Boolean {
        if (request.body.isEmpty()) return false
        val text = bodyAsText(request.body) ?: return true
        return text.lines().any { line ->
            line.startsWith("###") || line.startsWith("< ") || line.startsWith("> ") || line.startsWith("<> ")
        }
    }

    /**
     * Renders [request] as one IntelliJ HTTP Client request.
     *
     * [title] names the request after `###`; [comments] become `#` lines under
     * it. When [needsBodyFile] is `true`, [bodyFilePath] must point at a file
     * holding [HttpReplayRequest.body], and the request reads it with `<`.
     */
    fun toHttpClientFile(
        request: HttpReplayRequest,
        title: String,
        comments: List<String> = emptyList(),
        bodyFilePath: String? = null,
    ): String = buildString {
        append("### ").append(title).append('\n')
        for (comment in comments) append("# ").append(comment).append('\n')
        if (!request.bodyComplete) {
            append("# The capture holds only part of this body; the rest was not recorded.\n")
        }
        append(request.method).append(' ').append(request.url).append(' ').append(request.httpVersion).append('\n')
        for (header in replayHeaders(request)) {
            append(header.name).append(": ").append(header.value).append('\n')
        }
        if (request.body.isNotEmpty()) {
            append('\n')
            if (needsBodyFile(request)) {
                requireNotNull(bodyFilePath) { "A body that cannot be inlined needs a body file" }
                append("< ").append(bodyFilePath).append('\n')
            } else {
                append(bodyAsText(request.body)!!.replace("\r\n", "\n").trimEnd('\n')).append('\n')
            }
        }
    }

    /** Offset just past the blank line ending the head, or `-1` when it is not there. */
    internal fun headEnd(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == LF) {
                if (bytes[i + 1] == LF) return i + 2
                if (i + 2 < bytes.size && bytes[i + 1] == CR && bytes[i + 2] == LF) return i + 3
            }
        }
        return -1
    }

    /** Decodes a chunked body; the flag is `true` once the zero-size last chunk was seen. */
    private fun dechunk(encoded: ByteArray): Pair<ByteArray, Boolean> {
        val parts = mutableListOf<ByteArray>()
        var pos = 0
        while (pos < encoded.size) {
            val lineEnd = indexOfLf(encoded, pos)
            if (lineEnd < 0) break
            val size = latin1(encoded.copyOfRange(pos, lineEnd), lineEnd - pos)
                .trimEnd('\r').substringBefore(';').trim().toLongOrNull(16) ?: break
            pos = lineEnd + 1
            if (size == 0L) return PcapDissector.concatenateByteArrays(parts) to true
            val end = minOf(pos.toLong() + size, encoded.size.toLong()).toInt()
            parts.add(encoded.copyOfRange(pos, end))
            if (end - pos < size) break
            pos = end
            // CRLF after the chunk data.
            if (pos < encoded.size && encoded[pos] == CR) pos++
            if (pos < encoded.size && encoded[pos] == LF) pos++
        }
        return PcapDissector.concatenateByteArrays(parts) to false
    }

    private fun indexOfLf(bytes: ByteArray, from: Int): Int {
        for (i in from until bytes.size) if (bytes[i] == LF) return i
        return -1
    }

    /** HTTP heads are ISO-8859-1 on the wire; every byte maps to one char. */
    private fun latin1(bytes: ByteArray, len: Int): String =
        buildString(len) { for (i in 0 until len) append((bytes[i].toInt() and 0xFF).toChar()) }

    private const val CR: Byte = 0x0D
    private const val LF: Byte = 0x0A
}

/**
 * Collects TCP payload for one request and tells when it is whole, without
 * re-scanning everything collected after every segment: the head is located
 * once, and from then on completeness is a byte count or a look at the tail.
 */
internal class HttpRequestAssembly {
    private val parts = mutableListOf<ByteArray>()

    var size: Int = 0
        private set

    private var framing: Framing? = null

    private var tail = ByteArray(0)

    val isComplete: Boolean
        get() = when (val f = framing) {
            // No end of head within any sane head size: this is not a request
            // worth waiting for, so stop collecting.
            null -> size > MAX_HEAD_SIZE
            is Framing.Length -> size >= f.total
            Framing.Chunked -> tail.endsWith(LAST_CHUNK)
        }

    fun append(bytes: ByteArray) {
        parts.add(bytes)
        size += bytes.size
        val joined = tail + bytes
        tail = joined.copyOfRange(maxOf(0, joined.size - LAST_CHUNK.size), joined.size)
        if (framing == null && size <= MAX_HEAD_SIZE) framing = framingOf(toByteArray())
    }

    fun toByteArray(): ByteArray = PcapDissector.concatenateByteArrays(parts.toList())

    private fun framingOf(bytes: ByteArray): Framing? {
        val headEnd = HttpReplay.headEnd(bytes)
        if (headEnd < 0) return null
        val head = bytes.copyOfRange(0, headEnd).decodeToString().lowercase()
        if (Regex("""\ntransfer-encoding:[^\n]*chunked""").containsMatchIn(head)) {
            // The terminator may already be in this very segment.
            tail = bytes.copyOfRange(maxOf(headEnd, bytes.size - LAST_CHUNK.size), bytes.size)
            return Framing.Chunked
        }
        val length = Regex("""\ncontent-length:\s*(\d+)""").find(head)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        return Framing.Length(headEnd + length)
    }

    private sealed interface Framing {
        class Length(val total: Long) : Framing
        object Chunked : Framing
    }

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
        size >= suffix.size && suffix.indices.all { this[size - suffix.size + it] == suffix[it] }

    private companion object {
        val LAST_CHUNK = "0\r\n\r\n".encodeToByteArray()
        const val MAX_HEAD_SIZE = 64 * 1024
    }
}
