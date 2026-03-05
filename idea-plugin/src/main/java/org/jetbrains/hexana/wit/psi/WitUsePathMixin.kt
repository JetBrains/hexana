package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.ContributedReferenceHost
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parents

open class WitUsePathMixin(node: ASTNode): WitPsiElement(node), ContributedReferenceHost, WitUsePath {
    override fun getSemver(): WitSemver? {
        return findChildByClass(WitSemver::class.java)
    }

    override fun getReferences(): Array<out PsiReference?> {
        return if (identifier != null) arrayOf(WitPathReference(this)) else emptyArray()
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