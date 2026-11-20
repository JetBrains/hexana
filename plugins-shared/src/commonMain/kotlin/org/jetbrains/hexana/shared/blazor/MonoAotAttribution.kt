package org.jetbrains.hexana.shared.blazor

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.WasmBinaryProvider
import org.jetbrains.hexana.providers.pe.clr.ClrMetadata
import org.jetbrains.hexana.providers.pe.clr.ClrMethodDef
import org.jetbrains.hexana.providers.pe.clr.ClrTypeDef
import org.jetbrains.hexana.wasm.analysis.BinarySizeNode
import org.jetbrains.hexana.wasm.sections.WasmTypeIndexExternalType

/** An assembly of the app, with its metadata when the host could read its Webcil file. */
class MonoAotAssembly(val name: String, val metadata: ClrMetadata?)

/** One defined function of the runtime module: its name from the symbol map, and its code bytes. */
class MonoAotFunction(val index: Int, val name: String, val offset: Int, val size: Int)

/** Top-level groups of the AOT size tree. */
object MonoAotGroups {
    const val MANAGED = "Managed code (AOT)"
    const val GENERIC_INSTANCES = "Generic instances"
    const val WRAPPERS = "Wrappers and trampolines"
    const val RUNTIME = "Mono runtime and other native code"
    const val ICU = "ICU"
    const val NATIVE_LIBRARIES = "Native libraries"
    const val UNRESOLVED = "(unresolved)"

    /** Under an assembly: code it compiled for another assembly's types (generic instantiations, inlined helpers). */
    fun from(owner: String): String = "From $owner"

    /** Suffix of an unboxing stub (`ut_`), so it does not read as a duplicate of the method it unboxes for. */
    const val UNBOXING_STUB = " (unboxing stub)"
}

/**
 * Which .NET code fills a Blazor WebAssembly `dotnet.native.wasm` that was compiled ahead of time
 * (`RunAOTCompilation`). AOT links every compiled method into the runtime module, so it — not the
 * assemblies — is most of the download; this tells it apart again, per assembly, namespace, type and
 * method, next to the generic instantiations, wrappers, the Mono runtime itself and ICU.
 *
 * Mono names an AOT method after its assembly — the name with `.` as `_`, and `corlib` for
 * System.Private.CoreLib — then the method's full name passed through [sanitize]:
 * `System.Text.Json.JsonElement:TryGetInt64 (long&)` in System.Text.Json becomes
 * `System_Text_Json_System_Text_Json_JsonElement_TryGetInt64_long_`.
 * Unboxing trampolines add `ut_` in front, and instantiations of generic code shared across assemblies
 * live in the synthetic `aot-instances` assembly. Because `_` both joins and replaces, the namespace,
 * type and method boundaries are recovered from the assemblies' metadata, sanitized the same way.
 */
object MonoAotAttribution {
    private const val CORELIB_PREFIX = "corlib"
    private const val INSTANCES_PREFIX = "aot_instances"
    private const val UNBOX_TRAMPOLINE = "ut_"

    /** True when [functions] look like Mono AOT output for one of [assemblyNames]: cheap, no metadata. */
    fun isAotModule(functions: Collection<String>, assemblyNames: Collection<String>): Boolean {
        val prefixes = assemblyNames.map { symbolPrefix(it) + "_" } + "${INSTANCES_PREFIX}_"
        return functions.any { name -> prefixes.any { name.startsWith(it) } }
    }

    /** The prefix Mono gives the methods of [assemblyName]. */
    fun symbolPrefix(assemblyName: String): String =
        if (assemblyName == "System.Private.CoreLib" || assemblyName == "mscorlib") CORELIB_PREFIX else sanitize(assemblyName)

