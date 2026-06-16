package org.jetbrains.hexana.providers.format

/**
 * A parsed `--emit-symbol-map` file (Binaryen / emscripten). Restores original names on a
 * stripped or minified module so a diff can name-anchor against a named counterpart.
 *
 * Three line shapes are recognised, one entry per line:
 *  - `funcIndex:originalName` — the stack-trace symbol map (`--symbolmap`); a name per (global)
 *    function index. Captured in [byIndex]; acts as an external name section.
 *  - `minifiedName:originalName` — emscripten's import/export minification map (`--emit-symbol-map`).
 *    Captured in [translate]; de-minifies export/import bindings.
 *  - `originalName => minifiedName` — Binaryen's `--minify-imports-and-exports` console form (the
 *    minified token may carry a leading `$` display sigil, which is stripped). Stored reversed in
 *    [translate] (`minified -> original`).
 *
 * The `=>` form is checked before `:` so a C++-mangled original containing `:` is not split on the
 * colon. Within a `:` line the split is on the first `:`, preserving a mangled value's own colons.
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
                // `=>` (Binaryen console) before `:` so a mangled original with colons isn't split wrong.
                val arrow = line.indexOf("=>")
                if (arrow > 0) {
                    val original = line.substring(0, arrow).trim()
                    val minified = line.substring(arrow + 2).trim().removePrefix("$")
                    if (original.isNotEmpty() && minified.isNotEmpty()) translate[minified] = original
                    continue
                }
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
