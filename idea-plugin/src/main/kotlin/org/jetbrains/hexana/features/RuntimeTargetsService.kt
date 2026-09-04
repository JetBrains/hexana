package org.jetbrains.hexana.features

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import org.jetbrains.hexana.shared.features.RUNTIME_TARGETS_FILE
import org.jetbrains.hexana.shared.features.RuntimeTargets
import org.jetbrains.hexana.shared.features.WasmRuntimeCompatibility

/**
 * The runtime targets declared for this project in `.hexana/runtimes.json`.
 *
 * Deliberately backed by a project *file* rather than a `PersistentStateComponent`: `@State`
 * storage lands in `.idea/`, which the VS Code extension cannot read, and the point of this
 * file is that a declaration made in one editor is honoured by the other.
 *
 * The cache is invalidated from a VFS listener so an edit by hand, or a `git pull` that
 * brings in a teammate's targets, takes effect without restarting the IDE.
 */
@Service(Service.Level.PROJECT)
class RuntimeTargetsService(private val project: Project) {

    @Volatile
    private var cached: RuntimeTargets? = null

    init {
        // Connected to the project's bus, so the subscription ends with the project.
        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { it.path.endsWith(RUNTIME_TARGETS_FILE) }) {
                        cached = null
                    }
                }
            },
        )
    }

    /** The declared targets, empty when the project has no settings file. */
    val targets: RuntimeTargets
        get() {
            cached?.let { return it }
            val basePath = project.basePath ?: return RuntimeTargets.EMPTY
            return RuntimeTargetsStore.read(basePath).also { cached = it }
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
        cached = targets
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
    }

    companion object {
        fun getInstance(project: Project): RuntimeTargetsService = project.service()
    }
}
