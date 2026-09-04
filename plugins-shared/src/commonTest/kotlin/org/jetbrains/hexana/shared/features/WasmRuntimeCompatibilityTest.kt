package org.jetbrains.hexana.shared.features

import org.jetbrains.hexana.wasm.analysis.WasmProposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the status-resolution matrix: one test per (catalog cell, declared version) pair the
 * evaluator can see, plus the warning policy that drives the badge marker.
 */
class WasmRuntimeCompatibilityTest {

    private val catalog = assertNotNull(
        WasmFeatureCatalog.parse(
            """
            {
              "features": {
                "gc":       { "description": "Garbage Collection", "url": "https://example.invalid/gc", "phase": 5 },
                "tailCall": { "description": "Tail Call",          "url": "https://example.invalid/tc", "phase": 5 },
                "threads":  { "description": "Threads",            "url": "https://example.invalid/th", "phase": 4 },
                "simd":     { "description": "Fixed-width SIMD",   "url": "https://example.invalid/sd", "phase": 5 }
              },
              "browsers": {
                "Versioned": {
                  "url": "https://example.invalid",
                  "category": "Web Browsers",
                  "features": { "gc": "119", "tailCall": "112" }
                },
                "Absolute": {
                  "url": "https://example.invalid",
                  "category": "Standalone Runtimes",
                  "features": {
                    "gc": true,
                    "tailCall": false,
                    "threads": ["flag", "Requires -W threads"],
                    "simd": null
                  }
                }
              }
            }
            """.trimIndent()
        )
    )

    private fun compatibility(vararg targets: RuntimeTarget) =
        WasmRuntimeCompatibility(catalog, RuntimeTargets(targets.toList()))

    // --- version-gated cells ---

    @Test
    fun `declared version at the landing version is supported`() {
        val status = single(RuntimeTarget("Versioned", "119"), WasmProposal.GarbageCollection)
        assertEquals(FeatureSupport.Supported, status.support)
        assertEquals("119", status.sinceVersion)
    }

    @Test
    fun `declared version above the landing version is supported`() {
        assertEquals(
            FeatureSupport.Supported,
            single(RuntimeTarget("Versioned", "137"), WasmProposal.GarbageCollection).support,
        )
    }

    @Test
    fun `declared version below the landing version is unsupported and reports what to upgrade to`() {
        val status = single(RuntimeTarget("Versioned", "100"), WasmProposal.GarbageCollection)
        assertEquals(FeatureSupport.Unsupported, status.support)
        assertEquals("119", status.sinceVersion)
        assertEquals("No (since 119)", status.shortLabel)
        assertEquals("Versioned 100 — not supported, needs 119", status.describe)
    }

    @Test
    fun `no declared version takes the runtime at its best`() {
        val status = single(RuntimeTarget("Versioned"), WasmProposal.GarbageCollection)
        assertEquals(FeatureSupport.Supported, status.support)
        assertNull(status.targetVersion)
    }

    // --- absolute cells ---

    @Test
    fun `true is supported regardless of declared version`() {
        assertEquals(
            FeatureSupport.Supported,
            single(RuntimeTarget("Absolute", "0.1"), WasmProposal.GarbageCollection).support,
        )
    }

    @Test
    fun `false is unsupported with no upgrade target`() {
        val status = single(RuntimeTarget("Absolute", "1.0"), WasmProposal.TailCall)
        assertEquals(FeatureSupport.Unsupported, status.support)
        assertNull(status.sinceVersion)
        assertEquals("No", status.shortLabel)
    }

    @Test
    fun `flag is reported with the upstream note that names the flag`() {
        val status = single(RuntimeTarget("Absolute"), WasmProposal.Threads)
        assertEquals(FeatureSupport.FlagRequired, status.support)
        assertEquals("Requires -W threads", status.note)
        assertEquals("Flag", status.shortLabel)
    }

    // --- absent data ---

    @Test
    fun `an explicit null cell is unknown`() {
        val status = single(RuntimeTarget("Absolute"), WasmProposal.SIMD)
        assertEquals(FeatureSupport.Unknown, status.support)
        assertEquals("?", status.shortLabel)
    }

