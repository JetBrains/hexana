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
import org.jetbrains.hexana.BinaryFileType
import org.jetbrains.hexana.HexViewStateHolder
import org.jetbrains.hexana.HexanaDiffVirtualFile
import org.jetbrains.hexana.HexanaFileEditor
import org.jetbrains.hexana.HexanaFileEditorProvider
import org.jetbrains.hexana.actions.CompareFilesAction
import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.BinaryProvider
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
        HexDiffViewer(context, request as ContentDiffRequest)

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
        if (contents.size != 2 || !contents.all { it is FileContent && it.file.isValid }) return false
        // Offered only when a provider can compare the pair, so the switcher never leads to a
        // "not the same kind" message.
        return CompareFilesAction.isComparablePair((contents[0] as FileContent).file, (contents[1] as FileContent).file)
    }

    override fun createComponent(context: DiffContext, request: DiffRequest): FrameDiffTool.DiffViewer =
        HexanaStructuralDiffViewer(context, request as ContentDiffRequest)
}

private class HexDiffViewer(context: DiffContext, private val request: ContentDiffRequest) : FrameDiffTool.DiffViewer {
    private val state = HexDiffViewState(title(request, 0), title(request, 1), tabsExpected = expectsTabs(request))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Backs the table renderer of the diffed tabs; tables need a project to render. */
    private val tableState = context.project?.let { HexViewStateHolder(it) }
    private val composePanel = createComposePanel { HexDiffView(state, tableState) }
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
                // Structure comes second: parsing can take a while on a large binary, and the
                // byte diff is useful without it. Sections first, then the slower table tabs.
                val leftProvider = providerOf(diff.left, left)
                val rightProvider = providerOf(diff.right, right)
                if (!areComparable(leftProvider, rightProvider)) {
                    // Different formats: their sections and tables describe unrelated things,
                    // so only the byte diff is shown.
                    withContext(Dispatchers.EDT) { state.tabs = emptyList() }
                    return@launch
                }
                val sections = mergeSections(
                    sectionsOf(diff, leftProvider, diff.leftSize),
                    sectionsOf(diff, rightProvider, diff.rightSize),
                )
                withContext(Dispatchers.EDT) {
                    state.sections = sections
                    status.text = statusText(diff) + sectionStatus(sections)
                }
                if (tableState != null) {
                    val tabs = diffTableTabs(
                        leftProvider, rightProvider, nameOf(left), nameOf(right),
                        onError = { LOG.info("A table tab failed in the hex diff", it) },
                        checkCanceled = { ensureActive() },
                        onRowClick = { l, r ->
                            listOfNotNull(l, r).minOrNull()?.let { state.goToOffset(it.toLong()) }
                        },
                    )
                    withContext(Dispatchers.EDT) { state.tabs = tabs }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Hex diff failed", e)
                withContext(Dispatchers.EDT) {
                    state.error = "Could not compare: ${e.message ?: e::class.simpleName}"
                    // Settles the reserved tabs area; otherwise its spinner would never stop.
                    state.tabs = emptyList()
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
     * Whether both sides' structure can be compared: the same kind of provider (two ELF
     * files, two WASM modules, …). A missing side — a file added or deleted — compares
     * against nothing, so its sections and rows all show as added or removed.
     */
    private fun areComparable(left: BinaryProvider?, right: BinaryProvider?): Boolean =
        left == null || right == null || left::class == right::class

    private fun nameOf(content: DiffContent): String? = (content as? FileContent)?.file?.name

    /**
     * The provider Hexana would open one side with; `null` for a missing side or one that
     * fails to parse — the byte diff stands on its own.
     */
    private fun providerOf(bytes: CommonByteBuffer, content: DiffContent): BinaryProvider? {
        val name = nameOf(content) ?: return null
        return try {
            createBinaryProvider(bytes, sourcePath = null, fileName = name)
        } catch (e: Exception) {
            LOG.info("Cannot parse $name for the hex diff", e)
            null
        }
    }

    /** One side's sections; empty for a format without structure, or one that fails to parse. */
    private fun CoroutineScope.sectionsOf(diff: HexDiff, provider: BinaryProvider?, size: Int): List<DiffSection> {
        provider ?: return emptyList()
        return try {
            diffSections(diff, provider.structureRanges, size) { ensureActive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.info("No sections in the hex diff", e)
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var editor: HexanaFileEditor? = null
    private var disposed = false

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent? = editor?.preferredFocusedComponent

    override fun init(): FrameDiffTool.ToolbarComponents {
        val (left, right) = request.contents.map { (it as FileContent).file }
        show(JBLabel("Comparing…", SwingConstants.CENTER))
        scope.launch {
            // Reading the inputs maps both files, reads any `.symbols` sidecar and sniffs each
            // header (a remote file is even downloaded), so it stays off the EDT.
            val input = withContext(Dispatchers.IO) { CompareFilesAction.diffInput(left, right) }
            val rejection = if (input == null) "Could not read one of the files." else BinaryDiffProviderRegistry.rejectionReason(input)
            withContext(Dispatchers.EDT) {
                if (disposed) return@withContext
                if (input == null || rejection != null) {
                    show(JBLabel(rejection ?: "", SwingConstants.CENTER))
                } else {
                    val title = "${title(request, 0)} ↔ ${title(request, 1)}"
                    val diffEditor = HexanaFileEditor(context.project!!, HexanaDiffVirtualFile(title, input))
                    editor = diffEditor
                    show(diffEditor.component)
                }
            }
        }
        return FrameDiffTool.ToolbarComponents()
    }

    private fun show(component: JComponent) {
        panel.removeAll()
        panel.add(component, BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
    }

    override fun dispose() {
        disposed = true
        scope.cancel()
        editor?.let { Disposer.dispose(it) }
    }
}

/**
 * Whether table tabs are likely: a side is a format Hexana parses into tables — anything it
 * opens except the generic catch-all binary type — and the two sides, when both exist, are
 * of the same file type (two different formats get only the byte diff). A cheap file-type
 * check, made before the bytes are read; the provider check after parsing is authoritative.
 */
private fun expectsTabs(request: ContentDiffRequest): Boolean {
    val files = request.contents.filterIsInstance<FileContent>().map { it.file }
    if (files.size == 2 && files[0].fileType != files[1].fileType) return false
    return files.any { HexanaFileEditorProvider.acceptsFile(it) && it.fileType !is BinaryFileType }
}

/** The request's title for side [index], falling back to the file name. */
private fun title(request: ContentDiffRequest, index: Int): String =
    request.contentTitles.getOrNull(index)
        ?: (request.contents[index] as? FileContent)?.file?.name
        ?: "(none)"
