package org.jetbrains.hexana.wit

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wit.psi.WitElementTypes.AT
import org.jetbrains.hexana.wit.psi.WitElementTypes.BLOCK_COMMENT
import org.jetbrains.hexana.wit.psi.WitElementTypes.CLOSE_BRACE
import org.jetbrains.hexana.wit.psi.WitElementTypes.CLOSE_PAREN
import org.jetbrains.hexana.wit.psi.WitElementTypes.COMMA
import org.jetbrains.hexana.wit.psi.WitElementTypes.DASH
import org.jetbrains.hexana.wit.psi.WitElementTypes.DOT
import org.jetbrains.hexana.wit.psi.WitElementTypes.EQUALS
import org.jetbrains.hexana.wit.psi.WitElementTypes.GREATER_THAN
import org.jetbrains.hexana.wit.psi.WitElementTypes.LESS_THAN
import org.jetbrains.hexana.wit.psi.WitElementTypes.LINE_COMMENT
import org.jetbrains.hexana.wit.psi.WitElementTypes.NUMBER
import org.jetbrains.hexana.wit.psi.WitElementTypes.OPEN_BRACE
import org.jetbrains.hexana.wit.psi.WitElementTypes.OPEN_PAREN
import org.jetbrains.hexana.wit.psi.WitElementTypes.SEMI
import org.jetbrains.hexana.wit.psi.WitElementTypes.SLASH
import org.jetbrains.hexana.wit.psi.WitElementTypes.UNDERSCORE
import org.jetbrains.hexana.wit.psi.WitElements
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
            put(DOT, arrayOf(DefaultLanguageHighlighterColors.DOT))
            put(COMMA, arrayOf(DefaultLanguageHighlighterColors.COMMA))
            put(SEMI, arrayOf(DefaultLanguageHighlighterColors.SEMICOLON))
            put(LESS_THAN, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(GREATER_THAN, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(EQUALS, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(AT, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(DASH, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(UNDERSCORE, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(SLASH, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(OPEN_BRACE, arrayOf(DefaultLanguageHighlighterColors.BRACES))
            put(CLOSE_BRACE, arrayOf(DefaultLanguageHighlighterColors.BRACES))
            put(OPEN_PAREN, arrayOf(DefaultLanguageHighlighterColors.PARENTHESES))
            put(CLOSE_PAREN, arrayOf(DefaultLanguageHighlighterColors.PARENTHESES))
            put(LINE_COMMENT, arrayOf(DefaultLanguageHighlighterColors.LINE_COMMENT))
            put(BLOCK_COMMENT, arrayOf(DefaultLanguageHighlighterColors.BLOCK_COMMENT))
            put(NUMBER, arrayOf(DefaultLanguageHighlighterColors.NUMBER))
            WitElements.KEYWORDS.forEach {
                put(it, arrayOf(DefaultLanguageHighlighterColors.KEYWORD))
            }
            put(TokenType.BAD_CHARACTER, arrayOf(HighlighterColors.BAD_CHARACTER))
        }
    }
}
