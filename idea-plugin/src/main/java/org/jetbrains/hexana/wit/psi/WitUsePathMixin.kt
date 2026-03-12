package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase

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
}