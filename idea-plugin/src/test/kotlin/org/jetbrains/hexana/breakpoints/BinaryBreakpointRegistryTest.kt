package org.jetbrains.hexana.breakpoints

import com.intellij.testFramework.UsefulTestCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BinaryIdentity

/**
 * Tests for [BinaryBreakpointRegistry] — CRUD, toggle semantics, flow emission,
 * state round-trip, and identity matching.
 */
class BinaryBreakpointRegistryTest : UsefulTestCase() {

    private fun makeRegistry(): BinaryBreakpointRegistry {
        val r = BinaryBreakpointRegistry()
        // loadState with empty state seeds the flow
        r.loadState(BinaryBreakpointRegistryState())
        return r
    }

    private val identityA = BinaryIdentity(contentSha256 = "aabbccdd", elfBuildId = "build-a")
    private val identityB = BinaryIdentity(contentSha256 = "11223344", elfBuildId = "build-b")
    private val anchorWasm = BinaryAnchor.Wasm(funcIdx = 2, byteOffset = 100L, funcName = "foo", instrOrdinal = 3)
    private val anchorNative = BinaryAnchor.Native(virtualAddress = 0x8000uL, symbolName = "bar", offsetInSymbol = 4uL)

    // ---- add ----

    fun testAdd_returnsBreakpointWithGeneratedId() {
        val r = makeRegistry()
        val bp = r.add(identityA, anchorWasm, "/path/to/file.wasm")
        assertFalse("id should not be empty", bp.id.isEmpty())
        assertEquals(identityA, bp.identity)
        assertEquals(anchorWasm, bp.anchor)
        assertTrue("enabled by default", bp.enabled)
        assertFalse("not stale", bp.stale)
        assertFalse("not rehomed", bp.rehomed)
        assertEquals("/path/to/file.wasm", bp.filePath)
    }

    fun testAdd_appearsInFlow() = runBlocking {
        val r = makeRegistry()
        r.add(identityA, anchorWasm, null)
        val list = r.breakpoints.first()
        assertEquals(1, list.size)
    }

    fun testAdd_multipleBreakpointsAccumulate() {
        val r = makeRegistry()
        r.add(identityA, anchorWasm, null)
        r.add(identityA, anchorNative, null)
        r.add(identityB, anchorWasm, null)
        assertEquals(3, r.breakpoints.value.size)
    }

    // ---- remove ----

    fun testRemove_removesById() {
        val r = makeRegistry()
        val bp1 = r.add(identityA, anchorWasm, null)
        val bp2 = r.add(identityA, anchorNative, null)
        r.remove(bp1.id)
        val remaining = r.breakpoints.value
        assertEquals(1, remaining.size)
        assertEquals(bp2.id, remaining.first().id)
    }

    fun testRemove_unknownIdIsNoop() {
        val r = makeRegistry()
        r.add(identityA, anchorWasm, null)
        r.remove("nonexistent-id")
        assertEquals(1, r.breakpoints.value.size)
    }

    // ---- setEnabled ----

    fun testSetEnabled_togglesEnabledFlag() {
        val r = makeRegistry()
        val bp = r.add(identityA, anchorWasm, null)
        r.setEnabled(bp.id, false)
        assertEquals(false, r.breakpoints.value.first { it.id == bp.id }.enabled)
        r.setEnabled(bp.id, true)
        assertEquals(true, r.breakpoints.value.first { it.id == bp.id }.enabled)
    }

    // ---- toggle ----

    fun testToggle_addsWhenNoExisting() {
        val r = makeRegistry()
        val added = r.toggle(identityA, anchorWasm, null)
        assertTrue("should return true (added)", added)
        assertEquals(1, r.breakpoints.value.size)
    }

    fun testToggle_removesWhenSameSha256AndSamePosition() {
        val r = makeRegistry()
        r.toggle(identityA, anchorWasm, null)
        val removed = r.toggle(identityA, anchorWasm, null)
        assertFalse("should return false (removed)", removed)
        assertEquals(0, r.breakpoints.value.size)
    }