    /**
     * What Mono's symbol mangling makes of [text]: ASCII letters and digits kept, `>` `(` `)` dropped,
     * everything else `_`. Read off real output: `Counter+<>c` is `Counter__c`, `<<Main>$>d__0` is
     * `__Main_d__0`, `TryGetInt64 (long&)` is `TryGetInt64_long_`.
     */
    fun sanitize(text: String): String = buildString(text.length) {
        for (c in text) {
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> append(c)
                c == '>' || c == '(' || c == ')' -> Unit
                else -> append('_')
            }
        }
    }

    /**
     * The size tree of [functions] (every function with code, named), resolving managed code against
     * [assemblies]. Assemblies without metadata still get their code, under [MonoAotGroups.UNRESOLVED] types.
     */
    fun attribute(functions: List<MonoAotFunction>, assemblies: List<MonoAotAssembly>): BinarySizeNode =
        Attribution(assemblies).apply { functions.forEach(::add) }.result()

    /**
     * [attribute] for a single-threaded host (the VS Code webview): calls [pause] every [batch] functions
     * so the work does not hold the UI thread for its whole duration.
     */
    suspend fun attribute(
        functions: List<MonoAotFunction>,
        assemblies: List<MonoAotAssembly>,
        batch: Int,
        pause: suspend () -> Unit,
    ): BinarySizeNode {
        val attribution = Attribution(assemblies)
        pause()
        functions.forEachIndexed { index, function ->
            attribution.add(function)
            if ((index + 1) % batch == 0) pause()
        }
        return attribution.result()
    }

    /** One attribution in progress: the indexes built once, the trees filled one function at a time. */
    private class Attribution(assemblies: List<MonoAotAssembly>) {
        private val owners = assemblies.map { AssemblyTypes(it) }
        // Prefixes nest (`Microsoft_AspNetCore_Components` and `…_Components_Web`): longest first.
        private val byPrefix = owners.sortedByDescending { it.prefix.length }
        private val index = TypeIndex(owners)
        private val managed = Tree()
        private val instances = Tree()
        private val other = Tree()

        fun add(function: MonoAotFunction) {
            val name = function.name.removePrefix(UNBOX_TRAMPOLINE)
            val emitters = byPrefix.filter { name.startsWith(it.prefix + "_") }
            if (emitters.isNotEmpty()) {
                // The first emitting assembly whose symbol continues with a known type; an assembly compiles
                // code for other assemblies' types too (`ConcurrentDictionary<K, V>` instantiated in the DI
                // assembly), and that code stays under the assembly that compiled it.
                var emitter = emitters.first()
                var hit: TypeHit? = null
                for (candidate in emitters) {
                    val rest = name.substring(candidate.prefix.length + 1)
                    if (isWrapper(rest)) break
                    index.typeAt(rest, preferred = candidate)?.let { hit = it; emitter = candidate }
                    if (hit != null) break
                }
                val rest = name.substring(emitter.prefix.length + 1)
                val found = hit
                when {
                    isWrapper(rest) -> other.add(listOf(MonoAotGroups.WRAPPERS, emitter.name), function, function.name)
                    found != null -> {
                        val owned = found.owner === emitter
                        val group = if (owned) listOf(emitter.name) else listOf(emitter.name, MonoAotGroups.from(found.owner.name))
                        managed.add(group + labelled(found.labels(generic = !owned), function), function, null)
                    }
                    else -> managed.add(listOf(emitter.name, MonoAotGroups.UNRESOLVED), function, rest)
                }
            } else if (name.startsWith(INSTANCES_PREFIX + "_")) {
                val rest = name.substring(INSTANCES_PREFIX.length + 1)
                val hit = if (isWrapper(rest)) null else index.typeAt(rest, preferred = null)
                when {
                    isWrapper(rest) -> other.add(listOf(MonoAotGroups.WRAPPERS, "aot-instances"), function, function.name)
                    // Owned by whichever assembly defines the generic type or method.
                    hit != null -> instances.add(listOf(hit.owner.name) + labelled(hit.labels(generic = true), function), function, null)
                    else -> instances.add(listOf(MonoAotGroups.UNRESOLVED), function, rest)
                }
            } else {
                other.add(listOf(nativeGroup(function.name)), function, function.name)
            }
        }

        fun result(): BinarySizeNode {
            val children = buildList {
                managed.toNode(MonoAotGroups.MANAGED)?.let(::add)
                instances.toNode(MonoAotGroups.GENERIC_INSTANCES)?.let(::add)
                other.root.children.values.mapNotNull { it.toNode() }.forEach(::add)
            }.sortedByDescending { it.size }
            return BinarySizeNode("AOT", children.sumOf { it.size }, offset = 0, children = children)
        }
    }

    private fun labelled(labels: List<String>, function: MonoAotFunction): List<String> =
        if (function.name.startsWith(UNBOX_TRAMPOLINE)) labels.dropLast(1) + (labels.last() + MonoAotGroups.UNBOXING_STUB) else labels

    private fun isWrapper(rest: String): Boolean =
        rest.startsWith("wrapper_") || rest.startsWith("aot_wrapper_") || rest.contains("_icall_cold_wrapper_") ||
            rest.startsWith("llvm_")

    /** How `mono_method_full_name` spells CoreLib's primitive types. */
    private val PRIMITIVE_ALIASES = mapOf(
        "bool" to "System.Boolean", "char" to "System.Char", "sbyte" to "System.SByte", "byte" to "System.Byte",
        "int16" to "System.Int16", "uint16" to "System.UInt16", "int" to "System.Int32", "uint" to "System.UInt32",
        "long" to "System.Int64", "ulong" to "System.UInt64", "single" to "System.Single", "double" to "System.Double",
        "string" to "System.String", "object" to "System.Object", "intptr" to "System.IntPtr", "uintptr" to "System.UIntPtr",
    )

    private val ICU_NAME = Regex("""^(u[a-z0-9]*_|U[A-Z][A-Za-z]*_|.*icu::|ucol|ubrk|uloc|ures|ucnv)""")

    private fun nativeGroup(name: String): String = when {
        name.startsWith("wrapper_") || name.startsWith("aot_wrapper_") || name.startsWith("mono_aot_") || name.startsWith("wasm_invoke_") ->
            MonoAotGroups.WRAPPERS
        name.startsWith("SystemNative_") || name.startsWith("GlobalizationNative_") || name.startsWith("CompressionNative_") ||
            name.startsWith("Brotli") -> MonoAotGroups.NATIVE_LIBRARIES
        ICU_NAME.containsMatchIn(name) -> MonoAotGroups.ICU
        else -> MonoAotGroups.RUNTIME
    }

    /** One assembly's types and methods, sanitized the way Mono spells them. */
    private class AssemblyTypes(assembly: MonoAotAssembly) {
        val name = assembly.name
        val prefix = symbolPrefix(assembly.name)
        private val metadata = assembly.metadata

        /** Sanitized full type name → type; CoreLib adds the C# aliases Mono prints (`string`, `int16`). */
        val types: Map<String, ClrTypeDef> = run {
            val own = metadata?.typeDefs.orEmpty().filter { it.name != "<Module>" }.associateBy { sanitize(it.fullName) }
            if (prefix != CORELIB_PREFIX) own else own + PRIMITIVE_ALIASES.mapNotNull { (alias, fullName) -> own[sanitize(fullName)]?.let { alias to it } }
        }

        /** A type's methods by sanitized name, longest first so `get_Item` wins over `get`. */
        val methodsByType: Map<Int, List<Pair<String, ClrMethodDef>>> by lazy {
            metadata?.methodsByType.orEmpty().mapValues { (_, methods) ->
                methods.map { sanitize(it.name) to it }.sortedByDescending { it.first.length }
            }
        }
    }

    /** A type a symbol continues with, and what follows it (method, type arguments, signature). */
    private class TypeHit(val owner: AssemblyTypes, val type: ClrTypeDef, val afterType: String) {
        fun labels(generic: Boolean): List<String> = listOf(namespaceOf(type), typeName(type), methodLabel(generic))

        private fun methodLabel(generic: Boolean): String {
            val methods = owner.methodsByType[type.row].orEmpty()
            // Right after the type for a plain method; after the type arguments for an instantiated type.
            val match = methods.firstOrNull { (mangled, _) -> afterType == mangled || afterType.startsWith(mangled + "_") }
                ?: methods.firstOrNull { (mangled, _) -> afterType.contains("_${mangled}_") || afterType.endsWith("_$mangled") }
            val method = match?.second ?: return afterType
            val overloads = methods.count { it.second.name == method.name }
            return if (overloads == 1 && !generic && afterType.startsWith(match.first)) method.signature else {
                val tail = afterType.substringAfter(match.first, "").trim('_')
                if (tail.isEmpty()) method.name else "${method.name} [$tail]"
            }
        }

        private fun namespaceOf(type: ClrTypeDef): String =
            type.fullName.substringBefore('+').substringBeforeLast('.', "").ifEmpty { "(global namespace)" }

        private fun typeName(type: ClrTypeDef): String = type.fullName.substringAfterLast('.').let {
            if (type.genericParameters.isEmpty()) it else it.substringBeforeLast('`') + type.genericParameters.joinToString(", ", "<", ">")
        }
    }

    /** Every assembly's types under their sanitized names, so a symbol is scanned once whoever owns its type. */
    private class TypeIndex(owners: List<AssemblyTypes>) {
        private val types = HashMap<String, MutableList<Pair<AssemblyTypes, ClrTypeDef>>>()

        init {
            for (owner in owners) for ((key, type) in owner.types) types.getOrPut(key) { ArrayList(1) } += owner to type
        }

        /**
         * The longest type whose sanitized name starts [rest] and is followed by `_` — [preferred]'s own
         * when two assemblies define the same name.
         */
        fun typeAt(rest: String, preferred: AssemblyTypes?): TypeHit? {
            var cut = rest.lastIndexOf('_')
            while (cut > 0) {
                types[rest.substring(0, cut)]?.let { found ->
                    val (owner, type) = found.firstOrNull { it.first === preferred } ?: found.first()
                    return TypeHit(owner, type, rest.substring(cut + 1))
                }
                cut = rest.lastIndexOf('_', cut - 1)
            }
            return null
        }
    }

    /** A mutable grouping tree; leaves are functions, groups add up their children. */
    private class Tree {
        class Group(val name: String) {
            val children = LinkedHashMap<String, Group>()
            val functions = mutableListOf<Pair<String, MonoAotFunction>>()

            fun toNode(label: String = name): BinarySizeNode? {
                val nodes = children.values.mapNotNull { it.toNode() } +
                    functions.map { (leaf, f) -> BinarySizeNode(leaf, f.size, f.offset) }
                if (nodes.isEmpty()) return null
                // A group of one function is that function under the group's name.
                val sorted = nodes.sortedByDescending { it.size }
                return BinarySizeNode(label, sorted.sumOf { it.size }, sorted.minOf { it.offset }, sorted)
            }
        }

        val root = Group("")

        /** Adds [function] under [path]; the last path segment names the leaf unless [leaf] does. */
        fun add(path: List<String>, function: MonoAotFunction, leaf: String?) {
            val groups = if (leaf == null) path.dropLast(1) else path
            var group = root
            for (segment in groups) group = group.children.getOrPut(segment) { Group(segment) }
            group.functions += (leaf ?: path.last()) to function
        }

        fun toNode(label: String): BinarySizeNode? = root.toNode(label)
    }
}

