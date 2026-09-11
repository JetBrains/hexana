package org.jetbrains.hexana.mcp.tools

import java.util.Collections
import java.util.IdentityHashMap

/** Immutable, target-neutral normalization of a [ClassSpec] schema graph. */
class SchemaPlan(
    val root: SchemaClassPlan,
    classes: List<SchemaClassPlan>,
) {
    val classes: List<SchemaClassPlan> = immutableList(classes)
}

/** Stable within a compiled plan, including when the same nested class is used more than once. */
data class SchemaClassIdentity(val name: String, val ordinal: Int = 1) {
    val value: String get() = "class(${identitySegment(name)})#$ordinal"
}

data class SchemaFieldIdentity(val owner: SchemaClassIdentity, val name: String, val ordinal: Int = 1) {
    val value: String get() = "${owner.value}.field(${identitySegment(name)})#$ordinal"
}

class SchemaClassPlan(
    val identity: SchemaClassIdentity,
    val name: String,
    fields: List<SchemaFieldPlan>,
    oneOf: List<SchemaFieldIdentity>?,
    sourceGroups: List<SchemaExactOneSourceGroupPlan>,
) {
    val fields: List<SchemaFieldPlan> = immutableList(fields)
    val oneOf: List<SchemaFieldIdentity>? = oneOf?.let(::immutableList)
    val sourceGroups: List<SchemaExactOneSourceGroupPlan> = immutableList(sourceGroups)
}

data class SchemaFieldPlan(
    val identity: SchemaFieldIdentity,
    val name: String,
    val wireName: String,
    val type: SchemaTypePlan,
    val nullable: Boolean,
    val presence: FieldPresence,
    val description: String?,
    val defaultValue: FieldDefaultValue?,
    val constraints: SchemaConstraints,
)

data class SchemaConstraints(
    val minimum: Int?,
    val maximum: Int?,
    val maxItems: SchemaIntValue?,
    val maxLength: SchemaIntValue?,
    val itemMinimum: Int?,
    val itemMaximum: Int?,
)

class SchemaExactOneSourceGroupPlan(
    val name: String,
    fields: List<SchemaFieldIdentity>,
    val required: Boolean,
) {
    val fields: List<SchemaFieldIdentity> = immutableList(fields)
}

sealed class SchemaTypePlan {
    data class Scalar(val kind: SchemaScalarKind) : SchemaTypePlan()
    data class ListOf(val element: SchemaTypePlan) : SchemaTypePlan()
    data class MapOf(val value: SchemaTypePlan) : SchemaTypePlan()
    data class ClassRef(val clazz: SchemaClassPlan) : SchemaTypePlan()
}

enum class SchemaScalarKind {
    Int32,
    Int64,
    Float64,
    UInt32,
    Byte,
    Boolean,
    String,
    ByteArray,
    JsonObject,
}

class SchemaPath private constructor(private val segments: List<String>) {
    fun field(name: String): SchemaPath = SchemaPath(segments + name)
    fun listElement(): SchemaPath = SchemaPath(segments.dropLast(1) + "${segments.last()}[]")
    fun mapValue(): SchemaPath = SchemaPath(segments.dropLast(1) + "${segments.last()}{}")
    override fun toString(): String = segments.joinToString(".")
    override fun equals(other: Any?): Boolean = other is SchemaPath && segments == other.segments
    override fun hashCode(): Int = segments.hashCode()

    companion object {
        fun root(name: String): SchemaPath = SchemaPath(listOf(name))
    }
}

data class SchemaDiagnostic(val path: SchemaPath, val message: String) {
    override fun toString(): String = "$path: $message"
}

class SchemaPlanValidationException(diagnostics: List<SchemaDiagnostic>) :
    IllegalArgumentException(diagnostics.joinToString(separator = "\n") { it.toString() }) {
    val diagnostics: List<SchemaDiagnostic> = immutableList(diagnostics)
}

