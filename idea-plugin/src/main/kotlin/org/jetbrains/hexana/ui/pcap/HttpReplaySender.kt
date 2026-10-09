package org.jetbrains.hexana.ui.pcap

import com.intellij.util.net.ssl.CertificateManager
import kotlinx.coroutines.future.await
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
    /** As received, still content-coded. */
    val body: ByteArray,
    val elapsedMillis: Long,
)

/**
 * Sends replayed requests with the JDK client.
 *
 * Redirects are not followed - a replay is for seeing what the server answers
 * to this exact request. TLS trusts what the IDE trusts, so a server whose
 * certificate the user accepted in the IDE is reachable here too; proxies come
 * from the default selector the IDE installs.
 */
internal object HttpReplaySender {

    /**
     * Headers the JDK client refuses to take from the caller. `Host` is the
     * one that matters: it always follows the URL, so a replay that needs a
     * different virtual host has to change the URL instead.
     */
    private val RESTRICTED_HEADERS = setOf("host", "expect", "connection", "content-length", "upgrade")

    /** `true` for a header [send] drops; see [RESTRICTED_HEADERS]. */
    fun isDropped(name: String): Boolean =
        name.lowercase() in RESTRICTED_HEADERS || HttpReplay.isConnectionSpecific(name)

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
            .apply { headers.filterNot { isDropped(it.name) }.forEach { header(it.name, it.value) } }
            .build()
        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .sslContext(CertificateManager.getInstance().sslContext)
            .build()
        return client.use {
            val started = System.nanoTime()
            val response = it.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).await()
            HttpReplayResponse(
                statusCode = response.statusCode(),
                version = if (response.version() == HttpClient.Version.HTTP_2) "HTTP/2" else "HTTP/1.1",
                headers = response.headers().map().flatMap { (name, values) -> values.map { v -> HttpHeader(name, v) } },
                body = response.body(),
                elapsedMillis = (System.nanoTime() - started) / 1_000_000,
            )
        }
    }
}
