package org.jetbrains.hexana.mcp.tools

// ---------- Types ----------

sealed class FieldType {
    object Int32 : FieldType()
    object Int64 : FieldType()
    object Float64 : FieldType()
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
fun double(): FieldType = FieldType.Float64
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
    val minimum: Int? = null,
    val maximum: Int? = null,
    val maxItems: SchemaIntValue? = null,
    val maxLength: SchemaIntValue? = null,
    val itemMinimum: Int? = null,
    val itemMaximum: Int? = null,
) {
    init {
        if (default != null) {
            require(type !is FieldType.ClassRef) {
                "Field '$name': defaults are not supported for embedded classes."
            }
            val expected: Class<*> = when (type) {
                FieldType.Int32, FieldType.UInt32, FieldType.ByteT -> Int::class.javaObjectType
                FieldType.Int64 -> Long::class.javaObjectType
                FieldType.Float64 -> Double::class.javaObjectType
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
        if (minimum != null || maximum != null) {
            require(type.isNumeric()) {
                "Field '$name': minimum/maximum constraints require a numeric field type, got $type."
            }
        }
        if (maxItems != null || itemMinimum != null || itemMaximum != null) {
            require(type is FieldType.ListOf) {
                "Field '$name': maxItems/item constraints require a list field type, got $type."
            }
        }
        if (maxLength != null) {
            require(type == FieldType.StringT || type == FieldType.ByteArrayT) {
                "Field '$name': maxLength requires a string-like field type, got $type."
            }
        }
        if (itemMinimum != null || itemMaximum != null) {
            val element = (type as FieldType.ListOf).element
            require(element.isNumeric()) {
                "Field '$name': item minimum/maximum constraints require numeric list items, got $element."
            }
        }
    }

    val schemaKey: String get() = serialName ?: name
}

private fun FieldType.isNumeric(): Boolean =
    this == FieldType.Int32 ||
        this == FieldType.Int64 ||
        this == FieldType.Float64 ||
        this == FieldType.UInt32 ||
        this == FieldType.ByteT

sealed class SchemaIntValue {
    data class Literal(val value: Int) : SchemaIntValue()
    object MaxInlineWasmBytes : SchemaIntValue()
    object MaxInlineWasmBase64Length : SchemaIntValue()
}

// ---------- Class spec ----------

data class ClassSpec(
    val name: String,
    val fields: List<FieldSpec>,
    /** Exactly one of these field names must be present at runtime. */
    val oneOf: List<String>? = null,
    val kotlinFqn: String? = null,
) {
    /**
     * Returns a new ClassSpec that inherits this one's fields, plus whatever the block adds.
     * Optionally rename the derived class.
     */
    fun with(
        renameTo: String = name,
        kotlinPackage: String? = null,
        block: ClassBuilder.() -> Unit,
    ): ClassSpec {
        val builder = ClassBuilder(renameTo, fields, oneOf, kotlinFqn(renameTo, kotlinPackage))
        builder.block()
        return builder.build()
    }
}

fun class_(name: String, kotlinPackage: String? = null, block: ClassBuilder.() -> Unit): ClassSpec {
    return ClassBuilder(name, kotlinFqn = kotlinFqn(name, kotlinPackage)).apply(block).build()
}

private fun kotlinFqn(name: String, kotlinPackage: String?): String? =
    kotlinPackage?.let { "$it.$name" }

// ---------- Builders ----------

@DslMarker
annotation class HexanaToolsDsl

@HexanaToolsDsl
class ClassBuilder internal constructor(
    private val name: String,
    seed: List<FieldSpec> = emptyList(),
    seedOneOf: List<String>? = null,
    private val kotlinFqn: String? = null,
) {
    private val fields = seed.toMutableList()
    private var oneOf: List<String>? = seedOneOf

    fun int(name: String, default: Int? = null): FieldHandle =
        addField(name, FieldType.Int32, default)

    fun long(name: String, default: Long? = null): FieldHandle =
        addField(name, FieldType.Int64, default)

    fun double(name: String, default: Double? = null): FieldHandle =
        addField(name, FieldType.Float64, default)

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
        val choices = props.toList()
        require(choices.isNotEmpty()) { "oneOf in class '$name' must declare at least one field." }
        val duplicates = choices.groupBy { it }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "oneOf in class '$name' contains duplicate fields: $duplicates" }
        oneOf = choices
    }

    private fun addField(
        name: String,
        type: FieldType,
        default: Any? = null,
        nullable: Boolean = false,
    ): FieldHandle {
        require(FIELD_NAME_REGEX.matches(name)) {
            "Field name '$name' in class '${this.name}' must be lowerCamelCase."
        }
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
        kotlinFqn?.let {
            require(FQN_REGEX.matches(it)) {
                "Class '$name' kotlinPackage produced invalid FQN `$it`."
            }
            require(it.substringAfterLast('.') == name) {
                "Class '$name' kotlin FQN must end with the class name, got `$it`."
            }
        }
        return ClassSpec(name, fields.toList(), oneOf, kotlinFqn)
    }
}

