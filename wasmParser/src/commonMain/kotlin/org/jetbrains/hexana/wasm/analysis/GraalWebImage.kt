package org.jetbrains.hexana.wasm.analysis

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.customSectionByName

/**
 * Detection of WebAssembly modules produced by **GraalVM Web Image** (the Native Image / SubstrateVM
 * WebAssembly backend that compiles Java/JVM bytecode to WASM via `native-image --tool:svm-wasm`).
 *
 * Web Image modules import their JavaScript glue under a small set of fixed module names
 * (`interop`, `jsbody`, `compat`, plus `io` / `convert`). These import-module fields are mandatory
 * in the binary and survive both name stripping and identifier minification, which makes them the
 * most reliable detection signal — far stronger than the `"GraalVM Runtime Environment"` string
 * (which lives in a data segment, is generic to every Native Image build, and may be Base64-encoded
 * under the WasmGC backend).
 *
 * @see GraalSymbolDemangler for decoding individual function names back to Java symbols.
 */
data class GraalWebImageInfo(
    /** True when the import-module fingerprint matched. */
    val isWebImage: Boolean,
    /** The fingerprint import-module fields that were present (subset of [GraalWebImageDetector.FINGERPRINT_MODULES]). */
    val matchedImportModules: Set<String>,
    /**
     * True when a `"name"` custom section is present. Without it, function names are stripped and
     * no per-function Java symbol can be resolved (Web Image strips names unless built with `-g`).
     */
    val hasNameSection: Boolean,
) {
    /** True when the module is Web Image *and* carries function names we can attempt to demangle. */
    val canResolveSymbols: Boolean get() = isWebImage && hasNameSection

    companion object {
        val NOT_WEB_IMAGE = GraalWebImageInfo(isWebImage = false, matchedImportModules = emptySet(), hasNameSection = false)
    }
}

object GraalWebImageDetector {
    /**
     * Import-module fields emitted by the Web Image JS glue (see `WasmJSCounterparts` / `WasmImports`
     * in `oracle/graal`). `interop` + `jsbody` + `compat` together are a distinctive fingerprint.
     */
    val FINGERPRINT_MODULES: Set<String> = setOf("interop", "jsbody", "compat")

    /** Require at least this many distinct fingerprint modules before claiming a Web Image origin. */
    private const val MIN_MATCH = 2

    /**
     * Probe [module] for the Web Image import fingerprint. [buffer] must be positioned-independent
     * (callers pass a duplicated buffer); import records are read on demand.
     */
    fun detect(module: WasmModule, buffer: CommonByteBuffer): GraalWebImageInfo {
        val importSection = module.importSection ?: return GraalWebImageInfo.NOT_WEB_IMAGE
        val modules = with(importSection) { buffer.getImportRecords() }
            .mapTo(HashSet()) { it.prefix }
        val matched = modules.intersect(FINGERPRINT_MODULES)
        if (matched.size < MIN_MATCH) return GraalWebImageInfo.NOT_WEB_IMAGE
        val hasName = module.customSectionByName("name") != null
        return GraalWebImageInfo(isWebImage = true, matchedImportModules = matched, hasNameSection = hasName)
    }
}

/**
 * A Java symbol decoded (best-effort) from a GraalVM Web Image mangled WASM function name.
 *
 * In Web Image's default `REDUCED` naming mode a compiled Java method is named
 * `_<Class>_<method>__<paramTypes><returnType>`, e.g. `_Highlighter_append___C_V` is
 * `Highlighter.append(char) : void`. The class name is the **simple** name (no package), so callers
 * resolving to PSI should look up by [simpleClassName] and disambiguate by [methodName].
 *
 * The encoding is lossy (no delimiters between concatenated types, `_` is overloaded), so
 * [parameterTypes] / [returnType] are best-effort and intended for display, not for exact matching.
 */
data class GraalJavaSymbol(
    /** Class name as encoded, with `$` retained for nested classes (e.g. `JavacCompilerWrapper$Result`). */
    val className: String,
    /** Innermost simple class name for short-name lookups (e.g. `Result`, `Highlighter`). */
    val simpleClassName: String,
    val methodName: String,
    /** Best-effort decoded parameter type names (may be approximate for arrays / qualified refs). */
    val parameterTypes: List<String>,
    /** Best-effort decoded return type name, or `null` if it could not be determined. */
    val returnType: String?,
    /** True for compiler-synthesized symbols (lambdas etc.) that won't resolve to source-level bytecode. */
    val isSynthetic: Boolean,
) {
    /** Human-readable `Class.method(P1, P2) : R` for tooltips. */
    fun display(): String = buildString {
        append(className)
        append('.')
        append(methodName)
        append('(')
        append(parameterTypes.joinToString(", "))
        append(')')
        returnType?.let {
            append(" : ")
            append(it)
        }
    }
}

object GraalSymbolDemangler {
    /** Internal generated functions are resolved with this prefix in Web Image and are not Java methods. */
    private const val INTERNAL_PREFIX = "func."

