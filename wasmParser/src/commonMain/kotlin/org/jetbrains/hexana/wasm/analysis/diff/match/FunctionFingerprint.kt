package org.jetbrains.hexana.wasm.analysis.diff.match

/**
 * Cheap structural summary of a function body, computed alongside the content
 * hash. Used by fuzzy matching (pass 4) to rank candidates when exact/name/
 * propagation matching has not paired a function. [typeArity] is a hard gate
 * (different arity ⇒ never candidates); the rest feed a similarity score.
 *
 * [typeArity] packs param count and result count: `(params shl 16) or results`.
 */
public data class FunctionFingerprint(
    val typeArity: Int,
    val instrCount: Int,
    val callCount: Int,
    val maxNestDepth: Int,
) {
    public companion object {
        public fun arity(params: Int, results: Int): Int = (params shl 16) or (results and 0xFFFF)
    }
}
