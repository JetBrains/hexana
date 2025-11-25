package org.jetbrains.hexana.wat.psi


import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.ILazyParseableElementType
import org.jetbrains.hexana.wat.WatLanguage

class WatElementType(val name: String) : IElementType(name, WatLanguage) {
    companion object {
        @JvmField val SEXPR = WatElementType("SEXPR")
        @JvmField val MODULE = WatElementType("MODULE")
        @JvmField val TYPE = WatElementType("TYPE")
        @JvmField val IMPORT = WatElementType("IMPORT")
        @JvmField val EXPORT = WatElementType("EXPORT")
        @JvmField val GLOBAL = WatElementType("GLOBAL")
        @JvmField val MEMORY = WatElementType("MEMORY")
        @JvmField val DATA = WatElementType("DATA")
        @JvmField val TABLE = WatElementType("TABLE")
        @JvmField val ELEM = WatElementType("ELEM")
        @JvmField val TAG = WatElementType("TAG")
        @JvmField val FUNC = WatElementType("FUNC")
        @JvmField val FUNC_BODY = WatLazyElementType("FUNC_BODY")
        @JvmField val CALL = WatElementType("CALL")
        @JvmField val CALL_INDIRECT = WatElementType("CALL_INDIRECT")
        @JvmField val GLOBAL_SET = WatElementType("GLOBAL_SET")
        @JvmField val GLOBAL_GET = WatElementType("GLOBAL_GET")
        @JvmField val THROW = WatElementType("THROW")
        @JvmField val CATCH = WatElementType("CATCH")
        @JvmField val LOCAL = WatElementType("LOCAL")
        @JvmField val PARAM = WatElementType("PARAM")
        @JvmField val IDENTIFIER = WatElementType("IDENTIFIER")
        @JvmField val NUMBER = WatElementType("NUMBER")
        @JvmField val BLOCK = WatElementType("BLOCK")
        @JvmField val LOOP = WatElementType("LOOP")
        @JvmField val IF = WatElementType("IF")
    }
}

class WatLazyElementType(val name: String) : ILazyParseableElementType(name, WatLanguage)