/** Chainable handle returned by every field declaration; lets you attach `describe` / `serialName`. */
class FieldHandle internal constructor(
    private val builder: ClassBuilder,
    private val index: Int,
) {
    fun describe(text: String): FieldHandle = apply {
        val description = text.trimIndent().trim()
        builder.mutate(index) {
            require(description.isNotBlank()) { "Field '${it.name}' description must not be blank." }
            it.copy(describe = description)
        }
    }

    fun serialName(name: String): FieldHandle = apply {
        val serialName = name.trim()
        builder.mutate(index) {
            require(serialName.isNotBlank()) { "Field '${it.name}' serialName must not be blank." }
            it.copy(serialName = serialName)
        }
    }

    fun withSerializer(fqn: String): FieldHandle = apply {
        val serializerFqn = fqn.trim()
        builder.mutate(index) {
            require(FQN_REGEX.matches(serializerFqn)) {
                "Field '${it.name}' serializer FQN must be fully-qualified, got `$fqn`."
            }
            it.copy(serializerFqn = serializerFqn)
        }
    }

    fun minimum(value: Int): FieldHandle = apply {
        builder.mutate(index) { it.copy(minimum = value) }
    }

    fun maximum(value: Int): FieldHandle = apply {
        builder.mutate(index) { it.copy(maximum = value) }
    }

    fun maxItems(value: Int): FieldHandle =
        maxItems(SchemaIntValue.Literal(value))

    fun maxItems(value: SchemaIntValue): FieldHandle = apply {
        builder.mutate(index) { it.copy(maxItems = value) }
    }

    fun maxLength(value: Int): FieldHandle =
        maxLength(SchemaIntValue.Literal(value))

    fun maxLength(value: SchemaIntValue): FieldHandle = apply {
        builder.mutate(index) { it.copy(maxLength = value) }
    }

    fun itemsMinimum(value: Int): FieldHandle = apply {
        builder.mutate(index) { it.copy(itemMinimum = value) }
    }

    fun itemsMaximum(value: Int): FieldHandle = apply {
        builder.mutate(index) { it.copy(itemMaximum = value) }
    }
}

// ---------- Tool spec ----------

data class ToolSpec(
    val name: String,
    val summary: String,
    val guidance: String?,
    val input: ClassSpec,
    val inputPattern: ToolInputPattern,
    val output: ClassSpec,
    /**
     * Fully-qualified name of the top-level handler function that backs this tool.
     * Expressed as a string so the DSL has zero compile-time dependency on the
     * function's home module. Downstream generators validate FQN resolvability
     * when they emit the dispatcher code that references it.
     */
    val handlerFqn: String,
)

