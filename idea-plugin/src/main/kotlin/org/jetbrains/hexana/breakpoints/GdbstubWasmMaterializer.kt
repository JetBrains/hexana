package org.jetbrains.hexana.breakpoints

import org.jetbrains.hexana.run.AddressBreakpointOutcome
import org.jetbrains.hexana.run.LldbCommunicator
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BreakpointMaterializer
import org.jetbrains.hexana.shared.MaterializationResult
import java.util.concurrent.ConcurrentHashMap

/** Base marker bit of LLDB wasm-gdbstub code addresses. */
private const val GDBSTUB_PC_BASE = 0x4000000000000000UL

/**
 * Encodes a wasm byte offset as an LLDB wasm-gdbstub code address:
 * `0x4000000000000000 | (moduleId << 32) | byteOffset`, the inverse of
 * [org.jetbrains.hexana.shared.wasmOffsetForGdbPc] and
 * [org.jetbrains.hexana.shared.wasmModuleIdForGdbPc].
 */
internal fun encodeGdbstubPc(moduleId: Int, byteOffset: Long): ULong =
    GDBSTUB_PC_BASE or (moduleId.toULong() shl 32) or byteOffset.toULong()

/**
 * Materializes [BinaryAnchor.Wasm] breakpoints on a wasm gdbstub session
 * (WAMR and wasmtime `-g`): the anchor's byte offset is encoded as a stub
 * PC and set as an LLDB address breakpoint.
 *
 * The primary constructor takes function seams so tests need no
 * [LldbCommunicator]; the secondary constructor binds a real communicator.
 */
class GdbstubWasmMaterializer internal constructor(
    override val runtimeName: String,
    private val setAddressBreakpoint: (ULong) -> AddressBreakpointOutcome,
    private val deleteBreakpoint: (Int) -> Unit,
    private val moduleIdProvider: () -> Int,
) : BreakpointMaterializer {

    constructor(
        runtimeName: String,
        communicator: LldbCommunicator,
        moduleIdProvider: () -> Int = { 0 },
    ) : this(
        runtimeName,
        communicator::sendAddressBreakpointChecked,
        communicator::removeBreakpointById,
        moduleIdProvider,
    )

    /** LLDB breakpoint ids by [BinaryBreakpoint.id], recorded for precise deletion. */
    private val lldbBreakpointIds = ConcurrentHashMap<String, Int>()

    override suspend fun materialize(bp: BinaryBreakpoint, report: (MaterializationResult) -> Unit) {
        val anchor = bp.anchor
        if (anchor !is BinaryAnchor.Wasm) {
            report(MaterializationResult.Failed("native anchor not supported by the wasm gdbstub adapter"))
            return
        }
        val pc = encodeGdbstubPc(moduleIdProvider(), anchor.byteOffset)
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

    override suspend fun dematerialize(bp: BinaryBreakpoint) {
        // Deleting by the id parsed from "Breakpoint N:" is precise. When no id
        // was parseable the breakpoint is left in place: `breakpoint delete -f`
        // would also wipe the user's file/line breakpoints.
        val id = lldbBreakpointIds.remove(bp.id) ?: return
        deleteBreakpoint(id)
    }
}
