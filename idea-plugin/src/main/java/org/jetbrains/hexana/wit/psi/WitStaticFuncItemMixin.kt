package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import org.jetbrains.hexana.wit.psi.impl.WitPsiImplUtil

open class WitStaticFuncItemMixin(node: ASTNode): WitPsiElement(node), WitStaticFuncItem {
    override fun setName(name: @NlsSafe String): PsiElement? {
        val identifier = nameIdentifier ?: return this
        identifier.replace(WitPsiImplUtil.createId(project, name))
        return this
    }

    override fun getFuncType(): WitFuncType {
        return findNotNullChildByClass(WitFuncType::class.java)
    }

    override fun getIdentifier(): PsiElement {
        return findNotNullChildByType(WitElementTypes.IDENTIFIER)
    }

    override fun getNameIdentifier(): PsiElement? {
        return findNotNullChildByType(WitElementTypes.IDENTIFIER)
    }

    override fun getName(): String? {
        return identifier.text
    }

    override fun getTextOffset(): Int {
        return identifier.textOffset
    }
}