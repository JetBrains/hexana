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
    object JsonObjectT : FieldType()
    data class ListOf(val element: FieldType) : FieldType()
    data class MapOf(val value: FieldType) : FieldType()
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
fun jsonObject(): FieldType = FieldType.JsonObjectT

// Top-level `list(of = …)` usable wherever a `FieldType` is expected
// (e.g. `nullable("superTypes", of = list(of = uint()))`).
fun list(of: FieldType): FieldType = FieldType.ListOf(of)
fun list(of: ClassSpec): FieldType = FieldType.ListOf(FieldType.ClassRef(of))
fun map(of: FieldType): FieldType = FieldType.MapOf(of)
fun map(of: ClassSpec): FieldType = FieldType.MapOf(FieldType.ClassRef(of))

// ---------- Field spec ----------

/**
 * Semantic wire-field presence.  This is deliberately independent of a consumer's
 * argument reader: [Optional] is absent by default, while [Defaulted] has a value
 * when absent.  MCP-196/MCP-198/MCP-199/MCP-200 migrate remaining consumers from
 * the legacy [FieldSpec.default] bridge.
 */
sealed class FieldPresence {
    object Required : FieldPresence()
    object Optional : FieldPresence()
    data class Defaulted(val value: FieldDefaultValue) : FieldPresence()
}

/** Typed schema default; [Null] is distinct from a field with no default. */
sealed class FieldDefaultValue {
    object Null : FieldDefaultValue()
    data class IntValue(val value: Int) : FieldDefaultValue()
    data class LongValue(val value: Long) : FieldDefaultValue()
    data class DoubleValue(val value: Double) : FieldDefaultValue()
    data class BooleanValue(val value: Boolean) : FieldDefaultValue()
    data class StringValue(val value: String) : FieldDefaultValue()

    internal fun legacyValue(): Any? = when (this) {
        Null -> null
        is IntValue -> value
        is LongValue -> value
        is DoubleValue -> value
        is BooleanValue -> value
        is StringValue -> value
    }

    companion object {
        internal fun fromLegacy(value: Any): FieldDefaultValue = when (value) {
            is Int -> IntValue(value)
            is Long -> LongValue(value)
            is Double -> DoubleValue(value)
            is Boolean -> BooleanValue(value)
            is String -> StringValue(value)
            else -> throw IllegalArgumentException("Unsupported legacy schema default `$value` (${value::class.simpleName}).")
        }
    }
}

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
    /** Explicit semantic presence. `default` remains a generator compatibility bridge. */
    val presence: FieldPresence = if (default != null) {
        FieldPresence.Defaulted(FieldDefaultValue.fromLegacy(default))
    } else if (nullable) {
        FieldPresence.Optional
    } else {
        FieldPresence.Required
    },
) {

    val schemaKey: String get() = serialName ?: name
}

private fun FieldType.isNumeric(): Boolean =
    this == FieldType.Int32 ||
        this == FieldType.Int64 ||
        this == FieldType.Float64 ||
        this == FieldType.UInt32 ||
        this == FieldType.ByteT

private fun ClassSpec.validateCompletedSchema(visited: MutableSet<ClassSpec> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())) {
    if (!visited.add(this)) return
    val duplicateKeys = fields.groupBy(FieldSpec::schemaKey).filterValues { it.size > 1 }
    require(duplicateKeys.isEmpty()) {
        "Class '$name' has duplicate effective wire keys: " +
            duplicateKeys.entries.joinToString { (key, duplicates) ->
                "'$key' for fields ${duplicates.joinToString { "'${it.name}'" }}"
            }
    }
    fields.forEach { field ->
        field.validateCompletedSchema(name)
        field.type.validateNestedSchemas(visited)
    }
}

private fun FieldType.validateNestedSchemas(visited: MutableSet<ClassSpec>) {
    when (this) {
        is FieldType.ClassRef -> clazz.validateCompletedSchema(visited)
        is FieldType.ListOf -> element.validateNestedSchemas(visited)
        is FieldType.MapOf -> value.validateNestedSchemas(visited)
        else -> Unit
    }
}

private fun FieldSpec.validateCompletedSchema(className: String) {
    fun fail(message: String): Nothing = throw IllegalArgumentException("Class '$className', field '$name': $message")
    val typedDefault = (presence as? FieldPresence.Defaulted)?.value
    when (presence) {
        FieldPresence.Required -> {
            if (nullable) fail("Required presence requires a non-nullable field while the legacy bridge is active.")
            if (default != null) fail("legacy default bridge requires Defaulted presence.")
        }
        FieldPresence.Optional -> {
            if (!nullable) fail("Optional presence requires a nullable field while the legacy bridge is active.")
            if (default != null) fail("legacy default bridge requires Defaulted presence.")
        }
        is FieldPresence.Defaulted -> Unit
    }
    if (typedDefault != null) {
        val bridgeValue = typedDefault.legacyValue()
        require(default == bridgeValue) {
            "Class '$className', field '$name': legacy default bridge does not match explicit presence default."
        }
        if (typedDefault == FieldDefaultValue.Null) {
            require(type in setOf(
                FieldType.Int32,
                FieldType.Int64,
                FieldType.Float64,
                FieldType.UInt32,
                FieldType.ByteT,
                FieldType.BooleanT,
                FieldType.StringT,
            )) { "Class '$className', field '$name': defaults are supported only for scalar fields currently emitted by generators, got $type." }
        } else {
            when (type) {
                FieldType.Int32, FieldType.UInt32, FieldType.ByteT -> if (typedDefault !is FieldDefaultValue.IntValue) fail("default value `$typedDefault` does not match declared type $type.")
                FieldType.Int64 -> if (typedDefault !is FieldDefaultValue.LongValue) fail("default value `$typedDefault` does not match declared type $type.")
                FieldType.Float64 -> if (typedDefault !is FieldDefaultValue.DoubleValue) fail("default value `$typedDefault` does not match declared type $type.")
                FieldType.BooleanT -> if (typedDefault !is FieldDefaultValue.BooleanValue) fail("default value `$typedDefault` does not match declared type $type.")
                FieldType.StringT -> if (typedDefault !is FieldDefaultValue.StringValue) fail("default value `$typedDefault` does not match declared type $type.")
                else -> fail("defaults are supported only for scalar fields currently emitted by generators, got $type.")
            }
        }
        if (typedDefault == FieldDefaultValue.Null && !nullable) fail("a null default requires a nullable field.")
    }
    if (minimum != null || maximum != null) {
        if (!type.isNumeric()) fail("minimum/maximum constraints require a numeric field type, got $type.")
        if (minimum != null && maximum != null && minimum > maximum) fail("minimum $minimum exceeds maximum $maximum.")
        validateNumericDomain(type, minimum, "minimum", ::fail)
        validateNumericDomain(type, maximum, "maximum", ::fail)
    }
    if (maxItems != null || itemMinimum != null || itemMaximum != null) {
        if (type !is FieldType.ListOf) fail("maxItems/item constraints require a list field type, got $type.")
        literalValue(maxItems)?.let { if (it < 0) fail("maxItems must not be negative, got $it.") }
    }
    if (maxLength != null) {
        if (type != FieldType.StringT && type != FieldType.ByteArrayT) fail("maxLength requires a string-like field type, got $type.")
        literalValue(maxLength)?.let { if (it < 0) fail("maxLength must not be negative, got $it.") }
    }
    if (itemMinimum != null || itemMaximum != null) {
        val element = (type as FieldType.ListOf).element
        if (!element.isNumeric()) fail("item minimum/maximum constraints require numeric list items, got $element.")
        if (itemMinimum != null && itemMaximum != null && itemMinimum > itemMaximum) fail("item minimum $itemMinimum exceeds item maximum $itemMaximum.")
        validateNumericDomain(element, itemMinimum, "item minimum", ::fail)
        validateNumericDomain(element, itemMaximum, "item maximum", ::fail)
    }
    when (val value = typedDefault?.legacyValue()) {
        is Int -> validateNumericDefault(value, type, minimum, maximum, ::fail)
        is Long -> validateNumericDefault(value, type, minimum, maximum, ::fail)
        is Double -> {
            if (!value.isFinite()) fail("Float64 default must be finite, got $value.")
            validateNumericDefault(value, type, minimum, maximum, ::fail)
        }
        is String -> literalValue(maxLength)?.let { maximumLength ->
            if (value.codePointCount(0, value.length) > maximumLength) fail("default exceeds maxLength $maximumLength.")
        }
    }
}

