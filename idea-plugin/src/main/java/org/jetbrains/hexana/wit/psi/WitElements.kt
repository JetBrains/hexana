package org.jetbrains.hexana.wit.psi

import com.intellij.psi.tree.TokenSet

object WitElements {
    @JvmField
    val CONTAINERS = TokenSet.create(WitElementTypes.INTERFACE_DEFINITION,
        WitElementTypes.PACKAGE_DEFINITION, WitElementTypes.WORLD_DEFINITION,
        WitElementTypes.ENUM_DEFINITION, WitElementTypes.RECORD_DEFINITION, WitElementTypes.VARIANT_DEFINITION,
        WitElementTypes.FLAGS_DEFINITION, WitElementTypes.NESTED_PACKAGE_DEFINITION, WitElementTypes.RESOURCE_DEFINITION,
    )

    @JvmField
    val CONTAINER_KEYWORDS = TokenSet.create(WitElementTypes.INTERFACE, WitElementTypes.PACKAGE, WitElementTypes.WORLD,
        WitElementTypes.ENUM, WitElementTypes.RECORD, WitElementTypes.VARIANT, WitElementTypes.FLAGS, WitElementTypes.RESOURCE)

    @JvmField
    val GATES = TokenSet.create(WitElementTypes.SINCE_GATE, WitElementTypes.UNSTABLE_GATE, WitElementTypes.DEPRECATED_GATE)

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