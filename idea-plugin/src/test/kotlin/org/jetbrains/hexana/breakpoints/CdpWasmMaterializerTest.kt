package org.jetbrains.hexana.breakpoints

import com.intellij.testFramework.UsefulTestCase
import kotlinx.coroutines.runBlocking
import org.jetbrains.hexana.run.AddressBreakpointOutcome
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BinaryIdentity
import org.jetbrains.hexana.shared.BreakpointMaterializer
import org.jetbrains.hexana.shared.MaterializationResult
import org.jetbrains.hexana.shared.WasmtimeAddrmap

class CdpWasmMaterializerTest : UsefulTestCase() {

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

    private fun materializer(
        result: CdpOffsetBreakpointResult,
        removed: MutableList<String> = mutableListOf(),
    ) = CdpWasmMaterializer(setBp = { result }, removeBp = { removed += it })

    fun testSet_resolvedEqualsRequested_verified() = runBlocking {
        val reports = mutableListOf<MaterializationResult>()
        materializer(CdpOffsetBreakpointResult.Set("cdp-1", resolvedColumn = 0x100))
            .materialize(wasmBp(0x100)) { reports += it }
        assertEquals(listOf<MaterializationResult>(MaterializationResult.Verified), reports)
    }

    fun testSet_resolvedNull_verified() = runBlocking {
        val reports = mutableListOf<MaterializationResult>()
        materializer(CdpOffsetBreakpointResult.Set("cdp-1", resolvedColumn = null))
            .materialize(wasmBp(0x100)) { reports += it }
        assertEquals(listOf<MaterializationResult>(MaterializationResult.Verified), reports)
    }

    fun testSet_resolvedDiffers_degradedWithActualHexOffset() = runBlocking {
        val reports = mutableListOf<MaterializationResult>()
        materializer(CdpOffsetBreakpointResult.Set("cdp-1", resolvedColumn = 0x1A2))
            .materialize(wasmBp(0x100)) { reports += it }
        assertEquals(1, reports.size)
        val degraded = reports[0] as MaterializationResult.Degraded
        assertEquals("CDP", degraded.runtimeName)
        assertTrue(
            "expected hex of the actual offset in '${degraded.resolvedDescription}'",
            "0x1a2" in degraded.resolvedDescription,
        )
    }

    fun testRejected_failed() = runBlocking {
        val reports = mutableListOf<MaterializationResult>()
        materializer(CdpOffsetBreakpointResult.Rejected("no wasm script"))
            .materialize(wasmBp(0x100)) { reports += it }
        assertEquals(listOf<MaterializationResult>(MaterializationResult.Failed("no wasm script")), reports)
    }

    fun testNativeAnchor_failed() = runBlocking {
        val reports = mutableListOf<MaterializationResult>()
        materializer(CdpOffsetBreakpointResult.Set("cdp-1", null))
            .materialize(nativeBp()) { reports += it }
        assertEquals(1, reports.size)
        assertTrue(reports[0] is MaterializationResult.Failed)
    }

    fun testDematerialize_passesTheCdpBreakpointId() = runBlocking {
        val removed = mutableListOf<String>()
        val m = materializer(CdpOffsetBreakpointResult.Set("cdp-42", null), removed)
        val bp = wasmBp(0x100)
        m.materialize(bp) { }
        m.dematerialize(bp)
        assertEquals(listOf("cdp-42"), removed)
    }

    // ---- Golden no-silent-drop test over EVERY adapter ----

    /**
     * Every adapter's materialize MUST call report at least once with a
     * terminal result, for supported and unsupported anchors alike. This
     * test fails if any adapter returns without reporting.
     */
    fun testAllAdapters_materializeAlwaysReports() = runBlocking {
        val resolvedOutcome = AddressBreakpointOutcome(breakpointId = 1, resolved = true, error = null)
        val coveringMap = WasmtimeAddrmap.fromArrays(
            textLoadAddress = 0x7000u, textSize = 0x10000u,
            codeOffsets = intArrayOf(0x10), filePositions = intArrayOf(0x100),
        )
        val transport = object : GdbInstructionBreakpointTransport {
            override suspend fun setAddressBreakpoint(address: ULong) = GdbBreakpointOutcome.Resolved
            override suspend fun removeAddressBreakpoint(address: ULong) {}
        }
        val adapters: List<BreakpointMaterializer> = listOf(
            GdbElfMaterializer("gdb", transport),
            GdbstubWasmMaterializer("wamr", { resolvedOutcome }, { }, { 0 }),
            LldbJitWasmMaterializer({ resolvedOutcome }, { }, { listOf(coveringMap) }),
            LldbJitWasmMaterializer({ resolvedOutcome }, { }, { emptyList() }), // pending path
            CdpWasmMaterializer(setBp = { CdpOffsetBreakpointResult.Set("cdp-1", null) }, removeBp = { }),
        )
        val anchors = listOf(
            BinaryAnchor.Wasm(funcIdx = 0, byteOffset = 0x100, funcName = null, instrOrdinal = 0),
            BinaryAnchor.Native(virtualAddress = 0x1000u, symbolName = null, offsetInSymbol = 0u),
        )
        for (adapter in adapters) {
            for (anchor in anchors) {
                val bp = BinaryBreakpoint(
                    id = "bp", identity = identity, anchor = anchor,
                    enabled = true, rehomed = false, stale = false, filePath = null,
                )
                val reports = mutableListOf<MaterializationResult>()
                adapter.materialize(bp) { reports += it }
                assertTrue(
                    "${adapter::class.simpleName} (${adapter.runtimeName}) silently dropped $anchor",
                    reports.isNotEmpty(),
                )
            }
        }
    }
}