private fun literalValue(value: SchemaIntValue?): Int? = (value as? SchemaIntValue.Literal)?.value

private fun validateNumericDomain(type: FieldType, value: Int?, label: String, fail: (String) -> Nothing) {
    value ?: return
    when (type) {
        FieldType.UInt32 -> if (value < 0) fail("$label must be nonnegative for UInt32, got $value.")
        FieldType.ByteT -> if (value !in 0..255) fail("$label must be in 0..255 for Byte, got $value.")
        else -> Unit
    }
}

private fun validateNumericDefault(value: Number, type: FieldType, minimum: Int?, maximum: Int?, fail: (String) -> Nothing) {
    val numeric = value.toDouble()
    when (type) {
        FieldType.UInt32 -> if (numeric < 0) fail("default must be nonnegative for UInt32, got $value.")
        FieldType.ByteT -> if (numeric !in 0.0..255.0) fail("default must be in 0..255 for Byte, got $value.")
        else -> Unit
    }
    if (minimum != null && numeric < minimum) fail("default $value is below minimum $minimum.")
    if (maximum != null && numeric > maximum) fail("default $value exceeds maximum $maximum.")
}

sealed class SchemaIntValue {
    data class Literal(val value: Int) : SchemaIntValue()
    object MaxInlineWasmBytes : SchemaIntValue()
    object MaxInlineWasmBase64Length : SchemaIntValue()
    object MaxInlineBinaryBytes : SchemaIntValue()
    object MaxInlineBinaryBase64Length : SchemaIntValue()
}

// ---------- Class spec ----------

enum class InputConversionCapability {
    JsonObject,
    WasmPath,
    WasmFromString,
    BinaryPath,
    Stdin,
}

enum class OutputConversionCapability {
    SemanticJson,
    McpWrapper,
    Pretty,
    Table,
    RawText,
    RawBytes,
}

class ClassSpec(
    val name: String,
    fields: List<FieldSpec>,
    /** Exactly one of these field names must be present at runtime. */
    oneOf: List<String>? = null,
    val kotlinFqn: String? = null,
    val inputCapabilities: Set<InputConversionCapability> = DEFAULT_INPUT_CAPABILITIES,
    val outputCapabilities: Set<OutputConversionCapability> = DEFAULT_OUTPUT_CAPABILITIES,
    exactOneSourceGroups: List<ExactOneSourceGroup> = emptyList(),
    internal val identity: ClassIdentity = ClassIdentity(),
) {
    val fields: List<FieldSpec> = java.util.Collections.unmodifiableList(java.util.ArrayList(fields))
    val oneOf: List<String>? = oneOf?.let { java.util.Collections.unmodifiableList(java.util.ArrayList(it)) }
    val exactOneSourceGroups: List<ExactOneSourceGroup> =
        java.util.Collections.unmodifiableList(java.util.ArrayList(exactOneSourceGroups))

    init {
        validateCompletedSchema()
        this.oneOf?.let { choices ->
            require(choices.distinct().size == choices.size) { "Class '$name' oneOf contains duplicate fields: $choices." }
            choices.forEach { choice ->
                require(fields.any { it.name == choice }) { "Class '$name' oneOf references unknown field '$choice'." }
            }
        }
        this.exactOneSourceGroups.forEach { it.validateFor(this) }
    }

    operator fun component1(): String = name
    operator fun component2(): List<FieldSpec> = fields
    operator fun component3(): List<String>? = oneOf
    operator fun component4(): String? = kotlinFqn
    operator fun component5(): Set<InputConversionCapability> = inputCapabilities
    operator fun component6(): Set<OutputConversionCapability> = outputCapabilities

    fun copy(
        name: String = this.name,
        fields: List<FieldSpec> = this.fields,
        oneOf: List<String>? = this.oneOf,
        kotlinFqn: String? = this.kotlinFqn,
        inputCapabilities: Set<InputConversionCapability> = this.inputCapabilities,
        outputCapabilities: Set<OutputConversionCapability> = this.outputCapabilities,
        exactOneSourceGroups: List<ExactOneSourceGroup> = this.exactOneSourceGroups,
    ): ClassSpec {
        val copiedIdentity = ClassIdentity()
        return ClassSpec(
            name, fields, oneOf, kotlinFqn, inputCapabilities, outputCapabilities,
            exactOneSourceGroups.map { it.reboundFor(name, fields, copiedIdentity) }, copiedIdentity,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is ClassSpec &&
            name == other.name &&
            fields == other.fields &&
            oneOf == other.oneOf &&
            kotlinFqn == other.kotlinFqn &&
            inputCapabilities == other.inputCapabilities &&
            outputCapabilities == other.outputCapabilities &&
            exactOneSourceGroups == other.exactOneSourceGroups

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + fields.hashCode()
        result = 31 * result + (oneOf?.hashCode() ?: 0)
        result = 31 * result + (kotlinFqn?.hashCode() ?: 0)
        result = 31 * result + inputCapabilities.hashCode()
        result = 31 * result + outputCapabilities.hashCode()
        result = 31 * result + exactOneSourceGroups.hashCode()
        return result
    }

    override fun toString(): String =
        "ClassSpec(name=$name, fields=$fields, oneOf=$oneOf, kotlinFqn=$kotlinFqn, " +
        "inputCapabilities=$inputCapabilities, outputCapabilities=$outputCapabilities, exactOneSourceGroups=$exactOneSourceGroups)"

    fun fieldRef(name: String): FieldRef {
        val field = fields.singleOrNull { it.name == name }
            ?: throw IllegalArgumentException("Class '${this.name}' has no field `$name` to reference.")
        return FieldRef.create(identity, this.name, field)
    }

    /**
     * Returns a new ClassSpec that inherits this one's fields, plus whatever the block adds.
     * Optionally rename the derived class.
     */
    fun with(
        renameTo: String = name,
        kotlinPackage: String? = null,
        block: ClassBuilder.() -> Unit,
    ): ClassSpec {
        val builder = ClassBuilder(
            name = renameTo,
            seed = fields,
            seedOneOf = oneOf,
            kotlinFqn = kotlinFqn(renameTo, kotlinPackage),
            seedInputCapabilities = inputCapabilities,
            seedOutputCapabilities = outputCapabilities,
            seedExactOneSourceGroups = exactOneSourceGroups,
        )
        builder.block()
        return builder.build()
    }
}

/** A typed field reference, including its owning class and nullable/presence contract. */
class FieldRef private constructor(
    internal val owner: ClassIdentity,
    val ownerName: String,
    val name: String,
    val type: FieldType,
    val nullable: Boolean,
    val presence: FieldPresence,
) {
    override fun equals(other: Any?): Boolean =
        other is FieldRef && owner === other.owner && name == other.name && type == other.type &&
            nullable == other.nullable && presence == other.presence

    override fun hashCode(): Int =
        31 * System.identityHashCode(owner) + 31 * name.hashCode() + type.hashCode() +
            31 * nullable.hashCode() + presence.hashCode()

    override fun toString(): String = "FieldRef($ownerName.$name: $type${if (nullable) "?" else ""}, $presence)"

    companion object {
        internal fun create(owner: ClassIdentity, ownerName: String, field: FieldSpec): FieldRef =
            FieldRef(owner, ownerName, field.name, field.type, field.nullable, field.presence)
    }
}

/** Opaque owner token; only [ClassSpec] can create references associated with it. */
class ClassIdentity internal constructor()

enum class SourceSelectionKind { Path, Base64, Bytes }

data class SourceSelection(val kind: SourceSelectionKind, val field: FieldRef)

/**
 * An exact-one input source group.  It is semantic metadata only: current MCP
 * schemas retain their existing shape and do not emit a root `oneOf`.
 */
class ExactOneSourceGroup(
    val name: String,
    selections: List<SourceSelection>,
    val required: Boolean = true,
) {
    val selections: List<SourceSelection> =
        java.util.Collections.unmodifiableList(java.util.ArrayList(selections))
    private val valueShape: List<Pair<SourceSelectionKind, String>> =
        this.selections.map { it.kind to it.field.name }

    init {
        require(name.isNotBlank()) { "Exact-one source group name must not be blank." }
        require(this.selections.isNotEmpty()) { "Exact-one source group '$name' must select at least one field." }
    }

    override fun equals(other: Any?): Boolean =
        other is ExactOneSourceGroup &&
            name == other.name &&
            required == other.required &&
            valueShape == other.valueShape

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + required.hashCode()
        result = 31 * result + valueShape.hashCode()
        return result
    }

    override fun toString(): String =
        "ExactOneSourceGroup(name=$name, selections=$valueShape, required=$required)"

    internal fun reboundFor(ownerName: String, fields: List<FieldSpec>, identity: ClassIdentity): ExactOneSourceGroup =
        ExactOneSourceGroup(name, selections.map { selection ->
            val field = fields.single { it.name == selection.field.name }
            selection.copy(field = FieldRef.create(identity, ownerName, field))
        }, required)

    internal fun validateFor(owner: ClassSpec) {
        val duplicateKinds = selections.groupBy { it.kind }.filterValues { it.size > 1 }.keys
        require(duplicateKinds.isEmpty()) { "Exact-one source group '$name' has duplicate kinds: $duplicateKinds." }
        require(selections.map { it.kind }.toSet() == SourceSelectionKind.entries.toSet()) {
            "Exact-one source group '$name' must include Path, Base64, and Bytes selections."
        }
        val duplicateFields = selections.groupBy { it.field.name }.filterValues { it.size > 1 }.keys
        require(duplicateFields.isEmpty()) { "Exact-one source group '$name' references duplicate fields: $duplicateFields." }
        require(required) { "Exact-one source group '$name' must be required; no source is not a valid selection." }
        selections.forEach { selection ->
            val reference = selection.field
            require(reference.owner === owner.identity) {
                "Exact-one source group '$name' field `${reference.name}` belongs to `${reference.ownerName}` " +
                    "(a foreign class identity), not `${owner.name}`."
            }
            val field = owner.fields.singleOrNull { it.name == reference.name }
                ?: throw IllegalArgumentException("Exact-one source group '$name' references unknown field `${reference.name}` on `${owner.name}`.")
            require(owner.fieldRef(field.name) == reference) {
                "Exact-one source group '$name' field `${reference.name}` no longer matches its typed reference."
            }
            require(field.nullable && field.presence == FieldPresence.Optional) {
                "Exact-one source group '$name' field `${field.name}` must be nullable and optional individually."
            }
            val expectedType = when (selection.kind) {
                SourceSelectionKind.Path, SourceSelectionKind.Base64 -> FieldType.StringT
                SourceSelectionKind.Bytes -> FieldType.ListOf(FieldType.Int32)
            }
            require(field.type == expectedType) {
                "Exact-one source group '$name' ${selection.kind} field `${field.name}` must be $expectedType, got ${field.type}."
            }
        }
    }
}