sealed class ToolInputPattern {
    object WasmOnly : ToolInputPattern()
    object WasmPathLabel : ToolInputPattern()
    object WasmWithBaseline : ToolInputPattern()
    object FunctionPage : ToolInputPattern()
    object Funcidx : ToolInputPattern()
    object FunctionNameSearch : ToolInputPattern()
    object ExportNameSearch : ToolInputPattern()
    object Indices : ToolInputPattern()
    data class ClosedRange(val parameterName: String) : ToolInputPattern()
    object DataSegments : ToolInputPattern()
    object ArtifactQuery : ToolInputPattern()
    object RuntimeCompatibility : ToolInputPattern()
}

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

private val FQN_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
private val FIELD_NAME_REGEX = Regex("^[a-z][A-Za-z0-9]*$")
private val TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")

@HexanaToolsDsl
class ToolBuilder internal constructor(private val name: String) {
    var summary: String? = null
    var guidance: String? = null
    var input: ClassSpec? = null
    var inputPattern: ToolInputPattern? = null
    var output: ClassSpec? = null
    var handlerFqn: String? = null

    internal fun build(): ToolSpec {
        require(TOOL_NAME_REGEX.matches(name)) {
            "Tool name '$name' must be strict snake_case."
        }
        val resolvedSummary = requireNotNull(summary) { "Tool '$name' is missing `summary`." }
        require(resolvedSummary.isNotBlank()) { "Tool '$name' summary must not be blank." }
        val resolvedInput = requireNotNull(input) { "Tool '$name' is missing `input = `." }
        val resolvedInputPattern = requireNotNull(inputPattern) { "Tool '$name' is missing `inputPattern = `." }
        val resolvedOutput = requireNotNull(output) { "Tool '$name' is missing `output = `." }
        val resolvedHandler = requireNotNull(handlerFqn) { "Tool '$name' is missing `handlerFqn = `." }
        require(FQN_REGEX.matches(resolvedHandler)) {
            "Tool '$name' handlerFqn must be a fully-qualified function name (`pkg.subpkg.fn`), got `$resolvedHandler`."
        }
        validateInputPattern(name, resolvedInput, resolvedInputPattern)
        return ToolSpec(
            name = name,
            summary = resolvedSummary.trim(),
            guidance = guidance?.trimIndent()?.trim()?.takeIf { it.isNotEmpty() },
            input = resolvedInput,
            inputPattern = resolvedInputPattern,
            output = resolvedOutput,
            handlerFqn = resolvedHandler,
        )
    }
}

