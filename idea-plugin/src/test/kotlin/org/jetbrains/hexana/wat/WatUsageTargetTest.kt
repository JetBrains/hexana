package org.jetbrains.hexana.wat

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageTargetUtil
import com.intellij.util.Processor
import org.jetbrains.hexana.wat.psi.WatFuncElement

/**
 * The usage view navigates by calling `FileEditorManager.openTextEditor` on the usage's own file,
 * which for the WAT view means opening its in-memory document as a separate editor. Redirecting
 * that needs the usages themselves to be ours, and a [WatUsageTarget] is the only supported way in.
 */
class WatUsageTargetTest : BasePlatformTestCase() {

    private val watText = "(module (func \$f1) (func \$f2 (call \$f1)) (func \$f3 (call \$f1)))"

    /**
     * A WAT view of a single file, which is what every module under the split threshold gets --
     * note there is no [WatFileset], since only a split module has one.
     */
    private fun watViewFile(): LightWatFile {
        val origin = myFixture.configureByText(WatFileType.INSTANCE, "(module)").virtualFile
        return LightWatFile(origin, "view.wat", watText)
    }

    private fun watViewFileWithFileset(): LightWatFile {
        val file = watViewFile()
        WatFileset(files = listOf(file), watIndex = WatIndex(), onFileChange = {}).install()
        return file
    }

    /**
     * An editor over [file] itself. `myFixture.editor` is not usable here: the fixture's editor
     * belongs to the file `configureByText` set up, not to the in-memory WAT document.
     */
    private fun editorFor(file: VirtualFile): Editor {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val editor = EditorFactory.getInstance().createEditor(document, project, file, true)
        disposeOnTearDown(Disposable { EditorFactory.getInstance().releaseEditor(editor) })
        return editor
    }

    private fun targetAt(file: VirtualFile, offset: Int): UsageTarget? {
        val editor = editorFor(file)
        editor.caretModel.moveToOffset(offset)
        val psiFile = PsiManager.getInstance(project).findFile(file)!!
        return WatUsageTargetProvider().getTargets(editor, psiFile)?.singleOrNull()
    }

    /** Offset of the declaration `$f1`. */
    private fun declarationOffset() = watText.indexOf("\$f1")

    /** Offset of the `$f1` operand of the first `(call ...)`, i.e. a reference rather than a name. */
    private fun referenceOffset() = watText.indexOf("\$f1", watText.indexOf("(call"))

    private fun funcNamed(file: VirtualFile, name: String): WatFuncElement {
        val psiFile = PsiManager.getInstance(project).findFile(file)!!
        return SyntaxTraverser.psiTraverser(psiFile)
            .filter(WatFuncElement::class.java)
            .first { it.name == name }!!
    }

    /**
     * The regression this guards: gating on [WatFileset] missed every ordinary WAT view, because a
     * fileset exists only for a module too large for one document. Find Usages then fell back to
     * the platform's own usages and kept opening a separate editor.
     */
    fun testTargetIsProvidedForAViewWithoutAFileset() {
        val file = watViewFile()
        assertNull("no fileset -- that is the normal case", file.getUserData(WatFileset.KEY))

        val target = targetAt(file, referenceOffset())
        assertNotNull("a usage target is required to redirect navigation", target)
        assertInstanceOf(target, WatUsageTarget::class.java)
        assertEquals("function \$f1", target!!.name)
    }

    /**
     * The overload that actually runs in the WAT view, and the reason the feature stayed dead for
     * three rounds. `UsageTargetUtil` calls the `(editor, file)` form only when the data context has
     * a `PSI_FILE`, and the view has none -- its editor is not a `FileEditor`, so there is no current
     * file to report. Only `(element)` is reached.
     */
    fun testTargetIsProvidedForTheElementOverload() {
        val file = watViewFile()
        val target = WatUsageTargetProvider().getTargets(funcNamed(file, "\$f1"))?.singleOrNull()
        assertInstanceOf(target, WatUsageTarget::class.java)
        assertEquals("function \$f1", target!!.name)
    }

    fun testNoElementTargetOutsideTheView() {
        val psiFile = myFixture.configureByText(WatFileType.INSTANCE, watText)
        val func = SyntaxTraverser.psiTraverser(psiFile)
            .filter(WatFuncElement::class.java)
            .first { it.name == "\$f1" }!!
        assertNull(WatUsageTargetProvider().getTargets(func))
    }

    /**
     * The platform takes `usageTargets[0]`, and within the `(element)` overload every provider's
     * results are concatenated in extension order -- so `order="first"` in `plugin.xml` is what puts
     * ours ahead of `DefaultUsageTargetProvider`. Without it the default target wins and unwraps
     * back to the standard flow.
     */
    fun testOurTargetComesBeforeThePlatformDefault() {
        val file = watViewFile()
        val targets = UsageTargetUtil.findUsageTargets(funcNamed(file, "\$f1"))
        assertNotNull(targets)
        assertEquals(
            "ours must be first",
            WatUsageTarget::class.java,
            targets!!.first().javaClass,
        )
    }