    fun testToggle_doesNotMatchDifferentSha256EvenIfSameBuildId() {
        // Different SHA but same build-id: toggle does NOT remove (SHA is the exact match key for toggle)
        val r = makeRegistry()
        r.toggle(identityA, anchorWasm, null)
        val identityASameBuildDifferentSha = BinaryIdentity(contentSha256 = "different", elfBuildId = "build-a")
        val added = r.toggle(identityASameBuildDifferentSha, anchorWasm, null)
        assertTrue("different SHA -> new breakpoint added", added)
        assertEquals(2, r.breakpoints.value.size)
    }

    fun testToggle_nativeAnchorMatchesByVirtualAddress() {
        val r = makeRegistry()
        r.toggle(identityA, anchorNative, null)
        // Same VA, different symbolName (secondary key) -> should still remove
        val sameVaNoName = BinaryAnchor.Native(virtualAddress = 0x8000uL, symbolName = null, offsetInSymbol = 99uL)
        val removed = r.toggle(identityA, sameVaNoName, null)
        assertFalse("should remove matching native anchor", removed)
        assertEquals(0, r.breakpoints.value.size)
    }

    // ---- forIdentity ----

    fun testForIdentity_matchesBySha256() {
        val r = makeRegistry()
        r.add(identityA, anchorWasm, null)
        r.add(identityB, anchorNative, null)
        val found = r.forIdentity(BinaryIdentity(contentSha256 = "aabbccdd"))
        assertEquals(1, found.size)
        assertEquals(identityA, found.first().identity)
    }

    fun testForIdentity_matchesByBuildId() {
        val r = makeRegistry()
        // identityA has elfBuildId "build-a"
        r.add(identityA, anchorWasm, null)
        r.add(identityB, anchorNative, null)
        // Different SHA but same build-id
        val query = BinaryIdentity(contentSha256 = "xxxxxxxx", elfBuildId = "build-a")
        val found = r.forIdentity(query)
        assertEquals(1, found.size)
    }

    fun testForIdentity_pathAloneDoesNotMatch() {
        val r = makeRegistry()
        r.add(identityA, anchorWasm, "/same/path.wasm")
        // Completely different identity
        val query = BinaryIdentity(contentSha256 = "zzzzzzzz", elfBuildId = null)
        val found = r.forIdentity(query)
        assertEquals(0, found.size)
    }

    // ---- forPathWithDifferentIdentity ----

    fun testForPathWithDifferentIdentity_returnsOnlyDifferentIdentity() {
        val r = makeRegistry()
        val path = "/app/firmware.elf"
        r.add(identityA, anchorWasm, path)
        r.add(identityA, anchorNative, path) // same identity -> excluded
        r.add(identityB, anchorWasm, path)   // different identity -> included
        val found = r.forPathWithDifferentIdentity(path, identityA)
        assertEquals(1, found.size)
        assertEquals(identityB, found.first().identity)
    }

    // ---- applyRehome ----

    fun testApplyRehome_updatesIdentityAndAnchorAndSetsRehomed() {
        val r = makeRegistry()
        val bp = r.add(identityA, anchorWasm, "/old/path.wasm")
        val newAnchor = BinaryAnchor.Wasm(funcIdx = 5, byteOffset = 200L, funcName = "foo", instrOrdinal = 3)
        r.applyRehome(bp.id, identityB, newAnchor)
        val updated = r.breakpoints.value.first { it.id == bp.id }
        assertEquals(identityB, updated.identity)
        assertEquals(newAnchor, updated.anchor)
        assertTrue("rehomed flag set", updated.rehomed)
        assertFalse("not stale after rehome", updated.stale)
    }

    // ---- markStale ----

    fun testMarkStale_setsStaleAndDisables() {
        val r = makeRegistry()
        val bp = r.add(identityA, anchorWasm, null)
        r.markStale(bp.id)
        val updated = r.breakpoints.value.first { it.id == bp.id }
        assertTrue("stale", updated.stale)
        assertFalse("disabled", updated.enabled)
    }

    // ---- XML state round-trip ----

