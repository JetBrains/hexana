package org.jetbrains.hexana.breakpoints

import org.jetbrains.hexana.run.CdpDebugProcess
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BreakpointMaterializer
import org.jetbrains.hexana.shared.MaterializationResult
import java.util.concurrent.ConcurrentHashMap

/** Outcome of [CdpDebugProcess.setWasmOffsetBreakpoint]. */
sealed interface CdpOffsetBreakpointResult {
    /**
     * The breakpoint was set. [resolvedColumn] is the wasm byte offset V8
     * actually bound it to, or null when the response carried no location
     * (URL-regex breakpoints on scripts not parsed yet).
     */
    data class Set(val cdpBreakpointId: String, val resolvedColumn: Long?) : CdpOffsetBreakpointResult
    data class Rejected(val reason: String) : CdpOffsetBreakpointResult
}

/**
 * Materializes [BinaryAnchor.Wasm] breakpoints over CDP (V8 in Node.js or
 * Chrome): the anchor's byte offset becomes a `columnNumber` breakpoint on
 * the wasm script.
 *
 * The primary constructor takes function seams so tests need no
 * [CdpDebugProcess]; the secondary constructor binds a real process.
 */
class CdpWasmMaterializer(
    private val setBp: suspend (Long) -> CdpOffsetBreakpointResult,
    private val removeBp: suspend (String) -> Unit,
    override val runtimeName: String = "CDP",
) : BreakpointMaterializer {

    internal constructor(process: CdpDebugProcess) :
        this(process::setWasmOffsetBreakpoint, process::removeWasmOffsetBreakpoint)

    /** CDP breakpoint ids by [BinaryBreakpoint.id], recorded for removal. */
    private val cdpBreakpointIds = ConcurrentHashMap<String, String>()

    override suspend fun materialize(bp: BinaryBreakpoint, report: (MaterializationResult) -> Unit) {
        val anchor = bp.anchor
        if (anchor !is BinaryAnchor.Wasm) {
            report(MaterializationResult.Failed("native anchor not supported by the CDP adapter"))
            return
        }
        when (val result = setBp(anchor.byteOffset)) {
            is CdpOffsetBreakpointResult.Set -> {
                cdpBreakpointIds[bp.id] = result.cdpBreakpointId
                val resolved = result.resolvedColumn
                report(
                    if (resolved == null || resolved == anchor.byteOffset) {
                        MaterializationResult.Verified
                    } else {
                        MaterializationResult.Degraded(
                            runtimeName,
                            "runtime moved breakpoint to offset 0x${resolved.toString(16)}",
                        )
                    },
                )
            }
            is CdpOffsetBreakpointResult.Rejected -> report(MaterializationResult.Failed(result.reason))
        }
    }

    override suspend fun dematerialize(bp: BinaryBreakpoint) {
        val id = cdpBreakpointIds.remove(bp.id) ?: return
        removeBp(id)
    }
}
