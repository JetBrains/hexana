package org.jetbrains.hexana.actions

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.impl.DiffSettingsHolder
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.hexana.NativeBinaryFileType
import org.jetbrains.hexana.WasmFileType
import org.jetbrains.hexana.diff.HexanaStructuralDiffTool
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProviderType
import org.jetbrains.hexana.providers.WasmBinaryProviderSelector
import com.intellij.openapi.util.Key
import java.io.IOException
import org.jetbrains.hexana.providers.format.WasmSymbolMap
import org.jetbrains.hexana.shared.diff.BinaryDiffInput
import org.jetbrains.hexana.shared.diff.BinaryDiffProviderRegistry
import org.jetbrains.hexana.toCommonByteBuffer

/**
 * `Compare Binaries With…` — pick a second file and open a diff against the selected one.
 *
 * Opens the platform diff, the same one as the IDE's own Compare Files, where Hexana's
 * `diff.DiffTool`s show it (see `org.jetbrains.hexana.diff.HexDiffTool`): the hex diff with
 * sections and table tabs, and — for pairs a
 * [org.jetbrains.hexana.shared.diff.BinaryDiffProvider] compares — the structural diff with
 * size / entity / per-function tabs. The diff window's viewer switcher moves between them.
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

        // The platform diff, where Hexana's viewers live: the hex diff (any pair, structure
        // included when both are the same kind) and the structural diff (pairs a provider
        // compares), switchable there. Reading and comparing happen in the viewers, off the EDT.
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "${left.name} ↔ ${right.name}",
            factory.create(project, left),
            factory.create(project, right),
            left.name,
            right.name,
        )
        // A place of its own, so this entry opens on the size and functions diff — what it showed before it
        // moved into the diff window — while the IDE's Compare Files keeps the hex diff first.
        // The platform remembers the viewer order per place, so a switch here sticks here only.
        val chain = SimpleDiffRequestChain(request)
        chain.putUserData(DiffUserDataKeys.PLACE, DIFF_PLACE)
        preferStructuralViewer()
        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
    }


    private fun chooser(title: String, description: String) =
        FileChooserDescriptorFactory.singleFile()
            .withTitle(title)
            .withDescription(description)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    companion object {
        /** The diff place of Hexana's own compare entry, see [actionPerformed]. */
        internal const val DIFF_PLACE = "Hexana.CompareBinaries"

        /**
         * Seeds [DIFF_PLACE]'s viewer order with the structural viewer first — once: after that
         * the order is whatever the user last picked in the switcher. Pairs it does not accept
         * fall through to the next viewer, the hex diff.
         */
        internal fun preferStructuralViewer() {
            val settings = DiffSettingsHolder.DiffSettings.getSettings(DIFF_PLACE)
            if (settings.diffToolsOrder.isEmpty()) {
                settings.diffToolsOrder = listOf(HexanaStructuralDiffTool::class.java.canonicalName)
            }
        }

        /**
         * The diff input for [left] against [right], or null when either cannot be read.
         * Maps local files rather than reading them, so it is cheap enough for the EDT.
         */
        internal fun diffInput(left: VirtualFile, right: VirtualFile): BinaryDiffInput? {
            val leftBuffer = left.toCommonByteBuffer() ?: return null
            val rightBuffer = right.toCommonByteBuffer() ?: return null
            return BinaryDiffInput(
                left = leftBuffer,
                right = rightBuffer,
                leftName = left.name,
                rightName = right.name,
                leftSymbolMap = loadSymbolMap(left),
                rightSymbolMap = loadSymbolMap(right),
            )
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

        /**
         * Whether a diff provider compares [left] with [right]: two core WASM modules, two
         * components, or two native binaries — the split [BinaryDiffProviderRegistry] follows.
         * A module and a component share a file type, so WASM files are told apart by their
         * 8-byte header ([wasmKind]); native files by their file type alone.
         */
        internal fun isComparablePair(left: VirtualFile, right: VirtualFile): Boolean =
            isComparable(left) && isComparable(right) && wasmKind(left) == wasmKind(right)

        /**
         * What the header says a WASM-typed [file] is (module, component, or neither), the same
         * sniff the diff providers use; [BinaryProviderType.Generic] for a native file, unread.
         * Cached per modification stamp, since the diff window asks on every tool-list update.
         */
        private fun wasmKind(file: VirtualFile): BinaryProviderType {
            if (file.fileType !is WasmFileType) return BinaryProviderType.Generic
            file.getUserData(WASM_KIND)?.let { (stamp, kind) -> if (stamp == file.modificationStamp) return kind }
            val header = try {
                file.inputStream.use { it.readNBytes(8) }
            } catch (_: IOException) {
                ByteArray(0)
            }
            val kind =
                if (header.size < 8) BinaryProviderType.Generic else WasmBinaryProviderSelector.select(header.toCommonByteBuffer())
            file.putUserData(WASM_KIND, file.modificationStamp to kind)
            return kind
        }

        private val WASM_KIND = Key.create<Pair<Long, BinaryProviderType>>("hexana.compare.wasmKind")
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
