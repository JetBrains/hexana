package org.jetbrains.hexana.wat.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import org.jetbrains.hexana.wat.WatNavigation


open class WatPsiElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiElement

class WatPsiIdentifierElement(node: ASTNode) : WatPsiElement(node) {
    override fun getReference(): PsiReference {
        return WatReference(this)
    }
}

class WatPsiNumberElement(node: ASTNode) : WatPsiElement(node) {
    override fun getReference(): PsiReference {
        return WatReference(this)
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

    override fun navigate(requestFocus: Boolean) {
        if (!WatNavigation.navigateInWatEditor(containingFile.virtualFile, textOffset)) {
            super.navigate(requestFocus)
        }
    }
}

/**
 * [WatFunctionLocalElement] is a marker for elements which can be referenced
 * only in a function.
 */
interface WatFunctionLocalElement

class WatFuncElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatTableElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatTypeElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatGlobalElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatTagElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatLocalElement(node: ASTNode) : WatNameIdentifierOwner(node), WatFunctionLocalElement

class WatParamElement(node: ASTNode) : WatNameIdentifierOwner(node), WatFunctionLocalElement

open class WatLabelOwner(node: ASTNode) : WatNameIdentifierOwner(node), WatFunctionLocalElement

class WatBlockElement(node: ASTNode) : WatLabelOwner(node)

class WatLoopElement(node: ASTNode) : WatLabelOwner(node)

class WatIfElement(node: ASTNode) : WatLabelOwner(node)

class WatFuncBodyElement(node: ASTNode) : WatPsiElement(node)
