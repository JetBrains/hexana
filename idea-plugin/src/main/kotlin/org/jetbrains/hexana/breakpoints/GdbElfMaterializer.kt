package org.jetbrains.hexana.breakpoints

import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BreakpointMaterializer
import org.jetbrains.hexana.shared.MaterializationResult

/**
 * Transport contract for setting instruction breakpoints over a gdb
 * connection to an ELF target.
 *
 * This is the contract FirmwareDebugProcess/GdbDapClient implement later:
 * a console gdb transport maps [setAddressBreakpoint] to `break *0xADDR`,
 * a DAP transport to `setInstructionBreakpoints` with an
 * `instructionReference` of the same address. [loadBias] is added to every
 * anchor address before it reaches the target (nonzero for PIE images or
 * relocated XIP firmware).
 */
interface GdbInstructionBreakpointTransport {
    val loadBias: ULong get() = 0u
    suspend fun setAddressBreakpoint(address: ULong): GdbBreakpointOutcome
    suspend fun removeAddressBreakpoint(address: ULong)
}

/** Outcome of one [GdbInstructionBreakpointTransport.setAddressBreakpoint] call. */
sealed interface GdbBreakpointOutcome {
    data object Resolved : GdbBreakpointOutcome
    data object Pending : GdbBreakpointOutcome
    data class Rejected(val message: String) : GdbBreakpointOutcome
}

/**
 * Materializes [BinaryAnchor.Native] breakpoints through a
 * [GdbInstructionBreakpointTransport]: the anchor's virtual address plus the
 * transport's load bias becomes an address breakpoint on the target.
 */
class GdbElfMaterializer(
    override val runtimeName: String,
    private val transport: GdbInstructionBreakpointTransport,
) : BreakpointMaterializer {

    override suspend fun materialize(bp: BinaryBreakpoint, report: (MaterializationResult) -> Unit) {
        val anchor = bp.anchor
        if (anchor !is BinaryAnchor.Native) {
            report(MaterializationResult.Failed("wasm anchor not supported by gdb ELF adapter"))
            return
        }
        val outcome = transport.setAddressBreakpoint(anchor.virtualAddress + transport.loadBias)
        report(
            when (outcome) {
                is GdbBreakpointOutcome.Resolved -> MaterializationResult.Verified
                is GdbBreakpointOutcome.Pending ->
                    MaterializationResult.Degraded(runtimeName, "address pending until target image loads")
                is GdbBreakpointOutcome.Rejected -> MaterializationResult.Failed(outcome.message)
            },
        )
    }

    override suspend fun dematerialize(bp: BinaryBreakpoint) {
        val anchor = bp.anchor as? BinaryAnchor.Native ?: return
        transport.removeAddressBreakpoint(anchor.virtualAddress + transport.loadBias)
    }
}
