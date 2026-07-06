package org.jetbrains.hexana.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import org.jetbrains.hexana.HexanaProtobufMessageVirtualFile
import org.jetbrains.hexana.toCommonByteBuffer

/**
 * `Open As Protobuf Message` — decode the selected file's bytes as a raw, schema-less
 * Protobuf wire-encoded message and open the result in a Hexana table.
 *
 * Works on any single real file (the decoder is fault-tolerant), so it is not restricted
 * by extension. The bytes are wrapped in a [HexanaProtobufMessageVirtualFile], which forces
 * the [org.jetbrains.hexana.ProtobufMessageFileType] editor path.
 */
class OpenAsProtobufMessageAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = project != null && file != null && !file.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { !it.isDirectory } ?: return

        val buffer = file.toCommonByteBuffer()
        if (buffer == null) {
            Messages.showWarningDialog(project, "Could not read the selected file.", "Open As Protobuf Message")
            return
        }

        val synthetic = HexanaProtobufMessageVirtualFile("${file.name} (protobuf)", buffer)
        FileEditorManager.getInstance(project).openFile(synthetic, true)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
