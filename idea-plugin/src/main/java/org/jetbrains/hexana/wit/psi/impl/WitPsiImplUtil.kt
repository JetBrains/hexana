package org.jetbrains.hexana.wit.psi.impl

import com.intellij.psi.PsiElement
import org.jetbrains.hexana.wit.psi.WitElementTypes
import org.jetbrains.hexana.wit.psi.WitTypeDeclaration

object WitPsiImplUtil {
    @JvmStatic
    fun getName(typeDeclaration: WitTypeDeclaration): String {
        return typeDeclaration.node.findChildByType(WitElementTypes.IDENTIFIER)?.text ?: ""
    }

    @JvmStatic
    fun getNameIdentifier(typeDeclaration: WitTypeDeclaration): PsiElement? {
        return typeDeclaration.node.findChildByType(WitElementTypes.IDENTIFIER)?.psi
    }

    @JvmStatic
    fun getTextOffset(typeDeclaration: WitTypeDeclaration): Int {
        return getNameIdentifier(typeDeclaration)?.textOffset ?: typeDeclaration.textRange.startOffset
    }

    @JvmStatic
    fun setName(typeDeclaration: WitTypeDeclaration, newName: String): WitTypeDeclaration {
        /*val identifierNode = typeDeclaration.node.findChildByType(WitElementTypes.IDENTIFIER) ?: return typeDeclaration
        val newIdentifierNode = identifierNode.replace(WitElementTypes.Factory.createIdentifier(newName))
        return typeDeclaration.replace(WitElementTypes.createTypeDeclaration(newIdentifierNode))*/
        return typeDeclaration //todo
    }
}