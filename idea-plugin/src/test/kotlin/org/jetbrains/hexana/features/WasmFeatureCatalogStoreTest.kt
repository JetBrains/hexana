package org.jetbrains.hexana.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The catalog cache is pure filesystem work — the conditional-GET decision lives in
 * `shouldRefresh` in `plugins-shared` and is tested there — so this exercises the store
 * against a temporary directory with no network and no IDE.
 */
class WasmFeatureCatalogStoreTest {

    @get:Rule
    val cacheDir = TemporaryFolder()

    private val store: WasmFeatureCatalogStore get() = WasmFeatureCatalogStore(cacheDir.root.toPath())

    @Test
    fun `reads nothing from an empty cache directory`() {
        assertNull(store.read())
    }

    @Test
    fun `round trip preserves the payload, the etag and the timestamp`() {
        store.write("""{"features":{}}""", "\"abc123\"", 1_700_000_000_000L)

        val cached = requireNotNull(store.read())
        assertEquals("""{"features":{}}""", cached.json)
        assertEquals("\"abc123\"", cached.etag)
        assertEquals(1_700_000_000_000L, cached.fetchedAtMillis)
    }

    @Test
    fun `write creates the cache directory`() {
        val nested = WasmFeatureCatalogStore(cacheDir.root.toPath().resolve("a/b/c"))
        nested.write("{}", null, 1L)
        assertEquals("{}", requireNotNull(nested.read()).json)
    }

    @Test
    fun `the payload is stored verbatim so a stale catalog can be inspected on disk`() {
        val json = """{"features": {"gc": {"description": "Garbage Collection"}}}"""
        store.write(json, null, 1L)
        assertEquals(json, cacheDir.root.resolve(WasmFeatureCatalogStore.CATALOG_FILE).readText())
    }

    /** A 304 confirms the bytes we hold; only their freshness moves. */
    @Test
    fun `touch advances the timestamp and keeps the payload and etag`() {
        store.write("""{"features":{}}""", "\"abc123\"", 1_000L)
        store.touch(9_000L)

        val cached = requireNotNull(store.read())
        assertEquals("""{"features":{}}""", cached.json)
        assertEquals("\"abc123\"", cached.etag)
        assertEquals(9_000L, cached.fetchedAtMillis)
    }

    @Test
    fun `touch with no cached payload does nothing`() {
        store.touch(9_000L)
        assertNull(store.read())
        assertTrue(!cacheDir.root.resolve(WasmFeatureCatalogStore.METADATA_FILE).exists())
    }

    @Test
    fun `a blank payload reads as absent`() {
        store.write("   ", "\"etag\"", 1L)
        assertNull(store.read())
    }

    /**
     * A payload with no metadata cannot be revalidated or aged, so it must look ancient and
     * force a refresh rather than being trusted forever.
     */
    @Test
    fun `a payload with no metadata reads as ancient`() {
        store.write("{}", "\"etag\"", 5_000L)
        cacheDir.root.resolve(WasmFeatureCatalogStore.METADATA_FILE).delete()

        val cached = requireNotNull(store.read())
        assertEquals("{}", cached.json)
        assertNull(cached.etag)
        assertEquals(0L, cached.fetchedAtMillis)
    }

    @Test
    fun `corrupt metadata reads as ancient rather than throwing`() {
        store.write("{}", "\"etag\"", 5_000L)
        cacheDir.root.resolve(WasmFeatureCatalogStore.METADATA_FILE).writeText("{ truncated")

        val cached = requireNotNull(store.read())
        assertEquals(0L, cached.fetchedAtMillis)
    }

    @Test
    fun `a second write replaces the first and leaves no temp files behind`() {
        store.write("{}", "\"one\"", 1L)
        store.write("""{"features":{}}""", "\"two\"", 2L)

        assertEquals("\"two\"", requireNotNull(store.read()).etag)
        val leftovers = cacheDir.root.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertTrue("expected no temp files, found $leftovers", leftovers.isEmpty())
    }
}
