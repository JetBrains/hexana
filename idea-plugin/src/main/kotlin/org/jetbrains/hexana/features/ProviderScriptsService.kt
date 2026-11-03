package org.jetbrains.hexana.features

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.jetbrains.hexana.mcp.scripting.HexanaScriptRunner
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSession
import org.jetbrains.hexana.mcp.scripting.HexanaScriptSource
import org.jetbrains.hexana.mcp.scripting.ProviderScript
import org.jetbrains.hexana.mcp.scripting.ProviderScriptCatalog
import org.jetbrains.hexana.providers.BinaryProvider
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.shared.scripting.PROVIDER_SCRIPTS_DIR
import java.io.File

/**
 * Loads the provider scripts in `.hexana/providers/` and keeps [catalog] current.
 *
 * Scripts there are not opened by the user one at a time, they run on their own whenever a
 * binary opens — so they only run in a trusted project, and stop running when a project is
 * not. They run in the same sandbox as script tabs (`mcp:scripting`: no host access, no IO,
 * a timeout per call).
 *
 * What a script registers is learnt by evaluating it once against an empty binary; that
 * metadata pass is cached by the script's text, so an edit to one script re-runs only it.
 * Evaluating against a real file happens per editor, in [openSession].
 */
@Service(Service.Level.PROJECT)
class ProviderScriptsService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {

    private val _catalog = MutableStateFlow(ProviderScriptCatalog.EMPTY)

    /** The scripts' registrations; replaced, never mutated, whenever a script changes. */
    val catalog: StateFlow<ProviderScriptCatalog> = _catalog.asStateFlow()

    /** Metadata passes by script path, reused while the text is unchanged. */
    private val metadataCache = mutableMapOf<String, ProviderScript>()

    private var reloadJob: Job? = null

    /** Bumped by every [reload]; only the newest reload may publish its catalog. */
    private var reloadGeneration = 0L

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::touchesScripts)) reload()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(
            TrustedProjectsListener.TOPIC,
            object : TrustedProjectsListener {
                override fun onProjectTrusted(project: Project) {
                    if (project == this@ProviderScriptsService.project) reload()
                }

                override fun onProjectUntrusted(project: Project) {
                    if (project == this@ProviderScriptsService.project) reload()
                }
            },
        )
        reload()
    }

    /** Re-reads the scripts in the background; a newer reload supersedes one in flight. */
    fun reload() {
        synchronized(this) {
            reloadJob?.cancel()
            val generation = ++reloadGeneration
            reloadJob = scope.launch(Dispatchers.IO) {
                val loaded = loadCatalog()
                ensureActive()
                publish(generation, loaded)
            }
        }
    }

    /**
     * Publishes [loaded] unless a newer reload started meanwhile. Cancellation is cooperative
     * and [loadCatalog] does blocking work, so a superseded reload can still finish; without
     * this guard it could overwrite a newer catalog — e.g. republish executable scripts after
     * the project became untrusted. Trust is rechecked here for the same reason.
     */
    private fun publish(generation: Long, loaded: ProviderScriptCatalog) {
        synchronized(this) {
            if (generation != reloadGeneration) return
            _catalog.value =
                if (loaded.scripts.isNotEmpty() && !TrustedProjects.isProjectTrusted(project)) {
                    ProviderScriptCatalog(blockedByTrust = true)
                } else {
                    loaded
                }
        }
    }

    /** The scripts directory, whether or not it exists. */
    fun scriptsDirectory(): File? = project.basePath?.let { File(it, PROVIDER_SCRIPTS_DIR) }

    /**
     * Evaluates [script] against [provider] and leaves the session open for `parse()` and tab
     * paging. The caller owns the session and must close it. Never on the EDT.
     */
    fun openSession(
        script: ProviderScript,
        provider: BinaryProvider,
        fileName: String?,
        filePath: String?,
    ): HexanaScriptSession =
        runner().openSession(
            source = HexanaScriptSource.Inline(script.text, File(script.path).name),
            binaryProvider = provider,
            fileName = fileName,
            filePath = filePath,
        )

    private fun loadCatalog(): ProviderScriptCatalog {
        val files = scriptsDirectory()?.listFiles { file -> file.isFile && file.name.endsWith(".js") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (files.isEmpty()) return ProviderScriptCatalog.EMPTY
        if (!TrustedProjects.isProjectTrusted(project)) return ProviderScriptCatalog(blockedByTrust = true)

        val seenIds = mutableSetOf<String>()
        val scripts = files.mapNotNull { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@mapNotNull null
            val evaluated = synchronized(metadataCache) {
                metadataCache[file.path]?.takeIf { it.text == text }
            } ?: evaluateMetadata(file, text).also { synchronized(metadataCache) { metadataCache[file.path] = it } }
            // An id belongs to the first script, in name order, that defines it; a later one
            // keeps its other registrations and says which of its providers were ignored.
            val (kept, duplicates) = evaluated.providers.partition { seenIds.add(it.id) }
            if (duplicates.isEmpty()) {
                evaluated
            } else {
                evaluated.copy(
                    providers = kept,
                    error = "Already defined by an earlier script: ${duplicates.joinToString { it.id }}",
                )
            }
        }
        synchronized(metadataCache) { metadataCache.keys.retainAll(files.map { it.path }.toSet()) }
        return ProviderScriptCatalog(scripts)
    }

    /** Runs [text] once against an empty binary to learn what it registers. */
    private fun evaluateMetadata(file: File, text: String): ProviderScript {
        val empty = GenericBinaryProvider(ByteArray(0).toCommonByteBuffer())
        return runner().openSession(HexanaScriptSource.Inline(text, file.name), empty).use { session ->
            val result = session.initialResult
            ProviderScript(
                path = file.path,
                text = text,
                providers = result.providers,
                tabs = result.slices.filter { it.isTab },
                error = if (result.success) null else result.diagnostics.joinToString("; ") { it.message },
            )
        }
    }

    private fun runner() = HexanaScriptRunner(limits = HexanaScriptLimits.interactive())

    /** Whether [event] could have added, changed or removed a provider script. */
    private fun touchesScripts(event: VFileEvent): Boolean {
        val directory = scriptsDirectory()?.path?.replace(File.separatorChar, '/') ?: return false
        fun matches(path: String) =
            path == directory || directory.startsWith("$path/") ||
                (path.startsWith("$directory/") && path.endsWith(".js"))
        if (matches(event.path)) return true
        val renamed = (event as? VFilePropertyChangeEvent)?.newPath as? String
        return renamed != null && matches(renamed)
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(project: Project): ProviderScriptsService = project.service()
    }
}
