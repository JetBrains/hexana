package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.StructureRange
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which provider scripts run for a file, and what they contribute — with no IDE: sessions are
 * opened by a real [HexanaScriptRunner], the catalog is built by hand the way the IntelliJ
 * `ProviderScriptsService` builds it.
 */
class ProviderScriptBindingsTest {

    private val runner = HexanaScriptRunner(limits = HexanaScriptLimits.interactive())
    private val opened = AtomicInteger()

    private val open: (ProviderScript, BinaryProvider, String?, String?) -> HexanaScriptSession =
        { script, provider, name, path ->
            opened.incrementAndGet()
            runner.openSession(HexanaScriptSource.Inline(script.text, "test.js"), provider, name, path)
        }

    private fun binary(size: Int = 64) = GenericBinaryProvider(ByteArray(size).toCommonByteBuffer())

    /** Builds a catalog entry the way the service does: a metadata pass against an empty binary. */
    private fun script(text: String): ProviderScript =
        runner.openSession(HexanaScriptSource.Inline(text.trimIndent(), "test.js"), binary(0)).use { s ->
            check(s.initialResult.success) { "metadata pass failed: " + s.initialResult.diagnostics }
            ProviderScript("/p/test.js", text.trimIndent(), s.initialResult.providers, s.initialResult.slices.filter { it.isTab })
        }

    private val acme = """
        hexana.defineProvider({
            id: "user:acme", name: "ACME",
            parse(binary) { return { regions: [{ name: "Header", offset: 0, length: 8 }, { name: "Past end", offset: 60, length: 8 }] }; },
        });
        hexana.defineTab({
            name: "user:Fields", appliesTo: ["user:acme"],
            columns: [{ name: "Name", type: "string" }],
            rows() { return { rows: [{ cells: ["magic"] }] }; },
        });
        hexana.defineSlice({
            name: "user:Not a tab", columns: [{ name: "x", type: "int" }], rows() { return { rows: [] }; },
        });
    """

    @Test
    fun theChosenProviderContributesItsRegionsAndItsTabs() {
        val catalog = ProviderScriptCatalog(listOf(script(acme)))
        val scripted = catalog.provider("user:acme")!!.second
        bindProviderScripts(open, catalog, binary(), "fw.img", null, scripted).use { bound ->
            val overlay = bound.overlay!!
            assertEquals(listOf(StructureRange(0, 8, "Header")), overlay.ranges)
            assertTrue(overlay.error!!.contains("1 region"))
            // defineTab attaches; a plain defineSlice in the same script does not.
            assertEquals(listOf("user:Fields"), bound.tabs.map { it.tab.name })
        }
    }

    @Test
    fun aFileNotUsingTheProviderRunsNothing() {
        val catalog = ProviderScriptCatalog(listOf(script(acme)))
        val before = opened.get()
        bindProviderScripts(open, catalog, binary(), "other.bin", null, scripted = null).use { bound ->
            assertNull(bound.overlay)
            assertTrue(bound.tabs.isEmpty())
        }
        assertEquals(before, opened.get())
    }

    @Test
    fun tabsAttachByBuiltinFormatWithoutAnyProviderChosen() {
        val catalog = ProviderScriptCatalog(
            listOf(
                script(
                    """
                    hexana.defineTab({
                        name: "user:Everywhere", appliesTo: ["generic"],
                        columns: [{ name: "Size", type: "int" }],
                        rows(binary) { return { rows: [{ cells: [binary.size] }] }; },
                    });
                    """,
                ),
            ),
        )
        bindProviderScripts(open, catalog, binary(), "a.bin", null, scripted = null).use { bound ->
            assertEquals(listOf("user:Everywhere"), bound.tabs.map { it.tab.name })
            assertTrue(bound.tabs.single().session.isAlive)
        }
    }

    @Test
    fun aThrowingParseIsReportedOnTheOverlay() {
        val catalog = ProviderScriptCatalog(
            listOf(script("""hexana.defineProvider({ id: "user:bad", name: "Bad", parse() { throw new Error("nope"); } });""")),
        )
        bindProviderScripts(open, catalog, binary(), "a.bin", null, catalog.provider("user:bad")!!.second).use { bound ->
            assertTrue(bound.overlay!!.ranges.isEmpty())
            assertTrue(bound.overlay!!.error!!.contains("nope"))
        }
    }
}