/** Compiles, validates, and snapshots a schema without resolving target-specific capabilities or limits. */
fun compileSchemaPlan(root: ClassSpec): SchemaPlan = root.compiledSchemaPlan

internal class SchemaPlanCompiler {
    private val diagnostics = mutableListOf<SchemaDiagnostic>()
    private val validated = Collections.newSetFromMap(IdentityHashMap<ClassSpec, Boolean>())
    private val compiled = IdentityHashMap<ClassSpec, SchemaClassPlan>()
    private val classNames = mutableMapOf<String, Int>()
    private val allClasses = mutableListOf<SchemaClassPlan>()

    fun compile(root: ClassSpec): SchemaPlan {
        validateClass(root, SchemaPath.root(root.name))
        if (diagnostics.isNotEmpty()) throw SchemaPlanValidationException(diagnostics)
        val rootPlan = compileClass(root)
        return SchemaPlan(rootPlan, listOf(rootPlan) + allClasses.filterNot { it === rootPlan })
    }

    private fun validateClass(clazz: ClassSpec, path: SchemaPath) {
        if (!validated.add(clazz)) return
        val fieldsByWireName = clazz.fields.groupBy(FieldSpec::schemaKey)
        fieldsByWireName.filterValues { it.size > 1 }.forEach { (wireName, fields) ->
            fields.forEach { field ->
                report(path.field(field.schemaKey), "duplicate effective wire key `$wireName` for fields ${fields.joinToString { "`${it.name}`" }}.")
            }
        }
        clazz.oneOf?.let { choices ->
            if (choices.distinct().size != choices.size) report(path, "oneOf contains duplicate fields: $choices.")
            choices.forEach { choice ->
                val matchingFields = clazz.fields.filter { it.name == choice }
                when (matchingFields.size) {
                    0 -> report(path.field(choice), "oneOf choice `$choice` resolves to no raw field.")
                    1 -> Unit
                    else -> report(
                        path.field(choice),
                        "oneOf choice `$choice` resolves to ${matchingFields.size} raw fields " +
                            "with wire keys ${matchingFields.joinToString { "`${it.schemaKey}`" }}; it must resolve to exactly one.",
                    )
                }
            }
        }
        clazz.fields.forEach { field ->
            val fieldPath = path.field(field.schemaKey)
            validateField(field, fieldPath)
            validateNested(field.type, fieldPath)
        }
    }

    private fun validateNested(type: FieldType, path: SchemaPath) {
        when (type) {
            is FieldType.ClassRef -> validateClass(type.clazz, path)
            is FieldType.ListOf -> validateNested(type.element, path.listElement())
            is FieldType.MapOf -> validateNested(type.value, path.mapValue())
            else -> Unit
        }
    }

