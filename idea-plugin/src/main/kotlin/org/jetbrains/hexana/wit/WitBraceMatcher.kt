package org.jetbrains.hexana.wit

import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wit.psi.WitElementTypes
import org.jetbrains.hexana.wit.psi.WitElementTypes.*

class WitBraceMatcher : BraceMatcher {
    override fun getBraceTokenGroupId(token: IElementType): Int {
        if (token == OPEN_PAREN || token == CLOSE_PAREN) {
            return 1
        }
        if (token == OPEN_BRACE || token == CLOSE_BRACE) {
            return 2
        }
        if (token == WitElementTypes.LESS_THAN || token == WitElementTypes.GREATER_THAN) {
            return 3
        }
        return BraceMatchingUtil.UNDEFINED_TOKEN_GROUP
    }

    override fun isLBraceToken(
        iterator: HighlighterIterator,
        text: CharSequence,
        fileType: FileType
    ): Boolean {
        return iterator.tokenType == OPEN_PAREN || iterator.tokenType == OPEN_BRACE || iterator.tokenType == WitElementTypes.LESS_THAN
    }

    override fun isRBraceToken(
        iterator: HighlighterIterator,
        text: CharSequence,
        fileType: FileType
    ): Boolean {
        return iterator.tokenType == CLOSE_PAREN || iterator.tokenType == CLOSE_BRACE || iterator.tokenType == WitElementTypes.GREATER_THAN
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
            OPEN_PAREN -> CLOSE_PAREN
            CLOSE_PAREN -> OPEN_PAREN
            OPEN_BRACE -> CLOSE_BRACE
            CLOSE_BRACE -> OPEN_BRACE
            WitElementTypes.LESS_THAN -> WitElementTypes.GREATER_THAN
            WitElementTypes.GREATER_THAN -> WitElementTypes.LESS_THAN
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