package org.jetbrains.hexana.shared.features

import org.jetbrains.hexana.shared.ColumnDescriptor
import org.jetbrains.hexana.shared.TableTabDescriptor
import org.jetbrains.hexana.shared.compatibilityTabDescriptorForTest
import org.jetbrains.hexana.wasm.analysis.WasmProposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Compatibility tab is the home for the matrix and the documentation links, so what is
 * pinned here is the column layout, the cell text, and the two host hooks (open docs /
 * open settings) being optional.
 */
class CompatibilityTabTest {

    private val catalog = assertNotNull(
        WasmFeatureCatalog.parse(
            """
            {
              "features": {
                "gc":       { "description": "Garbage Collection", "url": "https://example.invalid/gc", "phase": 5 },
                "tailCall": { "description": "Tail Call",          "url": "https://example.invalid/tc", "phase": 5 }
              },
              "browsers": {
                "Chrome":   { "url": "https://example.invalid", "category": "Web Browsers",        "features": { "gc": "119", "tailCall": "112" } },
                "Wasmtime": { "url": "https://example.invalid", "category": "Standalone Runtimes", "features": { "gc": true, "tailCall": true } }
              }
            }
            """.trimIndent()
        )
    )

    private fun tab(
        targets: List<RuntimeTarget>,
        proposals: List<WasmProposal> = listOf(WasmProposal.GarbageCollection, WasmProposal.TailCall),
        openUrl: ((String) -> Unit)? = {},
        openRuntimeSettings: (() -> Unit)? = {},
    ): TableTabDescriptor? {
        val compatibility = WasmRuntimeCompatibility(catalog, RuntimeTargets(targets))
        return compatibilityTabDescriptorForTest(
            proposals = compatibility.evaluate(proposals),
            runtimeLabels = compatibility.runtimeLabels,
            openUrl = openUrl,
            openRuntimeSettings = openRuntimeSettings,
        )
    }

    @Test
    fun `no proposals yields no tab`() {
        assertNull(tab(listOf(RuntimeTarget("Chrome", "120")), proposals = emptyList()))
    }

