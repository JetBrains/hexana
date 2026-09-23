package org.jetbrains.hexana.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.HexanaDiffVirtualFile
import org.jetbrains.hexana.shared.diff.BinaryDiffInput
import org.jetbrains.hexana.toCommonByteBuffer

/**
 * `Compare Binary With…` — pick a second native binary file and open a diff
 * against the selected one, displaying per-section size differences.
 *
 * Supports ELF, Mach-O, PE, and other native formats that provide a
 * [org.jetbrains.hexana.providers.BinaryProvider.sizeAnalysis].
 *
 * Routes through the existing [org.jetbrains.hexana.HexanaFileEditorProvider].
 */
class CompareBinaryAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val twoSelected = files != null && files.size == 2 && files.all { isNativeBinary(it) }
        e.presentation.isEnabledAndVisible = if (e.isFromContextMenu) {
            project != null && (twoSelected || file != null && isNativeBinary(file))
        } else {
            project != null
        }
        e.presentation.text = if (twoSelected) "Compare Binary Files" else "Compare Binary With\u2026"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val twoSelected = selectedFiles != null && selectedFiles.size == 2 && selectedFiles.all { isNativeBinary(it) }

        val left: VirtualFile
        val right: VirtualFile

        if (twoSelected) {
            left = selectedFiles!![0]
            right = selectedFiles[1]
        } else {
            left = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { isNativeBinary(it) }
                ?: FileChooser.chooseFile(
                    binaryChooser("Compare Binary — Select First File", "Select the first binary file to compare"),
                    project,
                    null,
                ) ?: return

            right = FileChooser.chooseFile(
                binaryChooser("Compare With Binary File", "Select the second binary file to compare against ${left.name}"),
                project,
                left.parent,
            ) ?: return
        }

        val leftBuffer = left.toCommonByteBuffer()
        val rightBuffer = right.toCommonByteBuffer()
        if (leftBuffer == null || rightBuffer == null) {
            Messages.showWarningDialog(project, "Could not read one of the selected files.", "Compare Binary")
            return
        }

        val input = BinaryDiffInput(leftBuffer, rightBuffer, left.name, right.name)
        val diffFile = HexanaDiffVirtualFile(input.title, input)
        FileEditorManager.getInstance(project).openFile(diffFile, true)
    }

    private fun isNativeBinary(file: VirtualFile): Boolean =
        !file.isDirectory && NATIVE_EXTENSIONS.any { file.extension.equals(it, ignoreCase = true) }

    private fun binaryChooser(title: String, description: String) =
        FileChooserDescriptorFactory.singleFile()
            .withTitle(title)
            .withDescription(description)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        private val NATIVE_EXTENSIONS = setOf(
            "o", "obj", "so", "dylib", "dll", "exe", "elf", "a", "lib",
            "ko", "sys", "axf", "bin", "out",
        )
    }
}
