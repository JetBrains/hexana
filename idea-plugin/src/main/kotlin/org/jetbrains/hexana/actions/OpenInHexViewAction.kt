package org.jetbrains.hexana.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isFile
import com.intellij.util.FileContentUtilCore
import org.jetbrains.hexana.BinaryFileType
import java.util.concurrent.ConcurrentHashMap

class OpenInHexViewAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val overrider = HexFileTypeOverrider.getInstance()
        e.presentation.isEnabledAndVisible = project != null && file?.isFile == true && overrider != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val overrider = HexFileTypeOverrider.getInstance()
        overrider?.runWithHexFileType(file) {
            FileEditorManager.getInstance(project).closeFile(file)
            OpenFileDescriptor(project, file).navigateInEditor(project, true)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }
}

class HexFileTypeOverrider : FileTypeOverrider {

    companion object {
        fun getInstance(): HexFileTypeOverrider? {
            return FileTypeOverrider.EP_NAME.extensionList.find { it is HexFileTypeOverrider } as? HexFileTypeOverrider
        }
    }

    val acceptedFiles = ConcurrentHashMap<VirtualFile, FileType>()

    fun runWithHexFileType(file: VirtualFile, block: () -> Unit) {
        acceptedFiles[file] = BinaryFileType.INSTANCE
        try {
            FileContentUtilCore.reparseFiles(file)
            block()
        }
        finally {
            acceptedFiles.remove(file)
            FileContentUtilCore.reparseFiles(file)
        }
    }


    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        return acceptedFiles[file]
    }
}
