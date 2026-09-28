package org.jetbrains.hexana.mcp.tools.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.NameAllocator
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.jetbrains.hexana.mcp.tools.CliExecutionStatus
import org.jetbrains.hexana.mcp.tools.CliTool
import org.jetbrains.hexana.mcp.tools.FieldDefaultValue
import org.jetbrains.hexana.mcp.tools.FieldPresence
import org.jetbrains.hexana.mcp.tools.FieldSpec
import org.jetbrains.hexana.mcp.tools.FieldType
import org.jetbrains.hexana.mcp.tools.ParamBindingKind
import org.jetbrains.hexana.mcp.tools.ParamBindingSpec
import org.jetbrains.hexana.mcp.tools.SchemaIntValue
import org.jetbrains.hexana.mcp.tools.SourceSelectionKind
import org.jetbrains.hexana.mcp.tools.SourceSlotSpec
import org.jetbrains.hexana.mcp.tools.SourceTargetSpec
import org.jetbrains.hexana.mcp.tools.UnknownFieldPolicy
import org.jetbrains.hexana.mcp.tools.input
import org.jetbrains.hexana.mcp.tools.name

private const val METADATA_PACKAGE = "org.jetbrains.hexana.cli.metadata"
private val JSON_OBJECT = ClassName("kotlinx.serialization.json", "JsonObject")
private val JSON_ELEMENT = ClassName("kotlinx.serialization.json", "JsonElement")
private val JSON_NULL = ClassName("kotlinx.serialization.json", "JsonNull")
private val JSON_PRIMITIVE = ClassName("kotlinx.serialization.json", "JsonPrimitive")
private val JSON_ARRAY = ClassName("kotlinx.serialization.json", "JsonArray")
private val SOURCE_SELECTION = ClassName("org.jetbrains.hexana.cli.runtime", "ToolInputSourceSelection")
private val CONVERSION_REQUEST = ClassName("org.jetbrains.hexana.cli.runtime", "ToolInputConversionRequest")
private val INPUT_CONVERTER = ClassName("org.jetbrains.hexana.cli.runtime", "ToolInputConverter")
private val DECODING = ClassName(METADATA_PACKAGE, "CliInputDecoding")

internal data class CliInputOption(
    val name: String,
    val valueType: String,
    val description: String,
    val defaultValue: String? = null,
    val constraints: String? = null,
    val required: Boolean = false,
)

internal data class CliSourceGroup(val name: String, val required: Boolean, val alternatives: List<String>)

internal fun CliTool.cliSourceGroups(): List<CliSourceGroup> = inputModel?.sources.orEmpty().map { source ->
    CliSourceGroup(source.name, source.required, source.cli.bindings().map { it.value })
}

internal fun CliTool.cliInputOptions(): List<CliInputOption> {
    if (binding.executionStatus != CliExecutionStatus.Executable) return emptyList()
    val model = requireNotNull(inputModel)
    val sources = model.sources.flatMap { source ->
        val sourceClass = when (val target = source.target) {
            SourceTargetSpec.RootInput -> input
            is SourceTargetSpec.Field -> (input.fields.single { it.name == target.name }.type as FieldType.ClassRef).clazz
            is SourceTargetSpec.FieldReference -> (input.fields.single { it.name == target.reference.name }.type as FieldType.ClassRef).clazz
        }
        val group = sourceClass.exactOneSourceGroups.single()
        fun description(kind: SourceSelectionKind): String =
            sourceClass.fields.single { field -> field.name == group.selections.single { it.kind == kind }.field.name }
                .describe.orEmpty()
        fun constraints(kind: SourceSelectionKind): String? =
            sourceClass.fields.single { field -> field.name == group.selections.single { it.kind == kind }.field.name }
                .constraintText()
        val cli = source.cli
        listOfNotNull(
            cli.positionalPathName?.let { CliInputOption(it, "path", description(SourceSelectionKind.Path), constraints = constraints(SourceSelectionKind.Path)) },
            cli.pathOption?.let { CliInputOption(it, "path", description(SourceSelectionKind.Path), constraints = constraints(SourceSelectionKind.Path)) },
            cli.stdinOption?.let { CliInputOption(it, "stdin", "Read ${source.name} bytes from standard input.") },
            cli.base64Option?.let { CliInputOption(it, "base64", description(SourceSelectionKind.Base64), constraints = constraints(SourceSelectionKind.Base64)) },
            cli.bytesOption?.let { CliInputOption(it, "list<integer>", description(SourceSelectionKind.Bytes), constraints = constraints(SourceSelectionKind.Bytes)) },
        ) + cli.base64Aliases.map { CliInputOption(it, "base64", description(SourceSelectionKind.Base64), constraints = constraints(SourceSelectionKind.Base64)) }
    }
    val params = model.params.map { param ->
        val field = input.fields.single { it.name == param.targetField }
        CliInputOption(param.cliOption ?: param.name, field.type.cliValueType(), field.describe.orEmpty(), field.presence.defaultText(), field.constraintText(), field.presence == FieldPresence.Required)
    }
    return sources + params
}