fun pathSource(field: FieldRef) = SourceSelection(SourceSelectionKind.Path, field)
fun base64Source(field: FieldRef) = SourceSelection(SourceSelectionKind.Base64, field)
fun bytesSource(field: FieldRef) = SourceSelection(SourceSelectionKind.Bytes, field)

fun ClassSpec.withExactOneSourceGroup(name: String, vararg selections: SourceSelection): ClassSpec {
    val group = ExactOneSourceGroup(name, selections.toList())
    group.validateFor(this)
    val selectionNames = group.selections.map { it.field.name }
    require(oneOf == null || oneOf == selectionNames) {
        "Class '${this.name}' legacy oneOf does not match exact-one source group '$name'."
    }
    return copy(oneOf = selectionNames, exactOneSourceGroups = exactOneSourceGroups + group)
}

fun class_(name: String, kotlinPackage: String? = null, block: ClassBuilder.() -> Unit): ClassSpec {
    return ClassBuilder(name, kotlinFqn = kotlinFqn(name, kotlinPackage)).apply(block).build()
}

private fun kotlinFqn(name: String, kotlinPackage: String?): String? =
    kotlinPackage?.let { "$it.$name" }

private val DEFAULT_INPUT_CAPABILITIES = setOf(InputConversionCapability.JsonObject)
private val DEFAULT_OUTPUT_CAPABILITIES = setOf(
    OutputConversionCapability.SemanticJson,
    OutputConversionCapability.McpWrapper,
)

// ---------- Builders ----------

@DslMarker
annotation class HexanaToolsDsl

