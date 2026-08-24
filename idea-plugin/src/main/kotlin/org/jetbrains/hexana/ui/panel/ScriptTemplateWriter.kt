package org.jetbrains.hexana.ui.panel

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.shared.scripting.ScriptApiReference
import java.io.File

/**
 * Creates a new script file from the starter template or a bundled example, and
 * opens it in the editor.
 *
 * Starting from a blank `.js` file is the single biggest obstacle to writing a
 * script tab: the user has to know the API before they can type anything. Writing
 * a runnable file first turns that around — the script already produces output,
 * and editing it is a matter of changing lines that work.
 */
internal object ScriptTemplateWriter {

    private const val SCRIPTS_DIRECTORY = ".hexana/scripts"

    /** Writes [ScriptApiReference.starterTemplate] and opens it. */
    fun createFromTemplate(project: Project): VirtualFile? =
        create(project, ScriptApiReference.STARTER_FILE_NAME, ScriptApiReference.starterTemplate())

    /** Copies a bundled example out of plugin resources and opens it. */
    fun createFromExample(project: Project, exampleFileName: String): VirtualFile? {
        val content = readBundledExample(exampleFileName) ?: return null
        return create(project, exampleFileName, content)
    }

    private fun readBundledExample(fileName: String): String? =
        ScriptTemplateWriter::class.java.getResourceAsStream("/hexana-scripts/$fileName")
            ?.bufferedReader()
            ?.readText()

    private fun create(project: Project, fileName: String, content: String): VirtualFile? {
        val directory = File(project.basePath ?: return null, SCRIPTS_DIRECTORY)
        if (!directory.isDirectory && !directory.mkdirs()) return null

        // Never clobber a script the user has already edited.
        val target = uniqueFile(directory, fileName)
        target.writeText(content)

        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target) ?: return null
        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(virtualFile, true)
        }
        return virtualFile
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