private fun FieldSpec.constraintText(): String? = buildList {
    minimum?.let { add("minimum $it") }
    maximum?.let { add("maximum $it") }
    maxLength?.let { add("maximum length ${it.cliLimitText()}") }
    maxItems?.let { add("maximum items ${it.cliLimitText()}") }
    itemMinimum?.let { add("item minimum $it") }
    itemMaximum?.let { add("item maximum $it") }
}.joinToString(", ").ifEmpty { null }

private fun SchemaIntValue.cliLimitText(): String = when (this) {
    is SchemaIntValue.Literal -> value.toString()
    SchemaIntValue.MaxInlineWasmBytes,
    SchemaIntValue.MaxInlineWasmBase64Length,
    SchemaIntValue.MaxInlineBinaryBytes,
    SchemaIntValue.MaxInlineBinaryBase64Length -> "derived from --max-inline-bytes"
}

private fun FieldType.cliValueType(): String = when (this) {
    FieldType.Int32, FieldType.Int64, FieldType.UInt32, FieldType.ByteT -> "integer"
    FieldType.Float64 -> "number"
    FieldType.BooleanT -> "boolean"
    FieldType.StringT -> "string"
    FieldType.ByteArrayT -> "bytes"
    FieldType.JsonObjectT, is FieldType.MapOf, is FieldType.ClassRef -> "json"
    is FieldType.ListOf -> "list<${element.cliValueType()}>"
}

private fun FieldPresence.defaultText(): String? = when (this) {
    FieldPresence.Required, FieldPresence.Optional -> null
    is FieldPresence.Defaulted -> when (val default = value) {
        FieldDefaultValue.Null -> "null"
        is FieldDefaultValue.IntValue -> default.value.toString()
        is FieldDefaultValue.LongValue -> default.value.toString()
        is FieldDefaultValue.DoubleValue -> default.value.toString()
        is FieldDefaultValue.BooleanValue -> default.value.toString()
        is FieldDefaultValue.StringValue -> default.value
    }
}

internal data class CliInputAdapter(val request: TypeSpec, val decoder: FunSpec, val converter: TypeSpec)

