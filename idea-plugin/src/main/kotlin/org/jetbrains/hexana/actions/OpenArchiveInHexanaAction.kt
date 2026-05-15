package org.jetbrains.hexana.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.VirtualFile

/** Extensions Hexana's editor knows how to render structurally. */
internal val HEXANA_ARCHIVE_EXTENSIONS: Set<String> = setOf("jar", "zip", "war", "apk")

/**
 * `Open in... | Hexana` — opens the selected archive in Hexana's editor (hex
 * view on top + class list on the bottom; clicking a class opens it in the
 * structured class-file viewer). Lives next to the regular project-view
 * "Open" so users don't have to learn a separate path.
 *
 * Works on three kinds of project-view entries:
 *   1. Plain `.jar` / `.zip` / `.war` / `.apk` files in the project tree.
 *   2. Library entries under "External Libraries" — those appear as
 *      `JarFileSystem` roots (jar://.../foo.jar!/) and are resolved back
 *      to the local `.jar` via [JarFileSystem.getLocalByEntry].
 *   3. Any sub-entry inside a jar (a class, a directory) — we walk up to
 *      the jar's local file. Opening any node of an archive in Hexana is
 *      then equivalent to opening the archive itself.
 */
class OpenArchiveInHexanaAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            project != null && file != null && resolveHexanaArchive(file) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val archive = resolveHexanaArchive(file) ?: return
        val manager = FileEditorManager.getInstance(project)
        // Drop any cached editor (e.g. the default archive viewer) so our
        // FileEditorProvider gets first crack via PLACE_BEFORE_DEFAULT_EDITOR.
        manager.closeFile(archive)
        manager.openFile(archive, true)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Popup group that anchors a `Open in...` submenu in the project-view popup.
 * Visible only when the selected file is a Hexana-known archive (or any node
 * inside one) — otherwise the submenu would clutter the menu for every file.
 */
class HexanaOpenInGroup : DefaultActionGroup() {
    init {
        isPopup = true
        templatePresentation.text = "Open in..."
    }

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && file != null && resolveHexanaArchive(file) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Resolves [selected] (whatever the user clicked) to the local `.jar` /
 * `.zip` / `.war` / `.apk` file Hexana's editor can parse, or returns `null`
 * if there's no archive to open.
 *
 *   - Plain archive in [com.intellij.openapi.vfs.LocalFileSystem] → returned as-is.
 *   - Anything inside a [JarFileSystem] (jar root or a nested entry) →
 *     resolved to the local archive via [JarFileSystem.getLocalByEntry].
 *   - Any other file → `null`.
 */
internal fun resolveHexanaArchive(selected: VirtualFile): VirtualFile? {
    // Inside a jar — walk up to the local .jar regardless of where the click landed.
    if (selected.fileSystem is JarFileSystem) {
        val local = JarFileSystem.getInstance().getLocalByEntry(selected) ?: return null
        return if (hasArchiveExtension(local)) local else null
    }
    // Plain archive file on disk.
    if (!selected.isDirectory && hasArchiveExtension(selected)) return selected
    return null
}

private fun hasArchiveExtension(file: VirtualFile): Boolean {
    val ext = file.extension?.lowercase() ?: return false
    return ext in HEXANA_ARCHIVE_EXTENSIONS
}
