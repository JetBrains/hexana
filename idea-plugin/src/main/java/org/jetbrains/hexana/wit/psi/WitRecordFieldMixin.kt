package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import org.jetbrains.hexana.wit.psi.impl.WitPsiImplUtil

open class WitRecordFieldMixin(node: ASTNode): WitPsiElement(node), WitRecordField {
    override fun getIdentifier(): PsiElement {
        return findNotNullChildByType(WitElementTypes.IDENTIFIER)
    }

    override fun getTypeUsage(): WitTypeUsage {
        return findNotNullChildByClass(WitTypeUsage::class.java)
    }

    override fun setName(name: @NlsSafe String): PsiElement? {
        val identifier = getIdentifier()
        identifier.replace(WitPsiImplUtil.createId(project, name))
        return this
    }

    override fun getNameIdentifier(): PsiElement? {
        return getIdentifier()
    }

    override fun getName(): String? {
        return getIdentifier().text
    }

    override fun getTextOffset(): Int {
        return getIdentifier().textOffset
    }
}
