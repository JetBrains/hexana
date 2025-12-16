package org.jetbrains.hexana.wit

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wit.psi.WitLexer
import org.jetbrains.hexana.wit.psi.WitToken

class WitSyntaxHighlighter  : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer {
        return WitLexer()
    }

    override fun getTokenHighlights(tokenType: IElementType?): Array<out TextAttributesKey> {
        return WitSyntaxAttributes.ATTRIBUTES[tokenType] ?: TextAttributesKey.EMPTY_ARRAY
    }
}

class WitSyntaxAttributes {
    companion object {

        val ATTRIBUTES = HashMap<IElementType, Array<TextAttributesKey>>().apply {
            put(WitToken.DOT, arrayOf(DefaultLanguageHighlighterColors.DOT))
            put(WitToken.COMMA, arrayOf(DefaultLanguageHighlighterColors.COMMA))
            put(WitToken.SEMI, arrayOf(DefaultLanguageHighlighterColors.SEMICOLON))
            put(WitToken.LESS_THAN, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(WitToken.GREATER_THAN, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(WitToken.EQUALS, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(WitToken.AT, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(WitToken.UNDERSCORE, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(WitToken.SLASH, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(WitToken.OPEN_BRACE, arrayOf(DefaultLanguageHighlighterColors.BRACES))
            put(WitToken.CLOSE_BRACE, arrayOf(DefaultLanguageHighlighterColors.BRACES))
            put(WitToken.OPEN_PAREN, arrayOf(DefaultLanguageHighlighterColors.PARENTHESES))
            put(WitToken.CLOSE_PAREN, arrayOf(DefaultLanguageHighlighterColors.PARENTHESES))
            put(WitToken.LINE_COMMENT, arrayOf(DefaultLanguageHighlighterColors.LINE_COMMENT))
            put(WitToken.BLOCK_COMMENT, arrayOf(DefaultLanguageHighlighterColors.BLOCK_COMMENT))
            put(WitToken.NUMBER, arrayOf(DefaultLanguageHighlighterColors.NUMBER))
            WitToken.KEYWORDS.forEach {
                put(it, arrayOf(DefaultLanguageHighlighterColors.KEYWORD))
            }
            put(TokenType.BAD_CHARACTER, arrayOf(HighlighterColors.BAD_CHARACTER))
        }
    }
}
