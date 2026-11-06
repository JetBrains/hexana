package org.jetbrains.hexana.ui

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.providers.MAX_BINARY_WINDOW_BYTES
import org.jetbrains.hexana.shared.partialViewNotice
import java.util.function.Function
import javax.swing.JComponent

/**
 * Says so above the Hexana editor when a file is larger than it can load and only the head is
 * shown (see `HexanaFileEditor.partialFileSize`). Decided from the file's length rather than the
 * editor's state, which is filled in after notifications are first collected; the two agree
 * because the editor loads exactly [MAX_BINARY_WINDOW_BYTES] of such a file.
 */
class PartialViewNotification : EditorNotificationProvider, DumbAware {

    override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
        if (!file.isInLocalFileSystem || file.length <= MAX_BINARY_WINDOW_BYTES) return null
        val fileSize = file.length
        return Function { editor ->
            if (editor !is HexanaFileEditor) return@Function null
            EditorNotificationPanel(editor, EditorNotificationPanel.Status.Warning).apply {
                text = partialViewNotice(MAX_BINARY_WINDOW_BYTES, fileSize)
            }
        }
    }
}
