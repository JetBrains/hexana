package org.jetbrains.hexana.features

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.hexana.shared.features.RUNTIME_TARGETS_PATH
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility

/**
 * The runtime targets declared for this project in `.hexana/runtimes.json`.
 *
 * Deliberately backed by a project *file* rather than a `PersistentStateComponent`: `@State`
 * storage lands in `.idea/`, which the VS Code extension cannot read, and the point of this
 * file is that a declaration made in one editor is honoured by the other.
 *
 * A file that other tools write has to be watched, not just cached: an edit by hand, a
 * `git pull`, or a teammate's commit must take effect without restarting the IDE. [changes]
 * is the signal the UI recomposes on.
 */
@Service(Service.Level.PROJECT)
class RuntimeTargetsService(private val project: Project) : Disposable {

    private val tracker = RuntimeTargetsTracker()

    /**
     * Increments whenever the declared targets change, whether from this IDE or from outside
     * it. Read by the proposals badge and the Compatibility tab so both follow the file.
     *
     * Only *content* changes count — see [RuntimeTargetsTracker].
     */
    val changes: StateFlow<Int> get() = tracker.changes

    init {
        // Parented on the service, not left to the bus: the service is disposed when the
        // project closes *and* when the plugin is unloaded, so the listener never outlives
        // the classes it belongs to.
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::touchesSettingsFile)) reload()
                }
            },
        )
    }

    /** The declared targets, empty when the project has no settings file. */
    val targets: RuntimeTargets
        get() {
            val basePath = project.basePath ?: return RuntimeTargets.EMPTY
            return tracker.get { RuntimeTargetsStore.read(basePath) }
        }

    /**
     * The catalog paired with this project's targets, ready for the badge and the
     * Compatibility tab. `null` when the project has no directory to hold the settings file
     * (a light or default project), in which case the feature stays hidden.
     */
    fun compatibility(): WasmRuntimeCompatibility? {
        project.basePath ?: return null
        return WasmRuntimeCompatibility(WasmFeatureCatalogService.getInstance().catalog, targets)
    }

    /** Persists [targets] and refreshes VFS so the new file is visible in the project tree. */
    fun save(targets: RuntimeTargets) {
        val basePath = project.basePath ?: return
        val file = RuntimeTargetsStore.write(basePath, targets)
        tracker.recordSave(targets)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }

    /** Project-relative settings file as an absolute VFS-style path, or `null` without a project dir. */
    private fun settingsFilePath(): String? = project.basePath?.let { "$it/$RUNTIME_TARGETS_PATH" }

    /** Whether [event] could have changed the settings file. */
    private fun touchesSettingsFile(event: VFileEvent): Boolean {
        val settingsPath = settingsFilePath() ?: return false
        if (runtimeTargetsEventMatches(settingsPath, event.path)) return true
        // A rename reports the old path in `path`; the new one is a separate property.
        val renamed = (event as? VFilePropertyChangeEvent)?.newPath as? String
        return renamed != null && runtimeTargetsEventMatches(settingsPath, renamed)
    }

    private fun reload() {
        val basePath = project.basePath ?: return
        tracker.observe(RuntimeTargetsStore.read(basePath))
    }

    /** Nothing to release beyond the message-bus connection, which disposal handles. */
    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): RuntimeTargetsService = project.service()
    }
}