    fun testStateRoundTrip_wasmBreakpoint() {
        val r1 = makeRegistry()
        r1.add(identityA, anchorWasm, "/some/path")
        r1.add(identityB, anchorNative, null)

        // Serialize to state then load into a fresh registry
        val savedState = r1.state
        val r2 = makeRegistry()
        r2.loadState(savedState)

        val bps = r2.breakpoints.value
        assertEquals(2, bps.size)

        val wasm = bps.first { it.anchor is BinaryAnchor.Wasm }
        assertEquals(identityA.contentSha256, wasm.identity.contentSha256)
        assertEquals(identityA.elfBuildId, wasm.identity.elfBuildId)
        val wasmAnchor = wasm.anchor as BinaryAnchor.Wasm
        assertEquals(anchorWasm.funcIdx, wasmAnchor.funcIdx)
        assertEquals(anchorWasm.byteOffset, wasmAnchor.byteOffset)
        assertEquals(anchorWasm.funcName, wasmAnchor.funcName)
        assertEquals(anchorWasm.instrOrdinal, wasmAnchor.instrOrdinal)
    }

    fun testStateRoundTrip_nativeBreakpointULongHex() {
        val r1 = makeRegistry()
        // Use a large ULong address that exceeds Int range to verify hex encoding
        val bigNative = BinaryAnchor.Native(
            virtualAddress = 0xDEADBEEF_CAFEBABEuL,
            symbolName = "bigFunc",
            offsetInSymbol = 0x1000uL,
        )
        r1.add(identityB, bigNative, null)

        val r2 = makeRegistry()
        r2.loadState(r1.state)

        val bp = r2.breakpoints.value.first()
        val anchor = bp.anchor as BinaryAnchor.Native
        assertEquals(0xDEADBEEF_CAFEBABEuL, anchor.virtualAddress)
        assertEquals(0x1000uL, anchor.offsetInSymbol)
    }

    fun testStateRoundTrip_staleAndRehomed() {
        val r1 = makeRegistry()
        val bp = r1.add(identityA, anchorWasm, null)
        r1.markStale(bp.id)

        val r2 = makeRegistry()
        r2.loadState(r1.state)

        val loaded = r2.breakpoints.value.first()
        assertTrue(loaded.stale)
        assertFalse(loaded.enabled)
    }

    // ---- flow emission ----

    fun testFlow_emitsOnAdd() = runBlocking {
        val r = makeRegistry()
        assertEquals(0, r.breakpoints.value.size)
        r.add(identityA, anchorWasm, null)
        assertEquals(1, r.breakpoints.first { it.size == 1 }.size)
    }

    fun testFlow_emitsOnRemove() = runBlocking {
        val r = makeRegistry()
        val bp = r.add(identityA, anchorWasm, null)
        r.remove(bp.id)
        assertEquals(0, r.breakpoints.first { it.isEmpty() }.size)
    }

    // ---- identity matching ----

    fun testBinaryIdentityMatches_sameSha() {
        val a = BinaryIdentity("abc123")
        val b = BinaryIdentity("abc123", elfBuildId = "irrelevant")
        assertTrue(a.matches(b))
        assertTrue(b.matches(a))
    }

    fun testBinaryIdentityMatches_sameBuildIdDifferentSha() {
        val a = BinaryIdentity("sha-a", elfBuildId = "common-build")
        val b = BinaryIdentity("sha-b", elfBuildId = "common-build")
        assertTrue(a.matches(b))
        assertTrue(b.matches(a))
    }

    fun testBinaryIdentityDoesNotMatch_differentShaAndNoBuildId() {
        val a = BinaryIdentity("sha-a")
        val b = BinaryIdentity("sha-b")
        assertFalse(a.matches(b))
    }

    fun testBinaryIdentityDoesNotMatch_nullBuildIdCannotMatch() {
        // If one has null buildId, buildId leg is skipped
        val a = BinaryIdentity("sha-a", elfBuildId = null)
        val b = BinaryIdentity("sha-b", elfBuildId = "some-build")
        assertFalse(a.matches(b))
    }
}