@HexanaToolsDsl
class ClassBuilder internal constructor(
    private val name: String,
    seed: List<FieldSpec> = emptyList(),
    seedOneOf: List<String>? = null,
    private val kotlinFqn: String? = null,
    seedInputCapabilities: Set<InputConversionCapability> = DEFAULT_INPUT_CAPABILITIES,
    seedOutputCapabilities: Set<OutputConversionCapability> = DEFAULT_OUTPUT_CAPABILITIES,
    seedExactOneSourceGroups: List<ExactOneSourceGroup> = emptyList(),
) {
    private val fields = seed.toMutableList()
    private var oneOf: List<String>? = seedOneOf
    private var inputCapabilities = seedInputCapabilities
    private var outputCapabilities = seedOutputCapabilities
    private var exactOneSourceGroups = seedExactOneSourceGroups

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

    fun jsonObject(name: String): FieldHandle =
        addField(name, FieldType.JsonObjectT)

    fun list(name: String, of: FieldType): FieldHandle =
        addField(name, FieldType.ListOf(of))

    fun list(name: String, of: ClassSpec): FieldHandle =
        addField(name, FieldType.ListOf(FieldType.ClassRef(of)))

    fun map(name: String, of: FieldType): FieldHandle =
        addField(name, FieldType.MapOf(of))

    fun map(name: String, of: ClassSpec): FieldHandle =
        addField(name, FieldType.MapOf(FieldType.ClassRef(of)))

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

    fun inputCapabilities(vararg capabilities: InputConversionCapability) {
        require(capabilities.isNotEmpty()) { "Class '$name' input capabilities must not be empty." }
        inputCapabilities = capabilities.toSet()
    }

    fun outputCapabilities(vararg capabilities: OutputConversionCapability) {
        require(capabilities.isNotEmpty()) { "Class '$name' output capabilities must not be empty." }
        outputCapabilities = capabilities.toSet()
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
        val builtIdentity = ClassIdentity()
        return ClassSpec(
            name = name,
            fields = fields.toList(),
            oneOf = oneOf,
            kotlinFqn = kotlinFqn,
            inputCapabilities = inputCapabilities,
            outputCapabilities = outputCapabilities,
            exactOneSourceGroups = exactOneSourceGroups.map { it.reboundFor(name, fields, builtIdentity) },
            identity = builtIdentity,
        )
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

@JvmInline
value class ToolId(val value: String) {
    init {
        require(TOOL_NAME_REGEX.matches(value)) {
            "Tool id '$value' must be strict snake_case."
        }
    }

    override fun toString(): String = value
}

data class StandaloneHandlerSpec(val methodName: String) {
    init {
        require(STANDALONE_HANDLER_METHOD_NAME_REGEX.matches(methodName)) {
            "Standalone handler method name must be a Kotlin method name, got `$methodName`."
        }
    }
}

class ToolSpec(
    val id: ToolId,
    val summary: String,
    val guidance: String?,
    val input: ClassSpec,
    val output: ClassSpec,
) {
    val name: String get() = id.value

    fun copy(
        name: String = this.name,
        summary: String = this.summary,
        guidance: String? = this.guidance,
        input: ClassSpec = this.input,
        output: ClassSpec = this.output,
    ): ToolSpec = ToolSpec(
        id = ToolId(name),
        summary = summary,
        guidance = guidance,
        input = input,
        output = output,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ToolSpec) return false

        return name == other.name &&
            summary == other.summary &&
            guidance == other.guidance &&
            input == other.input &&
            output == other.output
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + summary.hashCode()
        result = 31 * result + (guidance?.hashCode() ?: 0)
        result = 31 * result + input.hashCode()
        result = 31 * result + output.hashCode()
        return result
    }

    override fun toString(): String =
        "ToolSpec(name=$name, summary=$summary, guidance=$guidance, input=$input, output=$output)"
}

enum class CliReleaseProduct(
    val productId: String,
    val executableName: String,
) {
    HexanaCli(productId = "hexana-cli", executableName = "hexana-cli"),
    HexanaCliScript(productId = "hexana-cli-script", executableName = "hexana-cli-script"),
}

data class CliToolBinding(
    val id: ToolId,
    val commandName: String,
    val groups: Set<ToolGroup>,
    val outputPolicy: ToolOutputPolicy,
    val executionStatus: CliExecutionStatus = CliExecutionStatus.Todo,
    val outputOverride: OutputConversionCapability? = null,
    val defaultOutput: OutputConversionCapability = OutputConversionCapability.SemanticJson,
    val releaseProducts: Set<CliReleaseProduct> = emptySet(),
    val inputModel: ToolInputModel? = null,
    val unknownFieldPolicy: UnknownFieldPolicy = UnknownFieldPolicy.Reject,
) {
    init {
        require(CLI_COMMAND_NAME_REGEX.matches(commandName)) {
            "CLI command '$commandName' must be strict kebab-case."
        }
        require(groups.isNotEmpty()) { "CLI tool '$commandName' must declare at least one group." }
        if (executionStatus == CliExecutionStatus.Executable) {
            require(inputModel != null) {
                "Executable CLI tool '$commandName' must declare an explicit input model."
            }
        } else {
            require(releaseProducts.isEmpty()) {
                "TODO CLI tool '$commandName' must not belong to a release product."
            }
            require(inputModel == null) {
                "TODO CLI tool '$commandName' must not declare an executable input model."
            }
        }
    }
}

/** Actual adapter-boundary handling of JSON/semantic keys not declared by a tool. */
enum class UnknownFieldPolicy { Reject, Ignore }

enum class CliExecutionStatus {
    Executable,
    Todo,
}

enum class ToolGroup {
    ArtifactQuery,
    Binary,
    Component,
    Converters,
    Data,
    Element,
    EntrypointPolicy,
    Export,
    Function,
    Global,
    Import,
    Memory,
    Module,
    Runtime,
    Scripting,
    Type,
}

enum class ToolOutputPolicy {
    ToolDefined,
}

fun cliToolBinding(
    id: ToolId,
    commandName: String,
    group: ToolGroup,
    outputPolicy: ToolOutputPolicy,
    vararg additionalGroups: ToolGroup,
    executionStatus: CliExecutionStatus = CliExecutionStatus.Todo,
    outputOverride: OutputConversionCapability? = null,
    defaultOutput: OutputConversionCapability = OutputConversionCapability.SemanticJson,
    releaseProducts: Set<CliReleaseProduct> = emptySet(),
    inputModel: ToolInputModel? = null,
    unknownFieldPolicy: UnknownFieldPolicy = UnknownFieldPolicy.Reject,
): CliToolBinding = CliToolBinding(
    id = id,
    commandName = commandName,
    groups = setOf(group, *additionalGroups),
    outputPolicy = outputPolicy,
    executionStatus = executionStatus,
    outputOverride = outputOverride,
    defaultOutput = defaultOutput,
    releaseProducts = releaseProducts,
    inputModel = inputModel,
    unknownFieldPolicy = unknownFieldPolicy,
)

val ToolSpec.mcpDescription: String
    get() = guidance?.trim()?.takeIf { it.isNotBlank() }?.let { "${summary.trim()}\n$it" } ?: summary.trim()

data class ToolCompatibilitySpec(
    val schemaVersion: Int,
    val policy: String,
) {
    init {
        require(schemaVersion > 0) { "MCP tool schema version must be positive." }
        require(policy.isNotBlank()) { "MCP tool schema compatibility policy must not be blank." }
    }
}

data class ToolInputModel(
    val sources: List<SourceSlotSpec> = emptyList(),
    val params: List<ParamBindingSpec> = emptyList(),
) {
    init {
        val duplicateSourceNames = sources.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateSourceNames.isEmpty()) { "Duplicate input source names: $duplicateSourceNames" }

        val rootSources = sources.filter { it.target == SourceTargetSpec.RootInput }
        require(rootSources.size <= 1) {
            "Input model can bind at most one source slot to the root input, got ${rootSources.map { it.name }}."
        }

        val duplicateSourceTargets = sources
            .mapNotNull { source -> source.target.fieldNameOrNull()?.let { it to source.name } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        require(duplicateSourceTargets.isEmpty()) {
            "Duplicate input source target fields: " +
                duplicateSourceTargets.entries.joinToString { (field, names) -> "`$field` used by $names" }
        }

        val duplicateCliSourceBindings = sources
            .flatMap { source -> source.cli.bindings().map { it to source.name } }
            .groupBy({ it.first.value }, { it.first.kind to it.second })
            .filterValues { it.size > 1 }
        require(duplicateCliSourceBindings.isEmpty()) {
            "Duplicate CLI source bindings in input model: " +
                duplicateCliSourceBindings.entries.joinToString { (value, bindings) ->
                    "`$value` used by ${bindings.map { (kind, sourceName) -> "$kind source `$sourceName`" }}"
                }
        }

        val autoStdinSources = sources.filter { it.cli.autoStdin }
        require(autoStdinSources.size <= 1) {
            "Input model can enable CLI auto-stdin for at most one source slot, got ${autoStdinSources.map { it.name }}."
        }

        val duplicateParamNames = params.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicateParamNames.isEmpty()) { "Duplicate input param names: $duplicateParamNames" }

        val duplicateParamTargets = params
            .groupBy { it.targetField }
            .filterValues { it.size > 1 }
        require(duplicateParamTargets.isEmpty()) {
            "Duplicate input param target fields: " +
                duplicateParamTargets.entries.joinToString { (field, params) -> "`$field` used by ${params.map { it.name }}" }
        }

        val duplicateParamCliOptions = params
            .mapNotNull { param -> param.cliOption?.let { it to param.name } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        require(duplicateParamCliOptions.isEmpty()) {
            "Duplicate CLI param options in input model: " +
                duplicateParamCliOptions.entries.joinToString { (option, params) -> "`$option` used by $params" }
        }

        val sourceCliOptions = sources.flatMap { source ->
            source.cli.bindings()
                .filter { it.value.startsWith("--") }
                .map { it.value to source.name }
        }
        val paramCliOptions = params.mapNotNull { param -> param.cliOption?.let { it to param.name } }
        val sourceParamCliOptionCollisions = (sourceCliOptions + paramCliOptions)
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        require(sourceParamCliOptionCollisions.isEmpty()) {
            "Duplicate CLI option bindings in input model: " +
                sourceParamCliOptionCollisions.entries.joinToString { (option, names) -> "`$option` used by $names" }
        }
    }
}

data class SourceSlotSpec(
    val name: String,
    val kind: SourceKind,
    val target: SourceTargetSpec,
    val required: Boolean = true,
    val cli: CliSourceSpec,
    /** stdin materializes the existing `bytes` selection; it is not a wire field. */
    val stdinSelection: SourceSelectionKind? = if (cli.stdinOption != null || cli.autoStdin) SourceSelectionKind.Bytes else null,
) {
    init {
        requireInputModelName(name, "Source slot")
    }
}

enum class SourceKind {
    Wasm,
    Binary,
    Text,
    Json,
}

sealed class SourceTargetSpec {
    object RootInput : SourceTargetSpec()

    data class Field(val name: String) : SourceTargetSpec() {
        init {
            requireInputModelName(name, "Source target field")
        }
    }

    /** Typed target used by new declarations; [Field] remains a migration bridge. */
    data class FieldReference(val reference: FieldRef) : SourceTargetSpec()
}

private fun SourceTargetSpec.fieldNameOrNull(): String? = when (this) {
    SourceTargetSpec.RootInput -> null
    is SourceTargetSpec.Field -> name
    is SourceTargetSpec.FieldReference -> reference.name
}

data class CliSourceSpec(
    val positionalPathName: String? = null,
    val pathOption: String? = null,
    val stdinOption: String? = null,
    val base64Option: String? = null,
    val bytesOption: String? = null,
    val autoStdin: Boolean = false,
) {
    init {
        positionalPathName?.let { validateCliName(it, "CLI positional path name") }
        optionBindings().forEach { validateCliOption(it.value, "CLI source ${it.kind}") }
        require(positionalPathName != null || optionBindings().isNotEmpty() || autoStdin) {
            "CLI source spec must declare a positional path, option, or auto-stdin binding."
        }

        val duplicateOptions = optionBindings()
            .groupBy { it.value }
            .filterValues { it.size > 1 }
        require(duplicateOptions.isEmpty()) {
            "CLI source spec reuses option bindings: " +
                duplicateOptions.entries.joinToString { (option, bindings) -> "$option used by ${bindings.map { it.kind }}" }
        }
    }

    internal fun bindings(): List<CliSourceBinding> =
        listOfNotNull(positionalPathName?.let { CliSourceBinding("positional path", it) }) + optionBindings()

    private fun optionBindings(): List<CliSourceBinding> = listOfNotNull(
        pathOption?.let { CliSourceBinding("path option", it) },
        stdinOption?.let { CliSourceBinding("stdin option", it) },
        base64Option?.let { CliSourceBinding("base64 option", it) },
        bytesOption?.let { CliSourceBinding("bytes option", it) },
    )
}

internal data class CliSourceBinding(val kind: String, val value: String)

@HexanaToolsDsl
class CliSourceSpecBuilder internal constructor() {
    private var positionalPathName: String? = null
    private var pathOption: String? = null
    private var stdinOption: String? = null
    private var base64Option: String? = null
    private var bytesOption: String? = null
    private var autoStdin: Boolean = false

    fun positional(name: String) {
        require(positionalPathName == null) { "CLI source positional path is already declared." }
        positionalPathName = name
    }

    fun pathOption(option: String) {
        require(pathOption == null) { "CLI source path option is already declared." }
        pathOption = option
    }

    fun stdin(option: String) {
        require(stdinOption == null) { "CLI source stdin option is already declared." }
        stdinOption = option
    }

    fun base64(option: String) {
        require(base64Option == null) { "CLI source base64 option is already declared." }
        base64Option = option
    }

    fun bytes(option: String) {
        require(bytesOption == null) { "CLI source bytes option is already declared." }
        bytesOption = option
    }

    fun autoStdin() {
        autoStdin = true
    }

    internal fun build(): CliSourceSpec = CliSourceSpec(
        positionalPathName = positionalPathName,
        pathOption = pathOption,
        stdinOption = stdinOption,
        base64Option = base64Option,
        bytesOption = bytesOption,
        autoStdin = autoStdin,
    )
}

fun cliSource(block: CliSourceSpecBuilder.() -> Unit): CliSourceSpec =
    CliSourceSpecBuilder().apply(block).build()

@HexanaToolsDsl
class SourceSlotBuilder internal constructor(
    private val name: String,
    private val kind: SourceKind,
    private val target: SourceTargetSpec,
    private val required: Boolean,
) {
    private var cli: CliSourceSpec? = null

    fun cli(block: CliSourceSpecBuilder.() -> Unit) {
        require(cli == null) { "Source slot '$name' already declares cli metadata." }
        cli = cliSource(block)
    }

    internal fun build(): SourceSlotSpec = SourceSlotSpec(
        name = name,
        kind = kind,
        target = target,
        required = required,
        cli = requireNotNull(cli) { "Source slot '$name' is missing `cli { }` metadata." },
    )
}

data class ParamBindingSpec(
    val name: String,
    val kind: ParamBindingKind,
    val targetField: String = name,
    val cliOption: String? = null,
    /** Typed target used by new declarations; `targetField` remains a migration bridge. */
    val targetReference: FieldRef? = null,
) {
    init {
        requireInputModelName(name, "Param")
        requireInputModelName(targetField, "Param target field")
        cliOption?.let { validateCliOption(it, "CLI param option") }
        targetReference?.let { reference ->
            require(reference.name == targetField) {
                "Param '$name' typed target `${reference.name}` does not match target field `$targetField`."
            }
        }
    }
}

enum class ParamBindingKind {
    Value,
    Json,
}

@HexanaToolsDsl
class ToolInputModelBuilder internal constructor() {
    private val sources = mutableListOf<SourceSlotSpec>()
    private val params = mutableListOf<ParamBindingSpec>()

    fun source(
        name: String,
        kind: SourceKind,
        target: SourceTargetSpec = SourceTargetSpec.RootInput,
        required: Boolean = true,
        cli: CliSourceSpec,
    ) {
        sources += SourceSlotSpec(
            name = name,
            kind = kind,
            target = target,
            required = required,
            cli = cli,
        )
    }

    fun wasmSource(
        name: String,
        target: SourceTargetSpec = SourceTargetSpec.Field(name),
        required: Boolean = true,
        block: SourceSlotBuilder.() -> Unit,
    ) {
        sourceSlot(name, SourceKind.Wasm, target, required, block)
    }

    fun wasmSource(
        name: String,
        targetField: String,
        required: Boolean = true,
        block: SourceSlotBuilder.() -> Unit,
    ) {
        wasmSource(name, SourceTargetSpec.Field(targetField), required, block)
    }

    fun wasmSource(name: String, target: FieldRef, required: Boolean = true, block: SourceSlotBuilder.() -> Unit) {
        wasmSource(name, SourceTargetSpec.FieldReference(target), required, block)
    }

    fun binarySource(
        name: String,
        target: SourceTargetSpec = SourceTargetSpec.Field(name),
        required: Boolean = true,
        block: SourceSlotBuilder.() -> Unit,
    ) {
        sourceSlot(name, SourceKind.Binary, target, required, block)
    }

    fun binarySource(
        name: String,
        targetField: String,
        required: Boolean = true,
        block: SourceSlotBuilder.() -> Unit,
    ) {
        binarySource(name, SourceTargetSpec.Field(targetField), required, block)
    }

    fun binarySource(name: String, target: FieldRef, required: Boolean = true, block: SourceSlotBuilder.() -> Unit) {
        binarySource(name, SourceTargetSpec.FieldReference(target), required, block)
    }

    fun param(
        name: String,
        targetField: String = name,
        cliOption: String? = null,
    ) {
        params += ParamBindingSpec(
            name = name,
            kind = ParamBindingKind.Value,
            targetField = targetField,
            cliOption = cliOption,
        )
    }

    fun param(name: String, target: FieldRef, cliOption: String? = null) {
        params += ParamBindingSpec(name, ParamBindingKind.Value, target.name, cliOption, target)
    }

    fun jsonParam(
        name: String,
        targetField: String = name,
        cliOption: String? = null,
    ) {
        params += ParamBindingSpec(
            name = name,
            kind = ParamBindingKind.Json,
            targetField = targetField,
            cliOption = cliOption,
        )
    }

    fun jsonParam(name: String, target: FieldRef, cliOption: String? = null) {
        params += ParamBindingSpec(name, ParamBindingKind.Json, target.name, cliOption, target)
    }

    private fun sourceSlot(
        name: String,
        kind: SourceKind,
        target: SourceTargetSpec,
        required: Boolean,
        block: SourceSlotBuilder.() -> Unit,
    ) {
        sources += SourceSlotBuilder(name, kind, target, required).apply(block).build()
    }

    internal fun build(): ToolInputModel = ToolInputModel(
        sources = sources.toList(),
        params = params.toList(),
    )
}

fun toolInputModel(block: ToolInputModelBuilder.() -> Unit): ToolInputModel =
    ToolInputModelBuilder().apply(block).build()

fun wasmCliInputModel(): ToolInputModel = rootSourceCliInputModel(
    sourceName = "wasm",
    kind = SourceKind.Wasm,
    positionalPathName = "wasm",
)

fun binaryCliInputModel(): ToolInputModel = rootSourceCliInputModel(
    sourceName = "binary",
    kind = SourceKind.Binary,
    positionalPathName = "binary",
)

private fun rootSourceCliInputModel(
    sourceName: String,
    kind: SourceKind,
    positionalPathName: String,
): ToolInputModel =
    ToolInputModel(
        sources = listOf(
            SourceSlotSpec(
                name = sourceName,
                kind = kind,
                target = SourceTargetSpec.RootInput,
                required = true,
                cli = CliSourceSpec(
                    positionalPathName = positionalPathName,
                    stdinOption = "--stdin",
                    base64Option = "--bytes-base64",
                    autoStdin = true,
                ),
            )
        ),
        params = emptyList(),
    )

sealed class ToolInputPattern {
    object WasmOnly : ToolInputPattern()
    object WasmPathLabel : ToolInputPattern()
    object WasmWithBaseline : ToolInputPattern()
    object WasmWithBinding : ToolInputPattern()
    object FunctionPage : ToolInputPattern()
    object Funcidx : ToolInputPattern()
    object FunctionNameSearch : ToolInputPattern()
    object ExportNameSearch : ToolInputPattern()
    object Indices : ToolInputPattern()
    object SourceMappedIndices : ToolInputPattern()
    object WasmFrames : ToolInputPattern()
    object SourceLocations : ToolInputPattern()
    data class ClosedRange(val parameterName: String) : ToolInputPattern()
    object DataSegments : ToolInputPattern()
    object DataPayloadPolicyAudit : ToolInputPattern()
    object LocalStackBudgetAudit : ToolInputPattern()
    object ArtifactQuery : ToolInputPattern()
    object PolicyWithBaselineAndLog : ToolInputPattern()
    object GlobalStatePolicyAudit : ToolInputPattern()
    object RuntimeCompatibility : ToolInputPattern()
    object ComponentSurface : ToolInputPattern()
    object ComponentDependencies : ToolInputPattern()
    object ComponentCompatibility : ToolInputPattern()
    object BinaryOnly : ToolInputPattern()
    object BoundedBinaryQuery : ToolInputPattern()
    object BinaryFunctionPage : ToolInputPattern()
    object BinaryFunctionSearch : ToolInputPattern()
}

@HexanaToolsDsl
class ToolsBuilder internal constructor() {
    private val specs = mutableListOf<ToolSpec>()

    fun tool(id: ToolId, block: ToolBuilder.() -> Unit) {
        val builder = ToolBuilder(id).apply(block)
        specs += builder.build()
    }

    internal fun build(): List<ToolSpec> {
        validateToolCollection(specs)
        return specs.toList()
    }
}

private val FQN_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
private val FIELD_NAME_REGEX = Regex("^[a-z][A-Za-z0-9]*$")
private val TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
private val STANDALONE_HANDLER_METHOD_NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private val CLI_COMMAND_NAME_REGEX = Regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
private val CLI_OPTION_NAME_REGEX = Regex("^--[a-z][a-z0-9]*(-[a-z0-9]+)*$")

private fun requireInputModelName(name: String, owner: String) {
    require(FIELD_NAME_REGEX.matches(name)) {
        "$owner name '$name' must be lowerCamelCase."
    }
}

private fun validateCliName(name: String, owner: String) {
    require(CLI_COMMAND_NAME_REGEX.matches(name)) {
        "$owner '$name' must be strict kebab-case."
    }
}

private fun validateCliOption(option: String, owner: String) {
    require(CLI_OPTION_NAME_REGEX.matches(option)) {
        "$owner '$option' must be a strict kebab-case long option like `--input-path`."
    }
}

@HexanaToolsDsl
class ToolBuilder internal constructor(private val id: ToolId) {
    private val name: String get() = id.value
    var summary: String? = null
    var guidance: String? = null
    var input: ClassSpec? = null
    var output: ClassSpec? = null

    internal fun build(): ToolSpec {
        val resolvedSummary = requireNotNull(summary) { "Tool '$name' is missing `summary`." }
        require(resolvedSummary.isNotBlank()) { "Tool '$name' summary must not be blank." }
        val resolvedInput = requireNotNull(input) { "Tool '$name' is missing `input = `." }
        val resolvedOutput = requireNotNull(output) { "Tool '$name' is missing `output = `." }
        return ToolSpec(
            id = id,
            summary = resolvedSummary.trim(),
            guidance = guidance?.trimIndent()?.trim()?.takeIf { it.isNotEmpty() },
            input = resolvedInput,
            output = resolvedOutput,
        )
    }
}

internal fun validateCliBinding(contract: ToolSpec, binding: CliToolBinding) {
    binding.inputModel?.let { validateExplicitInputModel(contract.name, contract.input, it) }
    val selectedOutputs = listOfNotNull(binding.outputOverride, binding.defaultOutput)
    selectedOutputs.forEach { selectedOutput ->
        require(selectedOutput in contract.output.outputCapabilities) {
            "Tool '${contract.name}' CLI output $selectedOutput is not declared by output class " +
                "`${contract.output.name}`. Declared output capabilities: ${contract.output.outputCapabilities}."
        }
    }
}

private fun validateExplicitInputModel(toolName: String, input: ClassSpec, inputModel: ToolInputModel) {
    fun requireInputField(fieldName: String): FieldSpec {
        return input.fields.singleOrNull { it.name == fieldName } ?: throw IllegalArgumentException(
            "Tool '$toolName' explicit CLI input model references unknown input field `$fieldName` on `${input.name}`."
        )
    }

    inputModel.sources.forEach { source ->
        val sourceInput = sourceInputClass(source.kind)
        val targetField: FieldSpec?
        val targetClass = when (val target = source.target) {
            SourceTargetSpec.RootInput -> {
                require(input.inputCapabilities.containsAll(source.kind.requiredRootCapabilities())) {
                    "Tool '$toolName' explicit CLI ${source.kind} root source `${source.name}` requires " +
                        "${source.kind.requiredRootCapabilities()} on `${input.name}`, got ${input.inputCapabilities}."
                }
                require(input.exactOneSourceGroups.singleOrNull()?.selections?.map { it.kind to it.field.name } ==
                    sourceInput.exactOneSourceGroups.singleOrNull()?.selections?.map { it.kind to it.field.name }) {
                    "Tool '$toolName' explicit CLI source `${source.name}` of kind ${source.kind} must target " +
                        "a root input derived from `${sourceInput.name}`, got `${input.name}`."
                }
                targetField = null
                sourceInput
            }
            is SourceTargetSpec.Field -> {
                val field = requireInputField(target.name)
                validateExplicitSourceTargetField(toolName, source, field)
                targetField = field
                (field.type as? FieldType.ClassRef)?.clazz ?: error("validated source target is not a class")
            }
            is SourceTargetSpec.FieldReference -> {
                val field = requireInputField(target.reference.name)
                require(input.fieldRef(field.name) == target.reference) {
                    "Tool '$toolName' explicit CLI source `${source.name}` references a foreign or stale field `${target.reference.name}`."
                }
                validateExplicitSourceTargetField(toolName, source, field)
                targetField = field
                (field.type as? FieldType.ClassRef)?.clazz ?: error("validated source target is not a class")
            }
        }
        val group = targetClass.exactOneSourceGroups.singleOrNull()
            ?: throw IllegalArgumentException(
                "Tool '$toolName' source `${source.name}` must target an input class with one exact-one source group."
            )
        if (targetField == null) {
            require(source.required == group.required) {
                "Tool '$toolName' root source `${source.name}` required=${source.required} " +
                    "does not match exact-one source group '${group.name}' required=${group.required}."
            }
        }
        val hasStdin = source.cli.stdinOption != null || source.cli.autoStdin
        require((source.stdinSelection != null) == hasStdin) {
            "Tool '$toolName' source `${source.name}` stdin selection must be absent without stdin metadata and present with it."
        }
        if (hasStdin) {
            require(source.stdinSelection == SourceSelectionKind.Bytes) {
                "Tool '$toolName' source `${source.name}` stdin must materialize the existing Bytes selection, not ${source.stdinSelection}."
            }
            require(group.selections.any { it.kind == source.stdinSelection }) {
                "Tool '$toolName' source `${source.name}` stdin must map to an existing ${source.stdinSelection} selection."
            }
        }
        targetField?.let { field ->
            val expectedPresence = if (source.required) FieldPresence.Required else FieldPresence.Optional
            require(field.presence == expectedPresence) {
                "Tool '$toolName' source `${source.name}` required=${source.required} does not match target field `${field.name}` presence ${field.presence}."
            }
        }
    }

    inputModel.params.forEach { param ->
        val field = requireInputField(param.targetField)
        param.targetReference?.let { reference ->
            require(input.fieldRef(field.name) == reference) {
                "Tool '$toolName' CLI param `${param.name}` references a foreign or stale field `${reference.name}`."
            }
        }
        if (param.kind == ParamBindingKind.Json) {
            require(field.type == FieldType.JsonObjectT) {
                "Tool '$toolName' JSON CLI param `${param.name}` must target a JSON object input field, " +
                    "got `${param.targetField}` with type ${field.type}."
            }
        }
    }
}

private fun sourceInputClass(kind: SourceKind): ClassSpec = when (kind) {
    SourceKind.Wasm -> WasmInputClass
    SourceKind.Binary -> BinaryInputClass
    SourceKind.Text, SourceKind.Json -> throw IllegalArgumentException("Source kind $kind has no declared input class yet.")
}

private fun SourceKind.requiredRootCapabilities(): Set<InputConversionCapability> = when (this) {
    SourceKind.Wasm -> setOf(InputConversionCapability.WasmPath, InputConversionCapability.WasmFromString, InputConversionCapability.Stdin)
    SourceKind.Binary -> setOf(InputConversionCapability.BinaryPath, InputConversionCapability.Stdin)
    SourceKind.Text, SourceKind.Json -> emptySet()
}

private fun validateExplicitSourceTargetField(toolName: String, source: SourceSlotSpec, field: FieldSpec) {
    val expectedClass = sourceInputClass(source.kind)
    val actualClass = (field.type as? FieldType.ClassRef)?.clazz
    require(actualClass == expectedClass) {
        "Tool '$toolName' explicit CLI source `${source.name}` of kind ${source.kind} must target " +
            "input field `${field.name}` as `${expectedClass.name}`, got ${field.describeType()}."
    }
}

private fun FieldSpec.describeType(): String {
    val base = type.describeForValidation()
    return if (nullable) "$base?" else base
}

private fun FieldType.describeForValidation(): String =
    when (this) {
        FieldType.Int32 -> "Int32"
        FieldType.Int64 -> "Int64"
        FieldType.Float64 -> "Float64"
        FieldType.UInt32 -> "UInt32"
        FieldType.ByteT -> "Byte"
        FieldType.BooleanT -> "Boolean"
        FieldType.StringT -> "String"
        FieldType.ByteArrayT -> "ByteArray"
        FieldType.JsonObjectT -> "JsonObject"
        is FieldType.ListOf -> "List<${element.describeForValidation()}>"
        is FieldType.MapOf -> "Map<${value.describeForValidation()}>"
        is FieldType.ClassRef -> clazz.name
    }

internal fun validateToolCollection(specs: List<ToolSpec>) {
    val duplicateToolNames = specs.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicateToolNames.isEmpty()) { "Duplicate tool names: $duplicateToolNames" }
}

