package org.jetbrains.hexana.mcp.tools

// ---------- Types ----------

sealed class FieldType {
    object Int32 : FieldType()
    object Int64 : FieldType()
    object UInt32 : FieldType()
    object ByteT : FieldType()
    object BooleanT : FieldType()
    object StringT : FieldType()
    object ByteArrayT : FieldType()
    data class ListOf(val element: FieldType) : FieldType()
    data class ClassRef(val clazz: ClassSpec) : FieldType()
}

// Helpers usable where a `FieldType` is expected (e.g. `list(of = string())`).
fun int(): FieldType = FieldType.Int32
fun long(): FieldType = FieldType.Int64
fun uint(): FieldType = FieldType.UInt32
fun byte(): FieldType = FieldType.ByteT
fun string(): FieldType = FieldType.StringT
fun boolean(): FieldType = FieldType.BooleanT
fun bytes(): FieldType = FieldType.ByteArrayT

// Top-level `list(of = …)` usable wherever a `FieldType` is expected
// (e.g. `nullable("superTypes", of = list(of = uint()))`).
fun list(of: FieldType): FieldType = FieldType.ListOf(of)
fun list(of: ClassSpec): FieldType = FieldType.ListOf(FieldType.ClassRef(of))

// ---------- Field spec ----------

data class FieldSpec(
    val name: String,
    val type: FieldType,
    val nullable: Boolean = false,
    val default: Any? = null,
    val serialName: String? = null,
    val describe: String? = null,
    /** Optional fully-qualified name of a [kotlinx.serialization.KSerializer] object. */
    val serializerFqn: String? = null,
) {
    init {
        if (default != null) {
            require(type !is FieldType.ClassRef) {
                "Field '$name': defaults are not supported for embedded classes."
            }
            val expected: Class<*> = when (type) {
                FieldType.Int32, FieldType.UInt32, FieldType.ByteT -> Int::class.javaObjectType
                FieldType.Int64 -> Long::class.javaObjectType
                FieldType.BooleanT -> Boolean::class.javaObjectType
                FieldType.StringT -> String::class.java
                FieldType.ByteArrayT -> ByteArray::class.java
                is FieldType.ListOf -> List::class.java
                is FieldType.ClassRef -> error("unreachable — filtered by require above")
            }
            require(expected.isInstance(default)) {
                "Field '$name': default value `$default` (${default.javaClass.simpleName}) does not match declared type $type."
            }
        }
    }

    val schemaKey: String get() = serialName ?: name
}

// ---------- Class spec ----------

data class ClassSpec(
    val name: String,
    val fields: List<FieldSpec>,
    /** Exactly one of these field names must be present at runtime; emitted as JSON Schema `oneOf`. */
    val oneOf: List<String>? = null,
) {
    /**
     * Returns a new ClassSpec that inherits this one's fields, plus whatever the block adds.
     * Optionally rename the derived class.
     */
    fun with(renameTo: String = name, block: ClassBuilder.() -> Unit): ClassSpec {
        val builder = ClassBuilder(renameTo, fields, oneOf)
        builder.block()
        return builder.build()
    }
}

fun class_(name: String, block: ClassBuilder.() -> Unit): ClassSpec {
    return ClassBuilder(name).apply(block).build()
}

// ---------- Builders ----------

@DslMarker
annotation class HexanaToolsDsl

