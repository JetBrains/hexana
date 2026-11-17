package org.jetbrains.hexana.wasm.analysis

import org.jetbrains.hexana.io.CommonByteBuffer
import org.jetbrains.hexana.wasm.WasmModule
import org.jetbrains.hexana.wasm.customSectionByName

/**
 * Detection of WebAssembly modules produced by the **Kotlin/Wasm** compiler backend
 * (the direct Kotlin → WasmGC `wasmJs` target — *not* GraalVM Web Image, *not* Emscripten).
 *
 * Unlike Web Image — which mangles a JVM type signature into every function name — the Kotlin/Wasm
 * backend writes plain, fully-qualified Kotlin names *verbatim* into the `name` custom section
 * (e.g. `com.example.Greeting.greet`, `kotlin.collections.joinToString$default`). No demangling is
 * required; see [KotlinWasmNameParser], which only *structures* the name.
 *
 * Detection keys on the import **field-name** prefix `kotlin.wasm.internal.`: the stdlib runtime
 * imports its intrinsics (e.g. `kotlin.wasm.internal.jsThrow`, `…getInterfaceVTable`) under field
 * names in the Kotlin package namespace. No other toolchain emits Kotlin-package import names, so
 * this separates Kotlin/Wasm cleanly from Web Image (`interop`/`jsbody`/`compat` modules) and
 * Emscripten (C-style `env` fields). Crucially these are real *import* fields, not the debug `name`
 * section, so they survive wasm-opt and release builds. The **host module** they sit under is *not*
 * a reliable signal — it has changed across Kotlin versions (older builds use `env`, current `wasmJs`
 * routes everything through a single `js_code` module), and `env` collides with Emscripten. A
 * single-`js_code`-module binary is accepted as a secondary signal. WasmGC usage is deliberately
 * **not** part of the fingerprint — Web Image emits WasmGC too, so it cannot discriminate.
 *
 * @see KotlinWasmNameParser for turning a retained function name into a navigable [KotlinWasmSymbol].
 */
data class KotlinWasmInfo(
    /** True when the `js_code` import fingerprint matched. */
    val isKotlinWasm: Boolean,
    /**
     * True when a `"name"` custom section is present. Release builds run wasm-opt, which strips it;
     * without it no per-function Kotlin symbol can be resolved.
     */
    val hasNameSection: Boolean,
) {
    /** True when the module is Kotlin/Wasm *and* retained function names we can resolve to source. */
    val canResolveSymbols: Boolean get() = isKotlinWasm && hasNameSection

    companion object {
        val NOT_KOTLIN_WASM = KotlinWasmInfo(isKotlinWasm = false, hasNameSection = false)
    }
}

object KotlinWasmDetector {
    /** Import field-name prefix carried by the Kotlin/Wasm stdlib runtime intrinsics — the primary signal. */
    const val RUNTIME_FIELD_PREFIX: String = "kotlin.wasm.internal."

    /** The single import module the current Kotlin/Wasm `wasmJs` backend routes all imports through. */
    const val JS_INTEROP_MODULE: String = "js_code"

    /**
     * Probe [module] for the Kotlin/Wasm import fingerprint. [buffer] must be position-independent
     * (callers pass a duplicated buffer); import records are read on demand.
     */
    fun detect(module: WasmModule, buffer: CommonByteBuffer): KotlinWasmInfo {
        val importSection = module.importSection ?: return KotlinWasmInfo.NOT_KOTLIN_WASM
        val records = with(importSection) { buffer.getImportRecords() }
        if (records.isEmpty()) return KotlinWasmInfo.NOT_KOTLIN_WASM

        // Primary, version-independent signal: a stdlib runtime intrinsic imported under a
        // `kotlin.wasm.internal.*` field name (present whether the host module is `env` or `js_code`).
        val hasRuntimeImport = records.any { it.name.startsWith(RUNTIME_FIELD_PREFIX) }
        // Secondary: the current wasmJs target routes every import through the single `js_code` module.
        val onlyJsCode = records.any { it.prefix == JS_INTEROP_MODULE } && records.all { it.prefix == JS_INTEROP_MODULE }
        if (!hasRuntimeImport && !onlyJsCode) return KotlinWasmInfo.NOT_KOTLIN_WASM

        val hasName = module.customSectionByName("name") != null
        return KotlinWasmInfo(isKotlinWasm = true, hasNameSection = hasName)
    }
}

/** How a [KotlinWasmSymbol] should be presented and resolved. */
enum class KotlinWasmSymbolKind {
    /** Instance/static member function of a class. */
    METHOD,

    /** Constructor (`<init>`). */
    CONSTRUCTOR,

    /** Property getter (`<get-name>`); [KotlinWasmSymbol.member] is the property name. */
    PROPERTY_GETTER,

    /** Property setter (`<set-name>`); [KotlinWasmSymbol.member] is the property name. */
    PROPERTY_SETTER,

    /** Top-level (file-level) function or property; [KotlinWasmSymbol.packageFqn] holds the package. */
    TOP_LEVEL,

    /** Compiler-synthesized lambda / SAM class member (`$lambda`); navigation targets the enclosing declaration. */
    LAMBDA,
}

/**
 * A Kotlin symbol structured from a Kotlin/Wasm `name`-section function name.
 *
 * The Kotlin/Wasm backend emits readable FQNs, so this is a *parse*, not a demangle. The shape is
 * `<package>.<Class>[$<synthetic>…][.<member>]`, with the member optionally being an accessor marker
 * (`<init>`, `<get-x>`, `<set-x>`) or a `$default` stub. Overloads share a name across function
 * indices, so resolution is by class + member name only — pick the first match or present a list.
 */
