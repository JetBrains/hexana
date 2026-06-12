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
import org.jetbrains.hexana.HexanaWasmDiffVirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.toCommonByteBuffer

/**
 * `Compare WASM With…` — pick a second `.wasm` file and open a size-impact diff
 * against the selected one. Opens a [HexanaWasmDiffVirtualFile], which the
 * existing [org.jetbrains.hexana.HexanaFileEditorProvider] routes to the
 * size-diff panel.
 *
 * v1 supports core WASM modules only; component modules are rejected with a
 * message.
 */
class CompareWasmAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && file != null && !file.isDirectory &&
            file.extension.equals("wasm", ignoreCase = true)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val left = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle("Compare With WASM File")
            .withDescription("Select the second .wasm module to compare against ${left.name}")
            .withFileFilter { it.extension.equals("wasm", ignoreCase = true) }
        val right = FileChooser.chooseFile(descriptor, project, left.parent) ?: return

        val leftBuffer = left.toCommonByteBuffer()
        val rightBuffer = right.toCommonByteBuffer()
        if (leftBuffer == null || rightBuffer == null) {
            Messages.showWarningDialog(project, "Could not read one of the selected files.", "Compare WASM")
            return
        }
        if (!isCoreWasm(leftBuffer) || !isCoreWasm(rightBuffer)) {
            Messages.showWarningDialog(
                project,
                "WASM size diff currently supports core modules only (not the Component Model).",
                "Compare WASM",
            )
            return
        }

        val diffFile = HexanaWasmDiffVirtualFile(
            name = "${left.name} ↔ ${right.name}",
            leftBuffer = leftBuffer,
            rightBuffer = rightBuffer,
            leftName = left.name,
            rightName = right.name,
        )
        FileEditorManager.getInstance(project).openFile(diffFile, true)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    // Duplicate so the selector's cursor advance does not disturb the buffer handed
    // to the diff panel (which expects position 0).
    private fun isCoreWasm(buffer: CommonByteBuffer): Boolean =
        WasmBinaryProviderSelector.select(buffer.duplicate()) == BinaryProviderType.Wasm
}
