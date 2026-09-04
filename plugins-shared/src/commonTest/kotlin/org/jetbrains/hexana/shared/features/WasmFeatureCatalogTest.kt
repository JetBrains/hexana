package org.jetbrains.hexana.shared.features

import org.jetbrains.hexana.wasm.analysis.WasmProposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WasmFeatureCatalogTest {

    @Test
    fun `bundled snapshot parses`() {
        val catalog = WasmFeatureCatalog.bundled
        assertTrue(catalog.features.isNotEmpty(), "no features parsed")
        assertTrue(catalog.runtimes.isNotEmpty(), "no runtimes parsed")
        assertTrue(catalog.categories.isNotEmpty(), "no categories parsed")
    }

    /**
     * The contract behind [WasmProposal.featureId]: every proposal we detect must resolve to
     * a real upstream feature, or its row in the Compatibility table would have no
     * documentation link and no matrix column values.
     */
    @Test
    fun `every proposal maps to a feature in the catalog`() {
        val catalog = WasmFeatureCatalog.bundled
        val missing = WasmProposal.entries.filter { catalog.feature(it.featureId) == null }
        assertTrue(missing.isEmpty(), "proposals with no features.json entry: $missing")
    }

    @Test
    fun `proposal feature ids are distinct`() {
        val byId = WasmProposal.entries.groupBy { it.featureId }.filterValues { it.size > 1 }
        assertTrue(byId.isEmpty(), "two proposals share a features.json key: $byId")
    }

    @Test
    fun `feature carries description, doc url and phase`() {
        val gc = assertNotNull(WasmFeatureCatalog.bundled.feature("gc"))
        assertEquals("Garbage Collection", gc.description)
        assertTrue(gc.url.startsWith("https://"), "expected an https doc url, got ${gc.url}")
        assertEquals("5", gc.phase)
    }

    @Test
    fun `inactive phase is preserved as a string`() {
        assertEquals("inactive", assertNotNull(WasmFeatureCatalog.bundled.feature("exceptions")).phase)
    }

    // --- status shapes, one test per branch of features.schema.json #/definitions/status ---

    @Test
    fun `version string becomes SinceVersion`() {
        val cell = assertNotNull(WasmFeatureCatalog.bundled.cell("Chrome", "gc"))
        assertEquals(FeatureStatus.SinceVersion("119"), cell.status)
        assertNull(cell.note)
    }

    @Test
    fun `flag becomes FlagRequired and keeps its note`() {
        val cell = assertNotNull(WasmFeatureCatalog.bundled.cell("Chrome", "stackSwitching"))
        assertEquals(FeatureStatus.FlagRequired, cell.status)
        assertTrue(assertNotNull(cell.note).contains("flag"), "expected the flag note, got ${cell.note}")
    }

    @Test
    fun `null becomes NotApplicable`() {
        val cell = assertNotNull(WasmFeatureCatalog.bundled.cell("Chrome", "profiles"))
        assertEquals(FeatureStatus.NotApplicable, cell.status)
    }

    @Test
    fun `true becomes Supported`() {
        val cell = assertNotNull(
            WasmFeatureCatalog.bundled.cell("Wasmtime", "customAnnotationSyntaxInTheTextFormat")
        )
        assertEquals(FeatureStatus.Supported, cell.status)
    }

    @Test
    fun `absent feature key yields no cell`() {
        assertNull(WasmFeatureCatalog.bundled.cell("Chrome", "no-such-feature"))
    }

    @Test
    fun `unknown runtime yields no cell`() {
        assertNull(WasmFeatureCatalog.bundled.cell("No Such Runtime", "gc"))
    }

    // --- shapes exercised through synthetic input, since upstream may not carry them today ---

    @Test
    fun `false becomes Unsupported`() {
        val catalog = assertNotNull(WasmFeatureCatalog.parse(catalogWith("""{"gc": false}""")))
        assertEquals(FeatureStatus.Unsupported, assertNotNull(catalog.cell("Probe", "gc")).status)
    }

    @Test
    fun `status wrapped with a note keeps both halves`() {
        val catalog = assertNotNull(
            WasmFeatureCatalog.parse(catalogWith("""{"gc": ["15", "needs a build flag"]}"""))
        )
        val cell = assertNotNull(catalog.cell("Probe", "gc"))
        assertEquals(FeatureStatus.SinceVersion("15"), cell.status)
        assertEquals("needs a build flag", cell.note)
    }

    @Test
    fun `boolean wrapped with a note keeps both halves`() {
        val catalog = assertNotNull(
            WasmFeatureCatalog.parse(catalogWith("""{"gc": [true, "verified end-to-end"]}"""))
        )
        val cell = assertNotNull(catalog.cell("Probe", "gc"))
        assertEquals(FeatureStatus.Supported, cell.status)
        assertEquals("verified end-to-end", cell.note)
    }

    /**
     * The leniency contract: an upstream schema addition must degrade, not break an
     * installed Hexana.
     */
    @Test
    fun `unrecognized status shape degrades to NotApplicable`() {
        val catalog = assertNotNull(
            WasmFeatureCatalog.parse(catalogWith("""{"gc": {"someFutureShape": 1}}"""))
        )
        assertEquals(FeatureStatus.NotApplicable, assertNotNull(catalog.cell("Probe", "gc")).status)
    }

    @Test
    fun `unparseable text yields null`() {
        assertNull(WasmFeatureCatalog.parse("not json at all"))
        assertNull(WasmFeatureCatalog.parse(""))
    }

    @Test
    fun `json without the expected top-level keys yields null`() {
        assertNull(WasmFeatureCatalog.parse("""{"unrelated": true}"""))
    }

    // --- runtime metadata used by the settings UI ---

    @Test
    fun `runtimes carry their category and a multi-category runtime keeps the primary first`() {
        val catalog = WasmFeatureCatalog.bundled
        assertEquals("Web Browsers", assertNotNull(catalog.runtime("Chrome")).primaryCategory)
        assertEquals("Standalone Runtimes", assertNotNull(catalog.runtime("Wasmtime")).primaryCategory)
        // Owi declares ["Tools", "Standalone Runtimes"]; the first entry is primary.
        val owi = assertNotNull(catalog.runtime("Owi"))
        assertTrue(owi.categories.size > 1, "expected Owi to declare several categories")
        assertEquals("Tools", owi.primaryCategory)
    }

    @Test
    fun `runtimes are grouped by category in upstream order`() {
        val grouped = WasmFeatureCatalog.bundled.runtimesByCategory()
        assertEquals("Web Browsers", grouped.first().first)
        assertTrue(grouped.any { it.first == "Standalone Runtimes" })
        val chromeGroups = grouped.filter { "Chrome" in it.second }
        assertEquals(1, chromeGroups.size, "a runtime must appear under exactly one group")
    }

    @Test
    fun `known versions are newest first and free of duplicates`() {
        val versions = assertNotNull(WasmFeatureCatalog.bundled.runtime("Chrome")).knownVersions
        assertTrue(versions.isNotEmpty())
        assertEquals(versions.distinct(), versions, "duplicate versions offered")
        val sorted = versions.sortedWith { a, b -> compareRuntimeVersions(b, a) }
        assertEquals(sorted, versions, "expected newest first")
    }

    @Test
    fun `status hierarchy uses value equality so cells can be compared`() {
        assertIs<FeatureStatus.SinceVersion>(FeatureStatus.SinceVersion("1"))
        assertEquals(FeatureStatus.SinceVersion("1.0"), FeatureStatus.SinceVersion("1.0"))
    }

    private fun catalogWith(probeFeatures: String): String = """
        {
          "features": {
            "gc": { "description": "Garbage Collection", "url": "https://example.invalid/gc", "phase": 5 }
          },
          "browsers": {
            "Probe": {
              "url": "https://example.invalid",
              "category": "Standalone Runtimes",
              "features": $probeFeatures
            }
          }
        }
    """.trimIndent()
}
