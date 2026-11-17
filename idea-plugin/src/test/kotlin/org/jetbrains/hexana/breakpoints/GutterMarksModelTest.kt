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
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BinaryIdentity
import org.jetbrains.hexana.shared.MaterializationState
import org.jetbrains.hexana.ui.panel.GutterMark
import org.jetbrains.hexana.ui.panel.GutterMarksModel

/**
 * Unit tests for [GutterMarksModel].
 *
 * Tests use [runBlocking] so they don't need the full IDE environment.
 * Each test creates its own [CoroutineScope] and cancels it after use
 * so stateIn / SharingStarted.Eagerly coroutines don't outlive the test.
 */
class GutterMarksModelTest : UsefulTestCase() {

    private val identityA = BinaryIdentity(contentSha256 = "aabb", elfBuildId = null)
    private val identityB = BinaryIdentity(contentSha256 = "ccdd", elfBuildId = null)

    private val wasmBp = BinaryBreakpoint(
        id = "wasm-1",
        identity = identityA,
        anchor = BinaryAnchor.Wasm(funcIdx = 0, byteOffset = 42L, funcName = null, instrOrdinal = 0),
        enabled = true,
        rehomed = false,
        stale = false,
        filePath = null,
    )
    private val nativeBp = BinaryBreakpoint(
        id = "native-1",
        identity = identityA,
        anchor = BinaryAnchor.Native(virtualAddress = 0x1000uL, symbolName = null, offsetInSymbol = 0uL),
        enabled = true,
        rehomed = false,
        stale = false,
        filePath = null,
    )
    private val otherIdentityBp = BinaryBreakpoint(
        id = "other-1",
        identity = identityB,
        anchor = BinaryAnchor.Wasm(funcIdx = 1, byteOffset = 99L, funcName = null, instrOrdinal = 0),
        enabled = true,
        rehomed = false,
        stale = false,
        filePath = null,
    )

    // ---- WASM offset keying ----

    fun testWasmBreakpoint_keyedByByteOffset() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bpFlow = MutableStateFlow(listOf(wasmBp))
            val stateFlow = MutableStateFlow(emptyMap<String, SessionBreakpointState>())
            val model = GutterMarksModel(bpFlow, stateFlow, identityA, scope)

            val marks = model.marks.first { it.isNotEmpty() }
            assertTrue("byteOffset 42 should be a key", marks.containsKey(42L))
            val mark = marks[42L]!!
            assertEquals("wasm-1", mark.breakpointId)
            assertTrue(mark.enabled)
            assertFalse(mark.stale)
            assertNull(mark.state)
        } finally {
            scope.cancel()
        }
    }

    // ---- Native VA keying ----

    fun testNativeBreakpoint_keyedByVirtualAddress() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bpFlow = MutableStateFlow(listOf(nativeBp))
            val stateFlow = MutableStateFlow(emptyMap<String, SessionBreakpointState>())
            val model = GutterMarksModel(bpFlow, stateFlow, identityA, scope)

            val marks = model.marks.first { it.isNotEmpty() }
            assertTrue("VA 0x1000 should be a key", marks.containsKey(0x1000L))
            assertEquals("native-1", marks[0x1000L]!!.breakpointId)
        } finally {
            scope.cancel()
        }
    }

    // ---- Identity filtering ----

    fun testBreakpointsForOtherIdentity_excluded() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // Only otherIdentityBp; model is keyed to identityA.
            val bpFlow = MutableStateFlow(listOf(otherIdentityBp))
            val stateFlow = MutableStateFlow(emptyMap<String, SessionBreakpointState>())
            val model = GutterMarksModel(bpFlow, stateFlow, identityA, scope)

            // Allow one emission cycle; the map should remain empty.
            val marks = model.marks.first()
            assertTrue("Foreign-identity breakpoint must not appear", marks.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    // ---- Session state reflected ----

    fun testVerifiedSessionState_reflectedInMark() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bpFlow = MutableStateFlow(listOf(wasmBp))
            val sessionState = SessionBreakpointState(MaterializationState.VERIFIED, null)
            val stateFlow = MutableStateFlow(mapOf("wasm-1" to sessionState))
            val model = GutterMarksModel(bpFlow, stateFlow, identityA, scope)

            val marks = model.marks.first { it.containsKey(42L) }
            val mark = marks[42L]!!
            assertEquals(MaterializationState.VERIFIED, mark.state)
            assertNull(mark.detail)
        } finally {
            scope.cancel()
        }
    }

    fun testFailedSessionState_detailPropagated() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bpFlow = MutableStateFlow(listOf(wasmBp))
            val sessionState = SessionBreakpointState(MaterializationState.FAILED, "adapter timeout")
            val stateFlow = MutableStateFlow(mapOf("wasm-1" to sessionState))
            val model = GutterMarksModel(bpFlow, stateFlow, identityA, scope)

            val marks = model.marks.first { it.containsKey(42L) }
            val mark = marks[42L]!!
            assertEquals(MaterializationState.FAILED, mark.state)
            assertEquals("adapter timeout", mark.detail)
        } finally {
            scope.cancel()
        }
    }

    // ---- Live flow update ----

    fun testRemovingBreakpoint_removesFromMarks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val bpFlow = MutableStateFlow(listOf(wasmBp))
            val stateFlow = MutableStateFlow(emptyMap<String, SessionBreakpointState>())
            val model = GutterMarksModel(bpFlow, stateFlow, identityA, scope)

            // Confirm breakpoint present.
            model.marks.first { it.containsKey(42L) }

            // Remove it.
            bpFlow.value = emptyList()

            val marks = model.marks.first { it.isEmpty() }
            assertTrue("Map must be empty after breakpoint removed", marks.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    // ---- Stale flag ----

    fun testStaleBreakpoint_markedInGutterMark() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val staleBp = wasmBp.copy(stale = true)
            val bpFlow = MutableStateFlow(listOf(staleBp))
            val stateFlow = MutableStateFlow(emptyMap<String, SessionBreakpointState>())
            val model = GutterMarksModel(bpFlow, stateFlow, identityA, scope)

            val marks = model.marks.first { it.containsKey(42L) }
            assertTrue("Stale flag must propagate", marks[42L]!!.stale)
        } finally {
            scope.cancel()
        }
    }
}
