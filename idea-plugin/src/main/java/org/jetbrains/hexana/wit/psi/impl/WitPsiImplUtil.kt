package org.jetbrains.hexana.wit.psi.impl

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.elementType
import org.jetbrains.hexana.wit.psi.WitElementTypes
import org.jetbrains.hexana.wit.psi.WitPackageItem
import org.jetbrains.hexana.wit.psi.WitTypeDeclaration

object WitPsiImplUtil {
    @JvmStatic
    fun getName(typeDeclaration: WitTypeDeclaration): String {
        return typeDeclaration.node.findChildByType(WitElementTypes.IDENTIFIER)?.text ?: ""
    }

    @JvmStatic
    fun getName(packageItem: WitPackageItem): String {
        return packageItem.node.findChildByType(WitElementTypes.IDENTIFIER)?.text ?: ""
    }

    @JvmStatic
    fun getNameIdentifier(typeDeclaration: WitTypeDeclaration): PsiElement? {
        return typeDeclaration.node.findChildByType(WitElementTypes.IDENTIFIER)?.psi
    }

    @JvmStatic
    fun getNameIdentifier(packageItem: WitPackageItem): PsiElement? {
        return packageItem.node.findChildByType(WitElementTypes.IDENTIFIER)?.psi
    }

    @JvmStatic
    fun getTextOffset(typeDeclaration: WitTypeDeclaration): Int {
        return getNameIdentifier(typeDeclaration)?.textOffset ?: typeDeclaration.textRange.startOffset
    }

    @JvmStatic
    fun getTextOffset(packageItem: WitPackageItem): Int {
        return getNameIdentifier(packageItem)?.textOffset ?: packageItem.textRange.startOffset
    }

    @JvmStatic
    fun setName(packageItem: WitPackageItem, newName: String): WitPackageItem {
        val name = packageItem.nameIdentifier ?: return packageItem
        name.replace(createId(packageItem.project, newName))
        return packageItem
    }

    @JvmStatic
    fun setName(typeDeclaration: WitTypeDeclaration, newName: String): WitTypeDeclaration {
        val name = typeDeclaration.nameIdentifier ?: return typeDeclaration
        name.replace(createId(typeDeclaration.project, newName))
        return typeDeclaration
    }

    private fun createId(
        project: Project,
        newName: String
    ): PsiElement {
        val file = WitHandleManipulator.createDummyFile(project, "package $newName;")
        val id = SyntaxTraverser.psiTraverser(file).bfsTraversal().find { it.elementType == WitElementTypes.IDENTIFIER }
        return id!!
    }
}