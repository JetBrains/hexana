package org.jetbrains.hexana.wit

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.lang.ASTNode
import com.intellij.lang.tree.util.children
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import org.jetbrains.hexana.wit.psi.WitElementTypes

class WitSemanticKeywordHighlighter : HighlightVisitor, DumbAware {
    override fun suitableForFile(psiFile: PsiFile): Boolean {
        return psiFile.language == WitLanguage
    }

    override fun visit(element: PsiElement) {
    }

    override fun analyze(
        psiFile: PsiFile,
        updateWholeFile: Boolean,
        holder: HighlightInfoHolder,
        action: Runnable
    ): Boolean {
        SyntaxTraverser.astTraverser(psiFile.node)
            .filterTypes {
                it == WitElementTypes.TY ||
                it == WitElementTypes.USE_NAMES_ITEM ||
                it == WitElementTypes.TOPLEVEL_USE_DECLARATION ||
                it == WitElementTypes.INCLUDE_NAMES_ITEM ||
                it == WitElementTypes.SINCE_GATE ||
                it == WitElementTypes.UNSTABLE_GATE ||
                it == WitElementTypes.DEPRECATED_GATE
            }
            .forEach {
                when (it.elementType) {
                    WitElementTypes.SINCE_GATE, WitElementTypes.UNSTABLE_GATE, WitElementTypes.DEPRECATED_GATE -> {
                        it.findChildByType(WitElementTypes.OPEN_PAREN)?.let { paren ->
                            holder.add(highlightKeyword(it, DefaultLanguageHighlighterColors.METADATA,
                                TextRange(it.textRange.startOffset, paren.textRange.startOffset)))
                        }
                    }
                    WitElementTypes.TY -> {
                        it.children().singleOrNull()?.let {
                            if (it.elementType == WitElementTypes.IDENTIFIER) {
                                holder.add(highlightKeyword(it, DefaultLanguageHighlighterColors.KEYWORD))
                            }
                        }
                    }
                    else -> {
                        it.children().singleOrNull { it.text == "as" }?.let {
                            holder.add(highlightKeyword(it, DefaultLanguageHighlighterColors.KEYWORD))
                        }
                    }
                }
            }
        return true
    }

    override fun clone(): HighlightVisitor {
        return WitSemanticKeywordHighlighter()
    }

    private fun highlightKeyword(keyword: ASTNode, key: TextAttributesKey,
                                 customRange: TextRange? = null): HighlightInfo? {
        return HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
            .let { i -> customRange?.let { i.range(it) } ?: i.range(keyword) }
            .textAttributes(key)
            .create()
    }
}