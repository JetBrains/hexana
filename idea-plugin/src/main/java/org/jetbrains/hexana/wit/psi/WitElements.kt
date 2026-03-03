package org.jetbrains.hexana.wit.psi

import com.intellij.psi.tree.TokenSet

object WitElements {
    @JvmField
    val COMMENTS_SET = TokenSet.create(WitElementTypes.LINE_COMMENT, WitElementTypes.BLOCK_COMMENT)

    @JvmField
    val KEYWORDS = arrayOf(
        WitElementTypes.ASYNC, WitElementTypes.BLOB,
        WitElementTypes.BORROW, WitElementTypes.CONSTRUCTOR, WitElementTypes.ENUM,
        WitElementTypes.EXPORT, WitElementTypes.FLAGS, WitElementTypes.FUNC, WitElementTypes.FUTURE,
        WitElementTypes.IMPORT, WitElementTypes.INCLUDE,
        WitElementTypes.INTERFACE, WitElementTypes.LIST, WitElementTypes.OPTION,
        WitElementTypes.PACKAGE, WitElementTypes.RECORD, WitElementTypes.RESOURCE,
        WitElementTypes.RESTRICTED, WitElementTypes.RESULT,
        WitElementTypes.STATIC, WitElementTypes.STREAM, WitElementTypes.TUPLE,
        WitElementTypes.TYPE, WitElementTypes.USE, WitElementTypes.VARIANT, WitElementTypes.WORLD,
    )
}