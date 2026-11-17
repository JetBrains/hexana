package org.jetbrains.hexana.breakpoints

import com.intellij.testFramework.UsefulTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryIdentity
import org.jetbrains.hexana.shared.MaterializationState
import org.jetbrains.hexana.ui.panel.GutterMarksModel

/**
 * Acceptance tests covering cross-view consistency and persistence scenarios for
 * [GutterMarksModel] backed by a shared [BinaryBreakpointRegistry].
 *
 * These tests exercise the multi-renderer and view-reopen paths that are NOT
 * covered by the per-component unit tests.
 *
 * No IntelliJ project services are used: the registry and GutterMarksModel are
 * instantiated directly, as in [BinaryBreakpointRegistryTest].
 */
class BreakpointCrossViewAcceptanceTest : UsefulTestCase() {

    private val identity = BinaryIdentity(contentSha256 = "cross-view-sha", elfBuildId = null)
    private val anchor = BinaryAnchor.Wasm(funcIdx = 0, byteOffset = 64L, funcName = "fn", instrOrdinal = 1)
    private val sessionStates = MutableStateFlow(emptyMap<String, SessionBreakpointState>())

    private fun makeRegistry(): BinaryBreakpointRegistry {
        val r = BinaryBreakpointRegistry()
        r.loadState(BinaryBreakpointRegistryState())
        return r
    }

    // ---- Two renderers, same registry ----

