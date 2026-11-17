package org.jetbrains.hexana.wat

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wat.psi.WatToken

class WatBraceMatcher : BraceMatcher {
    override fun getBraceTokenGroupId(token: IElementType): Int {
        if (token == WatToken.OPEN_PAREN || token == WatToken.CLOSE_PAREN) {
            return 1
        }
        return BraceMatchingUtil.UNDEFINED_TOKEN_GROUP
    }

    override fun isLBraceToken(
        iterator: HighlighterIterator,
        text: CharSequence,
        fileType: FileType
    ): Boolean {
        return iterator.tokenType == WatToken.OPEN_PAREN
    }

    override fun isRBraceToken(
        iterator: HighlighterIterator,
        text: CharSequence,
        fileType: FileType
    ): Boolean {
        return iterator.tokenType == WatToken.CLOSE_PAREN
    }

    override fun isPairBraces(
        token1: IElementType,
        token2: IElementType
    ): Boolean {
        return token1 == WatToken.OPEN_PAREN && token2 == WatToken.CLOSE_PAREN ||
                token1 == WatToken.CLOSE_PAREN && token2 == WatToken.OPEN_PAREN
    }

    override fun isStructuralBrace(
        iterator: HighlighterIterator,
        text: CharSequence,
        fileType: FileType
    ): Boolean {
        return true
    }

    override fun getOppositeBraceTokenType(token: IElementType): IElementType? {
        return when (token) {
            WatToken.OPEN_PAREN -> WatToken.CLOSE_PAREN
            WatToken.CLOSE_PAREN -> WatToken.OPEN_PAREN
            else -> null
        }
    }

    override fun isPairedBracesAllowedBeforeType(
        token1: IElementType,
        token2: IElementType?
    ): Boolean {
        return true
    }

    override fun getCodeConstructStart(file: PsiFile, openBraceOffset: Int): Int {
        return openBraceOffset
    }
}