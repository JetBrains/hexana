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
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = if (e.isFromContextMenu) {
            // In the Project-view popup, show the item when exactly two .wasm files are
            // selected, or when a single .wasm file is selected (prompts for the second).
            project != null && (files != null && files.size == 2 && files.all { isWasm(it) }
                || file != null && isWasm(file))
        } else {
            // Search Everywhere / main menu / keymap: always available. actionPerformed
            // prompts for both modules when nothing suitable is selected.
            project != null
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // When exactly two .wasm files are selected in the project tree, use them directly
        // without prompting for file chooser dialogs.
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val twoWasmSelected = selectedFiles != null && selectedFiles.size == 2 && selectedFiles.all { isWasm(it) }

        val left: VirtualFile
        val right: VirtualFile

        if (twoWasmSelected) {
            left = selectedFiles!![0]
            right = selectedFiles[1]
        } else {
            // The Project-view selection is the left-hand module. When invoked without a .wasm
            // selection (Search Everywhere, Keymap, or main menu), prompt for the first module
            // instead of silently doing nothing.
            left = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { isWasm(it) }
                ?: FileChooser.chooseFile(
                    wasmChooser("Compare WASM — Select First Module", "Select the first .wasm module to compare"),
                    project,
                    null,
                ) ?: return

            right = FileChooser.chooseFile(
                wasmChooser("Compare With WASM File", "Select the second .wasm module to compare against ${left.name}"),
                project,
                left.parent,
            ) ?: return
        }

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

    private fun isWasm(file: VirtualFile): Boolean =
        !file.isDirectory && file.extension.equals("wasm", ignoreCase = true)

    private fun wasmChooser(title: String, description: String) =
        FileChooserDescriptorFactory.singleFile()
            .withTitle(title)
            .withDescription(description)
            .withFileFilter { it.extension.equals("wasm", ignoreCase = true) }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    // Duplicate so the selector's cursor advance does not disturb the buffer handed
    // to the diff panel (which expects position 0).
    private fun detectType(buffer: CommonByteBuffer): BinaryProviderType =
        WasmBinaryProviderSelector.select(buffer.duplicate())
}
