package org.jetbrains.hexana.features

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.shared.features.FeatureSupport
import org.jetbrains.hexana.shared.features.RuntimeTarget
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.jetbrains.hexana.shared.features.WasmFeatureCatalog
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility
import org.jetbrains.hexana.wasm.analysis.WasmProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Walks the whole data path with real inputs: a real `.wasm` file on disk, the real committed
 * `features.json` snapshot, and a real `.hexana/runtimes.json` written through the store the
 * settings page uses.
 *
 * The per-layer tests cover each piece; this one catches the seams between them — a mapping
 * whose `featureId` does not match the catalog, or a version comparison that reverses.
 * No network and no IDE.
 */
class RuntimeCompatibilityEndToEndTest {

    @get:Rule
    val projectDir = TemporaryFolder()

    /** A real Wasm module from the plugin's own test data. */
    private fun provider(): WasmBinaryProvider {
        val file = File("src/test/data/js/calculator.wasm")
        assertTrue("missing fixture ${file.absolutePath}", file.isFile)
        return WasmBinaryProvider(file.readBytes().toCommonByteBuffer())
    }

    private fun declare(vararg targets: RuntimeTarget): WasmRuntimeCompatibility {
        RuntimeTargetsStore.write(projectDir.root.absolutePath, RuntimeTargets(targets.toList()))
        return WasmRuntimeCompatibility(
            WasmFeatureCatalog.bundled,
            RuntimeTargetsStore.read(projectDir.root.absolutePath),
        )
    }

    @Test
    fun `an old browser is reported as unable to run a module using a modern proposal`() {
        // Bulk memory landed in Chrome 75; a Chrome 60 target cannot run a module using it.
        val subject = declare(RuntimeTarget("Chrome", "60"))
        val evaluated = subject.evaluate(setOf(WasmProposal.BulkMemoryOperations)).single()

        assertTrue("expected a warning", evaluated.hasWarning)
        val status = evaluated.statuses.single()
        assertEquals(FeatureSupport.Unsupported, status.support)
        assertEquals("75", status.sinceVersion)
        assertEquals("Chrome 60 — not supported, needs 75", status.describe)
    }

    @Test
    fun `a current browser is reported as able to run the same proposal`() {
        val subject = declare(RuntimeTarget("Chrome", "137"))
        val evaluated = subject.evaluate(setOf(WasmProposal.BulkMemoryOperations)).single()

        assertFalse("expected no warning", evaluated.hasWarning)
        assertEquals(FeatureSupport.Supported, evaluated.statuses.single().support)
    }

    /**
     * The whole point of the file: a declaration written by one editor is read back
     * unchanged, so the other editor reaches the same verdict.
     */
    @Test
    fun `the declaration survives a round trip through the project file`() {
        val declared = listOf(
            RuntimeTarget("Chrome", "100"),
            RuntimeTarget("Wasmtime", "26.0"),
            RuntimeTarget("Node.js"),
        )
        RuntimeTargetsStore.write(projectDir.root.absolutePath, RuntimeTargets(declared))

        val reread = RuntimeTargetsStore.read(projectDir.root.absolutePath)
        assertEquals(declared, reread.targets)
        assertEquals(
            listOf("Chrome 100", "Wasmtime 26.0", "Node.js"),
            WasmRuntimeCompatibility(WasmFeatureCatalog.bundled, reread).runtimeLabels,
        )
    }

    /**
     * The plugin's own `.wasm` test data is MVP-only, and that is a real state worth pinning:
     * such a module gets no proposals, so no badges and no Compatibility tab.
     */
    @Test
    fun `an mvp module from the plugin test data uses no proposals`() {
        assertTrue(
            "expected the MVP fixture to use no proposals",
            provider().usedProposals.isEmpty(),
        )
    }

    @Test
    fun `every proposal resolves to a documentation url and a phase`() {
        val subject = declare(RuntimeTarget("Chrome", "137"))
        for (evaluated in subject.evaluate(WasmProposal.entries)) {
            assertTrue(
                "no documentation url for ${evaluated.proposal}",
                evaluated.docUrl?.startsWith("https://") == true,
            )
            assertTrue("no phase for ${evaluated.proposal}", !evaluated.phase.isNullOrBlank())
        }
    }

    @Test
    fun `declaring no runtimes yields verdicts for nothing and warns about nothing`() {
        val subject = declare()
        val evaluated = subject.evaluate(WasmProposal.entries)

        assertTrue(subject.runtimeLabels.isEmpty())
        assertTrue(evaluated.all { it.statuses.isEmpty() })
        assertFalse(evaluated.any { it.hasWarning })
    }

    /**
     * Every proposal resolves against every runtime the catalog knows, so no
     * (proposal, runtime) pair can throw or produce a verdict outside the enum.
     */
    @Test
    fun `all proposals resolve against all catalog runtimes`() {
        val catalog = WasmFeatureCatalog.bundled
        val allRuntimes = catalog.runtimes.keys.map { RuntimeTarget(it) }
        val subject = WasmRuntimeCompatibility(catalog, RuntimeTargets(allRuntimes))

        for (evaluated in subject.evaluate(WasmProposal.entries)) {
            assertEquals(
                "wrong number of verdicts for ${evaluated.proposal}",
                allRuntimes.size,
                evaluated.statuses.size,
            )
        }
    }
}
