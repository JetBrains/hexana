package org.jetbrains.hexana.wat

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.find.findUsages.PsiElement2UsageTargetAdapter
import com.intellij.ide.projectView.PresentationData
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.usageView.UsageInfo
import com.intellij.usages.PsiElementUsageTarget
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageSearcher
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetProvider
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import org.jetbrains.hexana.HexanaBundle
import org.jetbrains.hexana.wat.psi.WatNameIdentifierOwner
import java.util.function.Supplier

/**
 * A usage that lands in the embedded WAT view instead of an editor of its own.
 *
 * The usage view does not navigate through `PsiElement.navigate` -- `UsageInfo2UsageAdapter`
 * calls `FileEditorManager.openTextEditor` on the usage's file directly -- so a usage inside the
 * WAT view would otherwise open its in-memory [LightWatFile] as a separate editor.
 */
class WatUsage(usageInfo: UsageInfo) : UsageInfo2UsageAdapter(usageInfo) {

    override fun navigate(requestFocus: Boolean) {
        // A LightWatFile is created only by the WAT view. Anything else -- an ordinary .wat file on
        // disk -- navigates correctly through the platform already.
        val watFile = file as? LightWatFile
        if (watFile == null) {
            super.navigate(requestFocus)
            return
        }
        // Record the destination before asking for the editor: if the Hexana editor was closed,
        // reopening it reconverts the binary asynchronously and builds a *new* WAT document, so
        // there is nothing to move a caret in yet and this document will never be shown again. The
        // rebuilt view picks the request up as soon as it has an editor.
        WatViewEditors.requestNavigation(watFile, navigationOffset)
        // The WAT view is a tab of the Hexana editor for the binary, so that editor has to come
        // forward before moving the caret inside it.
        FileEditorManager.getInstance(usageInfo.project).openFile(watFile.originWasmFile, requestFocus)
        if (WatNavigation.navigateInWatEditor(watFile, navigationOffset, navigateIfFilesMatch = true)) {
            WatViewEditors.clearPending(watFile)
        }
        // Deliberately no super.navigate() fallback: that opens the in-memory document as a separate
        // editor, which is the very thing this class exists to avoid. If the view is not ready the
        // pending request handles it; if it never appears, nothing moves, which is the lesser evil.
    }
}

/**
 * Runs the usage search for a WAT declaration and shows the results as [WatUsage]es.
 *
 * Owning the search is the only way to own the usages: nothing converts a `UsageInfo` into a
 * `Usage` through an extension point, and `FindUsagesManager` wraps every result in a plain
 * `UsageInfo2UsageAdapter`. Reaching this at all needs a [WatUsageTargetProvider].
 */
class WatUsageTarget(element: WatNameIdentifierOwner) : PsiElementUsageTarget {

    private val project = element.project
    private val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(element)
    private val nodeText = LanguageFindUsages.getNodeText(element, true)

    /**
     * Everything except [findUsages] behaves exactly as the platform's own target. Note this cannot
     * be done by *extending* `PsiElement2UsageTargetAdapter`: the platform unwraps that concrete
     * class back to a plain PSI target, which would bypass this class entirely.
     */
    private val delegate = PsiElement2UsageTargetAdapter(element, true)

    override fun getElement(): PsiElement? = pointer.element

    override fun isValid(): Boolean = pointer.element != null

    override fun getName(): String = nodeText

    override fun getPresentation(): ItemPresentation = PresentationData(nodeText, null, null, null)

    override fun canNavigate(): Boolean = isValid

    override fun canNavigateToSource(): Boolean = isValid

    override fun navigate(requestFocus: Boolean) {
        pointer.element?.navigate(requestFocus)
    }

    override fun isReadOnly(): Boolean = delegate.isReadOnly

    override fun getFiles(): Array<VirtualFile>? = delegate.files

    override fun highlightUsages(file: PsiFile, editor: Editor, clearHighlights: Boolean) {
        delegate.highlightUsages(file, editor, clearHighlights)
    }

    override fun findUsagesInEditor(editor: FileEditor) {
        delegate.findUsagesInEditor(editor)
    }

    override fun findUsages() {
        val presentation = UsageViewPresentation().apply {
            searchString = HexanaBundle.message("wat.find.usages.search.string", nodeText)
            tabText = nodeText
            isOpenInNewTab = false
            isCodeUsages = true
        }
        UsageViewManager.getInstance(project).searchAndShowUsages(
            arrayOf(this),
            Supplier { UsageSearcher { processor -> collectUsages(processor) } },
            /* showPanelIfOnlyOneUsage = */ true,
            /* showNotFoundMessage = */ true,
            presentation,
            null,
        )
    }

    /**
     * Mirrors what the platform's own handler does for code usages -- [ReferencesSearch] over the
     * element's use scope, which [WatScopeOptimizer] narrows to the file -- but wraps each hit in a
     * [WatUsage].
     *
     * A `UsageSearcher` takes its own read action, the same way `FindUsagesManager`'s does.
     */
    internal fun collectUsages(processor: Processor<in Usage>) {
        ApplicationManager.getApplication().runReadAction {
            val element = pointer.element ?: return@runReadAction
            ReferencesSearch.search(element, element.useScope).forEach { reference ->
                processor.process(WatUsage(UsageInfo(reference)))
            }
        }
    }
}

/**
 * Routes Find Usages inside the WAT view through [WatUsageTarget].
 *
 * Both overloads are implemented on purpose. `UsageTargetUtil` calls the `(editor, file)` one only
 * when the data context has *both* an editor and a `PSI_FILE`, and in the WAT view it has no
 * `PSI_FILE` -- the view's editor is not a `FileEditor`, so there is no current file to report. Only
 * the `(element)` overload actually runs there, and it needs `order="first"` in `plugin.xml` to come
 * before `DefaultUsageTargetProvider`, since the platform takes the first target of the list.
 */
class WatUsageTargetProvider : UsageTargetProvider {

    override fun getTargets(element: PsiElement): Array<UsageTarget>? {
        if (element !is WatNameIdentifierOwner) {
            return null
        }
        // Only the embedded view needs redirecting, and a LightWatFile is created only by it; a
        // .wat file on disk keeps the standard flow, including the Show Usages popup.
        if (element.containingFile?.virtualFile !is LightWatFile) {
            return null
        }
        return arrayOf(WatUsageTarget(element))
    }

    override fun getTargets(editor: Editor, file: PsiFile): Array<UsageTarget>? {
        // `file` is the data context's PSI_FILE, which is *not* the WAT view's document: the view's
        // editor is not a FileEditor of its own, so the platform reports the binary being shown
        // instead. Only the editor's own document identifies what is under the caret -- which is
        // also what TargetElementUtil below resolves against.
        //
        // Only the embedded view needs redirecting, and a LightWatFile is created only by it; a
        // .wat file on disk keeps the standard flow, including the Show Usages popup. Do not narrow
        // this to files carrying a WatFileset either: only a module too large for one document gets
        // one, so that would miss every ordinary WAT view.
        if (FileDocumentManager.getInstance().getFile(editor.document) !is LightWatFile) {
            return null
        }
        val target = TargetElementUtil.getInstance().findTargetElement(
            editor,
            TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED,
            editor.caretModel.offset,
        ) as? WatNameIdentifierOwner ?: return null
        return arrayOf(WatUsageTarget(target))
    }
}
