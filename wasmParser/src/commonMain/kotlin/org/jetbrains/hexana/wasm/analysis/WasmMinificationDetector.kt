package org.jetbrains.hexana.wasm.analysis

/**
 * Verdict on whether a module's import/export names were minified (e.g. by Binaryen
 * `--minify-imports-and-exports[-and-modules]`, which emscripten applies at `-O2`/`-O3`).
 * When [minified] is true, name anchoring across two modules is unreliable — the public
 * surface was rewritten to dense short identifiers — and a diff should treat function
 * matches as structural and label them accordingly.
 */
public data class MinificationResult(
    val minified: Boolean,
    val confidence: Float,
    val evidence: String,
) {
    public companion object {
        public val NONE: MinificationResult = MinificationResult(false, 0f, "no public symbols to judge")
    }
}

/**
 * Heuristic detector for minified import/export names.
 *
 * Minifiers rewrite the public surface to dense short identifiers drawn from a small
 * alphabet (`a`, `b`, …, then `aa`, `Ec`, …), and the `-and-modules` variant collapses
 * import module names to 1–2 chars (`env` → `a`). We do not depend on a minifier's exact
 * generator alphabet — we measure how short the public names are and whether the import
 * module namespace collapsed. Real symbol names (`org_jetbrains_skia_…`, `env.fd_write`)
 * are almost never ≤2 chars, so the short-name fraction separates the two regimes cleanly.
 *
 * Pure and deterministic — unit-testable from name lists with no module/buffer.
 */
public object WasmMinificationDetector {

    private const val SHORT_LEN = 2                       // real symbols are almost never this short
    private const val MIN_SAMPLE = 5                      // too few names to judge
    private const val MINIFIED_FRACTION = 0.7f
    private const val WITH_MODULE_COLLAPSE_FRACTION = 0.5f

    public fun detect(
        importModules: List<String>,
        importFields: List<String>,
        exportNames: List<String>,
    ): MinificationResult {
        val publicNames = importFields + exportNames
        if (publicNames.size < MIN_SAMPLE) return MinificationResult.NONE

        val shortCount = publicNames.count { it.length in 1..SHORT_LEN && isMinifiedAlphabet(it) }
        val fraction = shortCount.toFloat() / publicNames.size

        val distinctModules = importModules.toSet()
        val moduleCollapse = distinctModules.isNotEmpty() && distinctModules.all { it.length <= SHORT_LEN }

        val minified = fraction >= MINIFIED_FRACTION ||
            (moduleCollapse && fraction >= WITH_MODULE_COLLAPSE_FRACTION)
        val confidence = if (moduleCollapse) minOf(1f, fraction + 0.15f) else fraction

        val evidence = buildString {
            append("${(fraction * 100).toInt()}% of ${publicNames.size} public symbols are ≤$SHORT_LEN chars")
            if (moduleCollapse) append("; import modules collapsed to $distinctModules")
        }
        return MinificationResult(minified, confidence, evidence)
    }

    /** A minified identifier is non-empty and uses only WASM-name-safe short alphabet chars. */
    private fun isMinifiedAlphabet(s: String): Boolean =
        s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '_' || it == '$' }
}
