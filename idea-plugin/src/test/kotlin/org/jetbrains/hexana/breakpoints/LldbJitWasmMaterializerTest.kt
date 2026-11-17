package org.jetbrains.hexana.breakpoints

import com.intellij.testFramework.UsefulTestCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.run.AddressBreakpointOutcome
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BinaryIdentity
import org.jetbrains.hexana.shared.MaterializationResult
import org.jetbrains.hexana.shared.WasmtimeAddrmap

class LldbJitWasmMaterializerTest : UsefulTestCase() {

    private val identity = BinaryIdentity(contentSha256 = "aabb", elfBuildId = null)

    private fun wasmBp(byteOffset: Long): BinaryBreakpoint =
        BinaryBreakpoint(
            id = "bp-1", identity = identity,
            anchor = BinaryAnchor.Wasm(funcIdx = 1, byteOffset = byteOffset, funcName = "f", instrOrdinal = 0),
            enabled = true, rehomed = false, stale = false, filePath = null,
        )

    private fun addrmap(
        textLoadAddress: ULong = 0x7000u,
        codeOffsets: IntArray = intArrayOf(0x10, 0x20, 0x30),
        filePositions: IntArray = intArrayOf(0x100, -1, 0x200),
    ): WasmtimeAddrmap =
        WasmtimeAddrmap.fromArrays(textLoadAddress, textSize = 0x10000u, codeOffsets, filePositions)

    private class FakeLldb(private val outcome: AddressBreakpointOutcome) {
        val setAddresses = mutableListOf<ULong>()
        val deletedIds = mutableListOf<Int>()

        fun set(address: ULong): AddressBreakpointOutcome {
            setAddresses += address
            return outcome
        }

        fun delete(id: Int) {
            deletedIds += id
        }
    }

    private val resolvedOutcome = AddressBreakpointOutcome(breakpointId = 1, resolved = true, error = null)

    // ---- pcForWasmOffset (reverse lookup on real instances) ----

    fun testPcForWasmOffset_exactMatch() {
        assertEquals(0x7010uL, addrmap().pcForWasmOffset(0x100))
        assertEquals(0x7030uL, addrmap().pcForWasmOffset(0x200))
    }

    fun testPcForWasmOffset_minusOneEntriesNeverMatch() {
        // Entry [1] has filePosition -1; querying -1 must not return its pc.
        assertNull(addrmap().pcForWasmOffset(-1))
    }

    fun testPcForWasmOffset_absentOffsetReturnsNull() {
        assertNull(addrmap().pcForWasmOffset(0x999))
    }

    fun testPcForWasmOffset_firstMatchWins() {
        val map = addrmap(codeOffsets = intArrayOf(0x10, 0x20), filePositions = intArrayOf(0x100, 0x100))
        assertEquals(0x7010uL, map.pcForWasmOffset(0x100))
    }

    // ---- adapter ----

    fun testCoveredOffset_setsAddressBreakpoint_andVerifies() = runBlocking {
        val fake = FakeLldb(resolvedOutcome)
        val m = LldbJitWasmMaterializer(fake::set, fake::delete) { listOf(addrmap()) }
        val reports = mutableListOf<MaterializationResult>()
        m.materialize(wasmBp(0x200)) { reports += it }
        assertEquals(listOf(0x7030uL), fake.setAddresses)
        assertEquals(listOf<MaterializationResult>(MaterializationResult.Verified), reports)
    }

    fun testUncoveredOffset_degradedThenVerifiedAfterJitLoad() = runBlocking {
        val fake = FakeLldb(resolvedOutcome)
        var maps = emptyList<WasmtimeAddrmap>()
        val m = LldbJitWasmMaterializer(fake::set, fake::delete) { maps }
        val reports = mutableListOf<MaterializationResult>()

        m.materialize(wasmBp(0x200)) { reports += it }
        assertEquals(1, reports.size)
        val degraded = reports[0] as MaterializationResult.Degraded
        assertEquals("wasmtime", degraded.runtimeName)
        assertEquals("module not JIT-compiled yet; will bind on JIT load", degraded.resolvedDescription)
        assertEmpty(fake.setAddresses)

        maps = listOf(addrmap())
        m.onJitObjectsLoaded()

        assertEquals(2, reports.size)
        assertEquals(MaterializationResult.Verified, reports[1])
        assertEquals(listOf(0x7030uL), fake.setAddresses)
    }

    fun testOnJitObjectsLoaded_stillUncovered_staysParked() = runBlocking {
        val fake = FakeLldb(resolvedOutcome)
        val m = LldbJitWasmMaterializer(fake::set, fake::delete) { emptyList() }
        val reports = mutableListOf<MaterializationResult>()
        m.materialize(wasmBp(0x200)) { reports += it }
        m.onJitObjectsLoaded()
        assertEquals(1, reports.size)
        assertTrue(reports[0] is MaterializationResult.Degraded)
        assertEmpty(fake.setAddresses)
    }

    fun testNativeAnchor_reportsFailed() = runBlocking {
        val fake = FakeLldb(resolvedOutcome)
        val m = LldbJitWasmMaterializer(fake::set, fake::delete) { listOf(addrmap()) }
        val reports = mutableListOf<MaterializationResult>()
        val bp = BinaryBreakpoint(
            id = "bp-n", identity = identity,
            anchor = BinaryAnchor.Native(virtualAddress = 0x1000u, symbolName = null, offsetInSymbol = 0u),
            enabled = true, rehomed = false, stale = false, filePath = null,
        )
        m.materialize(bp) { reports += it }
        assertEquals(1, reports.size)
        assertTrue(reports[0] is MaterializationResult.Failed)
    }

    fun testDematerialize_removesParkedBreakpoint_soJitLoadDoesNotBindIt() = runBlocking {
        val fake = FakeLldb(resolvedOutcome)
        var maps = emptyList<WasmtimeAddrmap>()
        val m = LldbJitWasmMaterializer(fake::set, fake::delete) { maps }
        val bp = wasmBp(0x200)
        m.materialize(bp) { }
        m.dematerialize(bp)
        maps = listOf(addrmap())
        m.onJitObjectsLoaded()
        assertEmpty(fake.setAddresses)
    }
}
