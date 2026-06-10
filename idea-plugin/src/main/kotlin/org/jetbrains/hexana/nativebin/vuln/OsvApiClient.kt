package org.jetbrains.hexana.nativebin.vuln

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.HexanaLog

/**
 * Opt-in online OSV lookups via `api.osv.dev`. Only used when the user enables
 * "query osv.dev online" — this sends the component list (purls) to a third party,
 * so it is gated behind [org.jetbrains.hexana.HexanaSettings.vulnerabilitiesOnline].
 * Blocking; call off the EDT. Best-effort: failures return empty rather than throw.
 *
 * Uses `/v1/querybatch` (≤1000 purls/request) to collect advisory ids, then resolves
 * each unique id via `/v1/vulns/{id}` into a full [OsvRecord].
 */
object OsvApiClient {

    private const val QUERY_BATCH_URL = "https://api.osv.dev/v1/querybatch"
    private const val VULN_BY_ID_URL = "https://api.osv.dev/v1/vulns/"
    private const val MAX_BATCH = 1000

    fun queryByPurls(purls: List<String>): List<OsvRecord> {
        val distinct = purls.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyList()
        return try {
            HttpClient().use { client ->
                runBlocking {
                    val ids = distinct.chunked(MAX_BATCH).flatMap { batchQueryIds(client, it) }.distinct()
                    ids.mapNotNull { id -> fetchRecord(client, id) }
                }
            }
        } catch (e: Exception) {
            HexanaLog.LOG.warn("OSV online query failed", e)
            emptyList()
        }
    }

    private suspend fun batchQueryIds(client: HttpClient, purls: List<String>): List<String> {
        val payload = JsonObject().apply {
            add(
                "queries",
                JsonArray().apply {
                    purls.forEach { purl ->
                        add(JsonObject().apply { add("package", JsonObject().apply { addProperty("purl", purl) }) })
                    }
                },
            )
        }
        val body: String = client.request(QUERY_BATCH_URL) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(Gson().toJson(payload))
        }.body()

        val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val results = root.get("results")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return results.flatMap { result ->
            (result as? JsonObject)?.get("vulns")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { (it as? JsonObject)?.get("id")?.takeIf { e -> e.isJsonPrimitive }?.asString }
                .orEmpty()
        }
    }

    private suspend fun fetchRecord(client: HttpClient, id: String): OsvRecord? = try {
        val body: String = client.request(VULN_BY_ID_URL + id) { method = HttpMethod.Get }.body()
        OsvParser.parseRecord(body)
    } catch (e: Exception) {
        HexanaLog.LOG.warn("OSV fetch failed for $id", e)
        null
    }
}
