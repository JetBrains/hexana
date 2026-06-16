package org.jetbrains.hexana.providers.format

/**
 * A parsed `--emit-symbol-map` file (Binaryen / emscripten). Restores original names on a
 * stripped or minified module so a diff can name-anchor against a named counterpart.
 *
 * Two line shapes are recognised, one entry per line, `key:value` (split on the first `:`,
 * so a C++-mangled value containing `:` is preserved):
 *  - `funcIndex:originalName` — the stack-trace symbol map; a name per (global) function index.
 *    Captured in [byIndex]; acts as an external name section.
 *  - `minifiedName:originalName` — the import/export minification map. Captured in [translate];
 *    de-minifies export/import bindings.
 *
 * Blank lines and `#` / `//` comments are ignored. Indices are the combined (imports-first)
 * function index space, matching [WasmSymbolResolver].
 */
public data class WasmSymbolMap(
    val byIndex: Map<Int, String>,
    val translate: Map<String, String>,
) {
    public val isEmpty: Boolean get() = byIndex.isEmpty() && translate.isEmpty()

    public companion object {
        public val EMPTY: WasmSymbolMap = WasmSymbolMap(emptyMap(), emptyMap())

        public fun parse(text: String): WasmSymbolMap {
            val byIndex = HashMap<Int, String>()
            val translate = HashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
                val sep = line.indexOf(':')
                if (sep <= 0) continue
                val key = line.substring(0, sep).trim()
                val value = line.substring(sep + 1).trim()
                if (key.isEmpty() || value.isEmpty()) continue
                val idx = key.toIntOrNull()
                if (idx != null) byIndex[idx] = value else translate[key] = value
            }
            return WasmSymbolMap(byIndex, translate)
        }
    }
}
