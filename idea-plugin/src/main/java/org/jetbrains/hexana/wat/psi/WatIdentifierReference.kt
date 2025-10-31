package org.jetbrains.hexana.wat.psi

import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
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
            val refName = identifier.text
            val file = identifier.containingFile
            val module = file.children.firstOrNull { it.elementType == WatElementType.MODULE } ?: return null
            for (child in module.children) {
                if (child.elementType == WatElementType.IMPORT) {
                    val importedFunc = child.children.find { it.elementType == WatElementType.FUNC }
                    val funcName = importedFunc?.children?.find { it.elementType == WatElementType.IDENTIFIER }
                    if (funcName?.textMatches(refName) == true) {
                        return funcName
                    }
                }
                if (child.elementType == WatElementType.FUNC) {
                    val funcName = child.children.find { it.elementType == WatElementType.IDENTIFIER }
                    if (funcName != null && funcName.textMatches(refName)) {
                        return funcName
                    }
                }
            }
            return null
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
}