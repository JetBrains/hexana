package org.jetbrains.hexana.feedback

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.NonNls

internal class HexanaFeedbackFileEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is HexanaFeedbackVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return HexanaFeedbackFileEditor(project, file as HexanaFeedbackVirtualFile)
    }

    override fun getEditorTypeId(): @NonNls String = "hexana-feedback-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
