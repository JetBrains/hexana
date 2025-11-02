package org.jetbrains.hexana.wat.psi

import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
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
            val tableName = getTableName(parent) ?: return null
            if (tableName == identifier) {
                return resolveTable(identifier.containingFile, identifier.text)
            } else {
                val table = resolveTable(identifier.containingFile, tableName.text)
                if (table?.node?.findChildByType(WatToken.FUNCREF) != null) {
                    return resolveFunc(identifier.containingFile, identifier.text)
                }

            }
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
                val importedFunc = child.children.find { it.elementType == WatElementType.FUNC }
                val nameElement = importedFunc?.children?.find { it.elementType == WatElementType.IDENTIFIER }
                if (nameElement?.textMatches(funcName) == true) {
                    return importedFunc
                }
            }
            if (child.elementType == WatElementType.FUNC) {
                val nameElement = child.children.find { it.elementType == WatElementType.IDENTIFIER }
                if (nameElement?.textMatches(funcName) == true) {
                    return child
                }
            }
        }
        return null
    }

    fun getTableName(elemOrTable: PsiElement?): WatPsiIdentifierElement? {
        return elemOrTable?.children?.firstOrNull() as? WatPsiIdentifierElement
    }

    fun resolveTable(file: PsiFile?, tableName: String): PsiElement? {
        val module = getModule(file) ?: return null
        for (child in module.children) {
            if (child.elementType == WatElementType.TABLE) {
                val nameElement = getTableName(child)
                if (nameElement?.textMatches(tableName) == true) {
                    return child
                }
            }
        }
        return null
    }

    fun getModule(file: PsiFile?): PsiElement? {
        return file?.children?.firstOrNull { it.elementType == WatElementType.MODULE }
    }
}