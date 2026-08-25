package org.jetbrains.hexana.shared

/** Lifecycle state of a breakpoint in the current debug session. */
enum class MaterializationState { PENDING, VERIFIED, DEGRADED, FAILED }

/** Result reported by a [BreakpointMaterializer] for one breakpoint. */
sealed interface MaterializationResult {
    data object Verified : MaterializationResult
    data class Degraded(val runtimeName: String, val resolvedDescription: String) : MaterializationResult
    data class Failed(val reason: String) : MaterializationResult
}

/**
 * Runtime-specific breakpoint materializer.
 *
 * Each runtime (wasmtime, WAMR, lldb, ...) provides one implementation.
 * [materialize] may invoke [report] more than once; the coordinator takes the
 * last reported value. Adapters MUST eventually call [report]; silence is
 * converted to [MaterializationResult.Failed] after a coordinator-defined deadline.
 */
interface BreakpointMaterializer {
    val runtimeName: String

    /**
     * Set the breakpoint in the runtime and report the outcome via [report].
     *
     * [report] may be called more than once (last write wins), allowing an
     * adapter to upgrade a DEGRADED result to VERIFIED after a JIT object loads.
     * Called on Dispatchers.IO.
     */
    suspend fun materialize(bp: BinaryBreakpoint, report: (MaterializationResult) -> Unit)

    /** Remove the breakpoint from the runtime. Called on Dispatchers.IO. */
    suspend fun dematerialize(bp: BinaryBreakpoint)
}
