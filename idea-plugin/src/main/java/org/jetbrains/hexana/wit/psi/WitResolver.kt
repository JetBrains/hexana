package org.jetbrains.hexana.wit.psi

import com.intellij.openapi.vfs.findPsiFile
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parentOfTypes
import com.intellij.psi.util.parents
import com.intellij.util.text.SemVer
import org.jetbrains.hexana.wit.WitComponentIndex
import org.jetbrains.hexana.wit.WitDeclarationType
import org.jetbrains.hexana.wit.WitIndexId
import org.jetbrains.hexana.wit.processIndexEntries

object WitResolver {
    private const val WASI_PREFIX = "wasi:"

    fun splitNameAndVersion(name: String): Pair<String, String> {
        return Pair(name.substringBeforeLast("@"), name.substringAfterLast("@"))
    }

    fun resolveWitPath(context: PsiElement, name: String): PsiElement? {
        val packageNameAndVersion = adjustPackageName(name, context)

        val interfaceName = name.substringAfterLast("/").substringBeforeLast("@")

        if (packageNameAndVersion != null) {
            val (packageName, packageVersion) = splitNameAndVersion(packageNameAndVersion)
            val defs = mutableListOf<PsiElement>()
            processIndexEntries(
                WitIndexId, packageName, GlobalSearchScope.everythingScope(context.project),
                { file, decl ->
                    val pkg = decl.singleOrNull { it.type == WitDeclarationType.PACKAGE }
                    if (pkg != null) {
                        file.findPsiFile(context.project)?.children?.filterIsInstance<WitPackageDefinition>()?.singleOrNull {
                            val (name, version) = splitNameAndVersion(it.packageName.text)
                            name == packageName
                                    && matchesVersions(version, packageVersion, name.startsWith(WASI_PREFIX))
                        }?.let {
                            findPackageItemByName(it.parent, interfaceName)?.let {
                                defs.add(it)
                            }
                        }
                    }
                    return@processIndexEntries true
                })

            if (defs.isNotEmpty()) {
                return defs.first()
            }
        }

        context.parents(withSelf = true)
            .forEach {
                findPackageItemByName(it, name)?.let { return it }
            }

        context.containingFile.parent?.files?.firstOrNull {
            it.name == "$name.wit"
        }?.let {
            findPackageItemByName(it, name)?.let { return it }
        }
        return null
    }

    fun adjustPackageName(name: String, context: PsiElement): String? =
        if (name.contains("/")) (name.substringBeforeLast("/") + name.indexOfLast { it == '@' }.let {
            if (it == -1) "" else name.substring(it)
        }) else context.containingFile?.children?.filterIsInstance<WitPackageDefinition>()
            ?.firstOrNull()?.packageName?.text

    fun matchesVersions(actualVersion: String, requestedVersion: String, isBuiltIn: Boolean): Boolean {
        if (!isBuiltIn) return true // todo review this
        // we must match built-ins exactly by version, so that 0.2 resolves to 0.2 etc
        val actualVer = SemVer.parseFromText(actualVersion) ?: return true
        val requestedVer = SemVer.parseFromText(requestedVersion) ?: return true
        return actualVer.major == requestedVer.major
            && actualVer.minor == requestedVer.minor
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
                }.find { it.lastChild.text == name }?.let {
                    it.references.singleOrNull()?.resolve() ?: it
                })
        }
        val externType = handle.parentOfType<WitExternType>(true)
        if (externType != null) {
            return (externType.typeDeclarationList.find { it.name == name }
                ?: externType.funcItemList.find { it.identifier.text == name }
                ?: externType.useDeclarationList.flatMap {
                    it.useNamesList.useNamesItemList
                }.find { it.lastChild.text == name }?.let {
                    it.references.singleOrNull()?.resolve() ?: it
                })
        }
        return null
    }
}