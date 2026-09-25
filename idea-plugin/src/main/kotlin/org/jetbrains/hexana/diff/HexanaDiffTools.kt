package org.jetbrains.hexana.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffTool
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.SuppressiveDiffTool
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.contents.FileContent
import com.intellij.diff.requests.ContentDiffRequest
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.binary.BinaryDiffTool
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.hexana.HexanaDiffVirtualFile
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.HexanaFileEditorProvider
import org.jetbrains.hexana.actions.CompareFilesAction
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.createBinaryProvider
import org.jetbrains.hexana.shared.diff.BinaryDiffProviderRegistry
import org.jetbrains.hexana.toCommonByteBuffer
import org.jetbrains.hexana.ui.createComposePanel
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Hexana's viewers for IntelliJ's own diff window (Compare Files, VCS revisions, …).
 *
 * Registered as `diff.DiffTool` extensions, which the platform lists ahead of its built-in
 * tools, so a pair of binaries Hexana opens is shown in [HexDiffTool] by default. Every
 * tool that accepts a request is offered in the diff window's viewer switcher, so the
 * user can move between the hex diff and the [HexanaStructuralDiffTool] (size / entity /
 * per-function comparison); the platform remembers the last choice.
 *
 * Wherever the hex diff applies it suppresses the platform's [BinaryDiffTool], which only
 * puts two plain editors side by side. Binaries Hexana does not open keep it.
 */
class HexDiffTool : FrameDiffTool, SuppressiveDiffTool {
    override fun getSuppressedTools(): List<Class<out DiffTool>> = listOf(BinaryDiffTool::class.java)

    override fun getName(): String = "Hex viewer"

    override fun getIcon() = AllIcons.Diff.SideBySide

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean {
        val contents = (request as? ContentDiffRequest)?.contents ?: return false
        if (contents.size != 2) return false
        if (!contents.all { it is EmptyContent || it is FileContent && isReadable(it.file) }) return false
        // An added or deleted file compares against nothing; at least one side must be a binary Hexana opens.
        return contents.any { it is FileContent && HexanaFileEditorProvider.acceptsFile(it.file) }
    }

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        HexDiffViewer(request as ContentDiffRequest)

    private fun isReadable(file: VirtualFile): Boolean = file.isValid && !file.isDirectory
}

/**
 * Hexana's structural binary diff — the view `Compare Binaries With…` opens in an editor
 * tab — hosted in the diff window. Offered only for pairs some
 * [org.jetbrains.hexana.shared.diff.BinaryDiffProvider] may compare (WASM, native).
 */
class HexanaStructuralDiffTool : FrameDiffTool {
    override fun getName(): String = "Hexana diff viewer"

    override fun getIcon() = AllIcons.Nodes.DataTables

    override fun canShow(context: DiffContext, request: DiffRequest): Boolean {
        if (context.project == null) return false
        val contents = (request as? ContentDiffRequest)?.contents ?: return false
        return contents.size == 2 && contents.all {
            it is FileContent && it.file.isValid && CompareFilesAction.isComparable(it.file)
        }
    }

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        HexanaStructuralDiffViewer(context, request as ContentDiffRequest)
}

private class HexDiffViewer(private val request: ContentDiffRequest) : FrameDiffTool.DiffViewer {
    private val state = HexDiffViewState(title(request, 0), title(request, 1))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val composePanel = createComposePanel { HexDiffView(state) }
    private val panel = JPanel(BorderLayout()).apply { add(composePanel, BorderLayout.CENTER) }
    private val status = JBLabel("Comparing…").apply { border = JBUI.Borders.emptyRight(8) }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent = composePanel

