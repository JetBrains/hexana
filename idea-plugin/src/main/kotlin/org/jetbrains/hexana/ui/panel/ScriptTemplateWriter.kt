package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.shared.scripting.PROVIDER_SCRIPTS_DIR
import org.jetbrains.hexana.shared.scripting.ScriptApiReference
import org.jetbrains.hexana.shared.scripting.USER_CONTRIBUTION_PREFIX
import org.jetbrains.hexana.shared.scripting.providerScript
import org.jetbrains.hexana.shared.scripting.providerScriptSlug
import java.io.File

/**
 * Creates and opens script files, and keeps the type definitions next to them so
 * that an editor resolves the scripting globals.
 *
 * Starting from a blank `.js` file is the single biggest obstacle to writing a
 * script tab: the user has to know the API before they can type anything. Writing
 * a runnable file first turns that around — the script already produces output,
 * and editing it is a matter of changing lines that work — and the sidecar types
 * mean `binary.` completes while they do it.
 */
internal object ScriptTemplateWriter {

    private val log = logger<ScriptTemplateWriter>()

    /** Project-relative directory the created scripts live in. */
    private const val SCRIPTS_DIRECTORY = ".hexana/scripts"

    /** Plugin-resource directory the bundled examples are packaged into. */
    private const val BUNDLED_EXAMPLES_RESOURCE_DIR = "hexana-scripts"

    /**
     * The directory created scripts live in, whether or not it exists yet.
     *
     * Exposed so the file chooser can start where the scripts already are; the
     * location is defined once, here.
     */
    fun scriptsDirectory(project: Project): File? =
        project.basePath?.let { basePath -> File(basePath, SCRIPTS_DIRECTORY) }

    /** The scripts directory as a virtual file, when it exists on disk. */
    fun existingScriptsDirectory(project: Project): VirtualFile? =
        scriptsDirectory(project)
            ?.takeIf { it.isDirectory }
            ?.let { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }

    /** Writes [ScriptApiReference.starterTemplate] and opens it. */
    fun createFromTemplate(project: Project): VirtualFile? =
        create(project, ScriptApiReference.STARTER_FILE_NAME, ScriptApiReference.starterTemplate())

    /** Copies a bundled example out of plugin resources and opens it. */
    fun createFromExample(project: Project, exampleFileName: String): VirtualFile? {
        val content = readBundledExample(exampleFileName)
        if (content == null) {
            log.warn("Bundled script example '$exampleFileName' is missing from plugin resources")
            return null
        }
        return create(project, exampleFileName, content)
    }

    /** A provider script [createProviderScript] wrote, and the provider id it registers. */
    class CreatedProviderScript(val file: VirtualFile, val providerId: String)

    /**
     * Writes a new provider script named [name] into `.hexana/providers/`, with the type
     * definitions beside it, and opens it. The id is derived from the file name actually used,
     * so a second "My format" becomes `my-format-2.js` with `user:my-format-2`: one script per
     * id, and no clash with the first. Returns `null` when nothing could be written.
     *
     * Writes files and refreshes VFS synchronously, so never on the EDT.
     */
    fun createProviderScript(project: Project, name: String): CreatedProviderScript? {
        val basePath = project.basePath ?: return null
        val directory = File(basePath, PROVIDER_SCRIPTS_DIR)
        if (!directory.isDirectory && !directory.mkdirs()) {
            log.warn("Cannot create the provider scripts directory: $directory")
            return null
        }
        val target = uniqueFile(directory, "${providerScriptSlug(name)}.js")
        val providerId = USER_CONTRIBUTION_PREFIX + target.name.removeSuffix(".js")
        try {
            target.writeText(providerScript(providerId, name))
        } catch (error: Exception) {
            log.warn("Cannot write the provider script: $target", error)
            return null
        }
        attachTypeDefinitions(directory)
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target) ?: return null
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
        return CreatedProviderScript(virtualFile, providerId)
    }

    /**
     * Opens an existing script for editing, attaching the type definitions first.
     *
     * Called for any script a user has attached as a tab, wherever it lives, so
     * that opening it from Hexana always lands in an editor that resolves
     * `binary`, `print`, `table` and `row`.
     */
    fun openForEditing(project: Project, scriptPath: String) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(scriptPath)
        if (file == null) {
            log.warn("Cannot open script for editing, file not found: $scriptPath")
            return
        }
        attachTypeDefinitions(File(scriptPath).parentFile)
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun readBundledExample(fileName: String): String? =
        ScriptTemplateWriter::class.java
            .getResourceAsStream("/$BUNDLED_EXAMPLES_RESOURCE_DIR/$fileName")
            ?.bufferedReader()
            ?.readText()

    private fun create(project: Project, fileName: String, content: String): VirtualFile? {
        val directory = scriptsDirectory(project)
        if (directory == null) {
            log.warn("Cannot create a script: the project has no base directory")
            return null
        }
        if (!directory.isDirectory && !directory.mkdirs()) {
            log.warn("Cannot create the script directory: $directory")
            return null
        }

        // Never clobber a script the user has already edited.
        val target = uniqueFile(directory, fileName)
        try {
            target.writeText(content)
        } catch (error: Exception) {
            log.warn("Cannot write the script file: $target", error)
            return null
        }
        attachTypeDefinitions(directory)

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
        if (virtualFile == null) {
            log.warn("Wrote $target but the virtual file system did not pick it up")
            return null
        }
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
        return virtualFile
    }

    /**
     * Writes the type definitions into [directory] and makes the IDE see them.
     *
     * The refresh matters as much as the write. These files are created with
     * `java.io`, behind the VFS's back; refreshing the directory *entry* is not
     * enough, because an already-known directory keeps its cached child list, so
     * the new files stay invisible in the Project view and unindexed — which means
     * nothing resolves from them. [VfsUtil.markDirtyAndRefresh] with `recursive`
     * invalidates the children too, which is what actually surfaces them.
     */
    private fun attachTypeDefinitions(directory: File?) {
        if (directory == null) return
        val written = try {
            ScriptSidecars.write(directory)
        } catch (error: Exception) {
            log.warn("Cannot write the scripting type definitions into $directory", error)
            return
        }
        if (written.isEmpty()) return

        val directoryFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(directory)
        if (directoryFile == null) {
            log.warn("Wrote ${written.size} sidecar file(s) but $directory is not visible to the IDE")
            return
        }
        VfsUtil.markDirtyAndRefresh(false, true, true, directoryFile)
    }

    private fun uniqueFile(directory: File, fileName: String): File {
        val base = fileName.substringBeforeLast('.')
        val extension = fileName.substringAfterLast('.', "js")
        var candidate = File(directory, fileName)
        var index = 2
        while (candidate.exists()) {
            candidate = File(directory, "$base-$index.$extension")
            index++
        }
        return candidate
    }
}
