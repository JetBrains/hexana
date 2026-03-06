package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ContributedReferenceHost
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase

open class WitIncludeNamesItemMixin(node: ASTNode): WitPsiElement(node), ContributedReferenceHost, WitIncludeNamesItem {
    override fun getReferences(): Array<out PsiReference?> {
        val identifier = this.findChildByType<PsiElement>(WitElementTypes.IDENTIFIER)
        return if (identifier != null) arrayOf(WitIncludeNamesItemReference(this)) else emptyArray()
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