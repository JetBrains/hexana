package org.jetbrains.hexana.breakpoints

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.HexanaService
import org.jetbrains.hexana.shared.BinaryAnchor
import org.jetbrains.hexana.shared.BinaryBreakpoint
import org.jetbrains.hexana.shared.BinaryIdentity
import org.jetbrains.hexana.shared.BreakpointMaterializer
import org.jetbrains.hexana.shared.MaterializationResult
import org.jetbrains.hexana.shared.MaterializationState

/** Per-breakpoint state within the current debug session. */
data class SessionBreakpointState(val state: MaterializationState, val detail: String?)

/** The binary identity and anchor of the instruction the debugger is currently paused at. */
data class BinaryExecutionPosition(val identity: BinaryIdentity, val anchor: BinaryAnchor)

/** Describes one active debug session. */
class MaterializationSession(
    val sessionId: String,
    val identity: BinaryIdentity,
    val launchedPath: String?,
    val materializer: BreakpointMaterializer,
    val rehomer: AnchorRehomer?,
    /** Milliseconds to wait for adapters to respond before declaring FAILED. Overridable in tests. */
    val deadlineMs: Long = 10_000L,
)

/**
 * Coordinates binary breakpoint lifecycle across debug sessions.
 *
 * Exactly one session may be active at a time. On [sessionStarted]:
 *   1. Exact-identity breakpoints are selected from the registry.
 *   2. Path-but-different-identity breakpoints are rehomed or marked stale.
 *   3. Every enabled non-stale breakpoint is materialized via the session's materializer.
 *   4. After [MaterializationSession.deadlineMs], any still-PENDING bp becomes FAILED.
 *   5. One notification summarizes the outcome.
 *
 * On [sessionEnded], all bps are dematerialized (best-effort) and state is cleared.
 */
