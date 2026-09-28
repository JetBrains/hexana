package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jetbrains.hexana.shared.scripting.CheckFinding
import java.util.concurrent.ConcurrentHashMap

/**
 * Project-scoped store of script check findings per binary file.
 *
 * Findings are stored per [VirtualFile]; each new run replaces the previous
 * entries for that file entirely (no accumulation across runs). Safe for
 * concurrent writes from pooled threads and reads from the EDT or composition.
 *
 * Registered as a light service via [@Service]. No plugin.xml entry needed on
 * IntelliJ Platform 252+ (the project's minimum build).
 */
@Service(Service.Level.PROJECT)
class ScriptFindingsStore {

    /**
     * Entry stored for one binary file: the script that produced the findings
     * and the findings themselves.
     */
    data class Entry(
        val scriptName: String,
        val findings: List<CheckFinding>,
    )

    private val store = ConcurrentHashMap<VirtualFile, Entry>()

    private val _changes = MutableStateFlow(0L)

    /**
     * Bumped after every write. The Problems tab and the toolbar problems badge read the
     * store when they are built, so this is what makes a finished script run show up
     * without reopening the editor.
     */
    val changes: StateFlow<Long> = _changes.asStateFlow()

    /**
     * Replaces all findings for [file] with [findings] attributed to [scriptName].
     *
     * Called from pooled threads after a script run completes.
     */
    fun replaceFindings(file: VirtualFile, scriptName: String, findings: List<CheckFinding>) {
        if (findings.isEmpty()) {
            store.remove(file)
        } else {
            store[file] = Entry(scriptName = scriptName, findings = findings)
        }
        _changes.update { it + 1 }
    }

    /**
     * Returns the current entry for [file], or null when none exists.
     *
     * Safe to call from any thread.
     */
    fun getEntry(file: VirtualFile): Entry? = store[file]

    /** Returns findings for [file], or an empty list when none exist. */
    fun findingsFor(file: VirtualFile): List<CheckFinding> = store[file]?.findings ?: emptyList()

    /**
     * Findings for the file at [path], matched on [VirtualFile.getPath]; for callers that
     * know the editor's path but not its [VirtualFile]. Empty for a `null` path.
     */
    fun findingsFor(path: String?): List<CheckFinding> {
        if (path.isNullOrEmpty()) return emptyList()
        return store.entries.firstOrNull { it.key.path == path }?.value?.findings ?: emptyList()
    }

    /** Removes all stored findings (e.g. on project close). */
    fun clearAll() {
        store.clear()
        _changes.update { it + 1 }
    }

    companion object {
        fun getInstance(project: Project): ScriptFindingsStore = project.service()
    }
}