    private fun validateField(field: FieldSpec, path: SchemaPath) {
        fun report(message: String) = report(path, message)
        val default = (field.presence as? FieldPresence.Defaulted)?.value
        when (field.presence) {
            FieldPresence.Required -> {
                if (field.nullable) report("Required presence requires a non-nullable field while the legacy bridge is active.")
                if (field.default != null) report("legacy default bridge requires Defaulted presence.")
            }
            FieldPresence.Optional -> {
                if (!field.nullable) report("Optional presence requires a nullable field while the legacy bridge is active.")
                if (field.default != null) report("legacy default bridge requires Defaulted presence.")
            }
            is FieldPresence.Defaulted -> Unit
        }
        if (default != null) {
            if (field.default != default.legacyValue()) report("legacy default bridge does not match explicit presence default.")
            if (default == FieldDefaultValue.Null) {
                if (field.type !in scalarDefaultTypes) report("defaults are supported only for scalar fields currently emitted by generators, got ${field.type}.")
                if (!field.nullable) report("a null default requires a nullable field.")
            } else if (!defaultMatches(default, field.type)) {
                report("default value `$default` does not match declared type ${field.type}.")
            }
        }

        val numeric = field.type.isNumeric()
        if (field.minimum != null || field.maximum != null) {
            if (!numeric) report("minimum/maximum constraints require a numeric field type, got ${field.type}.")
            if (field.minimum != null && field.maximum != null && field.minimum > field.maximum) {
                report("minimum ${field.minimum} exceeds maximum ${field.maximum}.")
            }
            validateNumericDomain(field.type, field.minimum, "minimum", ::report)
            validateNumericDomain(field.type, field.maximum, "maximum", ::report)
        }
        val listType = field.type as? FieldType.ListOf
        if (field.maxItems != null || field.itemMinimum != null || field.itemMaximum != null) {
            if (listType == null) report("maxItems/item constraints require a list field type, got ${field.type}.")
            literalValue(field.maxItems)?.takeIf { it < 0 }?.let { report("maxItems must not be negative, got $it.") }
        }
        if (field.maxLength != null) {
            if (field.type != FieldType.StringT && field.type != FieldType.ByteArrayT) {
                report("maxLength requires a string-like field type, got ${field.type}.")
            }
            literalValue(field.maxLength)?.takeIf { it < 0 }?.let { report("maxLength must not be negative, got $it.") }
        }
        if (listType != null && (field.itemMinimum != null || field.itemMaximum != null)) {
            if (!listType.element.isNumeric()) report("item minimum/maximum constraints require numeric list items, got ${listType.element}.")
            if (field.itemMinimum != null && field.itemMaximum != null && field.itemMinimum > field.itemMaximum) {
                report("item minimum ${field.itemMinimum} exceeds item maximum ${field.itemMaximum}.")
            }
            validateNumericDomain(listType.element, field.itemMinimum, "item minimum", ::report)
            validateNumericDomain(listType.element, field.itemMaximum, "item maximum", ::report)
        }
        when (val value = default?.legacyValue()) {
            is Int, is Long -> if (defaultMatches(default, field.type)) validateNumericDefault(value as Number, field.type, field.minimum, field.maximum, ::report)
            is Double -> if (defaultMatches(default, field.type)) {
                if (!value.isFinite()) report("Float64 default must be finite, got $value.")
                validateNumericDefault(value, field.type, field.minimum, field.maximum, ::report)
            }
            is String -> literalValue(field.maxLength)?.let { maximumLength ->
                if (value.codePointCount(0, value.length) > maximumLength) report("default exceeds maxLength $maximumLength.")
            }
        }
    }

    private fun compileClass(clazz: ClassSpec): SchemaClassPlan {
        compiled[clazz]?.let { return it }
        val identity = SchemaClassIdentity(clazz.name, nextClassOrdinal(clazz.name))
        val fieldNames = mutableMapOf<String, Int>()
        val fields = clazz.fields.map { field ->
            val fieldOrdinal = (fieldNames[field.name] ?: 0) + 1
            fieldNames[field.name] = fieldOrdinal
            SchemaFieldPlan(
                identity = SchemaFieldIdentity(identity, field.name, fieldOrdinal),
                name = field.name,
                wireName = field.schemaKey,
                type = compileType(field.type),
                nullable = field.nullable,
                presence = field.presence,
                description = field.describe,
                defaultValue = (field.presence as? FieldPresence.Defaulted)?.value,
                constraints = SchemaConstraints(field.minimum, field.maximum, field.maxItems, field.maxLength, field.itemMinimum, field.itemMaximum),
            )
        }
        val fieldsByName = fields.groupBy(SchemaFieldPlan::name)
        val sourceGroups = clazz.exactOneSourceGroups.map { group ->
            SchemaExactOneSourceGroupPlan(group.name, group.selections.map { fieldsByName.getValue(it.field.name).single().identity }, group.required)
        }
        val plan = SchemaClassPlan(identity, clazz.name, fields, clazz.oneOf?.map { fieldsByName.getValue(it).single().identity }, sourceGroups)
        compiled[clazz] = plan
        allClasses += plan
        return plan
    }

