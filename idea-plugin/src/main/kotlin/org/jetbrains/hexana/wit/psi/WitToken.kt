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
        @JvmField val NUMBER = WitToken("number")

        @JvmField val ASYNC = WitToken("async")
        @JvmField val BLOB = WitToken("blob")
        @JvmField val BORROW = WitToken("borrow")
        @JvmField val CONSTRUCTOR = WitToken("constructor")
        @JvmField val ENUM = WitToken("enum")
        @JvmField val EXPORT = WitToken("export")
        @JvmField val FLAGS = WitToken("flags")
        @JvmField val FUNC = WitToken("func")
        @JvmField val FUTURE = WitToken("future")
        @JvmField val IMPORT = WitToken("import")
        @JvmField val INCLUDE = WitToken("include")
        @JvmField val INTERFACE = WitToken("interface")
        @JvmField val LIST = WitToken("list")
        @JvmField val OPTION = WitToken("option")
        @JvmField val PACKAGE = WitToken("package")
        @JvmField val RECORD = WitToken("record")
        @JvmField val RESOURCE = WitToken("resource")
        @JvmField val RESTRICTED = WitToken("restricted")
        @JvmField val RESULT = WitToken("result")
        @JvmField val STATIC = WitToken("static")
        @JvmField val STREAM = WitToken("stream")
        @JvmField val TUPLE = WitToken("tuple")
        @JvmField val TYPE = WitToken("type")
        @JvmField val USE = WitToken("use")
        @JvmField val VARIANT = WitToken("variant")
        @JvmField val WORLD = WitToken("world")

        @JvmField
        val KEYWORDS = arrayOf<WitToken>(
            ASYNC, BLOB, BORROW, CONSTRUCTOR, ENUM, EXPORT, FLAGS, FUNC, FUTURE,
            IMPORT, INCLUDE, INTERFACE, LIST, OPTION, PACKAGE, RECORD, RESOURCE,
            RESTRICTED, RESULT, STATIC, STREAM, TUPLE, TYPE, USE, VARIANT, WORLD,
        )
    }
}