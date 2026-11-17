package org.jetbrains.hexana.breakpoints

import com.intellij.testFramework.UsefulTestCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BinaryIdentity
import org.jetbrains.hexana.shared.MaterializationResult

class GdbElfMaterializerTest : UsefulTestCase() {

    private val identity = BinaryIdentity(contentSha256 = "aabb", elfBuildId = null)

    private fun nativeBp(address: ULong): BinaryBreakpoint =
        BinaryBreakpoint(
            id = "bp-native", identity = identity,
            anchor = BinaryAnchor.Native(virtualAddress = address, symbolName = "main", offsetInSymbol = 0u),
            enabled = true, rehomed = false, stale = false, filePath = null,
        )

    private fun wasmBp(): BinaryBreakpoint =
        BinaryBreakpoint(
            id = "bp-wasm", identity = identity,
            anchor = BinaryAnchor.Wasm(funcIdx = 1, byteOffset = 0x10, funcName = "f", instrOrdinal = 0),
            enabled = true, rehomed = false, stale = false, filePath = null,
        )

    private class FakeTransport(
        private val outcome: GdbBreakpointOutcome,
        override val loadBias: ULong = 0u,
    ) : GdbInstructionBreakpointTransport {
        val setAddresses = mutableListOf<ULong>()
        val removedAddresses = mutableListOf<ULong>()

        override suspend fun setAddressBreakpoint(address: ULong): GdbBreakpointOutcome {
            setAddresses += address
            return outcome
        }

        override suspend fun removeAddressBreakpoint(address: ULong) {
            removedAddresses += address
        }
    }

    fun testResolved_setsBiasedAddress_andReportsVerified() = runBlocking {
        val transport = FakeTransport(GdbBreakpointOutcome.Resolved, loadBias = 0x2000u)
        val materializer = GdbElfMaterializer("qemu-gdb", transport)
        val reports = mutableListOf<MaterializationResult>()
        materializer.materialize(nativeBp(0x1000u)) { reports += it }
        assertEquals(listOf(0x3000uL), transport.setAddresses)
        assertEquals(listOf<MaterializationResult>(MaterializationResult.Verified), reports)
    }

    fun testPending_reportsDegraded_withRuntimeName() = runBlocking {
        val materializer = GdbElfMaterializer("renode-gdb", FakeTransport(GdbBreakpointOutcome.Pending))
        val reports = mutableListOf<MaterializationResult>()
        materializer.materialize(nativeBp(0x1000u)) { reports += it }
        assertEquals(1, reports.size)
        val degraded = reports[0] as MaterializationResult.Degraded
        assertEquals("renode-gdb", degraded.runtimeName)
        assertEquals("address pending until target image loads", degraded.resolvedDescription)
    }

    fun testRejected_reportsFailed_withMessage() = runBlocking {
        val materializer = GdbElfMaterializer("qemu-gdb", FakeTransport(GdbBreakpointOutcome.Rejected("x")))
        val reports = mutableListOf<MaterializationResult>()
        materializer.materialize(nativeBp(0x1000u)) { reports += it }
        assertEquals(listOf<MaterializationResult>(MaterializationResult.Failed("x")), reports)
    }

    fun testWasmAnchor_reportsFailed_withoutTouchingTransport() = runBlocking {
        val transport = FakeTransport(GdbBreakpointOutcome.Resolved)
        val materializer = GdbElfMaterializer("qemu-gdb", transport)
        val reports = mutableListOf<MaterializationResult>()
        materializer.materialize(wasmBp()) { reports += it }
        assertEquals(1, reports.size)
        assertTrue(reports[0] is MaterializationResult.Failed)
        assertEmpty(transport.setAddresses)
    }

    fun testDematerialize_removesTheSameBiasedAddress() = runBlocking {
        val transport = FakeTransport(GdbBreakpointOutcome.Resolved, loadBias = 0x2000u)
        val materializer = GdbElfMaterializer("qemu-gdb", transport)
        val bp = nativeBp(0x1000u)
        materializer.materialize(bp) { }
        materializer.dematerialize(bp)
        assertEquals(listOf(0x3000uL), transport.removedAddresses)
        assertEquals(transport.setAddresses, transport.removedAddresses)
    }
}
