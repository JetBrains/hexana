package org.jetbrains.hexana.breakpoints

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jetbrains.hexana.HexanaLog
import org.jetbrains.hexana.shared.BinaryBreakpoint

/**
 * Pure sync-logic for mirroring [BinaryBreakpoint] registry entries to/from
 * [XBreakpointManager] [XBreakpoint]s.
 *
 * Extracted from [XBreakpointMirror] so it can be tested without the platform.
 *
 * [onCreate] is called when a registry entry needs an XBreakpoint created.
 * [onRemove] is called when an XBreakpoint should be removed.
 * [onSetEnabled] is called when the enabled state of an existing XBreakpoint should change.
 */
class MirrorSyncLogic(
    private val onCreate: (registryId: String) -> Unit,
    private val onRemove: (registryId: String) -> Unit,
    private val onSetEnabled: (registryId: String, enabled: Boolean) -> Unit,
) {
    private var syncing = false
    private val mirroredIds = mutableSetOf<String>()

    /**
     * Called when the registry flow emits a new breakpoints list.
     *
     * Creates XBreakpoints for new registry entries and removes mirrors for
     * entries that no longer exist in the registry.
     */
    fun onRegistryUpdate(bps: List<BinaryBreakpoint>) {
        val currentIds = bps.map { it.id }.toSet()
        val toCreate = bps.filter { it.id !in mirroredIds }
        val toRemove = mirroredIds.filter { it !in currentIds }
        syncing = true
        try {
            for (bp in toCreate) {
                onCreate(bp.id)
                mirroredIds.add(bp.id)
            }
            for (id in toRemove) {
                onRemove(id)
                mirroredIds.remove(id)
            }
        } finally {
            syncing = false
        }
    }

    /**
     * Called when the platform fires [XBreakpointListener.breakpointChanged].
     *
     * Only propagates to the registry if NOT inside a [syncing] block
     * (avoiding feedback loops).
     */
    fun onXbpEnabledChanged(registryId: String, enabled: Boolean) {
        if (syncing) return
        onSetEnabled(registryId, enabled)
    }

    /**
     * Called when the platform fires [XBreakpointListener.breakpointRemoved].
     *
     * Only propagates to the registry if NOT inside a [syncing] block.
     */
    fun onXbpRemoved(registryId: String) {
        if (syncing) return
        mirroredIds.remove(registryId)
        onRemove(registryId)
    }

    /** Whether the given registry id is currently tracked as mirrored. */
    fun isMirrored(registryId: String): Boolean = registryId in mirroredIds

    /** Seed an existing set of registry IDs that are already mirrored (used on init). */
    fun seedMirrored(ids: Collection<String>) {
        mirroredIds.addAll(ids)
    }
}

/**
 * Project service that keeps the [BinaryBreakpointRegistry] and the platform
 * [XBreakpointManager] in sync.
 *
 * Registry flow changes drive XBreakpoint creation/removal (within a [syncing]
 * guard). Platform-side changes (user toggles enable/disable or removes a
 * breakpoint in the dialog) propagate back to the registry only when
 * [syncing] is false, preventing oscillation.
 *
 * Must be started eagerly; register as a [com.intellij.openapi.startup.ProjectActivity]
 * or via the `<projectService>` eager-init mechanism.
 */
@Service(Service.Level.PROJECT)
final class XBreakpointMirror(private val project: Project) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registry = BinaryBreakpointRegistry.getInstance(project)
    private val type = com.intellij.xdebugger.breakpoints.XBreakpointType
        .EXTENSION_POINT_NAME.extensionList
        .filterIsInstance<HexanaBinaryBreakpointType>()
        .firstOrNull()

    private val logic = MirrorSyncLogic(
        onCreate = { id -> createXbp(id) },
        onRemove = { id -> removeXbp(id) },
        onSetEnabled = { id, enabled -> registry.setEnabled(id, enabled) },
    )

    companion object {
        fun getInstance(project: Project): XBreakpointMirror = project.service()
    }

    init {
        if (type == null) {
            HexanaLog.LOG.warn("XBreakpointMirror: HexanaBinaryBreakpointType not found; mirroring disabled")
        } else {
            // Reconcile existing XBreakpoints on startup: seed logic with known mirrors.
            val xbpManager = XDebuggerManager.getInstance(project).breakpointManager
            val existing = xbpManager.getBreakpoints(type).mapNotNull { xbp ->
                xbp.properties?.registryId?.takeIf { it.isNotBlank() }
            }
            logic.seedMirrored(existing)
            // Remove orphaned XBreakpoints whose registry entry is gone.
            val knownIds = registry.breakpoints.value.map { it.id }.toSet()
            for (xbp in xbpManager.getBreakpoints(type)) {
                val id = xbp.properties?.registryId ?: continue
                if (id !in knownIds) {
                    ApplicationManager.getApplication().invokeLater {
                        com.intellij.openapi.application.runWriteAction {
                            xbpManager.removeBreakpoint(xbp)
                        }
                    }
                }
            }

            // Observe registry and sync to platform.
            registry.breakpoints
                .onEach { bps ->
                    ApplicationManager.getApplication().invokeLater {
                        logic.onRegistryUpdate(bps)
                    }
                }
                .launchIn(scope)

            // Observe platform breakpoint changes.
            project.messageBus.connect().subscribe(
                XBreakpointListener.TOPIC,
                object : XBreakpointListener<XBreakpoint<*>> {
                    override fun breakpointChanged(xbp: XBreakpoint<*>) {
                        val props = (xbp.properties as? HexanaBinaryBreakpointProperties) ?: return
                        val id = props.registryId.takeIf { it.isNotBlank() } ?: return
                        logic.onXbpEnabledChanged(id, xbp.isEnabled)
                    }

                    override fun breakpointRemoved(xbp: XBreakpoint<*>) {
                        val props = (xbp.properties as? HexanaBinaryBreakpointProperties) ?: return
                        val id = props.registryId.takeIf { it.isNotBlank() } ?: return
                        logic.onXbpRemoved(id)
                        // Also remove from the registry.
                        registry.remove(id)
                    }
                },
            )
        }
    }

    private fun createXbp(registryId: String) {
        val t = type ?: return
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.application.runWriteAction {
                try {
                    val xbpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val props = HexanaBinaryBreakpointProperties().also { it.registryId = registryId }
                    xbpManager.addBreakpoint(t, props)
                } catch (e: Exception) {
                    HexanaLog.LOG.warn("XBreakpointMirror: failed to create XBreakpoint for $registryId", e)
                }
            }
        }
    }

    private fun removeXbp(registryId: String) {
        val t = type ?: return
        ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.application.runWriteAction {
                try {
                    val xbpManager = XDebuggerManager.getInstance(project).breakpointManager
                    val xbp = xbpManager.getBreakpoints(t)
                        .firstOrNull { it.properties?.registryId == registryId } ?: return@runWriteAction
                    xbpManager.removeBreakpoint(xbp)
                } catch (e: Exception) {
                    HexanaLog.LOG.warn("XBreakpointMirror: failed to remove XBreakpoint for $registryId", e)
                }
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }
}