    /**
     * The second regression, and the one that kept the feature dead: the `file` the platform passes
     * is the data context's `PSI_FILE`, which is *not* the WAT view's document. The view's editor is
     * not a `FileEditor` of its own, so the platform reports whatever file it considers current --
     * the binary being shown. Gating on that parameter rejected every real invocation.
     */
    fun testTargetIsProvidedWhenThePassedFileIsNotTheViewsDocument() {
        val file = watViewFile()
        val editor = editorFor(file)
        editor.caretModel.moveToOffset(referenceOffset())
        val someOtherFile = myFixture.file!!

        assertNotSame("the fixture file must differ from the view's document", file, someOtherFile.virtualFile)
        val targets = WatUsageTargetProvider().getTargets(editor, someOtherFile)
        assertNotNull("the editor's own document is what identifies the WAT view", targets)
        assertEquals("function \$f1", targets!!.single().name)
    }

    fun testTargetIsProvidedForASplitView() {
        val target = targetAt(watViewFileWithFileset(), referenceOffset())
        assertInstanceOf(target, WatUsageTarget::class.java)
        assertEquals("function \$f1", target!!.name)
    }

    /** Find Usages has to work from the declaration as well as from a reference. */
    fun testTargetResolvesFromTheDeclarationItself() {
        assertEquals("function \$f1", targetAt(watViewFile(), declarationOffset())?.name)
    }

    fun testNoTargetForAWatFileOutsideTheView() {
        // A .wat file on disk already navigates correctly, so it must keep the standard flow --
        // including the lightweight Show Usages popup, which a custom target would replace.
        val file = myFixture.configureByText(
            WatFileType.INSTANCE,
            "(module (func \$f1) (func \$f2 (call <caret>\$f1)))",
        )
        assertNull(WatUsageTargetProvider().getTargets(myFixture.editor, file))
    }

    fun testNoTargetWhereThereIsNoDeclaration() {
        val file = watViewFile()
        assertNull("a space names nothing", targetAt(file, watText.indexOf(" (func")))
    }

    /** The target must find the same usages the standard flow did, wrapped as [WatUsage]. */
    fun testTargetCollectsUsagesAsWatUsages() {
        val file = watViewFile()
        // findUsages() would show a tool window, so drive the searcher the way the view does.
        val usages = mutableListOf<Usage>()
        WatUsageTarget(funcNamed(file, "\$f1")).collectUsages(Processor { usages.add(it) })

        assertEquals("both (call \$f1) sites", 2, usages.size)
        usages.forEach { assertInstanceOf(it, WatUsage::class.java) }
    }

    /**
     * With no fileset there is no `WatFileset.editor` to navigate through, so the view records its
     * editor on the file instead. Without that there is nothing to move the caret in, and
     * navigation falls back to opening a separate editor -- the bug this whole path exists to fix.
     */
    fun testNavigatesInTheViewEditorRecordedOnTheFile() {
        val file = watViewFile()
        val editor = editorFor(file)
        file.putUserData(LightWatFile.VIEW_EDITOR, editor)
        val offset = watText.indexOf("(func \$f3")

        assertTrue(WatNavigation.navigateInWatEditor(file, offset, navigateIfFilesMatch = true))
        assertEquals("the caret must move inside the view's own editor", offset, editor.caretModel.offset)
    }

    /** Without a recorded editor there is nothing to navigate into, so the caller must fall back. */
    fun testNoViewEditorMeansNoRedirect() {
        val file = watViewFile()
        assertFalse(WatNavigation.navigateInWatEditor(file, 0, navigateIfFilesMatch = true))
    }

    /**
     * Go-to-declaration passes `navigateIfFilesMatch = false` so that an unhandled case falls back
     * to the platform. That must stay untouched by the usage-view redirect.
     */
    fun testDeclarationNavigationStillDefersToThePlatform() {
        val file = watViewFile()
        file.putUserData(LightWatFile.VIEW_EDITOR, editorFor(file))
        assertFalse(WatNavigation.navigateInWatEditor(file, watText.indexOf("(func \$f3")))
    }

    /**
     * The redirect has to be conditional: a usage in a file that is not shown in a WAT view must
     * fall through to the platform's own navigation rather than silently do nothing.
     */
    fun testUsageOutsideTheViewIsLeftToThePlatform() {
        val psiFile = myFixture.configureByText(WatFileType.INSTANCE, watText)
        val func = SyntaxTraverser.psiTraverser(psiFile)
            .filter(WatFuncElement::class.java)
            .first { it.name == "\$f1" }!!
        val reference = ReferencesSearch.search(func, func.useScope).findAll().first()

        val usage = WatUsage(UsageInfo(reference))
        assertFalse("not a WAT view document", usage.file is LightWatFile)
        assertTrue("must still be navigable through the default path", usage.canNavigate())
    }

    fun testTargetElementUtilFlagsResolveReferences() {
        // Guards the assumption behind the provider: the flags used there accept a reference, not
        // only a declaration name.
        myFixture.configureByText(
            WatFileType.INSTANCE,
            "(module (func \$f1) (func \$f2 (call <caret>\$f1)))",
        )
        val resolved = TargetElementUtil.getInstance().findTargetElement(
            myFixture.editor,
            TargetElementUtil.ELEMENT_NAME_ACCEPTED or TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED,
            myFixture.editor.caretModel.offset,
        )
        assertInstanceOf(resolved, WatFuncElement::class.java)
        assertEquals("\$f1", (resolved as WatFuncElement).name)
    }
}
