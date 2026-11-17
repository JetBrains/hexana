package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import org.jetbrains.hexana.wit.psi.impl.WitPsiImplUtil

open class WitIncludeNamesItemMixin(node: ASTNode): WitPsiElement(node), WitIncludeNamesItem {
    override fun getReference(): PsiReference? {
        val identifier = this.findChildByType<PsiElement>(WitElementTypes.IDENTIFIER)
        return if (identifier != null) WitIncludeNamesItemReference(this) else null
    }

    override fun setName(name: @NlsSafe String): PsiElement? {
        val identifier = nameIdentifier ?: return this
        identifier.replace(WitPsiImplUtil.createId(project, name))
        return this
    }

    override fun getNameIdentifier(): PsiElement? {
        return WitPsiImplUtil.getNameIdentifier(this)
    }

    override fun getName(): String? {
        return WitPsiImplUtil.getName(this)
    }

    override fun getTextOffset(): Int {
        return WitPsiImplUtil.getTextOffset(this)
    }
}

class WitIncludeNamesItemReference(element: WitIncludeNamesItem): PsiReferenceBase<WitIncludeNamesItem>(element) {
    override fun resolve(): PsiElement? {
        return WitResolver.resolveWitInterfaceMemberByRef(element, element.firstChild.text)
    }

    override fun getRangeInElement(): TextRange {
        return element.firstChild.textRangeInParent
    }
}