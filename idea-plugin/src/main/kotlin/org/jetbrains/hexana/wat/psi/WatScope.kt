package org.jetbrains.hexana.wat.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.elementType

/**
 * One index space of a [watScopeKind] scope.
 *
 * A core module only ever uses the `CORE_*` entries. A component uses both sets at once, because
 * `(func ...)` names a component function while `(core func ...)` names a core one and the two are
 * numbered independently.
 */
enum class WatSort {
    CORE_TYPE,
    CORE_FUNC,
    CORE_TABLE,
    CORE_MEMORY,
    CORE_GLOBAL,
    CORE_TAG,
    CORE_DATA,
    CORE_MODULE,
    CORE_INSTANCE,
    TYPE,
    FUNC,
    COMPONENT,
    INSTANCE,
    VALUE,
}

/** The kind of scope an element opens; see [watScopeKind]. */
enum class WatScopeKind {
    /** `(module ...)` or `(core module ...)`: only the core index spaces. */
    CORE_MODULE,

    /** `(component ...)`, either a definition or the body of a component type. */
    COMPONENT,

    /** The `(instance ...)` body of an instance *type*, i.e. a list of instance declarations. */
    INSTANCE_TYPE,
}

/**
 * Elements that carry an id of their own and therefore can be the target of a reference.
 *
 * `(elem ...)` is deliberately absent: in the abbreviated form its first id is the *table* it
 * initialises, not a name of its own, and nothing resolves element segments yet.
 */
private val SORT_ELEMENT_TYPES = setOf(
    WatElementType.TYPE,
    WatElementType.FUNC,
    WatElementType.TABLE,
    WatElementType.MEMORY,
    WatElementType.GLOBAL,
    WatElementType.TAG,
    WatElementType.DATA,
    WatElementType.MODULE,
    WatElementType.INSTANCE,
    WatElementType.COMPONENT,
    WatElementType.VALUE,
)

/**
 * The kind of scope [element] opens, or null when it owns no index spaces.
 *
 * The component model nests scopes and each one restarts its index spaces from zero, so the same
 * id is reused at every level -- `wasm-tools` prints `$#type0` in a component, in every instance
 * type inside it and in every nested core module. A reference therefore only makes sense relative
 * to the scope that encloses it.
 */
fun watScopeKind(element: PsiElement): WatScopeKind? {
    return when (element.elementType) {
        WatElementType.MODULE -> WatScopeKind.CORE_MODULE
        // `(component $c)` also appears as a plain reference inside an export or an alias; only a
        // component with a body opens a scope.
        WatElementType.COMPONENT -> if (hasStructuralChildren(element)) WatScopeKind.COMPONENT else null
        // An instance *type* is written without an id -- the id, if any, sits on the enclosing
        // `(type ...)`. Every other `(instance ...)` either binds or names an instance instead.
        //
        // `core` and `(instantiate ...)` both rule a type out: instance types are a component-level
        // construct, and an instantiation defines an instance rather than describing one. Without
        // those two checks an anonymous `(core instance (instantiate $m))` would open a scope that
        // declares nothing, and `$m` would resolve nowhere.
        WatElementType.INSTANCE ->
            if (watDeclarationName(element) == null &&
                hasStructuralChildren(element) &&
                !hasCoreKeyword(element) &&
                !isInstantiation(element)
            ) {
                WatScopeKind.INSTANCE_TYPE
            } else {
                null
            }
        else -> null
    }
}

private fun isInstantiation(element: PsiElement): Boolean {
    return element.children.any { it.node.findChildByType(WatToken.INSTANTIATE) != null }
}

/** Whether ids and indices directly inside [scope] belong to the core index spaces. */
fun isCoreWatScope(scope: PsiElement): Boolean = watScopeKind(scope) == WatScopeKind.CORE_MODULE

/** The innermost scope strictly enclosing [element], or null when there is none. */
fun enclosingWatScope(element: PsiElement): PsiElement? {
    var e = element.parent
    while (e != null && e !is PsiFile) {
        if (watScopeKind(e) != null) {
            return e
        }
        e = e.parent
    }
    return null
}

/**
 * Every declaration of [scope], grouped by index space and kept in textual order -- which is also
 * index order, since the text format requires imports to precede definitions.
 */
fun watDeclarations(scope: PsiElement): Map<WatSort, List<PsiElement>> {
    return CachedValuesManager.getCachedValue(scope) {
        CachedValueProvider.Result.create(computeWatDeclarations(scope), scope)
    }
}

/**
 * Resolves [name] in [scope]'s [sort] index space, and nowhere else.
 *
 * The lookup deliberately does not widen to enclosing scopes. Index spaces are per-scope and the
 * component model gives no implicit access to an outer one -- reaching out is spelled `alias outer`,
 * which resolves against that scope explicitly. Widening would mean an id missing from its own scope
 * silently resolves to an unrelated declaration that merely shares the name, which for
 * `wasm-tools` output (a `$#type0` at every level) is close to guaranteed.
 */
