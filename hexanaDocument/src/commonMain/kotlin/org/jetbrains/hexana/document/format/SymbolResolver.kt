package org.jetbrains.hexana.document.format

/**
 * Format-specific name lookup: turns indices into human-readable names.
 *
 * For WASM Phase 1, the resolver chains: name section → exports (annotation) →
 * `$func<idx>` fallback. DWARF integration arrives in PR 2/3.
 */
public interface SymbolResolver {
    /**
     * Resolves [index] in the namespace [kind] to a display name, or `null`
     * if no name is known (the UI then falls back to `${kind}<idx>`).
     */
    public fun resolve(kind: SymbolKind, index: Int): String?
}

/** Namespace for [SymbolResolver.resolve]. Format-specific kinds extend the list as needed. */
public enum class SymbolKind {
    FUNCTION,
    TYPE,
    GLOBAL,
    MEMORY,
    TABLE,
    ELEMENT,
    DATA,
    LOCAL,
    LABEL,
}
