package org.jetbrains.hexana.wit.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentOfTypes
import com.intellij.psi.util.parents
import kotlin.sequences.forEach

object WitResolver {
    fun resolveWitPath(context: PsiElement, name: String): WitPackageItem? {
        context.parents(withSelf = true)
            .forEach {
                if (it is WitNestedPackageDefinition) {
                    it.packageItemList.singleOrNull { it.name == name }
                        ?.let { return it }
                }
                else if (it is WitPsiFile) {
                    it.children.filterIsInstance<WitPackageItem>().singleOrNull { it.name == name }
                        ?.let { return it }
                }
            }
        return null
    }

    fun resolveWitInterfaceMemberByRef(context: PsiElement, name: String): PsiElement? {
        val parent: PsiElement? = context.parentOfTypes(WitUseDeclaration::class,
            WitIncludeDeclaration::class)
        val path = when (parent) {
            is WitIncludeDeclaration -> {
                parent.usePath
            }
            is WitUseDeclaration -> {
                parent.usePath
            }
            else -> return null
        }
        val container = resolveWitPath(path, path.text) ?: return null
        if (container is WitInterfaceDefinition) {
            return container.typeDeclarationList.find { it.name == name }
                ?: container.funcItemList.find { it.identifier.text == name }
                ?: container.useDeclarationList.flatMap {
                    it.useNamesList.useNamesItemList
                }.find { it.lastChild.text == name }
        }
        return null
    }
}