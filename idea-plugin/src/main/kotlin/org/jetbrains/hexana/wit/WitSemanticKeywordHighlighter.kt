package org.jetbrains.hexana.wit

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.childLeafs
import com.intellij.psi.util.elementType
import org.jetbrains.hexana.wit.psi.*

class WitSemanticKeywordHighlighter : HighlightVisitor, DumbAware {
    private var analyzer: WitElementVisitor? = null

    override fun suitableForFile(psiFile: PsiFile): Boolean {
        return psiFile.language == WitLanguage
    }

    override fun visit(element: PsiElement) {
        element.accept(analyzer!!)
    }

    override fun analyze(
        psiFile: PsiFile,
        updateWholeFile: Boolean,
        holder: HighlightInfoHolder,
        action: Runnable
    ): Boolean {

        analyzer = object: WitElementVisitor() {
            override fun visitTypeUsage(o: WitTypeUsage) {
                if (o.firstChild is WitHandle) return
                val childLeafs = o.childLeafs().iterator()
                if (!childLeafs.hasNext()) return
                val firstLeaf = childLeafs.next()
                if (!childLeafs.hasNext() && firstLeaf.elementType == WitElementTypes.IDENTIFIER) {
                    holder.add(highlightKeyword(firstLeaf, DefaultLanguageHighlighterColors.KEYWORD))
                }
            }

            override fun visitGateItem(o: WitGateItem) {
                o.childLeafs().firstOrNull{it.elementType == WitElementTypes.OPEN_PAREN}?.let { paren ->
                    holder.add(highlightKeyword(o, DefaultLanguageHighlighterColors.METADATA,
                        TextRange(o.textRange.startOffset, paren.textRange.startOffset)))
                }
            }

            override fun visitUseNamesItem(o: WitUseNamesItem) {
                o.highlightAs()
            }

            override fun visitIncludeNamesItem(o: WitIncludeNamesItem) {
                o.highlightAs()
            }

            override fun visitToplevelUseDeclaration(o: WitToplevelUseDeclaration) {
                o.highlightAs()
            }

            override fun visitIncludeDeclaration(o: WitIncludeDeclaration) {
                o.childLeafs().singleOrNull { it.text == "with" }?.let {
                    holder.add(highlightKeyword(it, DefaultLanguageHighlighterColors.KEYWORD))
                }
            }

            private fun PsiElement.highlightAs() {
                this.childLeafs().singleOrNull { it.text == "as" }?.let {
                    holder.add(highlightKeyword(it, DefaultLanguageHighlighterColors.KEYWORD))
                }
            }
        }

        try {
            action.run()
        }
        finally {
            analyzer = null
        }
        return true
    }

    override fun clone(): HighlightVisitor {
        return WitSemanticKeywordHighlighter()
    }

    private fun highlightKeyword(keyword: PsiElement, key: TextAttributesKey,
                                 customRange: TextRange? = null): HighlightInfo? {
        return HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
            .let { i -> customRange?.let { i.range(it) } ?: i.range(keyword) }
            .textAttributes(key)
            .create()
    }
}