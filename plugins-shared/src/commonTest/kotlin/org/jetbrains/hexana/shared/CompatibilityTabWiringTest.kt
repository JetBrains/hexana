package org.jetbrains.hexana.shared

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.features.RuntimeTarget
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.jetbrains.hexana.shared.features.WasmFeatureCatalog
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Compatibility tab depends on data only a host can supply, so its presence is
 * conditional in three ways: on a host having supplied the catalog and targets, on the
 * binary being a Wasm module, and on that module actually using a proposal. Each of those
 * is pinned here, alongside the `availableTabNames`/`singleTab` fast path staying in step
 * with the batch `tableTabs` path — a mismatch there shows up as a tab placeholder that
 * appears and then vanishes.
 */
class CompatibilityTabWiringTest {

    private val provider = WasmTableTabsProvider()

    private fun compatibility(vararg targets: RuntimeTarget) =
        WasmRuntimeCompatibility(WasmFeatureCatalog.bundled, RuntimeTargets(targets.toList()))

    /**
     * A module using the sign-extension proposal: `i32.extend8_s` (0xC0) in a function body.
     * Built by hand rather than via `wat2wasm`, which `plugins-shared` tests cannot reach.
     */
    private fun wasmUsingSignExtension(): WasmBinaryProvider {
        val w = WasmBinaryWriter()
        w.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        w.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00))

        val types = WasmBinaryWriter()
        types.writeULEB128(1)
        types.writeByte(0x60)
        types.writeULEB128(0)
        types.writeULEB128(0)
        w.writeSection(1, types.toByteArray())

        val funcs = WasmBinaryWriter()
        funcs.writeULEB128(1)
        funcs.writeULEB128(0)
        w.writeSection(3, funcs.toByteArray())

        val body = WasmBinaryWriter()
        body.writeULEB128(0)          // no locals
        body.writeByte(0x41)          // i32.const
        body.writeULEB128(0)
        body.writeByte(0xC0)          // i32.extend8_s
        body.writeByte(0x1A)          // drop
        body.writeByte(0x0B)          // end
        val bodyBytes = body.toByteArray()

        val code = WasmBinaryWriter()
        code.writeULEB128(1)
        code.writeULEB128(bodyBytes.size)
        code.writeBytes(bodyBytes)
        w.writeSection(10, code.toByteArray())

        return WasmBinaryProvider(w.toByteArray().toCommonByteBuffer())
    }

    private fun emptyWasm(): WasmBinaryProvider {
        val w = WasmBinaryWriter()
        w.writeBytes(byteArrayOf(0x00, 0x61, 0x73, 0x6D))
        w.writeBytes(byteArrayOf(0x01, 0x00, 0x00, 0x00))
        return WasmBinaryProvider(w.toByteArray().toCommonByteBuffer())
    }

    @Test
    fun `the fixture module does use a proposal`() {
        assertTrue(
            wasmUsingSignExtension().usedProposals.isNotEmpty(),
            "the fixture must use a proposal or the rest of this test class proves nothing",
        )
    }

    @Test
    fun `the tab appears when a host supplies compatibility data`() {
        val context = TableTabsContext(runtimeCompatibility = compatibility(RuntimeTarget("Chrome", "120")))
        val names = provider.tableTabs(wasmUsingSignExtension(), context).map { it.name }
        assertTrue("Compatibility" in names, "expected a Compatibility tab, got $names")
    }

    /** The browser webapp path: no project directory, so no targets and no tab. */
    @Test
    fun `the tab is absent when the host supplies nothing`() {
        val names = provider.tableTabs(wasmUsingSignExtension(), TableTabsContext()).map { it.name }
        assertFalse("Compatibility" in names, "expected no Compatibility tab, got $names")
    }

    /**
     * Configuring no runtimes still shows the tab: its prologue is what explains the feature
     * and links to the settings page, so hiding it would hide the only route in.
     */
    @Test
    fun `the tab appears with no runtimes configured and explains itself`() {
        var opened = 0
        val context = TableTabsContext(
            runtimeCompatibility = compatibility(),
            openRuntimeSettings = { opened++ },
        )
        val tab = assertNotNull(provider.tableTabs(wasmUsingSignExtension(), context).find { it.name == "Compatibility" })
        assertEquals(listOf("Proposal", "Phase", "Supported in"), tab.columns.map { it.name })
        assertNotNull(tab.prologueText)

        assertNotNull(tab.prologueAction).action()
        assertEquals(1, opened, "expected the prologue link to open the settings page")
    }

    @Test
    fun `a module using no proposals gets no tab`() {
        val context = TableTabsContext(runtimeCompatibility = compatibility(RuntimeTarget("Chrome", "120")))
        val names = provider.tableTabs(emptyWasm(), context).map { it.name }
        assertFalse("Compatibility" in names, "expected no Compatibility tab, got $names")
    }

    @Test
    fun `a non-wasm binary gets no tab`() {
        val context = TableTabsContext(runtimeCompatibility = compatibility(RuntimeTarget("Chrome", "120")))
        val generic = GenericBinaryProvider(byteArrayOf(1, 2, 3, 4).toCommonByteBuffer())
        assertTrue(provider.tableTabs(generic, context).isEmpty())
        assertNull(provider.singleTab("Compatibility", generic, context))
    }

    // --- the cheap-discovery fast path must agree with the batch path ---

    @Test
    fun `the name is advertised exactly when the tab resolves`() {
        val wasm = wasmUsingSignExtension()
        val withData = TableTabsContext(runtimeCompatibility = compatibility(RuntimeTarget("Chrome", "120")))
        val (advertised, _) = assertNotNull(provider.availableTabNames(wasm, withData))
        assertTrue("Compatibility" in advertised, "expected the name advertised, got $advertised")
        assertNotNull(provider.singleTab("Compatibility", wasm, withData))

        val (notAdvertised, _) = assertNotNull(provider.availableTabNames(wasm, TableTabsContext()))
        assertFalse("Compatibility" in notAdvertised)
        assertNull(provider.singleTab("Compatibility", wasm, TableTabsContext()))
    }

    /**
     * A module with no proposals is the one case where the name is advertised and the tab
     * resolves to null - the documented "placeholder is removed" behaviour that Problems and
     * Exceptions also rely on.
     */
    @Test
    fun `an advertised name resolving to null is the documented placeholder path`() {
        val context = TableTabsContext(runtimeCompatibility = compatibility(RuntimeTarget("Chrome", "120")))
        val empty = emptyWasm()
        val (advertised, _) = assertNotNull(provider.availableTabNames(empty, context))
        assertTrue("Compatibility" in advertised)
        assertNull(provider.singleTab("Compatibility", empty, context))
    }

    @Test
    fun `singleTab and tableTabs produce the same columns`() {
        val wasm = wasmUsingSignExtension()
        val context = TableTabsContext(
            runtimeCompatibility = compatibility(RuntimeTarget("Chrome", "120"), RuntimeTarget("Wasmtime")),
        )
        val fromBatch = assertNotNull(provider.tableTabs(wasm, context).find { it.name == "Compatibility" })
        val fromSingle = assertNotNull(provider.singleTab("Compatibility", wasm, context))
        assertEquals(fromBatch.columns.map { it.name }, fromSingle.columns.map { it.name })
        assertEquals(
            listOf("Proposal", "Phase", "Chrome 120", "Wasmtime", "Other runtimes"),
            fromSingle.columns.map { it.name },
        )
        assertEquals(fromBatch.rowCount, fromSingle.rowCount)
    }
}
