package org.jetbrains.hexana.wit.psi.impl

import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.elementType
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
        val name = typeDeclaration.nameIdentifier ?: return typeDeclaration
        val file = WitHandleManipulator.createDummyFile(typeDeclaration.project, "package $newName;")
        val id = SyntaxTraverser.psiTraverser(file).bfsTraversal().find { it.elementType == WitElementTypes.IDENTIFIER }
        name.replace(id!!)
        return typeDeclaration
    }
}