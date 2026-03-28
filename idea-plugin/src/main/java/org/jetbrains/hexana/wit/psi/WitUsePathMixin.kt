package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.util.indexing.FileBasedIndex
import org.jetbrains.hexana.WasmIndexId
import org.jetbrains.hexana.wit.WitIndexId

open class WitUsePathMixin(node: ASTNode): WitPsiElement(node), WitUsePath {
    override fun getSemver(): WitSemver? {
        return findChildByClass(WitSemver::class.java)
    }

    override fun getReference(): PsiReference? {
        return if (identifier != null) WitPathReference(this) else null
    }

    override fun getIdentifier(): PsiElement? {
        return findChildByType(WitElementTypes.IDENTIFIER)
    }
}

class WitPathReference(element: WitUsePath): PsiReferenceBase<WitUsePath>(element) {
    override fun resolve(): PsiElement? {
       return WitResolver.resolveWitPath(element, element.text)
    }

    override fun getVariants(): Array<out Any> {
        return (FileBasedIndex.getInstance().getAllKeys(WitIndexId, element.project)
            + FileBasedIndex.getInstance().getAllKeys(WasmIndexId, element.project))
            .toSet().toTypedArray()
    }
}