@Service(Service.Level.PROJECT)
final class BreakpointMaterializationCoordinator(private val project: Project) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _states = MutableStateFlow<Map<String, SessionBreakpointState>>(emptyMap())
    val states: StateFlow<Map<String, SessionBreakpointState>> = _states.asStateFlow()

    private val _executionPosition = MutableStateFlow<BinaryExecutionPosition?>(null)
    val executionPosition: StateFlow<BinaryExecutionPosition?> = _executionPosition.asStateFlow()

    private var activeSession: MaterializationSession? = null
    private var sessionJob: Job? = null
    private var registryObserverJob: Job? = null

    fun sessionStarted(session: MaterializationSession): CompletableDeferred<Unit> {
        sessionJob?.cancel()
        registryObserverJob?.cancel()
        val initialPushDone = CompletableDeferred<Unit>()
        sessionJob = scope.launch { runSession(session, initialPushDone) }
        return initialPushDone
    }

    /**
     * Starts the session like [sessionStarted] and blocks until the initial materialization
     * pass has pushed every eligible breakpoint to the adapter (or [timeoutMs] elapses).
     *
     * Debug processes attached to a frozen target MUST use this variant and only resume the
     * target afterwards; otherwise fast firmware (hello_world reaches idle in well under a
     * millisecond of emulated time) runs past every breakpoint before it is inserted.
     * Call from a pooled thread, never the EDT.
     */
    fun sessionStartedAndAwaitInitialPush(session: MaterializationSession, timeoutMs: Long = 10_000) {
        val done = sessionStarted(session)
        runBlocking { withTimeoutOrNull(timeoutMs) { done.await() } }
    }

    fun sessionEnded(sessionId: String) {
        scope.launch {
            mutex.withLock {
                val session = activeSession?.takeIf { it.sessionId == sessionId } ?: return@launch
                activeSession = null
                sessionJob?.cancel()
                registryObserverJob?.cancel()
                val bpsToKill = _states.value.keys.toList()
                _states.value = emptyMap()
                _executionPosition.value = null
                val registry = BinaryBreakpointRegistry.getInstance(project)
                for (id in bpsToKill) {
                    val bp = registry.breakpoints.value.firstOrNull { it.id == id } ?: continue
                    try {
                        session.materializer.dematerialize(bp)
                    } catch (e: Exception) {
                        HexanaLog.LOG.warn("BreakpointMaterializationCoordinator: dematerialize failed for ${bp.id}", e)
                    }
                }
            }
        }
    }

    /** Called by the debug adapter when execution stops at a binary instruction. */
    fun breakpointHit(position: BinaryExecutionPosition) {
        _executionPosition.value = position
    }

    /** Called when the debugger resumes execution. */
    fun executionResumed() {
        _executionPosition.value = null
    }

    /** Materializes a breakpoint added while a session is already active. */
    fun breakpointAddedDuringSession(bp: BinaryBreakpoint) {
        scope.launch {
            val session = mutex.withLock { activeSession } ?: return@launch
            if (!session.identity.matches(bp.identity)) return@launch
            if (!bp.enabled || bp.stale) return@launch
            materializeOne(bp, session.materializer)
        }
    }

    /** Dematerializes a breakpoint removed while a session is already active. */
    fun breakpointRemovedDuringSession(bp: BinaryBreakpoint) {
        scope.launch {
            val session = mutex.withLock { activeSession } ?: return@launch
            try {
                session.materializer.dematerialize(bp)
            } catch (e: Exception) {
                HexanaLog.LOG.warn("BreakpointMaterializationCoordinator: dematerialize on remove failed", e)
            }
            _states.value = _states.value - bp.id
        }
    }

    private suspend fun runSession(session: MaterializationSession, initialPushDone: CompletableDeferred<Unit>? = null) {
        mutex.withLock { activeSession = session }

        val registry = BinaryBreakpointRegistry.getInstance(project)

        // Rehome pass: path-with-different-identity breakpoints.
        if (session.launchedPath != null) {
            val toRehome = registry.forPathWithDifferentIdentity(session.launchedPath, session.identity)
            for (bp in toRehome) {
                val newAnchor = session.rehomer?.rehome(bp.anchor)
                if (newAnchor != null) {
                    registry.applyRehome(bp.id, session.identity, newAnchor)
                } else {
                    registry.markStale(bp.id)
                }
            }
        }

        // Collect exact-identity breakpoints (after rehoming may have added some).
        val exactBps = registry.forIdentity(session.identity).filter { !it.stale && it.enabled }

        // Set all to PENDING.
        _states.value = exactBps.associate { it.id to SessionBreakpointState(MaterializationState.PENDING, null) }

        // Materialize each. The deferred is completed even on failure so a frozen-target
        // debug process awaiting the initial push never hangs past its timeout needlessly.
        try {
            for (bp in exactBps) {
                materializeOne(bp, session.materializer)
            }
        } finally {
            initialPushDone?.complete(Unit)
        }

        // Deadline: any still-PENDING after deadlineMs becomes FAILED.
        delay(session.deadlineMs)
        val failed = _states.value.filter { (_, v) -> v.state == MaterializationState.PENDING }
        if (failed.isNotEmpty()) {
            _states.value = _states.value + failed.mapValues {
                SessionBreakpointState(MaterializationState.FAILED, "adapter did not resolve the breakpoint")
            }
        }

        // Notify.
        val finalStates = _states.value
        val verified = finalStates.count { it.value.state == MaterializationState.VERIFIED }
        val degraded = finalStates.count { it.value.state == MaterializationState.DEGRADED }
        val failedCount = finalStates.count { it.value.state == MaterializationState.FAILED }
        if (finalStates.isNotEmpty()) {
            val sb = StringBuilder("Binary breakpoints: $verified applied")
            if (degraded > 0) sb.append(", $degraded degraded")
            if (failedCount > 0) sb.append(", $failedCount failed")
            val type = if (failedCount > 0 || degraded > 0) NotificationType.WARNING else NotificationType.INFORMATION
            try {
                Notification(HexanaService.NOTIFICATION_GROUP, sb.toString(), type).notify(project)
            } catch (e: Exception) {
                HexanaLog.LOG.warn("BreakpointMaterializationCoordinator: notification failed", e)
            }
        }

        // Observe registry for adds/removes while session is live.
        registryObserverJob = registry.breakpoints
            .onEach { bpList ->
                val currentIds = _states.value.keys
                val sessionIds = bpList
                    .filter { session.identity.matches(it.identity) && it.enabled && !it.stale }
                    .map { it.id }
                    .toSet()
                // Newly added.
                for (bp in bpList) {
                    if (bp.id !in currentIds && bp.id in sessionIds) {
                        breakpointAddedDuringSession(bp)
                    }
                }
                // Removed.
                for (id in currentIds) {
                    if (id !in sessionIds) {
                        val bp = bpList.firstOrNull { it.id == id } ?: continue
                        breakpointRemovedDuringSession(bp)
                    }
                }
            }
            .launchIn(scope)
    }

    private suspend fun materializeOne(bp: BinaryBreakpoint, materializer: BreakpointMaterializer) {
        scope.launch {
            try {
                materializer.materialize(bp) { result ->
                    val newState = when (result) {
                        is MaterializationResult.Verified ->
                            SessionBreakpointState(MaterializationState.VERIFIED, null)
                        is MaterializationResult.Degraded ->
                            SessionBreakpointState(MaterializationState.DEGRADED, result.resolvedDescription)
                        is MaterializationResult.Failed ->
                            SessionBreakpointState(MaterializationState.FAILED, result.reason)
                    }
                    _states.value = _states.value + (bp.id to newState)
                }
            } catch (e: Exception) {
                _states.value = _states.value + (bp.id to
                    SessionBreakpointState(MaterializationState.FAILED, e.message ?: "exception during materialize"))
                HexanaLog.LOG.warn("BreakpointMaterializationCoordinator: materialize threw for ${bp.id}", e)
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