/**
 * The AOT breakdown of one `dotnet.native.wasm`, as the host hands it to the tab
 * ([org.jetbrains.hexana.shared.TableTabsContext.monoAot]).
 */
class MonoAotReport(
    val root: BinarySizeNode,
    /** Bytes of the module itself, for the percentages in the prologue. */
    val moduleSize: Int,
    /** Assemblies whose metadata could not be read: their code is attributed to the assembly only. */
    val withoutMetadata: List<String>,
) {
    companion object {
        /** Largest assembly read for its metadata; System.Private.CoreLib is ~1.6 MB. */
        const val MAX_ASSEMBLY_FILE_SIZE: Long = 64L * 1024 * 1024

        /**
         * Assemblies a Blazor app's manifest lists, by assembly name (`System.Text.Json`), with their files.
         * The manifest is input, not trust: only entries that name a plain file the directory listing found
         * ([BlazorBundleFile.size] is set from the listing, so `../` names never have one) and that fit
         * [MAX_ASSEMBLY_FILE_SIZE] are returned, so opening a module never reads outside its `_framework`
         * directory or loads a huge file.
         */
        fun assemblyFiles(bundle: BlazorBundle): List<Pair<String, String>> = bundle.files
            .filter { it.resource.kind in setOf(BlazorResourceKind.CoreAssembly, BlazorResourceKind.Assembly, BlazorResourceKind.LazyAssembly) }
            .filter { file -> file.size?.let { it <= MAX_ASSEMBLY_FILE_SIZE } == true && isPlainFileName(file.resource.fileName) }
            .map { it.resource.virtualPath.removeSuffix(".wasm").removeSuffix(".dll") to it.resource.fileName }

        private fun isPlainFileName(name: String): Boolean =
            name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name && ':' !in name

        /** The named defined functions of [provider], with their code ranges; empty without names. */
        fun functionsOf(provider: WasmBinaryProvider): List<MonoAotFunction> {
            val names = provider.nameSection?.functionSubsection?.namemap ?: return emptyList()
            val imported = provider.imports.count { it.type is WasmTypeIndexExternalType }
            return provider.codeOffsets.mapIndexedNotNull { codeIndex, entry ->
                val index = imported + codeIndex
                names[index]?.let { MonoAotFunction(index, it, entry.offset, entry.size) }
            }
        }

        /**
         * The assemblies to read for [provider]'s report — the app's, from [bundle] — or empty when there is
         * no report to make: [provider] is not the app's runtime module, or its function names show no AOT
         * code (an interpreter-only build, or no symbol map). Cheap: names only, no assembly is read.
         */
        fun assembliesToRead(provider: WasmBinaryProvider, bundle: BlazorBundle): List<Pair<String, String>> {
            if (bundle.current?.resource?.kind != BlazorResourceKind.WasmNative) return emptyList()
            val files = assemblyFiles(bundle)
            val names = provider.nameSection?.functionSubsection?.namemap?.values ?: return emptyList()
            return if (MonoAotAttribution.isAotModule(names, files.map { it.first })) files else emptyList()
        }

        /** An assembly's metadata from its Webcil file (`.wasm` wrapper or bare `WbIL`); null when it has none. */
        fun metadataOf(bytes: ByteArray): ClrMetadata? = runCatching {
            WasmBinaryProvider(bytes.toCommonByteBuffer()).webcil?.clrMetadata
        }.getOrNull()

        /** The report for [provider] against [assemblies]; null when none of their code is in it. */
        fun build(provider: WasmBinaryProvider, assemblies: List<MonoAotAssembly>): MonoAotReport? {
            val functions = functionsOf(provider)
            if (!MonoAotAttribution.isAotModule(functions.map { it.name }, assemblies.map { it.name })) return null
            return MonoAotReport(
                root = MonoAotAttribution.attribute(functions, assemblies),
                moduleSize = provider.bytes.size,
                withoutMetadata = assemblies.filter { it.metadata == null }.map { it.name },
            )
        }

        /** [build] that pauses between batches of functions; see the suspending [MonoAotAttribution.attribute]. */
        suspend fun build(provider: WasmBinaryProvider, assemblies: List<MonoAotAssembly>, pause: suspend () -> Unit): MonoAotReport? {
            val functions = functionsOf(provider)
            pause()
            if (!MonoAotAttribution.isAotModule(functions.map { it.name }, assemblies.map { it.name })) return null
            return MonoAotReport(
                root = MonoAotAttribution.attribute(functions, assemblies, batch = PAUSE_BATCH, pause = pause),
                moduleSize = provider.bytes.size,
                withoutMetadata = assemblies.filter { it.metadata == null }.map { it.name },
            )
        }

        /** Functions attributed between pauses: ~2,000 is a few milliseconds of work on the JVM. */
        private const val PAUSE_BATCH = 2_000

        /** [assembliesToRead], [read] each one (null when unreadable), [build]: for hosts that read files synchronously. */
        fun load(provider: WasmBinaryProvider, bundle: BlazorBundle, read: (fileName: String) -> ByteArray?): MonoAotReport? {
            val files = assembliesToRead(provider, bundle).takeIf { it.isNotEmpty() } ?: return null
            return build(provider, files.map { (name, file) -> MonoAotAssembly(name, read(file)?.let(::metadataOf)) })
        }
    }
}