@HexanaToolsDsl
class ClassBuilder internal constructor(
    private val name: String,
    seed: List<FieldSpec> = emptyList(),
    seedOneOf: List<String>? = null,
) {
    private val fields = seed.toMutableList()
    private var oneOf: List<String>? = seedOneOf

    fun int(name: String, default: Int? = null): FieldHandle =
        addField(name, FieldType.Int32, default)

    fun long(name: String, default: Long? = null): FieldHandle =
        addField(name, FieldType.Int64, default)

    fun uint(name: String): FieldHandle =
        addField(name, FieldType.UInt32)

    fun byte(name: String): FieldHandle =
        addField(name, FieldType.ByteT)

    fun string(name: String, default: String? = null): FieldHandle =
        addField(name, FieldType.StringT, default)

    fun boolean(name: String, default: Boolean? = null): FieldHandle =
        addField(name, FieldType.BooleanT, default)

    fun bytes(name: String): FieldHandle =
        addField(name, FieldType.ByteArrayT)

    fun list(name: String, of: FieldType): FieldHandle =
        addField(name, FieldType.ListOf(of))

    fun list(name: String, of: ClassSpec): FieldHandle =
        addField(name, FieldType.ListOf(FieldType.ClassRef(of)))

    fun embed(name: String, of: ClassSpec): FieldHandle =
        addField(name, FieldType.ClassRef(of))

    fun nullable(name: String, of: FieldType): FieldHandle =
        addField(name, of, nullable = true)

    fun nullable(name: String, of: ClassSpec): FieldHandle =
        addField(name, FieldType.ClassRef(of), nullable = true)

    fun oneOf(vararg props: String) {
        oneOf = props.toList()
    }

    private fun addField(
        name: String,
        type: FieldType,
        default: Any? = null,
        nullable: Boolean = false,
    ): FieldHandle {
        require(fields.none { it.name == name }) { "Duplicate field '$name' in class '${this.name}'." }
        fields += FieldSpec(name, type, nullable, default)
        return FieldHandle(this, fields.lastIndex)
    }

    internal fun mutate(index: Int, transform: (FieldSpec) -> FieldSpec) {
        fields[index] = transform(fields[index])
    }

    internal fun build(): ClassSpec {
        oneOf?.forEach { prop ->
            require(fields.any { it.name == prop }) {
                "oneOf references unknown field '$prop' in class '$name'."
            }
        }
        return ClassSpec(name, fields.toList(), oneOf)
    }
}

/** Chainable handle returned by every field declaration; lets you attach `describe` / `serialName`. */
class FieldHandle internal constructor(
    private val builder: ClassBuilder,
    private val index: Int,
) {
    fun describe(text: String): FieldHandle = apply {
        builder.mutate(index) { it.copy(describe = text.trimIndent().trim()) }
    }

    fun serialName(name: String): FieldHandle = apply {
        builder.mutate(index) { it.copy(serialName = name) }
    }

    fun withSerializer(fqn: String): FieldHandle = apply {
        builder.mutate(index) { it.copy(serializerFqn = fqn) }
    }
}

// ---------- Tool spec ----------

data class ToolSpec(
    val name: String,
    val summary: String,
    val guidance: String?,
    val input: ClassSpec,
    val output: ClassSpec,
    /**
     * Fully-qualified name of the top-level handler function that backs this tool.
     * Expressed as a string so the DSL has zero compile-time dependency on the
     * function's home module. Downstream generators validate FQN resolvability
     * when they emit the dispatcher code that references it.
     */
    val handlerFqn: String,
)

@HexanaToolsDsl
class ToolsBuilder internal constructor() {
    private val specs = mutableListOf<ToolSpec>()

    fun tool(name: String, block: ToolBuilder.() -> Unit) {
        val builder = ToolBuilder(name).apply(block)
        specs += builder.build()
    }

    internal fun build(): List<ToolSpec> {
        val duplicates = specs.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate tool names: $duplicates" }
        return specs.toList()
    }
}

private val HANDLER_FQN_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")

@HexanaToolsDsl
class ToolBuilder internal constructor(private val name: String) {
    var summary: String? = null
    var guidance: String? = null
    var input: ClassSpec? = null
    var output: ClassSpec? = null
    var handlerFqn: String? = null

    internal fun build(): ToolSpec {
        val resolvedSummary = requireNotNull(summary) { "Tool '$name' is missing `summary`." }
        val resolvedInput = requireNotNull(input) { "Tool '$name' is missing `input = `." }
        val resolvedOutput = requireNotNull(output) { "Tool '$name' is missing `output = `." }
        val resolvedHandler = requireNotNull(handlerFqn) { "Tool '$name' is missing `handlerFqn = `." }
        require(HANDLER_FQN_REGEX.matches(resolvedHandler)) {
            "Tool '$name' handlerFqn must be a fully-qualified function name (`pkg.subpkg.fn`), got `$resolvedHandler`."
        }
        return ToolSpec(
            name = name,
            summary = resolvedSummary.trim(),
            guidance = guidance?.trimIndent()?.trim()?.takeIf { it.isNotEmpty() },
            input = resolvedInput,
            output = resolvedOutput,
            handlerFqn = resolvedHandler,
        )
    }
}

fun tools(block: ToolsBuilder.() -> Unit): List<ToolSpec> {
    return ToolsBuilder().apply(block).build()
}