internal fun validateCliReleaseProducts(
    tools: List<CliTool>,
    requireCompleteSelection: Boolean = true,
) {
    val selectedTools = tools.filter { it.binding.releaseProducts.isNotEmpty() }
    selectedTools.forEach { tool ->
        val cli = tool.binding
        require(cli.executionStatus == CliExecutionStatus.Executable) {
            "Release product tool '${tool.name}' must be an executable CLI tool."
        }
    }

    val toolsToCheck = if (requireCompleteSelection) {
        tools.filter { it.binding.executionStatus == CliExecutionStatus.Executable }
    } else {
        selectedTools
    }
    toolsToCheck.forEach { tool ->
        val products = tool.binding.releaseProducts
        require(products.size == 1) {
            "Executable CLI tool '${tool.name}' must select exactly one CLI release product, got $products."
        }
    }

    CliReleaseProduct.entries.forEach { product ->
        val duplicateCommands = selectedTools
            .filter { product in it.binding.releaseProducts }
            .groupBy { it.binding.commandName }
            .filterValues { it.size > 1 }
        require(duplicateCommands.isEmpty()) {
            "Duplicate command names in ${product.productId}: " +
                duplicateCommands.entries.joinToString { (command, tools) ->
                    "$command used by ${tools.map { it.name }}"
                }
        }
    }
}

