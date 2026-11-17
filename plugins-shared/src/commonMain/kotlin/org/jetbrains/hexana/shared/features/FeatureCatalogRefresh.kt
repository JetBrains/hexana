package org.jetbrains.hexana.shared.features

/** How long a cached `features.json` is trusted before a conditional re-fetch is attempted. */
const val FEATURE_CATALOG_TTL_MILLIS: Long = 24 * 60 * 60 * 1000L

/**
 * A host's on-disk copy of `features.json` plus the metadata needed to revalidate it.
 *
 * @param etag the `ETag` the file was served with, replayed as `If-None-Match` so an
 *   unchanged file costs a `304` with no body instead of a full download
 * @param fetchedAtMillis when the copy was last confirmed fresh — updated on a `304` too,
 *   since a `304` is a confirmation
 */
class CachedCatalog(val json: String, val etag: String?, val fetchedAtMillis: Long)

/**
 * Whether a host should attempt a conditional GET now.
 *
 * `true` when there is no cache at all, when the cache has no content, or when it is older
 * than [ttlMillis]. A clock that has moved backwards (restored backup, timezone-less system
 * clock reset) also refreshes rather than trusting a future timestamp forever.
 */
fun shouldRefresh(
    cached: CachedCatalog?,
    nowMillis: Long,
    ttlMillis: Long = FEATURE_CATALOG_TTL_MILLIS,
): Boolean {
    if (cached == null || cached.json.isBlank()) return true
    val age = nowMillis - cached.fetchedAtMillis
    return age < 0 || age >= ttlMillis
}

/**
 * Picks the catalog to use right now: the host cache when it parses, otherwise the
 * committed snapshot. A cache that does not parse is treated as absent — a refresh will
 * replace it — so a corrupted download can never disable the feature.
 */
fun resolveCatalog(cached: CachedCatalog?): WasmFeatureCatalog =
    cached?.json?.let(WasmFeatureCatalog::parse) ?: WasmFeatureCatalog.bundled