    @Test
    fun `a feature the runtime does not list at all is unknown`() {
        assertEquals(
            FeatureSupport.Unknown,
            single(RuntimeTarget("Versioned", "137"), WasmProposal.Threads).support,
        )
    }

    @Test
    fun `a runtime absent from the catalog is unknown rather than an error`() {
        assertEquals(
            FeatureSupport.Unknown,
            single(RuntimeTarget("Not In Catalog", "1"), WasmProposal.GarbageCollection).support,
        )
    }

    // --- warning policy ---

    @Test
    fun `unsupported and flag warn, supported and unknown do not`() {
        assertTrue(FeatureSupport.Unsupported.isWarning)
        assertTrue(FeatureSupport.FlagRequired.isWarning)
        assertFalse(FeatureSupport.Supported.isWarning)
        assertFalse(FeatureSupport.Unknown.isWarning)
    }

    /**
     * The reason `Unknown` is not a warning: upstream leaves many cells empty, and warning
     * on them would put a marker on nearly every module.
     */
    @Test
    fun `a proposal with only unknown verdicts does not warn`() {
        val result = compatibility(RuntimeTarget("Versioned", "137")).evaluate(WasmProposal.Threads)
        assertFalse(result.hasWarning)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `one failing target among several is enough to warn, and only it is listed`() {
        val result = compatibility(
            RuntimeTarget("Versioned", "100"),
            RuntimeTarget("Absolute"),
        ).evaluate(WasmProposal.GarbageCollection)

        assertTrue(result.hasWarning)
        assertEquals(listOf("Versioned"), result.warnings.map { it.runtime })
    }

    @Test
    fun `hasAnyWarning is true when any proposal warns`() {
        val subject = compatibility(RuntimeTarget("Versioned", "100"))
        assertTrue(subject.hasAnyWarning(listOf(WasmProposal.SIMD, WasmProposal.GarbageCollection)))
        assertFalse(subject.hasAnyWarning(listOf(WasmProposal.SIMD)))
    }

    // --- shape of the evaluated result ---

    @Test
    fun `evaluation carries the documentation url and phase from the catalog`() {
        val result = compatibility(RuntimeTarget("Versioned", "137")).evaluate(WasmProposal.GarbageCollection)
        assertEquals("https://example.invalid/gc", result.docUrl)
        assertEquals("5", result.phase)
    }

    /** A proposal the catalog has no entry for still renders, just without a doc link. */
    @Test
    fun `a proposal missing from the catalog degrades to no doc url`() {
        val result = compatibility(RuntimeTarget("Versioned", "137")).evaluate(WasmProposal.BranchHinting)
        assertNull(result.docUrl)
        assertNull(result.phase)
        assertFalse(result.hasWarning)
    }

    @Test
    fun `statuses follow declaration order, which is the table column order`() {
        val subject = compatibility(RuntimeTarget("Absolute"), RuntimeTarget("Versioned", "120"))
        assertEquals(listOf("Absolute", "Versioned"), subject.evaluate(WasmProposal.GarbageCollection).statuses.map { it.runtime })
        assertEquals(listOf("Absolute", "Versioned 120"), subject.runtimeLabels)
    }

    @Test
    fun `evaluating a collection sorts by proposal declaration order`() {
        val results = compatibility(RuntimeTarget("Absolute"))
            .evaluate(listOf(WasmProposal.GarbageCollection, WasmProposal.MultiValue))
        assertEquals(listOf(WasmProposal.MultiValue, WasmProposal.GarbageCollection), results.map { it.proposal })
    }

    @Test
    fun `no declared targets yields no statuses and no warnings`() {
        val result = WasmRuntimeCompatibility(catalog, RuntimeTargets.EMPTY)
            .evaluate(WasmProposal.GarbageCollection)
        assertTrue(result.statuses.isEmpty())
        assertFalse(result.hasWarning)
    }

    private fun single(target: RuntimeTarget, proposal: WasmProposal): ProposalRuntimeStatus =
        compatibility(target).evaluate(proposal).statuses.single()
}