    private fun compileType(type: FieldType): SchemaTypePlan = when (type) {
        FieldType.Int32 -> SchemaTypePlan.Scalar(SchemaScalarKind.Int32)
        FieldType.Int64 -> SchemaTypePlan.Scalar(SchemaScalarKind.Int64)
        FieldType.Float64 -> SchemaTypePlan.Scalar(SchemaScalarKind.Float64)
        FieldType.UInt32 -> SchemaTypePlan.Scalar(SchemaScalarKind.UInt32)
        FieldType.ByteT -> SchemaTypePlan.Scalar(SchemaScalarKind.Byte)
        FieldType.BooleanT -> SchemaTypePlan.Scalar(SchemaScalarKind.Boolean)
        FieldType.StringT -> SchemaTypePlan.Scalar(SchemaScalarKind.String)
        FieldType.ByteArrayT -> SchemaTypePlan.Scalar(SchemaScalarKind.ByteArray)
        FieldType.JsonObjectT -> SchemaTypePlan.Scalar(SchemaScalarKind.JsonObject)
        is FieldType.ListOf -> SchemaTypePlan.ListOf(compileType(type.element))
        is FieldType.MapOf -> SchemaTypePlan.MapOf(compileType(type.value))
        is FieldType.ClassRef -> SchemaTypePlan.ClassRef(compileClass(type.clazz))
    }

    private fun nextClassOrdinal(name: String): Int {
        val ordinal = (classNames[name] ?: 0) + 1
        classNames[name] = ordinal
        return ordinal
    }

    private fun report(path: SchemaPath, message: String) {
        diagnostics += SchemaDiagnostic(path, message)
    }
}

private val scalarDefaultTypes = setOf(
    FieldType.Int32, FieldType.Int64, FieldType.Float64, FieldType.UInt32,
    FieldType.ByteT, FieldType.BooleanT, FieldType.StringT,
)

private fun FieldType.isNumeric(): Boolean = this in setOf(FieldType.Int32, FieldType.Int64, FieldType.Float64, FieldType.UInt32, FieldType.ByteT)

private fun defaultMatches(default: FieldDefaultValue, type: FieldType): Boolean = when (type) {
    FieldType.Int32, FieldType.UInt32, FieldType.ByteT -> default is FieldDefaultValue.IntValue || default == FieldDefaultValue.Null
    FieldType.Int64 -> default is FieldDefaultValue.LongValue || default == FieldDefaultValue.Null
    FieldType.Float64 -> default is FieldDefaultValue.DoubleValue || default == FieldDefaultValue.Null
    FieldType.BooleanT -> default is FieldDefaultValue.BooleanValue || default == FieldDefaultValue.Null
    FieldType.StringT -> default is FieldDefaultValue.StringValue || default == FieldDefaultValue.Null
    else -> false
}

private fun literalValue(value: SchemaIntValue?): Int? = (value as? SchemaIntValue.Literal)?.value

private fun validateNumericDomain(type: FieldType, value: Int?, label: String, report: (String) -> Unit) {
    value ?: return
    when (type) {
        FieldType.UInt32 -> if (value < 0) report("$label must be nonnegative for UInt32, got $value.")
        FieldType.ByteT -> if (value !in 0..255) report("$label must be in 0..255 for Byte, got $value.")
        else -> Unit
    }
}

private fun validateNumericDefault(value: Number, type: FieldType, minimum: Int?, maximum: Int?, report: (String) -> Unit) {
    val numeric = value.toDouble()
    when (type) {
        FieldType.UInt32 -> if (numeric < 0) report("default must be nonnegative for UInt32, got $value.")
        FieldType.ByteT -> if (numeric !in 0.0..255.0) report("default must be in 0..255 for Byte, got $value.")
        else -> Unit
    }
    if (minimum != null && numeric < minimum) report("default $value is below minimum $minimum.")
    if (maximum != null && numeric > maximum) report("default $value exceeds maximum $maximum.")
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

private fun identitySegment(value: String): String = "${value.length}:$value"
