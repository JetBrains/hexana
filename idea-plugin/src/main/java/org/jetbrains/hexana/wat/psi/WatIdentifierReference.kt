package org.jetbrains.hexana.wat.psi

import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType

class WatIdentifierReference(
    val identifier: WatPsiIdentifierElement,
) : PsiReference {

    override fun getElement(): PsiElement {
        return identifier
    }

    override fun getRangeInElement(): TextRange {
        return TextRange.from(0, identifier.textRange.length)
    }

    override fun resolve(): PsiElement? {
        val parent = identifier.parent ?: return null
        if (parent.elementType == WatElementType.CALL) {
            return resolveFunc(identifier.containingFile, identifier.text)
        }
        if (parent.elementType == WatElementType.FUNC && parent.parent.elementType == WatElementType.EXPORT) {
            return resolveFunc(identifier.containingFile, identifier.text)
        }
        if (parent.elementType == WatElementType.ELEM) {
            val tableName = getElementTableName(parent) ?: return null
            if (tableName == identifier) {
                return resolveTable(identifier.containingFile, identifier.text)
            } else {
                val table = resolveTable(identifier.containingFile, tableName.text)
                if (table?.node?.findChildByType(WatToken.FUNCREF) != null) {
                    return resolveFunc(identifier.containingFile, identifier.text)
                }

            }
        }
        if (parent.elementType == WatElementType.TYPE) {
            val grandParent = parent.parent
            if (grandParent?.elementType == WatElementType.CALL_INDIRECT) {
                return resolveType(identifier.containingFile, identifier.text)
            }
        }
        if (parent.elementType == WatElementType.GLOBAL_GET || parent.elementType == WatElementType.GLOBAL_SET) {
            return resolveGlobal(identifier.containingFile, identifier.text)
        }
        return null
    }

    override fun getCanonicalText(): @NlsSafe String {
        return element.text
    }

    override fun handleElementRename(newElementName: String): PsiElement? {
        return null
    }

    override fun bindToElement(element: PsiElement): PsiElement? {
        return null
    }

    override fun isReferenceTo(element: PsiElement): Boolean {
        val resolveResult = resolve()
        return resolveResult == element
    }

    override fun isSoft(): Boolean {
        return false
    }

    fun resolveFunc(file: PsiFile?, funcName: String): PsiElement? {
        val module = getModule(file) ?: return null
        for (child in module.children) {
            if (child.elementType == WatElementType.IMPORT) {
                val importedFunc = PsiTreeUtil.findChildOfType(child, WatFuncElement::class.java)
                val nameElement = importedFunc?.nameIdentifier
                if (nameElement?.textMatches(funcName) == true) {
                    return importedFunc
                }
            }
            if (child is WatFuncElement) {
                val nameElement = child.nameIdentifier
                if (nameElement?.textMatches(funcName) == true) {
                    return child
                }
            }
        }
        return null
    }

    fun getElementTableName(elemOrTable: PsiElement?): WatPsiIdentifierElement? {
        return elemOrTable?.children?.firstOrNull() as? WatPsiIdentifierElement
    }

    fun resolveTable(file: PsiFile?, tableName: String): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithName<WatTableElement>(module, tableName)
    }

    fun getModule(file: PsiFile?): PsiElement? {
        return file?.children?.firstOrNull { it.elementType == WatElementType.MODULE }
    }

    fun resolveType(file: PsiFile?, typeName: String): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithName<WatTypeElement>(module, typeName)
    }

    fun resolveGlobal(file: PsiFile?, globalName: String): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithName<WatGlobalElement>(module, globalName)
    }

    inline fun <reified T : PsiNameIdentifierOwner> findChildWithName(parent: PsiElement?, name: String): T? {
        if (parent == null) {
            return null
        }
        for (child in parent.children) {
            if (child is T) {
                val identifier = child.nameIdentifier
                if (identifier?.textMatches(name) == true) {
                    return child
                }
            }
        }
        return null
    }
}