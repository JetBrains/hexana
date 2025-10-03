package org.jetbrains.hexana.wat.psi

import com.intellij.psi.tree.IElementType
import org.jetbrains.hexana.wat.WatLanguage


class WatToken(debugName: String) : IElementType(debugName, WatLanguage) {
    companion object {
        @JvmField val NUMBER = WatToken("number")
        @JvmField val WHITESPACE = WatToken("whitespace")
        @JvmField val NEWLINE = WatToken("newline")
        @JvmField val LINE_COMMENT = WatToken("line_comment")
        @JvmField val BLOCK_COMMENT = WatToken("block_comment")
        @JvmField val STRING = WatToken("string")
        @JvmField val IDENTIFIER = WatToken("identifier")
        @JvmField val ANNOTATION_ID = WatToken("annotation_id")
    }
}