fun resolveWatName(scope: PsiElement, sort: WatSort, name: String): PsiElement? {
    return watDeclarations(scope)[sort]?.firstOrNull { watDeclarationName(it)?.textMatches(name) == true }
}

/** Resolves the [index]-th entry of [scope]'s [sort] index space; see [resolveWatName]. */
fun resolveWatIndex(scope: PsiElement, sort: WatSort, index: Int): PsiElement? {
    return watDeclarations(scope)[sort]?.getOrNull(index)
}

/**
 * The id [element] binds, or null when it is anonymous. Ids always come first, so this is the
 * element's first composite child -- comments, strings and the leading keywords are not among them.
 */
fun watDeclarationName(element: PsiElement): PsiElement? {
    return element.children.firstOrNull() as? WatPsiIdentifierElement
}

/**
 * The index space [element] belongs to, or null when it is not a sort element.
 *
 * @param coreScope whether the enclosing scope is a core module, in which case an unprefixed
 *   `(type ...)` or `(func ...)` is already core
 */
fun watSortOf(element: PsiElement, coreScope: Boolean): WatSort? {
    val core = coreScope || hasCoreKeyword(element)
    return when (element.elementType) {
        WatElementType.TYPE -> if (core) WatSort.CORE_TYPE else WatSort.TYPE
        WatElementType.FUNC -> if (core) WatSort.CORE_FUNC else WatSort.FUNC
        WatElementType.INSTANCE -> if (core) WatSort.CORE_INSTANCE else WatSort.INSTANCE
        // Tables, memories, globals, tags and data segments exist only as core items; a component
        // reaches them exclusively through `(core table ...)`-style aliases.
        WatElementType.TABLE -> WatSort.CORE_TABLE
        WatElementType.MEMORY -> WatSort.CORE_MEMORY
        WatElementType.GLOBAL -> WatSort.CORE_GLOBAL
        WatElementType.TAG -> WatSort.CORE_TAG
        WatElementType.DATA -> WatSort.CORE_DATA
        WatElementType.MODULE -> WatSort.CORE_MODULE
        WatElementType.COMPONENT -> WatSort.COMPONENT
        WatElementType.VALUE -> WatSort.VALUE
        else -> null
    }
}

/** Whether [element] is a sort element, i.e. one that can carry an id of its own. */
fun isWatSortElement(element: PsiElement): Boolean = element.elementType in SORT_ELEMENT_TYPES

/** Whether [element] is written with the `core` prefix, as in `(core func ...)`. */
fun hasCoreKeyword(element: PsiElement): Boolean {
    return element.node.findChildByType(WatToken.CORE) != null
}

/** The described item of an `import` or `export`, i.e. its first sort element. */
fun watExternDesc(element: PsiElement): PsiElement? {
    return element.children.firstOrNull { it.elementType in SORT_ELEMENT_TYPES }
}

/**
 * The item an `alias` binds. It is the alias's trailing clause, e.g. the `(type $#type4 ...)` of
 * `(alias export $#instance3 "error" (type $#type4))`.
 */
fun watAliasTarget(alias: PsiElement): PsiElement? {
    return alias.children.lastOrNull { it.elementType in SORT_ELEMENT_TYPES }
}

/** The id/index operands written directly on [element], such as an alias's `outer` operands. */
fun watOperands(element: PsiElement): List<PsiElement> {
    return element.children.filter { it is WatPsiIdentifierElement || it is WatPsiNumberElement }
}

private fun hasStructuralChildren(element: PsiElement): Boolean {
    return element.children.any { it !is WatPsiIdentifierElement && it !is WatPsiNumberElement }
}

private fun computeWatDeclarations(scope: PsiElement): Map<WatSort, List<PsiElement>> {
    val core = isCoreWatScope(scope)
    val result = LinkedHashMap<WatSort, MutableList<PsiElement>>()

    fun add(declaration: PsiElement?, sortSource: PsiElement? = declaration) {
        if (declaration == null || sortSource == null) return
        val sort = watSortOf(sortSource, core) ?: return
        result.getOrPut(sort) { arrayListOf() }.add(declaration)
    }

    for (child in scope.children) {
        when (child.elementType) {
            // An import binds its id on the imported item: `(import "env" "f" (func $f))`.
            WatElementType.IMPORT -> add(watExternDesc(child))
            // A component export binds a *new* id for the exported item, in that item's index
            // space; a core export only names an existing one and adds nothing.
            WatElementType.EXPORT -> if (!core) add(child, watExternDesc(child))
            WatElementType.ALIAS -> add(watAliasTarget(child))
            else -> add(child)
        }
    }
    return result
}
