package org.jetbrains.hexana.wit.psi.impl

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.elementType
import org.jetbrains.hexana.wit.psi.WitElementTypes
import org.jetbrains.hexana.wit.psi.WitEnumCase
import org.jetbrains.hexana.wit.psi.WitFuncItem
import org.jetbrains.hexana.wit.psi.WitIncludeNamesItem
import org.jetbrains.hexana.wit.psi.WitPackageItem
import org.jetbrains.hexana.wit.psi.WitRecordField
import org.jetbrains.hexana.wit.psi.WitTypeDeclaration
import org.jetbrains.hexana.wit.psi.WitUseNamesItem
import org.jetbrains.hexana.wit.psi.WitVariantCase

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
    fun getName(useNamesItem: WitUseNamesItem): String {
        return useNamesItem.node.findChildByType(WitElementTypes.IDENTIFIER)?.text ?: ""
    }

    @JvmStatic
    fun getName(includeNamesItem: WitIncludeNamesItem): String {
        return getNameIdentifier(includeNamesItem)?.text ?: ""
    }

    @JvmStatic
    fun getNameIdentifier(useNamesItem: WitUseNamesItem): PsiElement? {
        return useNamesItem.node.findChildByType(WitElementTypes.IDENTIFIER)?.psi
    }

    @JvmStatic
    fun getNameIdentifier(includeNamesItem: WitIncludeNamesItem): PsiElement? {
        return includeNamesItem.lastChild
    }

    @JvmStatic
    fun getTextOffset(useNamesItem: WitUseNamesItem): Int {
        return getNameIdentifier(useNamesItem)?.textOffset ?: useNamesItem.textRange.startOffset
    }

    @JvmStatic
    fun getTextOffset(includeNamesItem: WitIncludeNamesItem): Int {
        return getNameIdentifier(includeNamesItem)?.textOffset ?: includeNamesItem.textRange.startOffset
    }

    @JvmStatic
    fun getTextOffset(typeDeclaration: WitTypeDeclaration): Int {
        return getNameIdentifier(typeDeclaration)?.textOffset ?: typeDeclaration.textRange.startOffset
    }

    @JvmStatic
    fun getName(funcItem: WitFuncItem): String {
        return funcItem.identifier.text ?: ""
    }

    @JvmStatic
    fun getNameIdentifier(funcItem: WitFuncItem): PsiElement? {
        return funcItem.identifier
    }

    @JvmStatic
    fun getTextOffset(funcItem: WitFuncItem): Int {
        return funcItem.identifier.textOffset
    }

    @JvmStatic
    fun getName(enumCase: WitEnumCase): String {
        return enumCase.identifier.text ?: ""
    }

    @JvmStatic
    fun getNameIdentifier(enumCase: WitEnumCase): PsiElement? {
        return enumCase.identifier
    }

    @JvmStatic
    fun getTextOffset(enumCase: WitEnumCase): Int {
        return enumCase.identifier.textOffset
    }

    @JvmStatic
    fun getName(variantCase: WitVariantCase): String {
        return variantCase.identifier.text ?: ""
    }

    @JvmStatic
    fun getNameIdentifier(variantCase: WitVariantCase): PsiElement? {
        return variantCase.identifier
    }

    @JvmStatic
    fun getTextOffset(variantCase: WitVariantCase): Int {
        return variantCase.identifier.textOffset
    }

    @JvmStatic
    fun getName(recordField: WitRecordField): String {
        return recordField.identifier.text ?: ""
    }

    @JvmStatic
    fun getNameIdentifier(recordField: WitRecordField): PsiElement? {
        return recordField.identifier
    }

    @JvmStatic
    fun getTextOffset(recordField: WitRecordField): Int {
        return recordField.identifier.textOffset
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

    @JvmStatic
    fun createId(
        project: Project,
        newName: String
    ): PsiElement {
        val file = WitHandleManipulator.createDummyFile(project, "package $newName;")
        val id = SyntaxTraverser.psiTraverser(file).bfsTraversal().find { it.elementType == WitElementTypes.IDENTIFIER }
        return id!!
    }
}