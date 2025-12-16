package org.jetbrains.hexana.wit.psi

import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wit.WitLanguage

class WitToken(val name: String) : IElementType(name, WitLanguage) {
    companion object {
        @JvmField val WHITESPACE = WitToken("whitespace")
        @JvmField val NEWLINE = WitToken("newline")
        @JvmField val LINE_COMMENT = WitToken("line_comment")
        @JvmField val BLOCK_COMMENT = WitToken("block_comment")
        @JvmField val DOT = WitToken("DOT")
        @JvmField val COMMA = WitToken("COMMA")
        @JvmField val COLON = WitToken("COLON")
        @JvmField val SEMI = WitToken("SEMI")
        @JvmField val LESS_THAN = WitToken("LESS_THAN")
        @JvmField val GREATER_THAN = WitToken("GREATER_THAN")
        @JvmField val OPEN_BRACE = WitToken("OPEN_BRACE")
        @JvmField val CLOSE_BRACE = WitToken("CLOSE_BRACE")
        @JvmField val OPEN_PAREN = WitToken("OPEN_PAREN")
        @JvmField val CLOSE_PAREN = WitToken("CLOSE_PAREN")
        @JvmField val EQUALS = WitToken("EQUALS")
        @JvmField val AT = WitToken("AT")
        @JvmField val UNDERSCORE = WitToken("UNDERSCORE")
        @JvmField val ARROW = WitToken("ARROW")
    }
}