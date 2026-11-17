package org.jetbrains.hexana.feedback

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

internal object HexanaFeedbackOpener {
    private val FILE_KEY: Key<HexanaFeedbackVirtualFile> = Key.create("hexana.feedback.virtual.file")

    fun open(project: Project) {
        if (!isInIdeFeedbackEnabled()) return

        val file = project.getUserData(FILE_KEY) ?: HexanaFeedbackVirtualFile().also {
            project.putUserData(FILE_KEY, it)
        }
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    fun close(project: Project, file: HexanaFeedbackVirtualFile) {
        FileEditorManager.getInstance(project).closeFile(file)
    }
}
