package org.jetbrains.hexana

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.VirtualFile

/**
 * Titles the editor tab of an object file extracted from a static-library archive as
 * `archive-name/member-name` (e.g. `libfoo.a/bar.o`) instead of the temp file's on-disk
 * name. The title is stashed on the extracted [VirtualFile] under [TITLE_KEY] by
 * [org.jetbrains.hexana.ui.panel.ArchiveMembersPanel] just before the file is opened.
 * Returns `null` for every other file so the platform's default title is used.
 */
class ArchiveMemberTabTitleProvider : EditorTabTitleProvider {

    override fun getEditorTabTitle(project: Project, file: VirtualFile): @NlsContexts.TabTitle String? =
        file.getUserData(TITLE_KEY)

    companion object {
        val TITLE_KEY: Key<String> = Key.create("hexana.archiveMemberTabTitle")
    }
}
