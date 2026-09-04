package org.jetbrains.hexana.shared.features

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeatureCatalogRefreshTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `no cache refreshes`() {
        assertTrue(shouldRefresh(null, now))
    }

    @Test
    fun `empty cache refreshes`() {
        assertTrue(shouldRefresh(CachedCatalog("   ", "etag", now), now))
    }

    @Test
    fun `fresh cache does not refresh`() {
        assertFalse(shouldRefresh(CachedCatalog("{}", "etag", now - 1000), now))
    }

    @Test
    fun `cache exactly at the ttl refreshes`() {
        val cached = CachedCatalog("{}", "etag", now - FEATURE_CATALOG_TTL_MILLIS)
        assertTrue(shouldRefresh(cached, now))
    }

    @Test
    fun `cache just inside the ttl does not refresh`() {
        val cached = CachedCatalog("{}", "etag", now - FEATURE_CATALOG_TTL_MILLIS + 1)
        assertFalse(shouldRefresh(cached, now))
    }

    /** A restored backup or a reset system clock must not pin the cache as fresh forever. */
    @Test
    fun `a future timestamp refreshes`() {
        assertTrue(shouldRefresh(CachedCatalog("{}", "etag", now + 60_000), now))
    }

    @Test
    fun `a parseable cache is preferred over the bundled snapshot`() {
        val text = """
            {
              "features": {"gc": {"description": "GC", "url": "https://example.invalid", "phase": 5}},
              "browsers": {"Probe": {"url": "https://example.invalid", "category": "Tools", "features": {}}}
            }
        """.trimIndent()
        val resolved = resolveCatalog(CachedCatalog(text, null, now))
        assertTrue("Probe" in resolved.runtimes, "expected the cached catalog, got the bundled one")
    }

    /** A corrupted download must never disable the feature. */
    @Test
    fun `an unparseable cache falls back to the bundled snapshot`() {
        assertSame(WasmFeatureCatalog.bundled, resolveCatalog(CachedCatalog("{ truncated", "etag", now)))
        assertSame(WasmFeatureCatalog.bundled, resolveCatalog(null))
    }
}