private fun validateInputPattern(toolName: String, input: ClassSpec, pattern: ToolInputPattern) {
    fun requireField(fieldName: String, owner: ClassSpec = input): FieldSpec {
        return owner.fields.singleOrNull { it.name == fieldName } ?: throw IllegalArgumentException(
            "Tool '$toolName' input pattern ${pattern::class.simpleName} requires field `$fieldName` on `${input.name}`."
        )
    }

    fun requireDescribed(field: FieldSpec) {
        require(!field.describe.isNullOrBlank()) {
            "Tool '$toolName' input field `${field.name}` must have a .describe()."
        }
    }

    fun requireType(field: FieldSpec, expected: FieldType) {
        require(field.type == expected) {
            "Tool '$toolName' input field `${field.name}` must be $expected, got ${field.type}."
        }
    }

    fun requireNullable(field: FieldSpec) {
        require(field.nullable) {
            "Tool '$toolName' input field `${field.name}` must be nullable."
        }
    }

    fun requireNonNullable(field: FieldSpec) {
        require(!field.nullable) {
            "Tool '$toolName' input field `${field.name}` must be non-nullable."
        }
    }

    fun requireListOf(field: FieldSpec, expectedElement: FieldType) {
        val listType = field.type as? FieldType.ListOf ?: throw IllegalArgumentException(
            "Tool '$toolName' input field `${field.name}` must be a list, got ${field.type}."
        )
        require(listType.element == expectedElement) {
            "Tool '$toolName' input field `${field.name}` must list $expectedElement, got ${listType.element}."
        }
    }

    fun requireClassRef(field: FieldSpec): ClassSpec {
        return (field.type as? FieldType.ClassRef)?.clazz ?: throw IllegalArgumentException(
            "Tool '$toolName' input field `${field.name}` must be an embedded class, got ${field.type}."
        )
    }

    fun requireDefault(field: FieldSpec, expectedType: String, predicate: (Any) -> Boolean) {
        val defaultValue = field.default
        require(defaultValue != null && predicate(defaultValue)) {
            "Tool '$toolName' input field `${field.name}` must declare a $expectedType default."
        }
    }

    fun requireWasmInput() {
        val path = requireField("path")
        requireType(path, FieldType.StringT)
        requireNullable(path)
        val bytesBase64 = requireField("bytesBase64")
        requireType(bytesBase64, FieldType.StringT)
        requireNullable(bytesBase64)
        val bytes = requireField("bytes")
        requireListOf(bytes, FieldType.Int32)
        requireNullable(bytes)
        require(input.oneOf == listOf("path", "bytesBase64", "bytes")) {
            "Tool '$toolName' input class `${input.name}` must keep Wasm input oneOf(path, bytesBase64, bytes)."
        }
    }

    fun requireClosedRange(field: FieldSpec) {
        requireDescribed(field)
        val range = requireClassRef(field)
        val from = requireField("from", owner = range)
        requireType(from, FieldType.Int32)
        requireNonNullable(from)
        requireDescribed(from)
        val to = requireField("to", owner = range)
        requireType(to, FieldType.Int32)
        requireNonNullable(to)
        requireDescribed(to)
    }

    fun requireHalfOpenRange(field: FieldSpec) {
        requireDescribed(field)
        val range = requireClassRef(field)
        val from = requireField("from", owner = range)
        requireType(from, FieldType.Int32)
        requireNonNullable(from)
        requireDescribed(from)
        val to = requireField("to", owner = range)
        requireType(to, FieldType.Int32)
        requireNullable(to)
        requireDescribed(to)
    }

    requireWasmInput()
    when (pattern) {
        ToolInputPattern.WasmOnly -> Unit
        ToolInputPattern.WasmPathLabel -> Unit
        ToolInputPattern.WasmWithBaseline -> {
            val baselinePath = requireField("baselinePath")
            requireType(baselinePath, FieldType.StringT)
            requireNonNullable(baselinePath)
            requireDescribed(baselinePath)
        }
        ToolInputPattern.FunctionPage -> {
            val offset = requireField("offset")
            requireType(offset, FieldType.Int32)
            requireNonNullable(offset)
            requireDefault(offset, "Int") { it is Int }
            requireDescribed(offset)
            val limit = requireField("limit")
            requireType(limit, FieldType.Int32)
            requireNonNullable(limit)
            requireDefault(limit, "Int") { it is Int }
            requireDescribed(limit)
        }
        ToolInputPattern.Funcidx -> {
            val funcidx = requireField("funcidx")
            requireType(funcidx, FieldType.Int32)
            requireNonNullable(funcidx)
            requireDescribed(funcidx)
        }
        ToolInputPattern.FunctionNameSearch -> {
            val query = requireField("query")
            requireType(query, FieldType.StringT)
            requireNonNullable(query)
            requireDescribed(query)
            val limit = requireField("limit")
            requireType(limit, FieldType.Int32)
            requireNonNullable(limit)
            requireDefault(limit, "Int") { it is Int }
            requireDescribed(limit)
        }
        ToolInputPattern.ExportNameSearch -> {
            val query = requireField("query")
            requireListOf(query, FieldType.StringT)
            requireNonNullable(query)
            requireDescribed(query)
            val limit = requireField("limit")
            requireType(limit, FieldType.Int32)
            requireNonNullable(limit)
            requireDefault(limit, "Int") { it is Int }
            requireDescribed(limit)
        }
        ToolInputPattern.Indices -> {
            val indices = requireField("indices")
            requireListOf(indices, FieldType.Int32)
            requireNonNullable(indices)
            requireDescribed(indices)
        }
        is ToolInputPattern.ClosedRange -> requireClosedRange(requireField(pattern.parameterName))
        ToolInputPattern.DataSegments -> {
            requireHalfOpenRange(requireField("segmentRange"))
            val includeExports = requireField("includeExports")
            requireType(includeExports, FieldType.BooleanT)
            requireNonNullable(includeExports)
            requireDefault(includeExports, "Boolean") { it is Boolean }
            requireDescribed(includeExports)
            val includeGlobals = requireField("includeGlobals")
            requireType(includeGlobals, FieldType.BooleanT)
            requireNonNullable(includeGlobals)
            requireDefault(includeGlobals, "Boolean") { it is Boolean }
            requireDescribed(includeGlobals)
            val minByteLength = requireField("minByteLength")
            requireType(minByteLength, FieldType.Int32)
            requireNullable(minByteLength)
            requireDescribed(minByteLength)
        }
        ToolInputPattern.ArtifactQuery -> {
            val baselinePath = requireField("baselinePath")
            requireType(baselinePath, FieldType.StringT)
            requireNullable(baselinePath)
            requireDescribed(baselinePath)

            val bindingPath = requireField("bindingPath")
            requireType(bindingPath, FieldType.StringT)
            requireNullable(bindingPath)
            requireDescribed(bindingPath)

            val configPath = requireField("configPath")
            requireType(configPath, FieldType.StringT)
            requireNullable(configPath)
            requireDescribed(configPath)

            val operation = requireField("operation")
            requireType(operation, FieldType.StringT)
            requireNonNullable(operation)
            requireDefault(operation, "String") { it is String }
            requireDescribed(operation)

            val entityTypes = requireField("entityTypes")
            requireListOf(entityTypes, FieldType.StringT)
            requireNullable(entityTypes)
            requireDescribed(entityTypes)

            val query = requireField("query")
            requireListOf(query, FieldType.StringT)
            requireNullable(query)
            requireDescribed(query)

            val startName = requireField("startName")
            requireType(startName, FieldType.StringT)
            requireNullable(startName)
            requireDescribed(startName)

            val startFuncidx = requireField("startFuncidx")
            requireType(startFuncidx, FieldType.Int32)
            requireNullable(startFuncidx)
            requireDescribed(startFuncidx)

            val relations = requireField("relations")
            requireListOf(relations, FieldType.StringT)
            requireNullable(relations)
            requireDescribed(relations)

            val limit = requireField("limit")
            requireType(limit, FieldType.Int32)
            requireNonNullable(limit)
            requireDefault(limit, "Int") { it is Int }
            requireDescribed(limit)

            val detail = requireField("detail")
            requireType(detail, FieldType.StringT)
            requireNonNullable(detail)
            requireDefault(detail, "String") { it is String }
            requireDescribed(detail)

            val maxBytes = requireField("maxBytes")
            requireType(maxBytes, FieldType.Int32)
            requireNonNullable(maxBytes)
            requireDefault(maxBytes, "Int") { it is Int }
            requireDescribed(maxBytes)
        }
        ToolInputPattern.RuntimeCompatibility -> {
            val runtime = requireField("runtime")
            requireType(runtime, FieldType.StringT)
            requireNonNullable(runtime)
            requireDescribed(runtime)

            val version = requireField("version")
            requireType(version, FieldType.StringT)
            requireNullable(version)
            requireDescribed(version)
        }
    }
}

fun tools(block: ToolsBuilder.() -> Unit): List<ToolSpec> {
    return ToolsBuilder().apply(block).build()
}
