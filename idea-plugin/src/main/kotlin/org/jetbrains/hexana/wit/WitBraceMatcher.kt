package org.jetbrains.hexana.wit

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wit.psi.WitToken

class WitBraceMatcher : BraceMatcher {
    override fun getBraceTokenGroupId(token: IElementType): Int {
        if (token == WitToken.OPEN_PAREN || token == WitToken.CLOSE_PAREN) {
            return 1
        }
        if (token == WitToken.OPEN_BRACE || token == WitToken.CLOSE_BRACE) {
            return 1
        }
        return BraceMatchingUtil.UNDEFINED_TOKEN_GROUP
    }

    override fun isLBraceToken(
        iterator: HighlighterIterator,
        text: CharSequence,
        fileType: FileType
    ): Boolean {
        return iterator.tokenType == WitToken.OPEN_PAREN || iterator.tokenType == WitToken.OPEN_BRACE
    }

    override fun isRBraceToken(
        iterator: HighlighterIterator,
        text: CharSequence,
        fileType: FileType
    ): Boolean {
        return iterator.tokenType == WitToken.CLOSE_PAREN || iterator.tokenType == WitToken.CLOSE_BRACE
    }

    override fun isPairBraces(
        token1: IElementType,
        token2: IElementType
    ): Boolean {
        return getOppositeBraceTokenType(token1) == token2
//        return token1 == WitToken.OPEN_PAREN && token2 == WitToken.CLOSE_PAREN ||
//                token1 == WitToken.CLOSE_PAREN && token2 == WitToken.OPEN_PAREN ||
//                token1 == WitToken.OPEN_BRACE && token2 == WitToken.CLOSE_BRACE ||
//                token1 == WitToken.CLOSE_BRACE && token2 == WitToken.OPEN_BRACE
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
            WitToken.OPEN_PAREN -> WitToken.CLOSE_PAREN
            WitToken.CLOSE_PAREN -> WitToken.OPEN_PAREN
            WitToken.OPEN_BRACE -> WitToken.CLOSE_BRACE
            WitToken.CLOSE_BRACE -> WitToken.OPEN_BRACE
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