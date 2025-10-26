package org.jetbrains.hexana.wat.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference

open class WatPsiElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiElement

class WatPsiIdentifierElement(node: ASTNode) : WatPsiElement(node) {
    override fun getReference(): PsiReference {
        return WatIdentifierReference(this)
    }
}