internal fun buildCliInputAdapter(tool: CliTool): CliInputAdapter? {
    val requestName = tool.binding.generatedInputRequest ?: return null
    val model = requireNotNull(tool.inputModel)
    val requestClass = ClassName(METADATA_PACKAGE, requestName)
    val fields = model.params.map { param -> param to tool.input.fields.single { it.name == param.targetField } }
    val duplicateNames = (model.sources.map { it.name } + model.params.map { it.name })
        .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    require(duplicateNames.isEmpty()) { "CLI generated request '$requestName' has colliding input names: $duplicateNames." }
    val names = NameAllocator()
    names.newName("request")
    names.newName("value")
    val sourceNames = model.sources.associate { it.name to names.newName(it.name) }
    val paramNames = fields.associate { (param, _) -> param.name to names.newName(param.name) }
    val sourceSetNames = model.sources.associate { it.name to names.newName("${it.name}Sources") }
    val unknownName = names.newName("unknown")
    val constructor = FunSpec.constructorBuilder().apply {
        model.sources.forEach { source ->
            addParameter(source.name, SOURCE_SELECTION.copy(nullable = !source.required))
        }
        fields.forEach { (param, field) -> addParameter(param.name, field.requestType()) }
    }.build()
    val request = TypeSpec.classBuilder(requestClass).addModifiers(KModifier.PUBLIC, KModifier.DATA)
        .primaryConstructor(constructor)
        .apply {
            constructor.parameters.forEach { parameter ->
                addProperty(com.squareup.kotlinpoet.PropertySpec.builder(parameter.name, parameter.type).initializer("%N", parameter).build())
            }
        }.build()

    val decoder = FunSpec.builder("decode$requestName").addModifiers(KModifier.PUBLIC)
        .addParameter("request", CONVERSION_REQUEST).returns(requestClass)
        .addStatement("require(request.context.toolName == %S)", tool.name)
        .apply {
            if (fields.isEmpty() && tool.binding.unknownFieldPolicy == UnknownFieldPolicy.Reject) {
                addStatement("require(request.rawArguments.isEmpty()) { %S }", "${tool.binding.commandName} does not accept semantic parameters.")
            } else if (tool.binding.unknownFieldPolicy == UnknownFieldPolicy.Reject) {
                addStatement("val %N = request.rawArguments.keys - setOf(${fields.joinToString { "\"${it.first.name}\"" }})", unknownName)
                addStatement("require(%N.isEmpty()) { %S + %N.joinToString() }", unknownName,
                    "${tool.binding.commandName} does not accept semantic parameters: ", unknownName)
            }
            val sourceLabel = model.sources.singleOrNull()?.name ?: "source"
            addStatement("require(request.sources.pathArguments.isEmpty()) { %S }", "${tool.binding.commandName} requires a selected $sourceLabel source.")
            addStatement("require(request.sources.stdinText == null && request.sources.stdinBytesCopy() == null) { %S }", "${tool.binding.commandName} requires a selected $sourceLabel source.")
            if (model.sources.size == 1 && model.sources.single().required) {
                val source = model.sources.single()
                addStatement("val %N = request.sources.sourceSelections.singleOrNull() ?: throw IllegalArgumentException(%S)",
                    sourceNames.getValue(source.name), "${tool.binding.commandName} requires exactly one ${source.name} source selection.")
                addStatement("require(%N.slotName == %S) { %S + %N.slotName + %S }", sourceNames.getValue(source.name), source.name,
                    "${tool.binding.commandName} source must use slot '${source.name}', got '", sourceNames.getValue(source.name), "'.")
            } else {
                addStatement("require(request.sources.sourceSelections.all { it.slotName in setOf(${model.sources.joinToString { "\"${it.name}\"" }}) }) { %S }",
                    "${tool.binding.commandName} has an unknown source slot.")
                model.sources.forEach { source ->
                    val sourceSetName = sourceSetNames.getValue(source.name)
                    val sourceName = sourceNames.getValue(source.name)
                    addStatement("val %N = request.sources.sourceSelections.filter { it.slotName == %S }", sourceSetName, source.name)
                    addStatement("require(%N.size <= 1) { %S }", sourceSetName,
                        "${tool.binding.commandName} requires exactly one ${source.name} source selection.")
                    if (source.required) {
                        addStatement("val %N = %N.singleOrNull() ?: throw IllegalArgumentException(%S)", sourceName, sourceSetName,
                            "${tool.binding.commandName} requires exactly one ${source.name} source selection.")
                    } else {
                        addStatement("val %N = %N.singleOrNull()", sourceName, sourceSetName)
                    }
                }
            }
            fields.forEach { (param, field) -> addCode(decodeParam(param, field, paramNames.getValue(param.name))) }
            val arguments = CodeBlock.builder().apply {
                (model.sources.map { sourceNames.getValue(it.name) } + fields.map { paramNames.getValue(it.first.name) })
                    .forEachIndexed { index, name ->
                        if (index > 0) add(", ")
                        add("%N", name)
                    }
            }.build()
            addStatement("return %T(%L)", requestClass, arguments)
        }.build()

    val converter = TypeSpec.objectBuilder("${requestName}Converter").addModifiers(KModifier.PUBLIC)
        .addSuperinterface(INPUT_CONVERTER)
        .addFunction(FunSpec.builder("convert").addModifiers(KModifier.OVERRIDE)
            .addParameter("request", CONVERSION_REQUEST).returns(JSON_OBJECT)
            .apply {
                if (fields.isEmpty()) {
                    addStatement("decode$requestName(request)")
                    addStatement("return %T(emptyMap())", JSON_OBJECT)
                }
                else {
                    addStatement("val decoded = decode$requestName(request)")
                    addStatement("val arguments = mutableMapOf<String, %T>()", JSON_ELEMENT)
                    fields.forEach { (param, field) ->
                        val encoded = field.encodeExpression(CodeBlock.of("decoded.%N", param.name))
                        if (field.requestType().isNullable) {
                            addStatement("decoded.%N?.let { arguments[%S] = %L }", param.name, field.schemaKey,
                                field.encodeExpression(CodeBlock.of("it")))
                            addStatement("if (decoded.%N == null && request.rawArguments.containsKey(%S)) arguments[%S] = %T",
                                param.name, param.name, field.schemaKey, JSON_NULL)
                        } else {
                            addStatement("arguments[%S] = %L", field.schemaKey, encoded)
                        }
                    }
                    addStatement("return %T(arguments)", JSON_OBJECT)
                }
            }.build())
        .build()
    return CliInputAdapter(request, decoder, converter)
}