data class KotlinWasmSymbol(
    /** The original verbatim name from the `name` section. */
    val raw: String,
    val kind: KotlinWasmSymbolKind,
    /**
     * Navigable outer-class FQN with synthetic `$…` suffixes stripped (e.g. `com.example.App` from
     * `com.example.App$Content$1`), or `null` for a pure top-level declaration.
     */
    val classFqn: String?,
    /** Package FQN for a top-level declaration (`kind == TOP_LEVEL`), else `null`. */
    val packageFqn: String?,
    /** Member to resolve: a function name, a property name (for get/set), or `<init>`; `null` if unknown. */
    val member: String?,
) {
    /** Innermost simple class name for short-name PSI fallback lookups, or `null` for top-level. */
    val simpleClassName: String? get() = classFqn?.substringAfterLast('.')

    /** Human-readable form for tooltips. */
    fun display(): String = when (kind) {
        KotlinWasmSymbolKind.CONSTRUCTOR -> "${classFqn ?: "?"}.<init>()"
        KotlinWasmSymbolKind.PROPERTY_GETTER -> "val ${qualifier()}$member"
        KotlinWasmSymbolKind.PROPERTY_SETTER -> "var ${qualifier()}$member"
        KotlinWasmSymbolKind.TOP_LEVEL -> "${packageFqn?.let { "$it." }.orEmpty()}${member ?: raw}()"
        KotlinWasmSymbolKind.LAMBDA -> "$raw (lambda)"
        KotlinWasmSymbolKind.METHOD -> "${classFqn ?: ""}.${member ?: ""}()"
    }

    private fun qualifier(): String = (classFqn ?: packageFqn)?.let { "$it." }.orEmpty()
}

/**
 * Structures a Kotlin/Wasm `name`-section function name into a [KotlinWasmSymbol]. Pure string work
 * (lives in `commonMain`); PSI resolution happens in the IDE layer.
 *
 * Heuristic for the package/class boundary: an accessor marker (`<init>`/`<get-…>`/`<set-…>`) proves
 * the preceding segment is a class; otherwise the final dotted segment is treated as a member of a
 * class when the segment *before* it looks class-like (starts upper-case, or carries a `$` synthetic
 * marker), and as a top-level declaration in a package when it does not.
 */
object KotlinWasmNameParser {
    private const val DEFAULT_SUFFIX = "\$default"
    private const val LAMBDA_MARKER = "\$lambda"
    private const val GET_PREFIX = "<get-"
    private const val SET_PREFIX = "<set-"

    /** Returns the structured symbol, or `null` for names that are not Kotlin declarations. */
    fun parse(name: String): KotlinWasmSymbol? {
        if (name.isBlank()) return null
        // Real Kotlin declarations are fully qualified; a name without a '.' is internal/synthetic.
        val lastDot = name.lastIndexOf('.')
        if (lastDot <= 0 || lastDot == name.length - 1) return null

        val head = name.substring(0, lastDot)
        val tail = name.substring(lastDot + 1)
        // Reject internal/generated names like `func.0` — a real Kotlin member is never all-digits.
        if (tail.all { it.isDigit() }) return null

        // Accessor markers unambiguously make `head` the (possibly synthetic) class path.
        when {
            tail == "<init>" ->
                return classMember(name, head, "<init>", KotlinWasmSymbolKind.CONSTRUCTOR)
            tail.startsWith(GET_PREFIX) && tail.endsWith(">") ->
                return classMember(name, head, tail.substring(GET_PREFIX.length, tail.length - 1), KotlinWasmSymbolKind.PROPERTY_GETTER)
            tail.startsWith(SET_PREFIX) && tail.endsWith(">") ->
                return classMember(name, head, tail.substring(SET_PREFIX.length, tail.length - 1), KotlinWasmSymbolKind.PROPERTY_SETTER)
        }

        // No accessor marker: distinguish a class method from a top-level declaration by the
        // shape of head's final segment.
        val member = tail.removeSuffix(DEFAULT_SUFFIX)
        val headLast = head.substringAfterLast('.')
        val headIsClass = headLast.firstOrNull()?.isUpperCase() == true || headLast.contains('$')
        return if (headIsClass) {
            classMember(name, head, member, KotlinWasmSymbolKind.METHOD)
        } else {
            KotlinWasmSymbol(
                raw = name,
                kind = if (name.contains(LAMBDA_MARKER)) KotlinWasmSymbolKind.LAMBDA else KotlinWasmSymbolKind.TOP_LEVEL,
                classFqn = null,
                packageFqn = head,
                member = member,
            )
        }
    }

    private fun classMember(raw: String, classPath: String, member: String, kind: KotlinWasmSymbolKind): KotlinWasmSymbol {
        // classPath may carry synthetic `$` suffixes (nested classes, $lambda, anonymous $N). The
        // navigable outer class is the path with its final segment truncated at the first `$`.
        val pkg = classPath.substringBeforeLast('.', "")
        val finalSeg = classPath.substringAfterLast('.')
        val outerClass = finalSeg.substringBefore('$')
        val navigableFqn = if (pkg.isEmpty()) outerClass else "$pkg.$outerClass"
        val effectiveKind = if (classPath.contains(LAMBDA_MARKER)) KotlinWasmSymbolKind.LAMBDA else kind
        return KotlinWasmSymbol(
            raw = raw,
            kind = effectiveKind,
            classFqn = navigableFqn.ifEmpty { null },
            packageFqn = null,
            member = member,
        )
    }
}
