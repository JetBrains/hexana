package org.jetbrains.hexana.wat

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wat.psi.WatLexer
import org.jetbrains.hexana.wat.psi.WatToken

class WatSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer {
        return WatLexer()
    }

    override fun getTokenHighlights(tokenType: IElementType?): Array<out TextAttributesKey> {
        return WatSyntaxAttributes.ATTRIBUTES[tokenType] ?: TextAttributesKey.EMPTY_ARRAY
    }
}

class WatSyntaxAttributes {
    companion object {

        val ATTRIBUTES = HashMap<IElementType, Array<TextAttributesKey>>().apply {
            put(WatToken.OPEN_PAREN, arrayOf(DefaultLanguageHighlighterColors.PARENTHESES))
            put(WatToken.CLOSE_PAREN, arrayOf(DefaultLanguageHighlighterColors.PARENTHESES))
            put(WatToken.STRING, arrayOf(DefaultLanguageHighlighterColors.STRING))
            put(WatToken.LINE_COMMENT, arrayOf(DefaultLanguageHighlighterColors.LINE_COMMENT))
            put(WatToken.BLOCK_COMMENT, arrayOf(DefaultLanguageHighlighterColors.BLOCK_COMMENT))
            put(WatToken.NUMBER, arrayOf(DefaultLanguageHighlighterColors.NUMBER))
            put(WatToken.PLUS, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(WatToken.MINUS, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(WatToken.EQUALS, arrayOf(DefaultLanguageHighlighterColors.OPERATION_SIGN))
            put(WatToken.ANNOTATION_ID, arrayOf(DefaultLanguageHighlighterColors.IDENTIFIER))
            WatToken.KEYWORDS.forEach {
                put(it, arrayOf(DefaultLanguageHighlighterColors.KEYWORD))
            }
            put(TokenType.BAD_CHARACTER, arrayOf(HighlighterColors.BAD_CHARACTER))
            put(WatToken.UNKNOWN, arrayOf(HighlighterColors.BAD_CHARACTER))
        }
    }
}