    @Test
    fun `columns are proposal, phase, then one per declared runtime in order`() {
        val tab = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "100"), RuntimeTarget("Wasmtime"))))
        assertEquals(listOf("Proposal", "Phase", "Chrome 100", "Wasmtime"), tab.columns.map { it.name })
        assertEquals(2, tab.rowCount)
    }

    @Test
    fun `a supported cell reads Yes and an unsupported one names the version to upgrade to`() {
        val supported = assertNotNull(tab(listOf(RuntimeTarget("Wasmtime"))))
        assertEquals("Yes", supported.cellText(0, 2))

        val tab = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "100"))))
        // Rows follow WasmProposal declaration order: TailCall precedes GarbageCollection.
        assertEquals("No (since 112)", tab.cellText(0, 2))
        assertEquals("No (since 119)", tab.cellText(1, 2))
    }

    @Test
    fun `a warning row is marked in the proposal column and a clean row is not`() {
        val tab = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "137"))))
        assertTrue(tab.columns.isNotEmpty())
        assertEquals(WasmProposal.TailCall.displayName, tab.cellText(0, 0))

        val warning = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "100"))))
        assertTrue(warning.cellText(0, 0).startsWith("⚠"), "expected a marker, got ${warning.cellText(0, 0)}")
    }

    @Test
    fun `a cell tooltip carries the upstream note or the verdict`() {
        val tab = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "100"))))
        assertEquals("Chrome 100 — not supported, needs 112", tab.cellTooltip(0, 2))
    }

    @Test
    fun `the proposal tooltip carries the documentation url`() {
        val tab = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "137"))))
        assertTrue(
            assertNotNull(tab.cellTooltip(0, 0)).contains("https://example.invalid/tc"),
            "expected the doc url in the tooltip, got ${tab.cellTooltip(0, 0)}",
        )
    }

    @Test
    fun `the docs row action opens the proposal url`() {
        val opened = mutableListOf<String>()
        val tab = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "137")), openUrl = { opened += it }))
        val action = assertNotNull(tab.rowAction)
        action.action(0)
        assertEquals(listOf("https://example.invalid/tc"), opened)
    }

    @Test
    fun `a host without a browser hook gets no docs action and no docs menu item`() {
        val tab = assertNotNull(
            tab(listOf(RuntimeTarget("Chrome", "137")), openUrl = null, openRuntimeSettings = null)
        )
        assertNull(tab.rowAction)
        assertTrue(tab.contextMenuItems.isEmpty())
    }

    @Test
    fun `the configure-runtimes menu item is offered only when the host has settings`() {
        val withSettings = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "137"))))
        assertEquals(
            listOf("Open documentation", "Configure runtimes…"),
            withSettings.contextMenuItems.map { it.labelProvider(0) },
        )

        val withoutSettings = assertNotNull(
            tab(listOf(RuntimeTarget("Chrome", "137")), openRuntimeSettings = null)
        )
        assertEquals(listOf("Open documentation"), withoutSettings.contextMenuItems.map { it.labelProvider(0) })
    }

    @Test
    fun `a proposal with no documentation url hides its menu item`() {
        val tab = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "137")), proposals = listOf(WasmProposal.BranchHinting)))
        val docsItem = tab.contextMenuItems.first()
        assertTrue(!docsItem.isVisible(0), "expected the documentation item hidden with no url")
        assertNull(tab.rowAction?.tooltipProvider?.invoke(0))
    }

    // --- prologue: the tab has to explain itself the first time it is opened ---

    @Test
    fun `with no runtimes configured the prologue points at the settings file`() {
        val tab = assertNotNull(tab(emptyList()))
        assertTrue(
            assertNotNull(tab.prologueText).contains(RUNTIME_TARGETS_PATH),
            "expected the prologue to name the settings file, got ${tab.prologueText}",
        )
    }

    @Test
    fun `an all-clear prologue names the runtimes it checked`() {
        val tab = assertNotNull(tab(listOf(RuntimeTarget("Wasmtime"))))
        assertEquals("Every proposal this module uses is supported by Wasmtime.", tab.prologueText)
    }

    @Test
    fun `the prologue counts the failing proposals`() {
        assertTrue(
            assertNotNull(assertNotNull(tab(listOf(RuntimeTarget("Chrome", "100")))).prologueText)
                .startsWith("2 proposals"),
        )
        val single = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "115"))))
        assertTrue(assertNotNull(single.prologueText).startsWith("One proposal"), single.prologueText)
    }

    // --- filters ---

    @Test
    fun `warnings get a preselected unsupported-only filter`() {
        val tab = assertNotNull(tab(listOf(RuntimeTarget("Chrome", "115"))))
        val filter = tab.predefinedFilters.single()
        assertEquals("Unsupported only", filter.label)
        // GarbageCollection (row 1) is the failing one at Chrome 115; TailCall landed in 112.
        assertTrue(!filter.predicate(0))
        assertTrue(filter.predicate(1))
        assertTrue(!tab.defaultFilterShowAll, "expected the filter preselected when something warns")
    }

    @Test
    fun `a clean module gets no filter and shows everything`() {
        val tab = assertNotNull(tab(listOf(RuntimeTarget("Wasmtime"))))
        assertTrue(tab.predefinedFilters.isEmpty())
        assertTrue(tab.defaultFilterShowAll)
    }

    private fun TableTabDescriptor.cellText(row: Int, column: Int): String =
        columnAt(column).cellProvider(row).representation

    private fun TableTabDescriptor.cellTooltip(row: Int, column: Int): String? =
        columnAt(column).cellProvider(row).tooltip

    private fun TableTabDescriptor.columnAt(column: Int): ColumnDescriptor = columns[column]
}
