package org.jetbrains.hexana.wat.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference

open class WatPsiElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiElement

class WatPsiIdentifierElement(node: ASTNode) : WatPsiElement(node) {
    override fun getReference(): PsiReference {
        return WatIdentifierReference(this)
    }
}


abstract class WatNameIdentifierOwner(node: ASTNode) : WatPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? {
        val result = children.firstOrNull() as? WatPsiIdentifierElement
        return result
    }

    override fun getName(): String? {
        return nameIdentifier?.text
    }

    override fun getTextOffset(): Int {
        return nameIdentifier?.textOffset ?: textRange.startOffset
    }

    override fun setName(name: @NlsSafe String): PsiElement? {
        return null
    }
}



class WatFuncElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatTableElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatTypeElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatGlobalElement(node: ASTNode) : WatNameIdentifierOwner(node)
