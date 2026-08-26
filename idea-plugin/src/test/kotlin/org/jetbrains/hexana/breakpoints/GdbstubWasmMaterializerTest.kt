package org.jetbrains.hexana.breakpoints

import com.intellij.testFramework.UsefulTestCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.run.AddressBreakpointOutcome
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BinaryIdentity
import org.jetbrains.hexana.shared.MaterializationResult

class GdbstubWasmMaterializerTest : UsefulTestCase() {

    private val identity = BinaryIdentity(contentSha256 = "aabb", elfBuildId = null)

    private fun wasmBp(byteOffset: Long): BinaryBreakpoint =
        BinaryBreakpoint(
            id = "bp-1", identity = identity,
            anchor = BinaryAnchor.Wasm(funcIdx = 1, byteOffset = byteOffset, funcName = "f", instrOrdinal = 0),
            enabled = true, rehomed = false, stale = false, filePath = null,
        )

    private fun nativeBp(): BinaryBreakpoint =
        BinaryBreakpoint(
            id = "bp-n", identity = identity,
            anchor = BinaryAnchor.Native(virtualAddress = 0x1000u, symbolName = null, offsetInSymbol = 0u),
            enabled = true, rehomed = false, stale = false, filePath = null,
        )

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

    private fun materializer(fake: FakeLldb, moduleId: Int = 0, runtimeName: String = "wamr") =
        GdbstubWasmMaterializer(runtimeName, fake::set, fake::delete) { moduleId }

    fun testPcEncoding_moduleId0() = runBlocking {
        val fake = FakeLldb(AddressBreakpointOutcome(breakpointId = 1, resolved = true, error = null))
        materializer(fake, moduleId = 0).materialize(wasmBp(0x1A2)) { }
        assertEquals(listOf(0x40000000000001A2uL), fake.setAddresses)
    }

    fun testPcEncoding_moduleId2() = runBlocking {
        val fake = FakeLldb(AddressBreakpointOutcome(breakpointId = 1, resolved = true, error = null))
        materializer(fake, moduleId = 2).materialize(wasmBp(0x1A2)) { }
        assertEquals(listOf(0x40000002000001A2uL), fake.setAddresses)
    }

    fun testResolved_reportsVerified() = runBlocking {
        val fake = FakeLldb(AddressBreakpointOutcome(breakpointId = 3, resolved = true, error = null))
        val reports = mutableListOf<MaterializationResult>()
        materializer(fake).materialize(wasmBp(0x10)) { reports += it }
        assertEquals(listOf<MaterializationResult>(MaterializationResult.Verified), reports)
    }

    fun testPending_reportsDegraded_withRuntimeName() = runBlocking {
        val fake = FakeLldb(AddressBreakpointOutcome(breakpointId = 3, resolved = false, error = null))
        val reports = mutableListOf<MaterializationResult>()
        materializer(fake, runtimeName = "wasmtime").materialize(wasmBp(0x10)) { reports += it }
        assertEquals(1, reports.size)
        val degraded = reports[0] as MaterializationResult.Degraded
        assertEquals("wasmtime", degraded.runtimeName)
        assertEquals("address breakpoint pending in wasmtime", degraded.resolvedDescription)
    }

    fun testError_reportsFailed() = runBlocking {
        val fake = FakeLldb(AddressBreakpointOutcome(breakpointId = null, resolved = false, error = "error: boom"))
        val reports = mutableListOf<MaterializationResult>()
        materializer(fake).materialize(wasmBp(0x10)) { reports += it }
        assertEquals(listOf<MaterializationResult>(MaterializationResult.Failed("error: boom")), reports)
    }

    fun testNativeAnchor_reportsFailed_withoutSettingBreakpoint() = runBlocking {
        val fake = FakeLldb(AddressBreakpointOutcome(breakpointId = 1, resolved = true, error = null))
        val reports = mutableListOf<MaterializationResult>()
        materializer(fake).materialize(nativeBp()) { reports += it }
        assertEquals(1, reports.size)
        assertTrue(reports[0] is MaterializationResult.Failed)
        assertEmpty(fake.setAddresses)
    }

    fun testDematerialize_deletesTheParsedLldbId() = runBlocking {
        val fake = FakeLldb(AddressBreakpointOutcome(breakpointId = 7, resolved = true, error = null))
        val m = materializer(fake)
        val bp = wasmBp(0x10)
        m.materialize(bp) { }
        m.dematerialize(bp)
        assertEquals(listOf(7), fake.deletedIds)
    }

    fun testDematerialize_withoutParsedId_deletesNothing() = runBlocking {
        val fake = FakeLldb(AddressBreakpointOutcome(breakpointId = null, resolved = true, error = null))
        val m = materializer(fake)
        val bp = wasmBp(0x10)
        m.materialize(bp) { }
        m.dematerialize(bp)
        assertEmpty(fake.deletedIds)
    }
}
