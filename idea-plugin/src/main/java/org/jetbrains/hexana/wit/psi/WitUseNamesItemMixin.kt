package org.jetbrains.hexana.wit.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.util.parentOfType
import org.jetbrains.hexana.wit.psi.impl.WitPsiImplUtil

open class WitUseNamesItemMixin(node: ASTNode): WitPsiElement(node), WitUseNamesItem {
    override fun getReference(): PsiReference? {
        val identifier = this.findChildByType<PsiElement>(WitElementTypes.IDENTIFIER)
        return if (identifier != null) WitUseNamesItemReference(this) else null
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

class WitUseNamesItemReference(element: WitUseNamesItem): PsiReferenceBase<WitUseNamesItem>(element) {
    override fun resolve(): PsiElement? {
        return WitResolver.resolveWitInterfaceMemberByRef(element, element.firstChild.text)
    }

    override fun getRangeInElement(): TextRange {
        return element.firstChild.textRangeInParent
    }

    override fun getVariants(): Array<out Any?> {
        val path = element.parentOfType<WitUseDeclaration>()?.usePath ?: return emptyArray()
        val element = WitResolver.resolveWitPath(path, path.text) ?: return emptyArray()
        return (element as? WitInterfaceDefinition)?.let {
            it.typeDeclarationList.map { it.name } +
                    it.funcItemList.map { it.name } +
                    it.useDeclarationList.flatMap {
                        it.useNamesList?.useNamesItemList?.map { it.name } ?: emptyList()
                    }
        }.orEmpty().filterNotNull().toTypedArray()
    }
}