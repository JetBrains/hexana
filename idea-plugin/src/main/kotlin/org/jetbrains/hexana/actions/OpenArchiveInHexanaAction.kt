package org.jetbrains.hexana.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile

/** Extensions Hexana's editor knows how to render structurally. */
internal val HEXANA_ARCHIVE_EXTENSIONS: Set<String> = setOf("jar", "zip", "war", "apk")

/**
 * `Open in... | Hexana` — opens the selected archive in Hexana's editor (hex
 * view on top + class list on the bottom; clicking a class opens it in the
 * structured class-file viewer). Lives next to the regular project-view
 * "Open" so users don't have to learn a separate path.
 */
class OpenArchiveInHexanaAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && file != null && isHexanaArchive(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val manager = FileEditorManager.getInstance(project)
        // Drop any cached editor (e.g. the default archive viewer) so our
        // FileEditorProvider gets first crack via PLACE_BEFORE_DEFAULT_EDITOR.
        manager.closeFile(file)
        manager.openFile(file, true)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Popup group that anchors a `Open in...` submenu in the project-view popup.
 * Visible only when the selected file is a Hexana-known archive — otherwise
 * the submenu would clutter the menu for every file.
 */
class HexanaOpenInGroup : DefaultActionGroup() {
    init {
        isPopup = true
        templatePresentation.text = "Open in..."
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && file != null && isHexanaArchive(file)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

internal fun isHexanaArchive(file: VirtualFile): Boolean {
    if (file.isDirectory) return false
    val ext = file.extension?.lowercase() ?: return false
    return ext in HEXANA_ARCHIVE_EXTENSIONS
}