private fun FieldSpec.requestType(): com.squareup.kotlinpoet.TypeName {
    val base = when (type) {
        FieldType.Int32 -> Int::class.asTypeName()
        FieldType.BooleanT -> Boolean::class.asTypeName()
        FieldType.StringT -> String::class.asTypeName()
        FieldType.JsonObjectT -> JSON_OBJECT
        is FieldType.ListOf -> when (type.element) {
            FieldType.Int32 -> ClassName("kotlin.collections", "List").parameterizedBy(Int::class.asTypeName())
            FieldType.StringT -> ClassName("kotlin.collections", "List").parameterizedBy(String::class.asTypeName())
            else -> error("Generated CLI request does not support list field `$name` of $type.")
        }
        else -> error("Generated CLI request does not support field `$name` of $type.")
    }
    return base.copy(nullable = nullable || presence == FieldPresence.Optional ||
        (presence is FieldPresence.Defaulted && presence.value == FieldDefaultValue.Null))
}

private fun decodeParam(param: ParamBindingSpec, field: FieldSpec, localName: String): CodeBlock {
    require(param.kind != ParamBindingKind.Json || field.type == FieldType.JsonObjectT)
    val default = when (val presence = field.presence) {
        FieldPresence.Required -> CodeBlock.of("throw IllegalArgumentException(%S)", "${param.name} is required.")
        FieldPresence.Optional -> CodeBlock.of("null")
        is FieldPresence.Defaulted -> when (val value = presence.value) {
            FieldDefaultValue.Null -> CodeBlock.of("null")
            is FieldDefaultValue.IntValue -> CodeBlock.of("%L", value.value)
            is FieldDefaultValue.BooleanValue -> CodeBlock.of("%L", value.value)
            is FieldDefaultValue.StringValue -> CodeBlock.of("%S", value.value)
            else -> error("Generated CLI request does not support default for `${field.name}`: $value.")
        }
    }
    val decoder = when (val type = field.type) {
        FieldType.Int32 -> CodeBlock.of("%T.int(value, %S, %L, %L)", DECODING, param.name, field.minimum, field.maximum)
        FieldType.BooleanT -> CodeBlock.of("%T.boolean(value, %S)", DECODING, param.name)
        FieldType.StringT -> {
            val maxLength = when (val limit = field.maxLength) {
                null -> null
                is SchemaIntValue.Literal -> limit.value
                else -> error("Generated CLI request needs a runtime length binding for `${field.name}`: $limit.")
            }
            CodeBlock.of("%T.string(value, %S, %L)", DECODING, param.name, maxLength)
        }
        FieldType.JsonObjectT -> CodeBlock.of("%T.jsonObject(value, %S)", DECODING, param.name)
        is FieldType.ListOf -> {
            val maxItems = when (val limit = field.maxItems) {
                null -> null
                is SchemaIntValue.Literal -> limit.value
                else -> error("Generated CLI request needs a runtime limit binding for `${field.name}`: $limit.")
            }
            when (type.element) {
                FieldType.Int32 -> CodeBlock.of("%T.intList(value, %S, %L, %L, %L)", DECODING, param.name,
                    maxItems, field.itemMinimum, field.itemMaximum)
                FieldType.StringT -> CodeBlock.of("%T.stringList(value, %S, %L)", DECODING, param.name, maxItems)
                else -> error("Generated CLI request does not support list field `${field.name}` of $type.")
            }
        }
        else -> error("Generated CLI request does not support field `${field.name}` of $type.")
    }
    return CodeBlock.builder()
        .addStatement("val %N = when (val value = request.rawArguments[%S]) {", localName, param.name)
        .indent()
        .add("null -> %L\n", default)
        .add("%T -> %L\n", JSON_NULL,
            if (field.nullable) CodeBlock.of("null") else CodeBlock.of("throw IllegalArgumentException(%S)", "${param.name} must not be null."))
        .add("else -> %L\n", decoder)
        .unindent().add("}\n")
        .build()
}

private fun FieldSpec.encodeExpression(value: CodeBlock): CodeBlock = when (type) {
    FieldType.Int32, FieldType.BooleanT, FieldType.StringT -> CodeBlock.of("%T(%L)", JSON_PRIMITIVE, value)
    FieldType.JsonObjectT -> value
    is FieldType.ListOf -> CodeBlock.of("%T(%L.map { %T(it) })", JSON_ARRAY, value, JSON_PRIMITIVE)
    else -> error("Generated CLI request does not support field `$name` of $type.")
}
