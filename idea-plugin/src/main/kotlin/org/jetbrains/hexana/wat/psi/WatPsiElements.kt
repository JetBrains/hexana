package org.jetbrains.hexana.wat.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiReference
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.hexana.wat.WatNavigation


open class WatPsiElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiElement

class WatPsiIdentifierElement(node: ASTNode) : WatPsiElement(node) {
    override fun getReference(): PsiReference {
        return WatReference(this)
    }
}

class WatPsiNumberElement(node: ASTNode) : WatPsiElement(node) {
    override fun getReference(): PsiReference {
        return WatReference(this)
    }
}

abstract class WatNameIdentifierOwner(node: ASTNode) : WatPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? {
        val result = children.firstOrNull() as? WatPsiIdentifierElement
        return result
    }

    override fun getName(): String? {
        return nameIdentifier?.text
    }

    override fun getTextOffset(): Int {
        return nameIdentifier?.textOffset ?: textRange.startOffset
    }

    override fun setName(name: @NlsSafe String): PsiElement? {
        return null
    }

    override fun navigate(requestFocus: Boolean) {
        if (!WatNavigation.navigateInWatEditor(containingFile.virtualFile, textOffset)) {
            super.navigate(requestFocus)
        }
    }
}

/**
 * [WatFunctionLocalElement] is a marker for elements which can be referenced
 * only in a function.
 */
interface WatFunctionLocalElement

class WatFuncElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatTableElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatTypeElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatMemoryElement(node: ASTNode) : WatNameIdentifierOwner(node)

/**
 * A component-model `(instance ...)`. The same element type covers three different roles, told
 * apart by [WatScopeKind] resolution: an instance *type* (a scope of its own), an instance
 * definition or import (which binds an id), and a plain reference such as
 * `(with "env" (instance $i))`.
 */
class WatInstanceElement(node: ASTNode) : WatNameIdentifierOwner(node)

/** A `(component ...)` definition, or the body of a component type. */
class WatComponentElement(node: ASTNode) : WatNameIdentifierOwner(node)

/**
 * An `(export ...)`. In a core module it only names an existing item, but a component export also
 * binds a new id in the index space of the sort it exports.
 */
class WatExportElement(node: ASTNode) : WatNameIdentifierOwner(node)

/** A component-model `(alias ...)`; see [WatElementType.ALIAS]. */
class WatAliasElement(node: ASTNode) : WatPsiElement(node)

/** A component-model `(value ...)`. */
class WatValueElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatGlobalElement(node: ASTNode) : WatNameIdentifierOwner(node) {
    /**
     * Position of this global in its scope's global index space, imported globals included -- the
     * same numbering `global.get`/`global.set` operands use.
     */
    fun getIndex(): Int? {
        val scope = parent as? WatModuleElement ?: return null
        return CachedValuesManager.getCachedValue(this) {
            val index = watDeclarations(scope)[WatSort.CORE_GLOBAL]?.indexOf(this)?.takeIf { it >= 0 }
            CachedValueProvider.Result.create(index, this, scope)
        }
    }
}

class WatTagElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatDataElement(node: ASTNode) : WatNameIdentifierOwner(node)

class WatLocalElement(node: ASTNode) : WatNameIdentifierOwner(node), WatFunctionLocalElement

class WatParamElement(node: ASTNode) : WatNameIdentifierOwner(node), WatFunctionLocalElement

class WatImportElement(node: ASTNode) : WatNameIdentifierOwner(node) {
    /**
     * An import binds its id on the imported item rather than on the `import` itself -- for
     * `(import "env" "f" (func $f))` the id lives on the `(func ...)`.
     */
    override fun getNameIdentifier(): PsiElement? {
        return watDeclarationName(watExternDesc(this) ?: return null)
    }
}

open class WatLabelOwner(node: ASTNode) : WatNameIdentifierOwner(node), WatFunctionLocalElement

class WatBlockElement(node: ASTNode) : WatLabelOwner(node)

class WatLoopElement(node: ASTNode) : WatLabelOwner(node)

class WatIfElement(node: ASTNode) : WatLabelOwner(node)
class WatThenElement(node: ASTNode) : WatPsiElement(node)
class WatElseElement(node: ASTNode) : WatPsiElement(node)

/**
 * `try_table` binds a label just like `block`, so it must be a [WatLabelOwner] for `br` to
 * resolve through it.
 */
class WatTryTableElement(node: ASTNode) : WatLabelOwner(node)

/** Legacy `try`, the pre-`try_table` form. Also a label owner. */
class WatTryElement(node: ASTNode) : WatLabelOwner(node)

/** The `(do ...)` clause of a folded legacy `try`. */
class WatDoElement(node: ASTNode) : WatPsiElement(node)

class WatFuncBodyElement(node: ASTNode) : WatPsiElement(node)

/** A core module: either a top-level `(module ...)` or a `(core module ...)` inside a component. */
class WatModuleElement(node: ASTNode) : WatNameIdentifierOwner(node)
