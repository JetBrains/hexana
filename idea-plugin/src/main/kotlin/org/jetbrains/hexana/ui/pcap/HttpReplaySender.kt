package org.jetbrains.hexana.ui.pcap

import com.intellij.util.net.ssl.CertificateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import org.jetbrains.hexana.pcap.HttpHeader
import org.jetbrains.hexana.pcap.HttpReplay
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** What came back from a replayed request. */
internal class HttpReplayResponse(
    val statusCode: Int,
    val version: String,
    val headers: List<HttpHeader>,
    /** As received, still content-coded; at most [HttpReplaySender.MAX_RESPONSE_BYTES]. */
    val body: ByteArray,
    /** `true` when the server sent more than [body] holds; the rest was not read. */
    val bodyTruncated: Boolean,
    val elapsedMillis: Long,
)

/**
 * Sends replayed requests with the JDK client.
 *
 * Redirects are not followed - a replay is for seeing what the server answers
 * to this exact request. TLS trusts what the IDE trusts, so a server whose
 * certificate the user accepted in the IDE is reachable here too; proxies come
 * from the default selector the IDE installs. Headers the client owns are
 * dropped through [HttpReplay.sendableHeaders]. Only the first
 * [MAX_RESPONSE_BYTES] of a response body are kept, so a huge answer cannot
 * fill the IDE's heap.
 */
internal object HttpReplaySender {

    const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

    suspend fun send(method: String, url: String, headers: List<HttpHeader>, body: ByteArray): HttpReplayResponse {
        val uri = URI(url)
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Only http and https URLs can be replayed"
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .method(
                method,
                if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body),
            )
            .apply { HttpReplay.sendableHeaders(headers).forEach { header(it.name, it.value) } }
            .build()
        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .sslContext(CertificateManager.getInstance().sslContext)
            .build()
        return client.use {
            val started = System.nanoTime()
            val response = it.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
            // Closing the stream before its end drops the rest of the body.
            val (responseBody, truncated) = runInterruptible(Dispatchers.IO) {
                response.body().use { stream ->
                    val head = stream.readNBytes(MAX_RESPONSE_BYTES)
                    head to (head.size == MAX_RESPONSE_BYTES && stream.read() >= 0)
                }
            }
            HttpReplayResponse(
                statusCode = response.statusCode(),
                version = if (response.version() == HttpClient.Version.HTTP_2) "HTTP/2" else "HTTP/1.1",
                headers = response.headers().map().flatMap { (name, values) -> values.map { v -> HttpHeader(name, v) } },
                body = responseBody,
                bodyTruncated = truncated,
                elapsedMillis = (System.nanoTime() - started) / 1_000_000,
            )
        }
    }
}
