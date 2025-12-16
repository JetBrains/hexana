package org.jetbrains.hexana.wit.psi

import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wit.WitLanguage

class WitToken(val name: String) : IElementType(name, WitLanguage) {
    companion object {
        @JvmField val WHITESPACE = WitToken("whitespace")
        @JvmField val NEWLINE = WitToken("newline")
        @JvmField val LINE_COMMENT = WitToken("line_comment")
        @JvmField val BLOCK_COMMENT = WitToken("block_comment")
    }
}