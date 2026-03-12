package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase

open class WitHandleMixin(node: ASTNode): WitPsiElement(node), WitHandle {
    override fun getReference(): PsiReference? {
        return WitHandleReference(this)
    }

    override fun getIdentifier(): PsiElement {
        return findChildByType(WitElementTypes.IDENTIFIER)!!
    }
}

class WitHandleReference(element: WitHandle): PsiReferenceBase<WitHandle>(element,
     element.identifier.textRangeInParent) {
    override fun resolve(): PsiElement? {
        return WitResolver.resolveHandle(element, element.identifier.text)
    }
}