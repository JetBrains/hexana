package org.jetbrains.hexana.breakpoints

import org.jetbrains.hexana.run.AddressBreakpointOutcome
import org.jetbrains.hexana.run.LldbCommunicator
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BreakpointMaterializer
import org.jetbrains.hexana.shared.MaterializationResult
import org.jetbrains.hexana.shared.WasmtimeAddrmap
import java.util.concurrent.ConcurrentHashMap

/**
 * Materializes [BinaryAnchor.Wasm] breakpoints on the legacy wasmtime JIT
 * path (wasmtime under LLDB, no gdbstub): the anchor's wasm byte offset is
 * reverse-mapped to a native pc via the JIT images' `.wasmtime.addrmap`
 * ([WasmtimeAddrmap.pcForWasmOffset]) and set as an LLDB address breakpoint.
 *
 * When no addrmap covers the offset yet (module not JIT-compiled), the
 * breakpoint is reported Degraded and parked; [onJitObjectsLoaded] retries it
 * after WasmDebugProcess refreshes the addrmaps, upgrading the report to
 * Verified (the [BreakpointMaterializer] contract allows repeated reports).
 *
 * Thread safety: the coordinator calls [materialize]/[dematerialize] on
 * Dispatchers.IO while the LLDB event loop thread calls [onJitObjectsLoaded];
 * the pending set is a concurrent map with atomic remove-then-act.
 */
class LldbJitWasmMaterializer internal constructor(
    private val setAddressBreakpoint: (ULong) -> AddressBreakpointOutcome,
    private val deleteBreakpoint: (Int) -> Unit,
    private val addrmapsProvider: () -> List<WasmtimeAddrmap>,
) : BreakpointMaterializer {

    constructor(
        communicator: LldbCommunicator,
        addrmapsProvider: () -> List<WasmtimeAddrmap>,
    ) : this(
        communicator::sendAddressBreakpointChecked,
        communicator::removeBreakpointById,
        addrmapsProvider,
    )

    override val runtimeName: String = "wasmtime"

    private class Pending(val bp: BinaryBreakpoint, val report: (MaterializationResult) -> Unit)

    /** Breakpoints whose module was not JIT-compiled at materialize time, by bp id. */
    private val pending = ConcurrentHashMap<String, Pending>()

    /** LLDB breakpoint ids by [BinaryBreakpoint.id], recorded for precise deletion. */
    private val lldbBreakpointIds = ConcurrentHashMap<String, Int>()

    override suspend fun materialize(bp: BinaryBreakpoint, report: (MaterializationResult) -> Unit) {
        val anchor = bp.anchor
        if (anchor !is BinaryAnchor.Wasm) {
            report(MaterializationResult.Failed("native anchor not supported by the wasmtime JIT adapter"))
            return
        }
        val pc = pcForOffset(anchor.byteOffset)
        if (pc == null) {
            report(MaterializationResult.Degraded(runtimeName, "module not JIT-compiled yet; will bind on JIT load"))
            pending[bp.id] = Pending(bp, report)
            return
        }
        setAndReport(bp, pc, report)
    }

    /**
     * Retries parked breakpoints against the refreshed addrmaps. Called by
     * WasmDebugProcess after a successful addrmap refresh (event loop thread).
     */
    fun onJitObjectsLoaded() {
        for ((id, parked) in pending) {
            val anchor = parked.bp.anchor as? BinaryAnchor.Wasm ?: continue
            val pc = pcForOffset(anchor.byteOffset) ?: continue
            if (pending.remove(id) == null) continue
            setAndReport(parked.bp, pc, parked.report)
        }
    }

    override suspend fun dematerialize(bp: BinaryBreakpoint) {
        pending.remove(bp.id)
        val id = lldbBreakpointIds.remove(bp.id) ?: return
        deleteBreakpoint(id)
    }

    private fun pcForOffset(byteOffset: Long): ULong? =
        addrmapsProvider().firstNotNullOfOrNull { it.pcForWasmOffset(byteOffset.toInt()) }

    private fun setAndReport(bp: BinaryBreakpoint, pc: ULong, report: (MaterializationResult) -> Unit) {
        val outcome = setAddressBreakpoint(pc)
        if (outcome.error != null) {
            report(MaterializationResult.Failed(outcome.error))
            return
        }
        outcome.breakpointId?.let { lldbBreakpointIds[bp.id] = it }
        report(
            if (outcome.resolved) MaterializationResult.Verified
            else MaterializationResult.Degraded(runtimeName, "address breakpoint pending in $runtimeName"),
        )
    }
}
