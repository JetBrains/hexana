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
import org.jetbrains.hexana.providers.format.WasmSymbolMap
import org.jetbrains.hexana.shared.diff.BinaryDiffInput
import org.jetbrains.hexana.shared.diff.BinaryDiffProviderRegistry
import org.jetbrains.hexana.shared.diff.WasmComponentDiffProvider
import org.jetbrains.hexana.shared.diff.WasmModuleDiffProvider
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import org.jetbrains.hexana.toCommonByteBuffer

/**
 * `Compare WASM With…` — pick a second `.wasm` file and open a diff against the
 * selected one.
 *
 * Opens a [HexanaDiffVirtualFile]; the format's
 * [org.jetbrains.hexana.shared.diff.BinaryDiffProvider] decides what it shows:
 *
 * - Two **core** modules → size / entity / dispatch tabs with a WAT side-by-side.
 * - Two **Component Model** binaries → their embedded submodules paired, each differing
 *   pair openable in its own tab.
 * - A core-vs-component mix has no provider and is rejected with a message.
 *
 * Routes through the existing [org.jetbrains.hexana.HexanaFileEditorProvider].
 */
class CompareWasmAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val twoSelected = files != null && files.size == 2 && files.all { isWasm(it) }
        e.presentation.isEnabledAndVisible = if (e.isFromContextMenu) {
            // In the Project-view popup, show the item when exactly two .wasm files are
            // selected, or when a single .wasm file is selected (prompts for the second).
            project != null && (twoSelected || file != null && isWasm(file))
        } else {
            // Search Everywhere / main menu / keymap: always available. actionPerformed
            // prompts for both modules when nothing suitable is selected.
            project != null
        }
        e.presentation.text = if (twoSelected) "Compare WASM Files" else "Compare WASM With\u2026"
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
        val input = BinaryDiffInput(
            left = leftBuffer,
            right = rightBuffer,
            leftName = left.name,
            rightName = right.name,
            leftSymbolMap = loadSymbolMap(left),
            rightSymbolMap = loadSymbolMap(right),
        )
        // Only the Wasm providers: the registry's native fallback accepts any non-Wasm pair, so
        // a `.wasm` whose header is not Wasm (truncated, mislabelled) would otherwise open as an
        // empty native size diff instead of being reported.
        val provider = BinaryDiffProviderRegistry.forInput(input)
        if (provider !is WasmModuleDiffProvider && provider !is WasmComponentDiffProvider) {
            val notWasm = listOf(left to leftBuffer, right to rightBuffer)
                .firstOrNull { (_, buffer) -> WasmBinaryProviderSelector.select(buffer.duplicate()) == BinaryProviderType.Generic }
                ?.first
            val message = if (notWasm != null) {
                "${notWasm.name} is not a WebAssembly binary (no `\\0asm` header)."
            } else {
                "Compare a core module with a core module, or a component with a component — " +
                    "the two selected files are not the same kind of WASM binary."
            }
            Messages.showWarningDialog(project, message, "Compare WASM")
            return
        }
        val diffFile = HexanaDiffVirtualFile(input.title, input)
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
}
