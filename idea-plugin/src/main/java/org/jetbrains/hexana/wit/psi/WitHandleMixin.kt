package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.psi.ContributedReferenceHost
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.parentOfType

open class WitHandleMixin(node: ASTNode): WitPsiElement(node), ContributedReferenceHost, WitHandle {
    override fun getReferences(): Array<out PsiReference?> {
        return arrayOf(WitHandleReference(this))
    }

    override fun getIdentifier(): PsiElement {
        return findChildByType(WitElementTypes.IDENTIFIER)!!
    }
}

class WitHandleReference(element: WitHandle): PsiReferenceBase<WitHandle>(element) {
    override fun resolve(): PsiElement? {
        return element.parentOfType<WitInterfaceDefinition>()
            ?.typeDeclarationList
            ?.find { it.name == element.text }
    }
}