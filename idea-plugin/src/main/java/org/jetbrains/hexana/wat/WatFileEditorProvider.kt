package org.jetbrains.hexana.wat

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.NonNls


class WatFileEditorProvider : FileEditorProvider, DumbAware {
    companion object {
        const val EDITOR_TYPE_ID: String = "wat-editor"
    }

    private val myDelegate: FileEditorProvider = PsiAwareTextEditorProvider()

    override fun accept(
        project: Project,
        file: VirtualFile
    ): Boolean {
        return file.extension == "wat" || file.fileType is WatFileType
    }

    override fun createEditor(
        project: Project,
        file: VirtualFile
    ): FileEditor {
        val fileEditor = myDelegate.createEditor(project, file)
        if (fileEditor is TextEditor) {
            fileEditor.editor.settings.isUseSoftWraps = false
        }
        return fileEditor
    }

    override fun getEditorTypeId(): @NonNls String {
        return EDITOR_TYPE_ID
    }

    override fun getPolicy(): FileEditorPolicy {
        return FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
    }
}
