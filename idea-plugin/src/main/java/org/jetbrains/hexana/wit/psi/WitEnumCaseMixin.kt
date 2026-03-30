package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import org.jetbrains.hexana.wit.psi.impl.WitPsiImplUtil

open class WitEnumCaseMixin(node: ASTNode): WitPsiElement(node), WitEnumCase {
    override fun getIdentifier(): PsiElement {
        return findNotNullChildByType(WitElementTypes.IDENTIFIER)
    }

    override fun setName(name: @NlsSafe String): PsiElement? {
        val identifier = getIdentifier()
        identifier.replace(WitPsiImplUtil.createId(project, name))
        return this
    }

    override fun getNameIdentifier(): PsiElement? {
        return identifier
    }

    override fun getName(): String? {
        return identifier.text
    }

    override fun getTextOffset(): Int {
        return identifier.textOffset
    }
}