    /**
     * Two [GutterMarksModel] instances share the same registry flow. Toggling a
     * breakpoint through the registry must appear in BOTH marks maps at the same
     * key.
     */
    fun testTwoRenderers_bothSeeBreakpoint() = runBlocking {
        val registry = makeRegistry()
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model1 = GutterMarksModel(registry.breakpoints, sessionStates, identity, scope1)
            val model2 = GutterMarksModel(registry.breakpoints, sessionStates, identity, scope2)

            registry.toggle(identity, anchor, null)

            val marks1 = model1.marks.first { it.containsKey(64L) }
            val marks2 = model2.marks.first { it.containsKey(64L) }

            assertEquals("model1 must have exactly 1 mark", 1, marks1.size)
            assertEquals("model2 must have exactly 1 mark", 1, marks2.size)
            assertEquals("both must key on byteOffset 64", marks1[64L]!!.breakpointId, marks2[64L]!!.breakpointId)
        } finally {
            scope1.cancel()
            scope2.cancel()
        }
    }

    /**
     * Removing a breakpoint through the registry must make it disappear from both
     * marks maps.
     */
    fun testTwoRenderers_removalDisappearsFromBoth() = runBlocking {
        val registry = makeRegistry()
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model1 = GutterMarksModel(registry.breakpoints, sessionStates, identity, scope1)
            val model2 = GutterMarksModel(registry.breakpoints, sessionStates, identity, scope2)

            registry.toggle(identity, anchor, null)
            // Wait for both to reflect the add.
            model1.marks.first { it.containsKey(64L) }
            model2.marks.first { it.containsKey(64L) }

            // Remove via second toggle.
            registry.toggle(identity, anchor, null)

            val marks1 = model1.marks.first { it.isEmpty() }
            val marks2 = model2.marks.first { it.isEmpty() }

            assertEquals("model1 must be empty after removal", 0, marks1.size)
            assertEquals("model2 must be empty after removal", 0, marks2.size)
        } finally {
            scope1.cancel()
            scope2.cancel()
        }
    }

    // ---- View close + reopen ----

    /**
     * Cancelling the first model's scope (simulating view close) and creating a
     * fresh model over the same registry must still show the breakpoint.
     * The registry is the source of truth; the model is a view-level projection.
     */
    fun testViewReopen_breakpointSurvivesViewClose() = runBlocking {
        val registry = makeRegistry()
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model1 = GutterMarksModel(registry.breakpoints, sessionStates, identity, scope1)
            registry.toggle(identity, anchor, null)
            model1.marks.first { it.containsKey(64L) }

            // Close the first view.
            scope1.cancel()
        } finally {
            // scope1 is already cancelled.
        }

        // Reopen: a fresh model over the same registry.
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model2 = GutterMarksModel(registry.breakpoints, sessionStates, identity, scope2)
            val marks = model2.marks.first { it.containsKey(64L) }
            assertEquals("mark must survive view close+reopen", 1, marks.size)
            assertEquals("key must be byteOffset 64", 64L, marks.keys.first())
        } finally {
            scope2.cancel()
        }
    }

    // ---- Persistence round-trip for two-renderer scenario ----

    /**
     * getState/loadState round-trip: two breakpoints survive serialisation into a
     * fresh registry, and a new model sees exactly those two breakpoints at the
     * correct keys.
     *
     * Extends [BinaryBreakpointRegistryTest.testStateRoundTrip_wasmBreakpoint] with
     * the two-renderer and correct-key assertions that are not present there.
     */
    fun testPersistenceRoundTrip_twoRenderersSeeRestoredBreakpoints() = runBlocking {
        val anchor2 = BinaryAnchor.Wasm(funcIdx = 1, byteOffset = 128L, funcName = "fn2", instrOrdinal = 2)

        val r1 = makeRegistry()
        r1.add(identity, anchor, "/some/file.wasm")
        r1.add(identity, anchor2, "/some/file.wasm")
        assertEquals("precondition: 2 breakpoints in r1", 2, r1.breakpoints.value.size)

        val saved = r1.state
        val r2 = makeRegistry()
        r2.loadState(saved)

        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model1 = GutterMarksModel(r2.breakpoints, sessionStates, identity, scope1)
            val model2 = GutterMarksModel(r2.breakpoints, sessionStates, identity, scope2)

            val marks1 = model1.marks.first { it.size == 2 }
            val marks2 = model2.marks.first { it.size == 2 }

            assertEquals("model1 sees 2 breakpoints", 2, marks1.size)
            assertEquals("model2 sees 2 breakpoints", 2, marks2.size)
            assertTrue("model1 has key 64", marks1.containsKey(64L))
            assertTrue("model1 has key 128", marks1.containsKey(128L))
            assertTrue("model2 has key 64", marks2.containsKey(64L))
            assertTrue("model2 has key 128", marks2.containsKey(128L))
            assertEquals("both models return same ids for key 64",
                marks1[64L]!!.breakpointId, marks2[64L]!!.breakpointId)
        } finally {
            scope1.cancel()
            scope2.cancel()
        }
    }

    // ---- Session state reflected consistently across views ----

    /**
     * When the shared session-states flow updates to VERIFIED for a breakpoint,
     * both models must reflect it at the same key with MaterializationState.VERIFIED.
     */
    fun testSessionState_reflectedInBothRenderers() = runBlocking {
        val registry = makeRegistry()
        val mutableSession = MutableStateFlow(emptyMap<String, SessionBreakpointState>())
        val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val model1 = GutterMarksModel(registry.breakpoints, mutableSession, identity, scope1)
            val model2 = GutterMarksModel(registry.breakpoints, mutableSession, identity, scope2)

            val bp = registry.add(identity, anchor, null)
            model1.marks.first { it.containsKey(64L) }
            model2.marks.first { it.containsKey(64L) }

            mutableSession.value = mapOf(bp.id to SessionBreakpointState(MaterializationState.VERIFIED, null))

            val marks1 = model1.marks.first { it[64L]?.state == MaterializationState.VERIFIED }
            val marks2 = model2.marks.first { it[64L]?.state == MaterializationState.VERIFIED }

            assertEquals("model1 must show VERIFIED", MaterializationState.VERIFIED, marks1[64L]!!.state)
            assertEquals("model2 must show VERIFIED", MaterializationState.VERIFIED, marks2[64L]!!.state)
        } finally {
            scope1.cancel()
            scope2.cancel()
        }
    }
}
