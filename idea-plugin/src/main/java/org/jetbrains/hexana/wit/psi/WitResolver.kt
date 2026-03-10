package org.jetbrains.hexana.wit.psi

import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentOfTypes
import com.intellij.psi.util.parents
import org.jetbrains.hexana.wit.WitComponentIndex
import org.jetbrains.hexana.wit.WitDeclarationType
import kotlin.sequences.forEach

object WitResolver {
    fun resolveWitPath(context: PsiElement, name: String): PsiElement? {
        val packageName = if (name.contains("/")) (name.substringBeforeLast("/") + name.indexOfLast { it == '@' }.let {
            if (it == -1) "" else name.substring(it)
        }) else context.containingFile?.children?.filterIsInstance<WitPackageDefinition>()?.firstOrNull()?.packageName?.text

        val interfaceName = name.substringAfterLast("/").substringBeforeLast("@")

        if (packageName != null) {
            val defs = mutableListOf<PsiElement>()
            WitComponentIndex.processPackageDeclarations(
                packageName, GlobalSearchScope.allScope(context.project),
                { file, decl ->
                    val pkg = decl.singleOrNull { it.type == WitDeclarationType.PACKAGE }
                    if (pkg != null) {
                        file.findPsiFile(context.project)?.children?.singleOrNull {
                            it is WitPackageDefinition && it.packageName.text == packageName
                        }?.let {
                            findPackageItemByName(it.parent, interfaceName)?.let {
                                defs.add(it)
                            }
                        }
                    }
                    return@processPackageDeclarations true
                })

            if (defs.isNotEmpty()) {
                return defs.first()
            }
        }

        context.parents(withSelf = true)
            .forEach {
                findPackageItemByName(it, name)?.let { return it }
            }
        return null
    }

    private fun findPackageItemByName(
        element: PsiElement,
        name: String
    ): WitPackageItem? {
        if (element is WitNestedPackageDefinition) {
            element.packageItemList.singleOrNull { it.name == name }
                ?.let { return it }
        } else if (element is WitPsiFile) {
            element.children.filterIsInstance<WitPackageItem>().singleOrNull { it.name == name }
                ?.let { return it }
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
            return resolveHandle(container, name)
        }
        return null
    }

    fun resolveHandle(
        handle: PsiElement,
        name: String
    ): PsiElement? {
        val container = handle.parentOfType<WitInterfaceDefinition>(true)
        if (container != null) {
            return (container.typeDeclarationList.find { it.name == name }
                ?: container.funcItemList.find { it.identifier.text == name }
                ?: container.useDeclarationList.flatMap {
                    it.useNamesList.useNamesItemList
                }.find { it.lastChild.text == name })
        }
        val externType = handle.parentOfType<WitExternType>(true)
        if (externType != null) {
            return (externType.typeDeclarationList.find { it.name == name }
                ?: externType.funcItemList.find { it.identifier.text == name }
                ?: externType.useDeclarationList.flatMap {
                    it.useNamesList.useNamesItemList
                }.find { it.lastChild.text == name })
        }
        return null
    }
}