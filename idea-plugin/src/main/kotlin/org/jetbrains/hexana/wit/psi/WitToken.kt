package org.jetbrains.hexana.wit.psi

import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wit.WitLanguage

class WitToken(val name: String) : IElementType(name, WitLanguage) {
    companion object {
        @JvmField val WHITESPACE = WitToken("whitespace")
        @JvmField val NEWLINE = WitToken("newline")
        @JvmField val LINE_COMMENT = WitToken("line_comment")
        @JvmField val BLOCK_COMMENT = WitToken("block_comment")
        @JvmField val DOT = WitToken("dot")
        @JvmField val COMMA = WitToken("comma")
        @JvmField val COLON = WitToken("colon")
        @JvmField val SEMI = WitToken("semi")
        @JvmField val LESS_THAN = WitToken("less_than")
        @JvmField val GREATER_THAN = WitToken("greater_than")
        @JvmField val OPEN_BRACE = WitToken("open_brace")
        @JvmField val CLOSE_BRACE = WitToken("close_brace")
        @JvmField val OPEN_PAREN = WitToken("open_paren")
        @JvmField val CLOSE_PAREN = WitToken("close_paren")
        @JvmField val EQUALS = WitToken("equals")
        @JvmField val AT = WitToken("at")
        @JvmField val UNDERSCORE = WitToken("underscore")
        @JvmField val ARROW = WitToken("arrow")
        @JvmField val IDENTIFIER = WitToken("identifier")
    }
}