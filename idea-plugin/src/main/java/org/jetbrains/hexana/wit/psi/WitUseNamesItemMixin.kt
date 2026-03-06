package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ContributedReferenceHost
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.parentOfType

open class WitUseNamesItemMixin(node: ASTNode): WitPsiElement(node), ContributedReferenceHost, WitUseNamesItem {
    override fun getReferences(): Array<out PsiReference?> {
        val identifier = this.findChildByType<PsiElement>(WitElementTypes.IDENTIFIER)
        return if (identifier != null) arrayOf(WitUseNamesItemReference(this)) else emptyArray()
    }

    override fun setName(name: @NlsSafe String): PsiElement? {
        return null;
    }

    override fun getNameIdentifier(): PsiElement? {
        return lastChild;
    }
}

class WitUseNamesItemReference(element: WitUseNamesItem): PsiReferenceBase<WitUseNamesItem>(element) {
    override fun resolve(): PsiElement? {
        return WitResolver.resolveWitInterfaceMemberByRef(element, element.firstChild.text)
    }

    override fun getRangeInElement(): TextRange {
        return element.firstChild.textRangeInParent
    }
}