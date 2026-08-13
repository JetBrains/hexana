package org.jetbrains.hexana.wasisdk

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import org.jetbrains.hexana.shared.WasiSdkReleases
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Fetches the list of wasi-sdk releases from GitHub.
 *
 * Results are cached to disk for one hour. On network failure or HTTP 403/429,
 * falls back to the cache and marks results as stale.
 */
class WasiSdkReleasesClient(private val storage: WasiSdkStorage) {

    private val LOG = Logger.getInstance(WasiSdkReleasesClient::class.java)

    private val cacheFile: Path get() = storage.root.resolve("releases-cache.json")
    private val gson = Gson()

    companion object {
        private const val RELEASES_URL =
            "https://api.github.com/repos/WebAssembly/wasi-sdk/releases?per_page=30"

        /** Cache is valid for 1 hour. */
        private const val CACHE_MAX_AGE_MS = 3_600_000L
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    data class ReleaseAssetDto(
        @SerializedName("name") val name: String,
        @SerializedName("size") val size: Long,
        @SerializedName("browser_download_url") val downloadUrl: String,
        @SerializedName("digest") val digest: String?,
    )

    data class ReleaseDto(
        @SerializedName("tag_name") val tagName: String,
        @SerializedName("prerelease") val prerelease: Boolean,
        @SerializedName("published_at") val publishedAt: String?,
        @SerializedName("assets") val assets: List<ReleaseAssetDto>,
    )

    data class CachedReleases(
        @SerializedName("fetchedAt") val fetchedAt: Long,
        @SerializedName("releases") val releases: List<ReleaseDto>,
    )

    /** A release available for download on the current OS/arch. */
    data class AvailableRelease(
        val version: Int,
        val assetName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val sha256: String?,
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns available releases for the current OS/arch, sorted descending by version.
     * Uses the cache when fresh; falls back to cache on error.
     *
     * @param stale set to true in result if cache was used due to an error
     */
    fun fetchReleases(): FetchResult {
        val cached = readCache()
        val cacheAge = System.currentTimeMillis() - (cached?.fetchedAt ?: 0L)
        if (cached != null && cacheAge < CACHE_MAX_AGE_MS) {
            return FetchResult(mapReleases(cached.releases), stale = false)
        }

        return try {
            val dtos = fetchFromNetwork()
            writeCache(CachedReleases(System.currentTimeMillis(), dtos))
            FetchResult(mapReleases(dtos), stale = false)
        } catch (e: IOException) {
            LOG.warn("Failed to fetch wasi-sdk releases, using cache: ${e.message}")
            val fallback = cached ?: return FetchResult(emptyList(), stale = true)
            FetchResult(mapReleases(fallback.releases), stale = true)
        } catch (e: Exception) {
            LOG.warn("Unexpected error fetching wasi-sdk releases: ${e.message}")
            val fallback = cached ?: return FetchResult(emptyList(), stale = true)
            FetchResult(mapReleases(fallback.releases), stale = true)
        }
    }

    data class FetchResult(val releases: List<AvailableRelease>, val stale: Boolean)

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun fetchFromNetwork(): List<ReleaseDto> {
        val json = HttpRequests.request(RELEASES_URL)
            .accept("application/vnd.github+json")
            .readString(null)
        return gson.fromJson(json, Array<ReleaseDto>::class.java)?.toList() ?: emptyList()
    }

    private fun mapReleases(dtos: List<ReleaseDto>): List<AvailableRelease> {
        val os = storage.currentOs()
        val arch = storage.currentArch()
        return dtos
            .filter { !it.prerelease }
            .mapNotNull { dto ->
                val version = WasiSdkReleases.parseTag(dto.tagName) ?: return@mapNotNull null
                val assetName = WasiSdkReleases.selectAsset(dto.assets.map { it.name }, version, os, arch)
                    ?: return@mapNotNull null
                val asset = dto.assets.first { it.name == assetName }
                AvailableRelease(
                    version = version,
                    assetName = assetName,
                    downloadUrl = asset.downloadUrl,
                    sizeBytes = asset.size,
                    sha256 = WasiSdkReleases.parseSha256Digest(asset.digest),
                )
            }
            .sortedByDescending { it.version }
    }

    private fun readCache(): CachedReleases? {
        if (!cacheFile.exists()) return null
        return try {
            gson.fromJson(cacheFile.readText(), CachedReleases::class.java)
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun writeCache(cache: CachedReleases) {
        try {
            Files.createDirectories(storage.root)
            val tmp = cacheFile.resolveSibling("releases-cache.json.tmp")
            tmp.writeText(gson.toJson(cache))
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            LOG.warn("Failed to write wasi-sdk releases cache", e)
        }
    }
}