    private val PRIMITIVES = mapOf(
        'Z' to "boolean", 'B' to "byte", 'C' to "char", 'S' to "short",
        'I' to "int", 'J' to "long", 'F' to "float", 'D' to "double", 'V' to "void",
    )

    /**
     * Best-effort decode of a Web Image WASM function name into a [GraalJavaSymbol].
     * Returns `null` for names that are not compiled Java methods (internal `func.*`, imports,
     * or anything that does not match the `_<class>_<method>__<types>` shape).
     */
    fun demangle(functionName: String): GraalJavaSymbol? {
        if (functionName.startsWith(INTERNAL_PREFIX)) return null
        // Defined Java methods start with the REDUCED type prefix '_'.
        if (!functionName.startsWith("_")) return null

        // Method / type-list boundary is the inserted "__"; the first occurrence is the separator
        // (class and method names do not contain "__"; synthetic classes use "$$").
        val sepIdx = functionName.indexOf("__")
        if (sepIdx <= 1) return null

        val head = functionName.substring(1, sepIdx) // drop leading '_'
        val typeList = functionName.substring(sepIdx + 2)

        // Split head into <class>_<method> on the last underscore.
        val lastUnderscore = head.lastIndexOf('_')
        if (lastUnderscore <= 0 || lastUnderscore == head.length - 1) return null
        val className = head.substring(0, lastUnderscore)
        val methodName = head.substring(lastUnderscore + 1)
        if (className.isEmpty() || methodName.isEmpty()) return null

        val simpleClassName = className.substringAfterLast('.').substringAfterLast('$')
        val isSynthetic = className.contains("$$") || methodName.contains("$$")

        val types = decodeTypeList(typeList)
        val returnType = types.lastOrNull()
        val parameterTypes = if (types.isEmpty()) emptyList() else types.dropLast(1)

        return GraalJavaSymbol(
            className = className,
            simpleClassName = simpleClassName,
            methodName = methodName,
            parameterTypes = parameterTypes,
            returnType = returnType,
            isSynthetic = isSynthetic,
        )
    }

    /**
     * Decode the concatenated type list. The encoding is irregular (Graal's own source flags it as
     * a TODO), so this is best-effort. Observed shapes:
     * - reduced ref / primitive: `_<SimpleName>` / `_<PrimitiveLetter>` (e.g. `_String`, `_J`)
     * - primitive-element array: `_$<elem>` (e.g. `_$C` = `char[]`)
     * - reference-element array: `$L<pkg>_<Name>_` with NO leading `_` (e.g. `$Ljava_lang_String_` = `java.lang.String[]`)
     *
     * Note the asymmetric leading `_`, and that a `_`-introduced simple name may itself start with
     * `L` (e.g. `Long`, `List`), so JVM-`L…;` qualified parsing is applied only to array elements.
     */
    private fun decodeTypeList(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = s.length
        while (i < n) {
            when (s[i]) {
                '_' -> {
                    i++ // consume the separating underscore
                    if (i < n && s[i] == '$') {
                        val (elem, next) = readArray(s, i)
                        i = next
                        out.add(elem)
                    } else {
                        val (elem, next) = readSimple(s, i)
                        i = next
                        out.add(elem)
                    }
                }
                '$' -> {
                    val (elem, next) = readArray(s, i)
                    i = next
                    out.add(elem)
                }
                else -> {
                    // Unexpected shape — keep the remainder verbatim and stop.
                    out.add(s.substring(i))
                    break
                }
            }
        }
        return out
    }

    /** Read an array type starting at the first `$` in [start]; returns `elem[]…` and the next index. */
    private fun readArray(s: String, start: Int): Pair<String, Int> {
        val n = s.length
        var i = start
        var dims = 0
        while (i < n && s[i] == '$') { dims++; i++ }
        val (elem, next) = readArrayElement(s, i)
        return (elem + "[]".repeat(dims)) to next
    }

    /** Array element: either a JVM-qualified ref (`L<pkg>_<Name>_`) or a primitive/simple name. */
    private fun readArrayElement(s: String, start: Int): Pair<String, Int> {
        val n = s.length
        if (start >= n) return "" to start
        if (s[start] == 'L') {
            // Qualified object ref: read until the `_` that stands for `;` (the one followed by
            // another type start: `_`, `$`, or end-of-string).
            var j = start + 1
            while (j < n) {
                if (s[j] == '_' && (j + 1 == n || s[j + 1] == '_' || s[j + 1] == '$')) break
                j++
            }
            val name = s.substring(start + 1, j).replace('_', '.')
            return name to (if (j < n) j + 1 else j)
        }
        return readSimple(s, start)
    }

    /** Read a primitive (single letter) or reduced simple reference name up to the next type boundary. */
    private fun readSimple(s: String, start: Int): Pair<String, Int> {
        val n = s.length
        var j = start
        while (j < n && s[j] != '_' && s[j] != '$') j++
        val tok = s.substring(start, j)
        val decoded = if (tok.length == 1) PRIMITIVES[tok[0]] ?: tok else tok
        return decoded to j
    }
}