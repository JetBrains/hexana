package org.jetbrains.hexana.wat.psi

import com.intellij.lang.tree.util.children
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType

class WatReference(
    val nameOrIndex: WatPsiElement,
) : PsiReference {
    override fun getElement(): PsiElement {
        return nameOrIndex
    }

    override fun getRangeInElement(): TextRange {
        return TextRange.from(0, nameOrIndex.textRange.length)
    }

    override fun resolve(): PsiElement? {
        val parent = nameOrIndex.parent
        if (parent.elementType == WatElementType.CALL) {
            return resolveFunc(nameOrIndex.containingFile, nameOrIndex)
        }
        if (parent.elementType == WatElementType.FUNC && parent.parent.elementType == WatElementType.EXPORT) {
            return resolveFunc(nameOrIndex.containingFile, nameOrIndex)
        }
        if (parent.elementType == WatElementType.ELEM) {
            val tableNameOrIndex = getElementTableNameOrIndex(parent) ?: return null
            if (tableNameOrIndex == nameOrIndex) {
                return resolveTable(nameOrIndex.containingFile, nameOrIndex)
            }  else {
                val table = resolveTable(nameOrIndex.containingFile, tableNameOrIndex)
                if (table?.node?.findChildByType(WatToken.FUNCREF) != null) {
                    return resolveFunc(nameOrIndex.containingFile, nameOrIndex)
                }
            }
            return null
        }
        if (parent.elementType == WatElementType.TYPE) {
            val grandParent = parent.parent
            if (grandParent?.elementType == WatElementType.CALL_INDIRECT ||
                PsiTreeUtil.getParentOfType(grandParent, WatFuncElement::class.java) != null) {
                return resolveType(nameOrIndex.containingFile, nameOrIndex)
            }
            return null
        }
        if (parent.elementType == WatElementType.GLOBAL_GET || parent.elementType == WatElementType.GLOBAL_SET) {
            return resolveGlobal(nameOrIndex.containingFile, nameOrIndex)
        }
        if (parent.elementType == WatElementType.THROW || parent.elementType == WatElementType.CATCH) {
            return resolveTag(nameOrIndex.containingFile, nameOrIndex)
        }
        val exprType = getExprToken(parent)
        if (exprType == WatToken.LOCAL_SET || exprType == WatToken.LOCAL_GET || exprType == WatToken.LOCAL_TEE) {
            return resolveLocal(nameOrIndex)
        }
        if (exprType == WatToken.BR || exprType == WatToken.BR_IF || exprType == WatToken.BR_TABLE ||
            exprType == WatToken.BR_ON_NULL || exprType == WatToken.BR_ON_NON_NULL ||
            exprType == WatToken.BR_ON_CAST || exprType == WatToken.BR_ON_CAST_FAIL) {
            return resolveLabel(nameOrIndex)
        }
        return null
    }

    override fun getCanonicalText(): @NlsSafe String {
        return nameOrIndex.text
    }

    override fun handleElementRename(newElementName: String): PsiElement? {
        return null
    }

    override fun bindToElement(element: PsiElement): PsiElement? {
        return null
    }

    override fun isReferenceTo(element: PsiElement): Boolean {
        val target = resolve()
        return target == element
    }

    override fun isSoft(): Boolean {
        return false
    }


    fun getModule(file: PsiFile?): PsiElement? {
        return file?.children?.firstOrNull { it is WatModuleElement }
    }

    fun resolveFunc(file: PsiFile?, nameOrIndex: WatPsiElement): PsiElement? {
        return resolveByNameOrIndex(file, nameOrIndex, ::resolveFuncByName, ::resolveFuncByIndex)
    }

    fun resolveFuncByName(file: PsiFile?, name: String): PsiElement? {
        val module = getModule(file) ?: return null
        for (e in module.children) {
            if (e.elementType == WatElementType.IMPORT) {
                val importedFunc = PsiTreeUtil.findChildOfType(e, WatFuncElement::class.java)
                val nameElement = importedFunc?.nameIdentifier
                if (nameElement?.textMatches(name) == true) {
                    return importedFunc
                }
            }
            if (e is WatFuncElement) {
                val nameElement = e.nameIdentifier
                if (nameElement?.textMatches(name) == true) {
                    return e
                }
            }
        }
        return null
    }

    fun resolveFuncByIndex(file: PsiFile?, index: Int): PsiElement? {
        val module = getModule(file) ?: return null
        var currentIndex = 0
        for (e in module.children) {
            if (e.elementType == WatElementType.IMPORT) {
                val importedFunc = PsiTreeUtil.findChildOfType(e, WatFuncElement::class.java)
                if (importedFunc != null) {
                    if (currentIndex == index) {
                        return importedFunc
                    }
                    currentIndex++
                }
            }
            else if (e is WatFuncElement) {
                if (currentIndex == index) {
                    return e
                }
                currentIndex++
            }
        }
        return null
    }

    fun getElementTableNameOrIndex(elemOrTable: PsiElement?): WatPsiElement? {
        return elemOrTable?.children?.firstOrNull() as? WatPsiElement
    }

    fun resolveTable(file: PsiFile?, nameOrIndex: WatPsiElement): PsiElement? {
        return resolveByNameOrIndex(file, nameOrIndex, ::resolveTableByName, ::resolveTableByIndex)
    }

    fun resolveTableByName(file: PsiFile?, name: String): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithName<WatTableElement>(module, name)
    }

    fun resolveTableByIndex(file: PsiFile?, index: Int): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithIndex<WatTableElement>(module, index)
    }

    fun resolveType(file: PsiFile?, nameOrIndex: WatPsiElement): PsiElement? {
        return resolveByNameOrIndex(file, nameOrIndex, ::resolveTypeByName, ::resolveTypeByIndex)
    }

    fun resolveTypeByName(file: PsiFile?, name: String): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithName<WatTypeElement>(module, name)
    }

    fun resolveTypeByIndex(file: PsiFile?, index: Int): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithIndex<WatTypeElement>(module, index)
    }

    fun resolveGlobal(file: PsiFile?, nameOrIndex: WatPsiElement): PsiElement? {
        return resolveByNameOrIndex(file, nameOrIndex, ::resolveGlobalByName, ::resolveGlobalByIndex)
    }

    fun resolveGlobalByName(file: PsiFile?, name: String): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithName<WatGlobalElement>(module, name)
    }

    fun resolveGlobalByIndex(file: PsiFile?, index: Int): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithIndex<WatGlobalElement>(module, index)
    }

    fun resolveTag(file: PsiFile?, nameOrIndex: WatPsiElement): PsiElement? {
        return resolveByNameOrIndex(file, nameOrIndex, ::resolveTagByName, ::resolveTagByIndex)
    }

    fun resolveTagByName(file: PsiFile?, name: String): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithName<WatTagElement>(module, name)
    }

    fun resolveTagByIndex(file: PsiFile?, index: Int): PsiElement? {
        val module = getModule(file) ?: return null
        return findChildWithIndex<WatTagElement>(module, index)
    }

    fun resolveLocal(nameOrIndex: WatPsiElement): PsiElement? {
        return resolveByNameOrIndex(
            nameOrIndex.containingFile,
            nameOrIndex,
            { _, name -> resolveLocalByName(nameOrIndex as WatPsiIdentifierElement, name) },
            { _, index -> resolveLocalByIndex(nameOrIndex as WatPsiNumberElement, index) }
        )
    }

    fun resolveLocalByName(identifier: WatPsiIdentifierElement, name: String): PsiElement? {
        val func = PsiTreeUtil.getParentOfType(identifier, WatFuncBodyElement::class.java) ?: return null
        for (e in func.children) {
            if ((e is WatLocalElement || e is WatParamElement) && e.nameIdentifier?.textMatches(name) == true) {
                return e
            }
        }
        return null
    }

    fun resolveLocalByIndex(number: WatPsiNumberElement, index: Int): PsiElement? {
        val func = PsiTreeUtil.getParentOfType(number, WatFuncBodyElement::class.java) ?: return null
        var currentIndex = 0
        for (e in func.children) {
            if (e is WatLocalElement || e is WatParamElement) {
                if (currentIndex == index) {
                    return e
                }
                currentIndex++
            }
        }
        return null
    }

    fun resolveLabel(nameOrIndex: WatPsiElement): PsiElement? {
        return resolveByNameOrIndex(
            nameOrIndex.containingFile,
            nameOrIndex,
            { _, name -> resolveLabelByName(nameOrIndex as WatPsiIdentifierElement, name) },
            { _, index -> resolveLabelByIndex(nameOrIndex as WatPsiNumberElement, index) }
        )
    }

    fun resolveLabelByName(identifier: WatPsiIdentifierElement, name: String): PsiElement? {
        var e: PsiElement? = identifier
        while (e != null) {
            val labelOwner = PsiTreeUtil.getParentOfType(e, WatLabelOwner::class.java)
            if (labelOwner?.nameIdentifier?.textMatches(name) == true) {
                return labelOwner
            }
            e = labelOwner
        }
        return null
    }

    fun resolveLabelByIndex(number: WatPsiNumberElement, index: Int): PsiElement? {
        var e: PsiElement? = number
        var currentIndex = 0
        while (e != null) {
            val labelOwner = PsiTreeUtil.getParentOfType(e, WatLabelOwner::class.java)
            if (currentIndex == index) {
                return labelOwner
            }
            currentIndex++
            e = labelOwner
        }
        return null
    }

    inline fun <reified T : PsiNameIdentifierOwner> findChildWithName(parent: PsiElement?, name: String): T? {
        if (parent == null) {
            return null
        }
        for (e in parent.children) {
            if (e is T) {
                val identifier = e.nameIdentifier
                if (identifier?.textMatches(name) == true) {
                    return e
                }
            }
        }
        return null
    }

    inline fun <reified T : PsiNameIdentifierOwner> findChildWithIndex(parent: PsiElement?, index: Int): T? {
        if (parent == null) {
            return null
        }
        var currentIndex = 0
        for (e in parent.children) {
            if (e is T) {
                if (currentIndex == index) {
                    return e
                }
                currentIndex++
            }
        }
        return null
    }

    fun parseIndex(text: String): Int? {
        return try {
            Integer.parseInt(text)
        }
        catch (_: NumberFormatException) {
            null
        }
    }

    fun resolveByNameOrIndex(
        file: PsiFile?,
        nameOrIndex: WatPsiElement,
        resolveByName: (file: PsiFile?, name: String) -> PsiElement?,
        resolveByIndex: (file: PsiFile?, index: Int) -> PsiElement?,
    ): PsiElement? {
        return when (nameOrIndex) {
            is WatPsiIdentifierElement -> {
                resolveByName(file, nameOrIndex.text)
            }
            is WatPsiNumberElement -> {
                val index = parseIndex(nameOrIndex.text)
                if (index != null) {
                    resolveByIndex(file, index)
                } else {
                    null
                }
            }
            else -> null
        }
    }

    fun getExprToken(expr: PsiElement?): IElementType? {
        if (expr?.elementType == WatElementType.SEXPR) {
            val withoutParen = expr.node.children().drop(1)
            return withoutParen.firstOrNull()?.elementType
        }
        return null
    }
}