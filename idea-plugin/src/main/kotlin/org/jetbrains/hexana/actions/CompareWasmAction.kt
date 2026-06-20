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
import org.jetbrains.hexana.HexanaComponentDiffVirtualFile
import org.jetbrains.hexana.HexanaWasmDiffVirtualFile
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.providers.format.WasmSymbolMap
import org.jetbrains.hexana.toCommonByteBuffer

/**
 * `Compare WASM With…` — pick a second `.wasm` file and open a diff against the
 * selected one.
 *
 * - Two **core** modules → a [HexanaWasmDiffVirtualFile] (size / entity / WAT diff).
 * - Two **Component Model** binaries → a [HexanaComponentDiffVirtualFile] that pairs
 *   their embedded submodules and lets the user open each differing pair in its own tab.
 * - A core-vs-component mix is rejected with a message.
 *
 * Both route through the existing [org.jetbrains.hexana.HexanaFileEditorProvider].
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
        val leftType = detectType(leftBuffer)
        val rightType = detectType(rightBuffer)
        val diffFile: VirtualFile = when {
            leftType == BinaryProviderType.Wasm && rightType == BinaryProviderType.Wasm ->
                HexanaWasmDiffVirtualFile(
                    name = "${left.name} ↔ ${right.name}",
                    leftBuffer = leftBuffer,
                    rightBuffer = rightBuffer,
                    leftName = left.name,
                    rightName = right.name,
                    leftSymbolMap = loadSymbolMap(left),
                    rightSymbolMap = loadSymbolMap(right),
                )

            leftType == BinaryProviderType.WasmComponent && rightType == BinaryProviderType.WasmComponent ->
                HexanaComponentDiffVirtualFile(
                    name = "${left.name} ↔ ${right.name}",
                    leftBuffer = leftBuffer,
                    rightBuffer = rightBuffer,
                    leftName = left.name,
                    rightName = right.name,
                    leftSymbolMap = loadSymbolMap(left),
                    rightSymbolMap = loadSymbolMap(right),
                )

            else -> {
                Messages.showWarningDialog(
                    project,
                    "Compare a core module with a core module, or a component with a component — " +
                        "the two selected files are not the same kind of WASM binary.",
                    "Compare WASM",
                )
                return
            }
        }
        FileEditorManager.getInstance(project).openFile(diffFile, true)
    }

    /**
     * Auto-loads a `--emit-symbol-map` sidecar named `<file>.symbols` sitting next to the
     * module (mirrors the `.hexana.json` / `.map` sibling conventions). Returns null when
     * absent or unreadable — the diff then runs without de-minification.
     */
    private fun loadSymbolMap(file: VirtualFile): WasmSymbolMap? {
        val sidecar = file.parent?.findChild("${file.name}.symbols") ?: return null
        return try {
            WasmSymbolMap.parse(sidecar.contentsToByteArray().decodeToString())
        } catch (t: Throwable) {
            null
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    // Duplicate so the selector's cursor advance does not disturb the buffer handed
    // to the diff panel (which expects position 0).
    private fun detectType(buffer: CommonByteBuffer): BinaryProviderType =
        WasmBinaryProviderSelector.select(buffer.duplicate())
}
