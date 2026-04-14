package org.jetbrains.hexana.wasm.analysis

/**
 * A single monomorphization group: functions that share the same demangled base name.
 */
data class MonosGroup(
    val baseName: String,
    val count: Int,
    val totalSize: Int,
    val funcIndices: List<Int>,
) {
    val avgSize: Int get() = if (count == 0) 0 else totalSize / count
}

/**
 * Result of monomorphization analysis.
 *
 * @param groups Monomorphization groups sorted by total size descending. Only groups with count >= 2.
 * @param totalBloat Approximate bytes wasted — for each group: (count - 1) * avgSize.
 */
data class MonosResult(
    val groups: List<MonosGroup>,
    val totalBloat: Int,
)

/**
 * Detects monomorphization bloat by grouping functions that share the same demangled base name.
 *
 * Strips generic type parameters, hash suffixes, lambda numbering, and closure indices
 * to recover the base template/generic from which multiple specializations were generated.
 */
object MonosAnalyzer {

    /**
     * @param functionNames map from funcIdx → name (from the WASM name section)
     * @param functionSizes map from funcIdx → code size in bytes
     * @return [MonosResult] with groups of count >= 2, sorted by total size descending
     */
    fun analyze(functionNames: Map<Int, String>, functionSizes: Map<Int, Int>): MonosResult {
        val grouped = mutableMapOf<String, MutableList<Int>>()

        for ((funcIdx, rawName) in functionNames) {
            if (functionSizes[funcIdx] == null) continue
            val base = extractBaseName(rawName)
            if (base.isBlank()) continue
            grouped.getOrPut(base) { mutableListOf() }.add(funcIdx)
        }

        val groups = grouped
            .filter { it.value.size >= 2 }
            .map { (baseName, indices) ->
                val totalSize = indices.sumOf { functionSizes[it] ?: 0 }
                MonosGroup(baseName, indices.size, totalSize, indices.sorted())
            }
            .sortedByDescending { it.totalSize }

        val totalBloat = groups.sumOf { group ->
            (group.count - 1) * group.avgSize
        }

        return MonosResult(groups, totalBloat)
    }

    /**
     * Extracts a base name by stripping:
     * 1. Rust hash suffixes (`::h[0-9a-f]{8,16}`)
     * 2. Balanced generic type parameters (`<...>`)
     * 3. Kotlin lambda/closure numbering (`$lambda$N`, `$invoke$N`, etc.)
     * 4. Emscripten invoke wrappers (`invoke_viiii` → `invoke_*`)
     * 5. Trailing numeric suffixes (`.42`, `$0`)
     */
    internal fun extractBaseName(name: String): String {
        var s = name

        // Strip Rust hash suffix: ::h followed by 8-16 hex chars at end
        s = s.replace(Regex("::h[0-9a-f]{8,16}$"), "")

        // Strip balanced angle brackets (generics/type parameters)
        s = stripAngleBrackets(s)

        // Normalize Kotlin lambda/closure suffixes: $lambda$N, $invoke$N, $<init>$N, etc.
        s = s.replace(Regex("\\${'$'}(lambda|invoke|create|default|[a-z]+Impl)\\${'$'}\\d+"), "\\$$1")

        // Normalize trailing numeric disambiguators: .42, $0, _0
        s = s.replace(Regex("[\\.\\${'$'}_]\\d+$"), "")

        // Normalize Emscripten invoke wrappers: invoke_viiii → invoke_*
        s = s.replace(Regex("^invoke_[vifd]+$"), "invoke_*")

        return s.trim()
    }

    private fun stripAngleBrackets(input: String): String {
        val sb = StringBuilder(input.length)
        var depth = 0
        for (ch in input) {
            when {
                ch == '<' -> depth++
                ch == '>' -> { if (depth > 0) depth-- }
                depth == 0 -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}