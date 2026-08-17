package org.jetbrains.hexana.wat.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import org.jetbrains.hexana.wat.FileOffset
import org.jetbrains.hexana.wat.WatFileset

/**
 * A reference from an id or index operand to the item it names.
 *
 * Resolve is scope-relative: [enclosingWatScope] decides which core module, component or instance
 * type the operand belongs to, and the lookup then only sees that scope's index spaces. That is
 * what makes the nested ids of component-model output work -- `wasm-tools` restarts numbering in
 * every scope, so a file can contain dozens of unrelated `$#type0` declarations.
 */
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
        // A custom annotation's contents are free-form text, so an id inside one names nothing.
        if (PsiTreeUtil.getParentOfType(nameOrIndex, WatAnnotationElement::class.java) != null) {
            return null
        }
        val scope = enclosingWatScope(nameOrIndex)
        // A missing scope means the operand sits outside any module or component; function-local
        // operands still resolve, everything else has nothing to resolve against.
        if (scope != null && !isCoreWatScope(scope)) {
            return resolveInComponent(scope)
        }
        return resolveInCoreModule(scope)
    }

    // -----------------------------------------------------------------------------------------
    // Core modules
    // -----------------------------------------------------------------------------------------

    private fun resolveInCoreModule(scope: PsiElement?): PsiElement? {
        val parent = nameOrIndex.parent
        if (parent.elementType == WatElementType.CALL) {
            return resolveCoreSort(scope, WatSort.CORE_FUNC)
        }
        if (parent.elementType == WatElementType.FUNC && parent.parent.elementType == WatElementType.EXPORT) {
            return resolveCoreSort(scope, WatSort.CORE_FUNC)
        }
        if (parent.elementType == WatElementType.ELEM) {
            val tableNameOrIndex = getElementTableNameOrIndex(parent) ?: return null
            val table = WatReference(tableNameOrIndex).resolveCoreSort(scope, WatSort.CORE_TABLE)
            if (tableNameOrIndex == nameOrIndex) {
                return table
            }
            if (table?.node?.findChildByType(WatToken.FUNCREF) != null) {
                return resolveCoreSort(scope, WatSort.CORE_FUNC)
            }
            return null
        }
        if (parent.elementType == WatElementType.TYPE) {
            val grandParent = parent.parent
            if (grandParent?.elementType == WatElementType.CALL_INDIRECT ||
                PsiTreeUtil.getParentOfType(grandParent, WatFuncElement::class.java) != null) {
                return resolveCoreSort(scope, WatSort.CORE_TYPE)
            }
            return null
        }
        if (parent.elementType == WatElementType.GLOBAL_GET || parent.elementType == WatElementType.GLOBAL_SET) {
            return resolveCoreSort(scope, WatSort.CORE_GLOBAL)
        }
        if (parent.elementType == WatElementType.THROW) {
            return resolveCoreSort(scope, WatSort.CORE_TAG)
        }
        if (parent.elementType == WatElementType.CATCH) {
            // Legacy `catch $tag`, but `try_table`'s clause is `(catch $tag $label)`, so the
            // second operand is a label rather than another tag.
            return if (operandIndex() == 0) resolveCoreSort(scope, WatSort.CORE_TAG) else resolveLabel(nameOrIndex)
        }
        val exprType = getExprToken(parent)
        if (exprType == WatToken.LOCAL_SET || exprType == WatToken.LOCAL_GET || exprType == WatToken.LOCAL_TEE) {
            return resolveLocal(nameOrIndex)
        }
        if (exprType == WatToken.START) {
            return resolveCoreSort(scope, WatSort.CORE_FUNC)
        }
        if (exprType == WatToken.BR || exprType == WatToken.BR_IF || exprType == WatToken.BR_TABLE ||
            exprType == WatToken.BR_ON_NULL || exprType == WatToken.BR_ON_NON_NULL ||
            exprType == WatToken.BR_ON_CAST || exprType == WatToken.BR_ON_CAST_FAIL ||
            // Legacy exception handling: `rethrow <label>`.
            exprType == WatToken.RETHROW ||
            // `try_table` clauses that carry only a label.
            exprType == WatToken.CATCH_ALL || exprType == WatToken.CATCH_ALL_REF) {
            return resolveLabel(nameOrIndex)
        }
        if (exprType == WatToken.DELEGATE) {
            // `delegate <label>` terminates a legacy `try`, and its label is resolved in the
            // scope *outside* that try -- so `delegate 0` targets the enclosing block, not the
            // try itself, even though the parser keeps the clause inside the try's marker.
            return resolveLabel(nameOrIndex, skipInnermostLabel = true)
        }
        // `(catch_ref $tag $label)` -- like CATCH above, but reached as a plain s-expression.
        if (exprType == WatToken.CATCH_REF) {
            return if (operandIndex() == 0) resolveCoreSort(scope, WatSort.CORE_TAG) else resolveLabel(nameOrIndex)
        }
        if (exprType == WatToken.SUSPEND) {
            return resolveCoreSort(scope, WatSort.CORE_TAG)
        }
        // Stack switching: `resume_throw <type> <tag>` and `switch <type> <tag>`.
        if (exprType == WatToken.RESUME_THROW || exprType == WatToken.SWITCH) {
            return if (operandIndex() == 0) {
                resolveCoreSort(scope, WatSort.CORE_TYPE)
            } else {
                resolveCoreSort(scope, WatSort.CORE_TAG)
            }
        }
        // `cont.new <type>`, `cont.bind <type> <type>`, `resume <type>`, `resume_throw_ref <type>`.
        if (exprType == WatToken.CONT_NEW || exprType == WatToken.CONT_BIND ||
            exprType == WatToken.RESUME || exprType == WatToken.RESUME_THROW_REF) {
            return resolveCoreSort(scope, WatSort.CORE_TYPE)
        }
        return null
    }

    /**
     * A very large single-module file is split into several editor documents, and its module-level
     * items are then indexed up front so a reference can cross documents without any of them being
     * parsed. Only such a split file has a fileset, and splitting only ever applies to a top-level
     * module, so nested scopes fall through to the ordinary lookup.
     */
    private fun resolveCoreSort(scope: PsiElement?, sort: WatSort): PsiElement? {
        return findInFileset(scope, sort) ?: resolveInScope(scope, sort)
    }

    // -----------------------------------------------------------------------------------------
    // Components, component types and instance types
    // -----------------------------------------------------------------------------------------

    private fun resolveInComponent(scope: PsiElement): PsiElement? {
        val parent = nameOrIndex.parent ?: return null
        if (parent.elementType == WatElementType.ALIAS) {
            return resolveAliasOperand(scope, parent)
        }
        if (isBoundId(parent)) {
            return null
        }
        val sort = componentReferenceSort(parent) ?: return null
        return resolveInScope(scope, sort)
    }

    /** Whether [parent] binds [nameOrIndex] as a new id rather than naming an existing item. */
    private fun isBoundId(parent: PsiElement): Boolean {
        if (nameOrIndex != watDeclarationName(parent)) {
            return false
        }
        // A component `(export $id "name" ...)` binds `$id` in the exported item's index space.
        if (parent.elementType == WatElementType.EXPORT) {
            return true
        }
        if (!isWatSortElement(parent)) {
            return false
        }
        val grandParent = parent.parent ?: return false
        return when {
            // A declaration written directly in the scope, e.g. `(type $t ...)` in a component.
            watScopeKind(grandParent) != null -> true
            // `(import "x" (instance $i (type $t)))` binds `$i`.
            grandParent.elementType == WatElementType.IMPORT -> true
            // The trailing clause of an alias binds its id; see WatElementType.ALIAS.
            grandParent.elementType == WatElementType.ALIAS -> watAliasTarget(grandParent) == parent
            else -> false
        }
    }

    /** The index space a component-model operand names, given its enclosing [parent] element. */
    private fun componentReferenceSort(parent: PsiElement): WatSort? {
        // `(sort $x)` naming an existing item, e.g. `(with "env" (instance $i))`.
        if (isWatSortElement(parent) && nameOrIndex == watDeclarationName(parent)) {
            return watSortOf(parent, isCoreSortContext(parent))
        }
        return when (val head = headTokenOf(parent)) {
            // `(instantiate $m ...)` names a core module inside a `(core instance ...)` and a
            // component inside a component `(instance ...)`.
            WatToken.INSTANTIATE ->
                if (enclosingInstanceIsCore(nameOrIndex)) WatSort.CORE_MODULE else WatSort.COMPONENT
            in CORE_FUNC_WRAPPERS -> WatSort.CORE_FUNC
            // `(canon resource.new|resource.drop|resource.rep $t)`. The lift/lower forms wrap their
            // operands in `(func ...)`/`(core func ...)` instead, so they are handled above.
            WatToken.CANON -> if (isResourceCanon(parent)) WatSort.TYPE else null
            // Everything else that carries a bare id in a component is a type: the defvaltype
            // forms (`own`, `borrow`, `list`, `tuple`, `variant`'s cases, ...), the `eq`/`sub`
            // type bounds, and the named parameters and results of a component function type.
            else -> if (head == null) null else WatSort.TYPE
        }
    }

    /**
     * Whether a sort element written without the `core` prefix nevertheless names a core item,
     * which happens where the surrounding form only accepts core items.
     */
    private fun isCoreSortContext(sortElement: PsiElement): Boolean {
        val holder = sortElement.parent ?: return false
        val head = headTokenOf(holder)
        return when {
            // `(realloc (func $f))` and friends always wrap a core function.
            head in CORE_FUNC_WRAPPERS -> true
            // Instantiation arguments and inline exports follow the instance being defined: a
            // `(core instance ...)` is wired from core items, a component one from component items.
            head == WatToken.WITH || head == WatToken.INSTANTIATE ||
                holder.elementType == WatElementType.EXPORT -> enclosingInstanceIsCore(sortElement)
            else -> false
        }
    }

    /**
     * `(alias ...)` operands are positional, and the `outer` form is the only reference in the
     * whole format that deliberately leaves the current scope.
     */
    private fun resolveAliasOperand(scope: PsiElement, alias: PsiElement): PsiElement? {
        val operands = watOperands(alias)
        val operandIndex = operands.indexOf(nameOrIndex)
        if (operandIndex < 0) {
            return null
        }
        if (alias.node.findChildByType(WatToken.OUTER) != null) {
            // `(alias outer <component> <index> (<sort> <id>))`
            val outerScope = resolveOuterScope(scope, operands.first()) ?: return null
            if (operandIndex == 0) {
                return outerScope
            }
            if (operandIndex != 1) {
                return null
            }
            val target = watAliasTarget(alias) ?: return null
            val sort = watSortOf(target, isCoreWatScope(outerScope)) ?: return null
            return resolveInScope(outerScope, sort)
        }
        // `(alias export $i "name" (<sort> <id>))`, or `(alias core export $i ...)` for a core one.
        if (operandIndex != 0) {
            return null
        }
        val sort = if (hasCoreKeyword(alias)) WatSort.CORE_INSTANCE else WatSort.INSTANCE
        return resolveInScope(scope, sort)
    }

    /**
     * The scope an `outer` alias reaches into: either the enclosing scope carrying [operand] as its
     * id, or -- when [operand] is a count -- the operand-th enclosing scope, counting the immediate
     * parent of [scope] as zero.
     */
    private fun resolveOuterScope(scope: PsiElement, operand: PsiElement): PsiElement? {
        var current = enclosingWatScope(scope)
        when (operand) {
            is WatPsiIdentifierElement -> {
                while (current != null) {
                    if (watDeclarationName(current)?.textMatches(operand.text) == true) {
                        return current
                    }
                    current = enclosingWatScope(current)
                }
                return null
            }
            is WatPsiNumberElement -> {
                var remaining = parseIndex(operand.text) ?: return null
                while (current != null && remaining > 0) {
                    current = enclosingWatScope(current)
                    remaining--
                }
                return current
            }
            else -> return null
        }
    }

    // -----------------------------------------------------------------------------------------
    // Shared lookup
    // -----------------------------------------------------------------------------------------

    private fun resolveInScope(scope: PsiElement?, sort: WatSort): PsiElement? {
        scope ?: return null
        return when (nameOrIndex) {
            is WatPsiIdentifierElement -> resolveWatName(scope, sort, nameOrIndex.text)
            is WatPsiNumberElement -> parseIndex(nameOrIndex.text)?.let { resolveWatIndex(scope, sort, it) }
            else -> null
        }
    }

    /**
     * Position of [nameOrIndex] among the id/index operands of its enclosing expression. Used by
     * instructions whose operands are of differing kinds, such as `(catch $tag $label)`.
     */
    private fun operandIndex(): Int {
        var index = 0
        var sibling = nameOrIndex.prevSibling
        while (sibling != null) {
            if (sibling is WatPsiIdentifierElement || sibling is WatPsiNumberElement) {
                index++
            }
            sibling = sibling.prevSibling
        }
        return index
    }

    private fun findInFileset(scope: PsiElement?, sort: WatSort): PsiElement? {
        // Splitting only applies to a top-level module, so the index cannot describe a nested scope.
        if (scope == null || scope.parent !is PsiFile) {
            return null
        }
        val targetElementType = FILESET_ELEMENT_TYPES[sort] ?: return null
        val fileset = nameOrIndex.containingFile.virtualFile?.getUserData(WatFileset.KEY) ?: return null
        return when (nameOrIndex) {
            is WatPsiIdentifierElement ->
                getWatNameIdentifierOwner(fileset.getByName(targetElementType, nameOrIndex.text))
            is WatPsiNumberElement ->
                parseIndex(nameOrIndex.text)?.let { getWatNameIdentifierOwner(fileset.getByIndex(targetElementType, it)) }
            else -> null
        }
    }

    private fun getWatNameIdentifierOwner(
        fileOffset: FileOffset?,
    ): WatNameIdentifierOwner? {
        fileOffset ?: return null
        val psiFile = PsiManager.getInstance(nameOrIndex.project).findFile(fileOffset.file)
        val element = psiFile?.findElementAt(fileOffset.offset)
        val result = PsiTreeUtil.getParentOfType(element, WatNameIdentifierOwner::class.java, false)
        return result
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

    fun getElementTableNameOrIndex(elemOrTable: PsiElement?): WatPsiElement? {
        return elemOrTable?.children?.firstOrNull() as? WatPsiElement
    }

    fun resolveLocal(nameOrIndex: WatPsiElement): PsiElement? {
        return when (nameOrIndex) {
            is WatPsiIdentifierElement -> resolveLocalByName(nameOrIndex, nameOrIndex.text)
            is WatPsiNumberElement -> parseIndex(nameOrIndex.text)?.let { resolveLocalByIndex(nameOrIndex, it) }
            else -> null
        }
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

    /**
     * @param skipInnermostLabel start the walk one label owner further out. Needed by `delegate`,
     *   whose label is resolved outside the `try` that encloses it.
     */
    fun resolveLabel(nameOrIndex: WatPsiElement, skipInnermostLabel: Boolean = false): PsiElement? {
        return when (nameOrIndex) {
            is WatPsiIdentifierElement -> resolveLabelByName(nameOrIndex, nameOrIndex.text, skipInnermostLabel)
            is WatPsiNumberElement ->
                parseIndex(nameOrIndex.text)?.let { resolveLabelByIndex(nameOrIndex, it, skipInnermostLabel) }
            else -> null
        }
    }

    fun resolveLabelByName(
        identifier: WatPsiIdentifierElement,
        name: String,
        skipInnermostLabel: Boolean = false,
    ): PsiElement? {
        var e: PsiElement? = startOfLabelWalk(identifier, skipInnermostLabel) ?: return null
        while (e != null) {
            val labelOwner = PsiTreeUtil.getParentOfType(e, WatLabelOwner::class.java)
            if (labelOwner?.nameIdentifier?.textMatches(name) == true) {
                return labelOwner
            }
            e = labelOwner
        }
        return null
    }

    fun resolveLabelByIndex(
        number: WatPsiNumberElement,
        index: Int,
        skipInnermostLabel: Boolean = false,
    ): PsiElement? {
        var e: PsiElement? = startOfLabelWalk(number, skipInnermostLabel) ?: return null
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

    /**
     * [PsiTreeUtil.getParentOfType] is strict, so advancing to the innermost label owner before
     * the walk begins is what makes it the *second* owner that index 0 refers to.
     */
    private fun startOfLabelWalk(from: PsiElement, skipInnermostLabel: Boolean): PsiElement? {
        if (!skipInnermostLabel) {
            return from
        }
        return PsiTreeUtil.getParentOfType(from, WatLabelOwner::class.java)
    }

    fun parseIndex(text: String): Int? {
        return try {
            Integer.parseInt(text)
        }
        catch (_: NumberFormatException) {
            null
        }
    }

    fun getExprToken(expr: PsiElement?): IElementType? {
        if (expr?.elementType == WatElementType.SEXPR || expr?.elementType == WatElementType.UEXPR) {
            return headTokenOf(expr)
        }
        return null
    }

    private companion object {
        /**
         * Clauses that always wrap a *core* function even though they write it without the `core`
         * prefix: the canonical ABI options and a resource destructor.
         */
        val CORE_FUNC_WRAPPERS = TokenSet.create(
            WatToken.REALLOC,
            WatToken.POST_RETURN,
            WatToken.CALLBACK,
            WatToken.DTOR,
        )

        val RESOURCE_CANON_OPS = TokenSet.create(
            WatToken.RESOURCE_NEW,
            WatToken.RESOURCE_DROP,
            WatToken.RESOURCE_REP,
        )

        /** Index spaces the split-file index of [WatFileset] can answer for. */
        val FILESET_ELEMENT_TYPES = mapOf(
            WatSort.CORE_FUNC to WatElementType.FUNC,
            WatSort.CORE_TYPE to WatElementType.TYPE,
            WatSort.CORE_TABLE to WatElementType.TABLE,
            WatSort.CORE_GLOBAL to WatElementType.GLOBAL,
            WatSort.CORE_TAG to WatElementType.TAG,
        )

        fun isResourceCanon(sexpr: PsiElement): Boolean {
            return sexpr.node.getChildren(RESOURCE_CANON_OPS).isNotEmpty()
        }

        /** The keyword that opens [element], skipping the parenthesis, whitespace and comments. */
        fun headTokenOf(element: PsiElement?): IElementType? {
            var node: ASTNode? = element?.node?.firstChildNode
            var seenParen = false
            while (node != null) {
                val type = node.elementType
                when {
                    WatToken.WHITESPACES_SET.contains(type) || WatToken.COMMENTS_SET.contains(type) -> {}
                    type == WatToken.OPEN_PAREN && !seenParen -> seenParen = true
                    else -> return type
                }
                node = node.treeNext
            }
            return null
        }

        /**
         * Whether the innermost instance definition around [element] is a `(core instance ...)`,
         * which is what decides between the core and the component index spaces in forms that do
         * not spell out `core` on every operand.
         */
        fun enclosingInstanceIsCore(element: PsiElement): Boolean {
            var e: PsiElement? = element.parent
            while (e != null && e !is PsiFile) {
                if (e.elementType == WatElementType.INSTANCE) {
                    return hasCoreKeyword(e)
                }
                watScopeKind(e)?.let { return isCoreWatScope(e) }
                e = e.parent
            }
            return false
        }
    }
}
