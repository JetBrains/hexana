package org.jetbrains.hexana.actions

import com.intellij.openapi.actionSystem.ActionPlaces
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
import org.jetbrains.hexana.NativeBinaryFileType
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.providers.format.WasmSymbolMap
import org.jetbrains.hexana.shared.diff.BinaryDiffInput
import org.jetbrains.hexana.shared.diff.BinaryDiffProviderRegistry
import org.jetbrains.hexana.toCommonByteBuffer

/**
 * `Compare Binaries With…` — pick a second file and open a diff against the selected one.
 *
 * One action for every format: it opens a [HexanaDiffVirtualFile], and the
 * [org.jetbrains.hexana.shared.diff.BinaryDiffProvider] that accepts the pair decides what
 * it shows — size / entity / dispatch tabs with a WAT side by side for two core WASM
 * modules, paired submodules for two Component Model binaries, section sizes and a
 * per-function comparison for two native binaries. A pair that is not the same kind is
 * rejected with [BinaryDiffProviderRegistry.rejectionReason].
 *
 * Routes through the existing [org.jetbrains.hexana.HexanaFileEditorProvider].
 */
open class CompareFilesAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val twoSelected = files != null && files.size == 2 && files.all { isComparable(it) }
        e.presentation.isEnabledAndVisible = if (e.isFromContextMenu) {
            // In the Project-view popup, show the item when exactly two comparable files are
            // selected, or when a single one is selected (prompts for the second).
            project != null && (twoSelected || file != null && isComparable(file))
        } else {
            // Search Everywhere / main menu / keymap: always available. actionPerformed
            // prompts for both files when nothing suitable is selected.
            project != null
        }
        e.presentation.text = if (twoSelected) "Compare Binary Files" else "Compare Binaries With…"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // When exactly two comparable files are selected in the project tree, use them
        // directly without prompting for file chooser dialogs.
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        val twoSelected = selectedFiles != null && selectedFiles.size == 2 && selectedFiles.all { isComparable(it) }

        val left: VirtualFile
        val right: VirtualFile

        if (twoSelected) {
            left = selectedFiles!![0]
            right = selectedFiles[1]
        } else {
            // The Project-view selection is the left-hand file. When invoked without one
            // (Search Everywhere, Keymap, or main menu), prompt for it instead of silently
            // doing nothing.
            left = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { isComparable(it) }
                ?: FileChooser.chooseFile(
                    chooser("Compare Binaries — Select First File", "Select the first binary to compare"),
                    project,
                    null,
                ) ?: return

            right = FileChooser.chooseFile(
                chooser("Compare With Binary", "Select the binary to compare against ${left.name}"),
                project,
                left.parent,
            ) ?: return
        }

        val leftBuffer = left.toCommonByteBuffer()
        val rightBuffer = right.toCommonByteBuffer()
        if (leftBuffer == null || rightBuffer == null) {
            Messages.showWarningDialog(project, "Could not read one of the selected files.", TITLE)
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
        BinaryDiffProviderRegistry.rejectionReason(input)?.let { reason ->
            Messages.showWarningDialog(project, reason, TITLE)
            return
        }
        FileEditorManager.getInstance(project).openFile(HexanaDiffVirtualFile(input.title, input), true)
    }

    /**
     * Auto-loads a `--emit-symbol-map` sidecar named `<file>.symbols` sitting next to a WASM
     * module (mirrors the `.hexana.json` / `.map` sibling conventions). Returns null when
     * absent or unreadable — the diff then runs without de-minification.
     */
    private fun loadSymbolMap(file: VirtualFile): WasmSymbolMap? {
        if (!file.extension.equals("wasm", ignoreCase = true)) return null
        val sidecar = file.parent?.findChild("${file.name}.symbols") ?: return null
        return try {
            WasmSymbolMap.parse(sidecar.contentsToByteArray().decodeToString())
        } catch (t: Throwable) {
            null
        }
    }


    private fun chooser(title: String, description: String) =
        FileChooserDescriptorFactory.singleFile()
            .withTitle(title)
            .withDescription(description)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        private const val TITLE = "Compare Binaries"

        /**
         * Whether [file] is a binary some diff provider compares: a WASM module or component,
         * or a native binary (ELF, Mach-O, PE, COFF object, `ar` archive).
         *
         * Decided by file type, not extension: [org.jetbrains.hexana.NativeBinaryFileTypeDetector]
         * types these by their magic, so an extensionless executable or a module saved without
         * `.wasm` qualifies, while a headerless `.bin` image — which no provider can compare —
         * does not. Reading the cached file type keeps this cheap enough for `update`.
         */
        internal fun isComparable(file: VirtualFile): Boolean =
            !file.isDirectory && (file.fileType is WasmFileType || file.fileType is NativeBinaryFileType)
    }
}

/**
 * The former `hexana.compareWasm` / `hexana.compareBinary` action ids, kept so shortcuts users
 * bound to them keep working. They do exactly what [CompareFilesAction] does, but are in no
 * menu and hidden from Find Action, so only `hexana.compareFiles` is offered.
 */
class LegacyCompareFilesAction : CompareFilesAction() {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.place == ActionPlaces.ACTION_SEARCH) e.presentation.isEnabledAndVisible = false
    }
}
