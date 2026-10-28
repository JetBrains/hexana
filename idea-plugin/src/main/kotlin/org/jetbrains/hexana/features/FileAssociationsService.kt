package org.jetbrains.hexana.features

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.hexana.providers.BinaryParser
import org.jetbrains.hexana.shared.features.FILE_ASSOCIATIONS_PATH
import org.jetbrains.hexana.shared.features.FileAssociations
import org.jetbrains.hexana.shared.features.fileAssociationKey

/**
 * The parsers chosen by hand for individual files, declared in
 * `.hexana/file_associations.json`.
 *
 * Backed by a project file rather than `@State` for the reason [RuntimeTargetsService]
 * gives: the VS Code extension reads and writes the same file. Watched the same way, so an
 * edit by hand or a checkout reaches open editors without a restart; [changes] is the
 * signal they follow.
 */
@Service(Service.Level.PROJECT)
class FileAssociationsService(private val project: Project) : Disposable {

    private val tracker = FileAssociationsTracker()

    /** Increments whenever the declared associations change, from this IDE or outside it. */
    val changes: StateFlow<Int> get() = tracker.changes

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::touchesSettingsFile)) reload()
                }
            },
        )
    }

    /** The declared associations, empty when the project has no settings file. */
    val associations: FileAssociations
        get() {
            val basePath = project.basePath ?: return FileAssociations.EMPTY
            return tracker.get { FileAssociationsStore.read(basePath) }
        }

    /**
     * The key [file] is stored under, or `null` when it cannot have an association: it is
     * not a file on the local disk (an archive entry, a nested module, an in-memory view),
     * or it lies outside the project directory.
     */
    fun keyFor(file: VirtualFile): String? {
        val basePath = project.basePath ?: return null
        if (!file.isInLocalFileSystem || file.isDirectory) return null
        return fileAssociationKey(basePath, file.path)
    }

    /** The parser chosen for [file], or `null` when it is detected automatically. */
    fun parserFor(file: VirtualFile): BinaryParser? = associations.parserFor(keyFor(file))

    /** Chooses [parser] for [file], or returns it to automatic detection when `null`. */
    fun setParser(file: VirtualFile, parser: BinaryParser?) {
        val key = keyFor(file) ?: return
        save(associations.with(key, parser))
    }

    /** Persists [associations] (and the schema) and refreshes VFS so the files show up. */
    fun save(associations: FileAssociations) {
        val basePath = project.basePath ?: return
        val file = FileAssociationsStore.write(basePath, associations)
        tracker.recordSave(associations)
        val vfs = LocalFileSystem.getInstance()
        vfs.refreshAndFindFileByIoFile(FileAssociationsStore.schemaFile(basePath))
        vfs.refreshAndFindFileByIoFile(file)
    }

    private fun touchesSettingsFile(event: VFileEvent): Boolean {
        val settingsPath = project.basePath?.let { "$it/$FILE_ASSOCIATIONS_PATH" } ?: return false
        if (runtimeTargetsEventMatches(settingsPath, event.path)) return true
        val renamed = (event as? VFilePropertyChangeEvent)?.newPath as? String
        return renamed != null && runtimeTargetsEventMatches(settingsPath, renamed)
    }

    private fun reload() {
        val basePath = project.basePath ?: return
        tracker.observe(FileAssociationsStore.read(basePath))
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): FileAssociationsService = project.service()
    }
}
