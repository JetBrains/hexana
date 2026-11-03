package org.jetbrains.hexana.vscode

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.StructureRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The webview's provider-script runtime: the browser side of `defineProvider` / `defineTab`. */
class ProviderScriptRuntimeTest {

    private fun binary(size: Int = 64) = GenericBinaryProvider(ByteArray(size) { it.toByte() }.toCommonByteBuffer())

    private val acme = ProviderScriptSource(
        "/w/.hexana/providers/acme.js",
        """
        hexana.defineProvider({
            id: "user:acme", name: "ACME", base: "generic",
            parse(binary) { return { regions: [{ name: "Header", offset: 0, length: binary.size / 8 }, { name: "Bad", offset: -1, length: 2 }] }; },
        });
        hexana.defineTab({
            name: "user:Fields", appliesTo: ["user:acme"],
            columns: [{ name: "Name", type: "string" }, { name: "Offset", type: "offset" }],
            rows(binary, params, paging) {
                var all = [{ cells: ["magic", 0] }, { cells: ["size", 4] }, { anchor: 8, cells: ["body", 8] }];
                var page = all.slice(paging.offset, paging.offset + 2);
                return { rows: page, nextOffset: paging.offset + 2 < all.length ? paging.offset + 2 : undefined };
            },
        });
        hexana.defineSlice({ name: "user:ignored", columns: [{ name: "x", type: "int" }], rows() { return { rows: [] }; } });
        """.trimIndent(),
    )

    @Test
    fun registrationsAreCollectedPerScript() {
        val catalog = loadProviderCatalog(listOf(acme), untrusted = false)
        val info = catalog.providers.single()
        assertEquals("user:acme", info.id)
        assertEquals(BinaryParser.GENERIC, info.base)
        assertEquals(listOf("user:Fields"), catalog.scripts.single().tabs.map { it.info.name })
    }

    @Test
    fun theChosenProviderReportsRegionsAndAttachesItsTabs() {
        val catalog = loadProviderCatalog(listOf(acme), untrusted = false)
        val bound = bindBrowserProviderScripts(catalog, binary(), "fw.img", null, "user:acme")
        assertEquals("ACME", bound.provider?.name)
        assertEquals(listOf(StructureRange(0, 8, "Header")), bound.ranges)
        assertTrue(bound.error!!.contains("1 region"))
        assertEquals(listOf("user:Fields"), bound.tabs.map { it.tab.info.name })
    }

    @Test
    fun tabRowsArePagedThroughTheScript() {
        val catalog = loadProviderCatalog(listOf(acme), untrusted = false)
        val tab = bindBrowserProviderScripts(catalog, binary(), "fw.img", null, "user:acme").tabs.single()
        val first = fetchBrowserTabPage(tab.tab, tab.binary, 0, 2)
        assertEquals(listOf("magic", "0"), first.rows[0].second)
        assertEquals(2, first.nextOffset)
        val second = fetchBrowserTabPage(tab.tab, tab.binary, 2, 2)
        assertEquals(8L, second.rows.single().first)
        assertNull(second.nextOffset)
    }

    @Test
    fun aFileNotUsingTheProviderGetsNothing() {
        val catalog = loadProviderCatalog(listOf(acme), untrusted = false)
        val bound = bindBrowserProviderScripts(catalog, binary(), "a.bin", null, scriptedId = null)
        assertNull(bound.provider)
        assertTrue(bound.tabs.isEmpty())
    }

    @Test
    fun aBrokenScriptIsReportedAndRegistersNothing() {
        val catalog = loadProviderCatalog(
            listOf(
                ProviderScriptSource("/w/bad.js", """hexana.defineProvider({ id: "acme", name: "A" });"""),
                ProviderScriptSource("/w/tab.js", """hexana.defineTab({ name: "user:t", columns: [{ name: "c", type: "int" }], rows() {} });"""),
            ),
            untrusted = false,
        )
        assertTrue(catalog.scripts[0].error!!.contains("must start with \"user:\""), catalog.scripts[0].error)
        assertTrue(catalog.scripts[1].error!!.contains("appliesTo is required"), catalog.scripts[1].error)
        assertTrue(catalog.providers.isEmpty())
    }

    @Test
    fun aThrowingParseLeavesTheTabsAndSaysWhy() {
        val catalog = loadProviderCatalog(
            listOf(
                ProviderScriptSource(
                    "/w/p.js",
                    """
                    hexana.defineProvider({ id: "user:p", name: "P", parse() { throw new Error("boom"); } });
                    hexana.defineTab({ name: "user:t", appliesTo: ["*"], columns: [{ name: "c", type: "int" }], rows() { return { rows: [] }; } });
                    """.trimIndent(),
                ),
            ),
            untrusted = false,
        )
        val bound = bindBrowserProviderScripts(catalog, binary(), "a.bin", null, "user:p")
        assertNotNull(bound.error)
        assertTrue(bound.error!!.contains("boom"), bound.error)
        assertEquals(1, bound.tabs.size)
    }
}
