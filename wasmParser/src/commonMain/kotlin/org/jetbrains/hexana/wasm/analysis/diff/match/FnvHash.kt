package org.jetbrains.hexana.wasm.analysis.diff.match

/**
 * 64-bit FNV-1a accumulator over a token stream. Used to fingerprint a
 * normalized function body. Not cryptographic — chosen for speed and a uniform
 * distribution good enough to bucket millions of tokens without a dense matrix.
 *
 * Determinism is required only *within a single process* (a diff hashes both
 * modules in one run and compares); it is never persisted or compared across
 * runs, so platform-specific class metadata folded in via [FnvHash] is fine.
 */
public class FnvHash {
    private var hash: Long = OFFSET_BASIS

    /** Mixes all 8 bytes of [v] (little-endian) into the running hash. */
    public fun update(v: Long): FnvHash {
        var x = v
        repeat(8) {
            hash = (hash xor (x and 0xFF)) * PRIME
            x = x ushr 8
        }
        return this
    }

    public fun update(s: String): FnvHash {
        for (ch in s) update(ch.code.toLong())
        return this
    }

    public fun value(): Long = hash

    public companion object {
        // 0xcbf29ce484222325 / 0x100000001b3 as signed Long (wrapping arithmetic).
        private val OFFSET_BASIS: Long = 0xcbf29ce484222325uL.toLong()
        private val PRIME: Long = 0x100000001b3uL.toLong()

        /** One-shot hash of a single Long (e.g. for combining two hashes). */
        public fun of(vararg values: Long): Long {
            val h = FnvHash()
            for (v in values) h.update(v)
            return h.value()
        }
    }
}
