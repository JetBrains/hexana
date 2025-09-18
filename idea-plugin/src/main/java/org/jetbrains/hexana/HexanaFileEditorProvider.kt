package org.jetbrains.hexana

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.NonNls

class HexanaFileEditorProvider : FileEditorProvider, DumbAware {
    companion object {
        const val EDITOR_TYPE_ID: String = "hexana-editor"
    }

    override fun accept(
        project: Project,
        file: VirtualFile
    ): Boolean {
        return file.extension == "wasm"
    }

    override fun createEditor(
        project: Project,
        file: VirtualFile
    ): FileEditor {
        return HexanaFileEditor(project, file)
    }

    override fun getEditorTypeId(): @NonNls String {
        return EDITOR_TYPE_ID
    }

    override fun getPolicy(): FileEditorPolicy {
        return FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
    }
}
