package org.jetbrains.hexana.nativebin.vuln

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.providers.sbom.vuln.OsvConstants
import org.jetbrains.hexana.providers.sbom.vuln.OsvParser
import org.jetbrains.hexana.providers.sbom.vuln.OsvRecord

/**
 * Opt-in online OSV lookups via `api.osv.dev`. Only used when the user enables
 * "query osv.dev online" — this sends the component list (purls) to a third party,
 * so it is gated behind [org.jetbrains.hexana.HexanaSettings.vulnerabilitiesOnline].
 * Blocking; call off the EDT. Best-effort: failures return empty rather than throw.
 *
 * Uses `/v1/querybatch` (≤[OsvConstants.MAX_BATCH_SIZE] purls/request) to collect
 * advisory ids, then resolves each unique id via `/v1/vulns/{id}` into a full [OsvRecord].
 */
object OsvApiClient {

    fun queryByPurls(purls: List<String>): List<OsvRecord> {
        val distinct = purls.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return emptyList()
        return try {
            HttpClient().use { client ->
                runBlocking {
                    val ids = distinct.chunked(OsvConstants.MAX_BATCH_SIZE).flatMap { batchQueryIds(client, it) }.distinct()
                    ids.mapNotNull { id -> fetchRecord(client, id) }
                }
            }
        } catch (e: Exception) {
            HexanaLog.LOG.warn("OSV online query failed", e)
            emptyList()
        }
    }

    private suspend fun batchQueryIds(client: HttpClient, purls: List<String>): List<String> {
        val queriesJson = purls.joinToString(",") { purl ->
            """{"package":{"purl":${kotlinx.serialization.json.Json.encodeToString(purl)}}}"""
        }
        val payload = """{"queries":[$queriesJson]}"""
        val body: String = client.request(OsvConstants.QUERY_BATCH_URL) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.body()

        return OsvParser.parseQueryBatchResponse(body, purls).values.flatten()
    }

    private suspend fun fetchRecord(client: HttpClient, id: String): OsvRecord? = try {
        val body: String = client.request(OsvConstants.VULN_BY_ID_URL + id) { method = HttpMethod.Get }.body()
        OsvParser.parseRecord(body)
    } catch (e: Exception) {
        HexanaLog.LOG.warn("OSV fetch failed for $id", e)
        null
    }
}