    override fun init(): FrameDiffTool.ToolbarComponents {
        val (left, right) = request.contents
        scope.launch {
            try {
                val diff = HexDiff.compute(bytesOf(left), bytesOf(right)) { ensureActive() }
                withContext(Dispatchers.EDT) {
                    state.diff = diff
                    status.text = statusText(diff)
                }
                // Sections come second: parsing can take a while on a large binary, and the
                // byte diff is useful without them.
                val leftSections = sectionsOf(diff, diff.left, left, diff.leftSize)
                val rightSections = sectionsOf(diff, diff.right, right, diff.rightSize)
                val sections = mergeSections(leftSections, rightSections)
                withContext(Dispatchers.EDT) {
                    state.sections = sections
                    status.text = statusText(diff) + sectionStatus(sections)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Hex diff failed", e)
                withContext(Dispatchers.EDT) {
                    state.error = "Could not compare: ${e.message ?: e::class.simpleName}"
                    status.text = "Comparison failed"
                }
            }
        }
        return FrameDiffTool.ToolbarComponents().apply {
            toolbarActions = listOf(PreviousDifferenceAction(), NextDifferenceAction(), CollapseUnchangedAction())
            statusPanel = status
        }
    }

    override fun dispose() {
        scope.cancel()
    }

    private suspend fun bytesOf(content: DiffContent): CommonByteBuffer = when (content) {
        is FileContent -> withContext(Dispatchers.IO) { content.file.toCommonByteBuffer() }
            ?: error("cannot read ${content.file.name}")
        else -> ByteArray(0).toCommonByteBuffer()
    }

    /**
     * The sections of one side, from the provider Hexana would open it with. Empty for a
     * missing side, a format without structure, or one that fails to parse — the byte diff
     * stands on its own.
     */
    private fun CoroutineScope.sectionsOf(diff: HexDiff, bytes: CommonByteBuffer, content: DiffContent, size: Int): List<DiffSection> {
        val file = (content as? FileContent)?.file ?: return emptyList()
        return try {
            val ranges = createBinaryProvider(bytes, sourcePath = null, fileName = file.name).structureRanges
            diffSections(diff, ranges, size) { ensureActive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.info("No sections for ${file.name} in the hex diff", e)
            emptyList()
        }
    }

    /** E.g. " · 3 of 28 sections modified", counting added and removed sections as modified. */
    private fun sectionStatus(sections: DiffSections): String {
        val all = sections.merged.size
        if (all == 0) return ""
        val modified = sections.merged.count { it.status != SectionStatus.UNCHANGED }
        return " · ${"%,d".format(modified)} of ${"%,d".format(all)} " +
            (if (all == 1) "section" else "sections") + " modified"
    }

    private fun statusText(diff: HexDiff): String = if (diff.isIdentical) {
        "Contents are identical"
    } else {
        val bytes = "%,d".format(diff.changedBytes)
        val blocks = "%,d".format(diff.blockCount)
        "$bytes ${if (diff.changedBytes == 1L) "byte differs" else "bytes differ"} in $blocks " +
            if (diff.blockCount == 1) "block" else "blocks"
    }

    private inner class PreviousDifferenceAction :
        DumbAwareAction("Previous Difference", null, AllIcons.Actions.PreviousOccurence) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = state.canGo(next = false)
        }

        override fun actionPerformed(e: AnActionEvent) = state.go(next = false)
    }

    private inner class NextDifferenceAction :
        DumbAwareAction("Next Difference", null, AllIcons.Actions.NextOccurence) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = state.canGo(next = true)
        }

        override fun actionPerformed(e: AnActionEvent) = state.go(next = true)
    }

    private inner class CollapseUnchangedAction :
        DumbAwareToggleAction("Collapse Unchanged Rows", null, AllIcons.Actions.Collapseall) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun isSelected(e: AnActionEvent): Boolean = state.collapseUnchanged

        override fun setSelected(e: AnActionEvent, selected: Boolean) {
            state.collapseUnchanged = selected
            if (selected) state.expandedFolds.clear()
        }
    }

    private companion object {
        val LOG = logger<HexDiffViewer>()
    }
}

/**
 * Wraps the [HexanaFileEditor] that renders a [HexanaDiffVirtualFile], so the diff window
 * shows exactly the editor-tab diff: same tabs, same detail pane, same row navigation.
 */
private class HexanaStructuralDiffViewer(
    private val context: DiffContext,
    private val request: ContentDiffRequest,
) : FrameDiffTool.DiffViewer {
    private val panel = JPanel(BorderLayout())
    private var editor: HexanaFileEditor? = null

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent? = editor?.preferredFocusedComponent

    override fun init(): FrameDiffTool.ToolbarComponents {
        val (left, right) = request.contents.map { (it as FileContent).file }
        val input = CompareFilesAction.diffInput(left, right)
        val rejection = if (input == null) {
            "Could not read one of the files."
        } else {
            BinaryDiffProviderRegistry.rejectionReason(input)
        }
        if (input == null || rejection != null) {
            panel.add(JBLabel(rejection ?: "", SwingConstants.CENTER), BorderLayout.CENTER)
        } else {
            val title = "${title(request, 0)} ↔ ${title(request, 1)}"
            val diffEditor = HexanaFileEditor(context.project!!, HexanaDiffVirtualFile(title, input))
            editor = diffEditor
            panel.add(diffEditor.component, BorderLayout.CENTER)
        }
        return FrameDiffTool.ToolbarComponents()
    }

    override fun dispose() {
        editor?.let { Disposer.dispose(it) }
    }
}

/** The request's title for side [index], falling back to the file name. */
private fun title(request: ContentDiffRequest, index: Int): String =
    request.contentTitles.getOrNull(index)
        ?: (request.contents[index] as? FileContent)?.file?.name
        ?: "(none)"