internal fun validateInputPattern(toolName: String, input: ClassSpec, pattern: ToolInputPattern) {
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

    fun requireListOfClass(field: FieldSpec): ClassSpec {
        val listType = field.type as? FieldType.ListOf ?: throw IllegalArgumentException(
            "Tool '$toolName' input field `${field.name}` must be a list, got ${field.type}."
        )
        return (listType.element as? FieldType.ClassRef)?.clazz ?: throw IllegalArgumentException(
            "Tool '$toolName' input field `${field.name}` must list embedded classes, got ${listType.element}."
        )
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

    fun requireExactBinaryBytesInput(
        inputKind: String,
        expectedBytesMaxItems: SchemaIntValue,
        expectedBase64MaxLength: SchemaIntValue,
    ) {
        val path = requireField("path")
        requireType(path, FieldType.StringT)
        requireNullable(path)
        requireDescribed(path)
        val bytesBase64 = requireField("bytesBase64")
        requireType(bytesBase64, FieldType.StringT)
        requireNullable(bytesBase64)
        requireDescribed(bytesBase64)
        require(bytesBase64.maxLength == expectedBase64MaxLength) {
            "Tool '$toolName' input field `bytesBase64` must declare $inputKind inline base64 bounds."
        }
        val bytes = requireField("bytes")
        requireListOf(bytes, FieldType.Int32)
        requireNullable(bytes)
        requireDescribed(bytes)
        require(bytes.maxItems == expectedBytesMaxItems) {
            "Tool '$toolName' input field `bytes` must declare $inputKind inline byte bounds."
        }
        require(input.oneOf == listOf("path", "bytesBase64", "bytes")) {
            "Tool '$toolName' input class `${input.name}` must keep $inputKind input oneOf(path, bytesBase64, bytes)."
        }
    }

    fun requireWasmInput() {
        requireExactBinaryBytesInput(
            inputKind = "Wasm",
            expectedBytesMaxItems = SchemaIntValue.MaxInlineWasmBytes,
            expectedBase64MaxLength = SchemaIntValue.MaxInlineWasmBase64Length,
        )
    }

    fun requireBinaryInput() {
        requireExactBinaryBytesInput(
            inputKind = "binary",
            expectedBytesMaxItems = SchemaIntValue.MaxInlineBinaryBytes,
            expectedBase64MaxLength = SchemaIntValue.MaxInlineBinaryBase64Length,
        )
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

    fun requireBinaryFunctionPageInput() {
        requireBinaryInput()

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

    when (pattern) {
        ToolInputPattern.WasmOnly -> {
            requireWasmInput()
        }
        ToolInputPattern.WasmPathLabel -> {
            requireWasmInput()
        }
        ToolInputPattern.WasmWithBaseline -> {
            requireWasmInput()
            val baselinePath = requireField("baselinePath")
            requireType(baselinePath, FieldType.StringT)
            requireNonNullable(baselinePath)
            requireDescribed(baselinePath)
        }
        ToolInputPattern.WasmWithBinding -> {
            requireWasmInput()
            val bindingPath = requireField("bindingPath")
            requireType(bindingPath, FieldType.StringT)
            requireNonNullable(bindingPath)
            requireDescribed(bindingPath)
        }
        ToolInputPattern.FunctionPage -> {
            requireWasmInput()
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
            requireWasmInput()
            val funcidx = requireField("funcidx")
            requireType(funcidx, FieldType.Int32)
            requireNonNullable(funcidx)
            requireDescribed(funcidx)
        }
        ToolInputPattern.FunctionNameSearch -> {
            requireWasmInput()
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
            requireWasmInput()
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
            requireWasmInput()
            val indices = requireField("indices")
            requireListOf(indices, FieldType.Int32)
            requireNonNullable(indices)
            requireDescribed(indices)
        }
        ToolInputPattern.SourceMappedIndices -> {
            requireWasmInput()
            val indices = requireField("indices")
            requireListOf(indices, FieldType.Int32)
            requireNonNullable(indices)
            requireDescribed(indices)
            val sourceMapPath = requireField("sourceMapPath")
            requireType(sourceMapPath, FieldType.StringT)
            requireNullable(sourceMapPath)
            requireDescribed(sourceMapPath)
        }
        ToolInputPattern.WasmFrames -> {
            requireWasmInput()
            val frames = requireField("frames")
            requireDescribed(frames)
            requireNullable(frames)
            val frameClass = requireListOfClass(frames)
            val funcidx = requireField("funcidx", owner = frameClass)
            requireType(funcidx, FieldType.Int32)
            requireNonNullable(funcidx)
            requireDescribed(funcidx)
            val codeOffset = requireField("codeOffset", owner = frameClass)
            requireType(codeOffset, FieldType.Int64)
            requireNullable(codeOffset)
            requireDescribed(codeOffset)
            val module = requireField("module", owner = frameClass)
            requireType(module, FieldType.StringT)
            requireNullable(module)
            requireDescribed(module)
            val label = requireField("label", owner = frameClass)
            requireType(label, FieldType.StringT)
            requireNullable(label)
            requireDescribed(label)
            val crashReportPath = requireField("crashReportPath")
            requireType(crashReportPath, FieldType.StringT)
            requireNullable(crashReportPath)
            requireDescribed(crashReportPath)
            val sourceMapPath = requireField("sourceMapPath")
            requireType(sourceMapPath, FieldType.StringT)
            requireNullable(sourceMapPath)
            requireDescribed(sourceMapPath)
        }
        ToolInputPattern.SourceLocations -> {
            requireWasmInput()
            val locations = requireField("locations")
            requireDescribed(locations)
            val locationClass = requireListOfClass(locations)
            val funcidx = requireField("funcidx", owner = locationClass)
            requireType(funcidx, FieldType.Int32)
            requireNullable(funcidx)
            requireDescribed(funcidx)
            val codeOffset = requireField("codeOffset", owner = locationClass)
            requireType(codeOffset, FieldType.Int64)
            requireNullable(codeOffset)
            requireDescribed(codeOffset)
            val sourceMapPath = requireField("sourceMapPath")
            requireType(sourceMapPath, FieldType.StringT)
            requireNullable(sourceMapPath)
            requireDescribed(sourceMapPath)
        }
        is ToolInputPattern.ClosedRange -> {
            requireWasmInput()
            requireClosedRange(requireField(pattern.parameterName))
        }
        ToolInputPattern.DataSegments -> {
            requireWasmInput()
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
        ToolInputPattern.DataPayloadPolicyAudit -> {
            requireWasmInput()
            val policyPath = requireField("policyPath")
            requireType(policyPath, FieldType.StringT)
            requireNonNullable(policyPath)
            requireDescribed(policyPath)
        }
        ToolInputPattern.LocalStackBudgetAudit -> {
            requireWasmInput()
            val policyPath = requireField("policyPath")
            requireType(policyPath, FieldType.StringT)
            requireNullable(policyPath)
            requireDescribed(policyPath)

            val logPath = requireField("logPath")
            requireType(logPath, FieldType.StringT)
            requireNullable(logPath)
            requireDescribed(logPath)

            val checkedExports = requireField("checkedExports")
            requireListOf(checkedExports, FieldType.StringT)
            requireNullable(checkedExports)
            requireDescribed(checkedExports)

            val maxLocals = requireField("maxLocals")
            requireType(maxLocals, FieldType.Int32)
            requireNullable(maxLocals)
            requireDescribed(maxLocals)

            val countParams = requireField("countParams")
            requireType(countParams, FieldType.BooleanT)
            requireNonNullable(countParams)
            requireDefault(countParams, "Boolean") { it is Boolean }
            requireDescribed(countParams)
        }
        ToolInputPattern.ArtifactQuery -> {
            requireWasmInput()
            val baselinePath = requireField("baselinePath")
            requireType(baselinePath, FieldType.StringT)
            requireNullable(baselinePath)
            requireDescribed(baselinePath)

            val bindingPath = requireField("bindingPath")
            requireType(bindingPath, FieldType.StringT)
            requireNullable(bindingPath)
            requireDescribed(bindingPath)

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
        ToolInputPattern.PolicyWithBaselineAndLog -> {
            requireWasmInput()
            val baselinePath = requireField("baselinePath")
            requireType(baselinePath, FieldType.StringT)
            requireNonNullable(baselinePath)
            requireDescribed(baselinePath)

            val policyPath = requireField("policyPath")
            requireType(policyPath, FieldType.StringT)
            requireNonNullable(policyPath)
            requireDescribed(policyPath)

            val logPath = requireField("logPath")
            requireType(logPath, FieldType.StringT)
            requireNullable(logPath)
            requireDescribed(logPath)
        }
        ToolInputPattern.GlobalStatePolicyAudit -> {
            requireWasmInput()
            val baselinePath = requireField("baselinePath")
            requireType(baselinePath, FieldType.StringT)
            requireNonNullable(baselinePath)
            requireDescribed(baselinePath)

            val policyPath = requireField("policyPath")
            requireType(policyPath, FieldType.StringT)
            requireNonNullable(policyPath)
            requireDescribed(policyPath)

            val logPath = requireField("logPath")
            requireType(logPath, FieldType.StringT)
            requireNullable(logPath)
            requireDescribed(logPath)

            val detail = requireField("detail")
            requireType(detail, FieldType.StringT)
            requireNonNullable(detail)
            requireDefault(detail, "String") { it is String }
            requireDescribed(detail)
        }
        ToolInputPattern.RuntimeCompatibility -> {
            requireWasmInput()
            val runtime = requireField("runtime")
            requireType(runtime, FieldType.StringT)
            requireNonNullable(runtime)
            requireDescribed(runtime)

            val version = requireField("version")
            requireType(version, FieldType.StringT)
            requireNullable(version)
            requireDescribed(version)
        }
        ToolInputPattern.ComponentSurface -> {
            requireWasmInput()
        }
        ToolInputPattern.ComponentDependencies -> {
            requireWasmInput()
            val projectRoot = requireField("projectRoot")
            requireType(projectRoot, FieldType.StringT)
            requireNullable(projectRoot)
            requireDescribed(projectRoot)

            val scanRoots = requireField("scanRoots")
            requireListOf(scanRoots, FieldType.StringT)
            requireNullable(scanRoots)
            requireDescribed(scanRoots)
        }
        ToolInputPattern.ComponentCompatibility -> {
            requireWasmInput()
            val dependencyPaths = requireField("dependencyPaths")
            requireListOf(dependencyPaths, FieldType.StringT)
            requireNullable(dependencyPaths)
            requireDescribed(dependencyPaths)

            val projectRoot = requireField("projectRoot")
            requireType(projectRoot, FieldType.StringT)
            requireNullable(projectRoot)
            requireDescribed(projectRoot)

            val previousPath = requireField("previousPath")
            requireType(previousPath, FieldType.StringT)
            requireNullable(previousPath)
            requireDescribed(previousPath)

            val dependencyLockPath = requireField("dependencyLockPath")
            requireType(dependencyLockPath, FieldType.StringT)
            requireNullable(dependencyLockPath)
            requireDescribed(dependencyLockPath)
        }
        ToolInputPattern.BinaryOnly -> {
            requireBinaryInput()
        }
        ToolInputPattern.BoundedBinaryQuery -> {
            requireBinaryInput()

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
        ToolInputPattern.BinaryFunctionPage -> {
            requireBinaryFunctionPageInput()
        }
        ToolInputPattern.BinaryFunctionSearch -> {
            requireBinaryFunctionPageInput()

            val query = requireField("query")
            requireListOf(query, FieldType.StringT)
            requireNonNullable(query)
            requireDescribed(query)
        }
    }
}

fun tools(block: ToolsBuilder.() -> Unit): List<ToolSpec> {
    return ToolsBuilder().apply(block).build()
}
