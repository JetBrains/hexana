package org.jetbrains.hexana.mcp.tools.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeAliasSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.jetbrains.hexana.mcp.tools.ClassSpec
import org.jetbrains.hexana.mcp.tools.CliExecutionStatus
import org.jetbrains.hexana.mcp.tools.CliProductRegistry
import org.jetbrains.hexana.mcp.tools.CliProductSpec
import org.jetbrains.hexana.mcp.tools.CliTool
import org.jetbrains.hexana.mcp.tools.FieldSpec
import org.jetbrains.hexana.mcp.tools.FieldType
import org.jetbrains.hexana.mcp.tools.HexanaToolSchemaCompatibility
import org.jetbrains.hexana.mcp.tools.HexanaCliProductRegistry
import org.jetbrains.hexana.mcp.tools.HexanaCliTools
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneAuthority
import org.jetbrains.hexana.mcp.tools.ProfileId
import org.jetbrains.hexana.mcp.tools.InputConversionCapability
import org.jetbrains.hexana.mcp.tools.OutputConversionCapability
import org.jetbrains.hexana.mcp.tools.SchemaIntValue
import org.jetbrains.hexana.mcp.tools.SourceKind
import org.jetbrains.hexana.mcp.tools.SourceSlotSpec
import org.jetbrains.hexana.mcp.tools.StandaloneTool
import org.jetbrains.hexana.mcp.tools.StandaloneToolAuthority
import org.jetbrains.hexana.mcp.tools.ToolId
import org.jetbrains.hexana.mcp.tools.ToolGroup
import org.jetbrains.hexana.mcp.tools.ToolOutputPolicy
import org.jetbrains.hexana.mcp.tools.input
import org.jetbrains.hexana.mcp.tools.mcpDescription
import org.jetbrains.hexana.mcp.tools.name
import org.jetbrains.hexana.mcp.tools.output
import org.jetbrains.hexana.mcp.tools.summary
import org.jetbrains.hexana.mcp.tools.validateCliProducts
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val SERVER_PACKAGE = "org.jetbrains.hexana.mcp.server"
private const val CLI_METADATA_PACKAGE = "org.jetbrains.hexana.cli.metadata"
private val SERVER_CLASS = ClassName("io.modelcontextprotocol.kotlin.sdk.server", "Server")
private val TOOL_CLASS = ClassName("io.modelcontextprotocol.kotlin.sdk.types", "Tool")
private val TOOL_ANNOTATIONS = ClassName("io.modelcontextprotocol.kotlin.sdk.types", "ToolAnnotations")
private val CALL_TOOL_REQUEST = ClassName("io.modelcontextprotocol.kotlin.sdk.types", "CallToolRequest")
private val TOOL_HANDLERS = ClassName(SERVER_PACKAGE, "HexanaToolHandlers")
private val TOOL_PROFILE = ClassName(SERVER_PACKAGE, "HexanaMcpToolProfile")
private val CLI_TOOL_SPEC = ClassName(CLI_METADATA_PACKAGE, "HexanaCliToolSpec")
private val CLI_INPUT_MODE = ClassName(CLI_METADATA_PACKAGE, "HexanaCliInputMode")
private val CLI_OUTPUT_FORMAT = ClassName(CLI_METADATA_PACKAGE, "HexanaCliOutputFormat")
private val TOOL_OUTPUT_FORMAT = ClassName("org.jetbrains.hexana.cli.runtime", "ToolOutputFormat")
private val CLI_TOOL_GROUP = ClassName(CLI_METADATA_PACKAGE, "HexanaCliToolGroup")
private val CLI_OUTPUT_POLICY = ClassName(CLI_METADATA_PACKAGE, "HexanaCliOutputPolicy")
private val CLI_EXECUTION_STATUS = ClassName(CLI_METADATA_PACKAGE, "HexanaCliExecutionStatus")
private val CLI_PRODUCT_DESCRIPTOR = ClassName(CLI_METADATA_PACKAGE, "HexanaCliProductDescriptor")
private val CLI_PRODUCT_TOOL_SELECTION = ClassName(CLI_METADATA_PACKAGE, "HexanaCliProductToolSelection")
private val KOTLIN_LIST = ClassName("kotlin.collections", "List")
private val KOTLIN_MAP = ClassName("kotlin.collections", "Map")
private val KOTLIN_SET = ClassName("kotlin.collections", "Set")
private val HEXANA_ANALYSIS_LIMITS = ClassName("org.jetbrains.hexana.mcp", "HexanaAnalysisLimits")
private val TOOL_SCHEMA = ClassName("io.modelcontextprotocol.kotlin.sdk.types", "ToolSchema")
private val JSON_PRIMITIVE = ClassName("kotlinx.serialization.json", "JsonPrimitive")
private val BUILD_JSON_OBJECT = MemberName("kotlinx.serialization.json", "buildJsonObject")
private val BUILD_JSON_ARRAY = MemberName("kotlinx.serialization.json", "buildJsonArray")

fun main(args: Array<String>) {
    val serverOutputRoot = args.firstOrNull()?.let(Path::of) ?: run {
        System.err.println("Usage: standalone-toolset-generator <server-output-root> [cli-metadata-output-root]")
        exitProcess(1)
    }
    val metadataOutputRoot = args.getOrNull(1)?.let(Path::of) ?: serverOutputRoot

    try {
        Files.createDirectories(serverOutputRoot)
        Files.createDirectories(metadataOutputRoot)
    } catch (error: Exception) {
        System.err.println("Failed to create output directories: ${error.message}")
        exitProcess(1)
    }
    val serverFiles = buildStandaloneServerFiles(HexanaStandaloneAuthority)
    val metadataFiles = buildStandaloneCliMetadataFiles(HexanaCliTools, HexanaCliProductRegistry)
    serverFiles.forEach { it.writeTo(serverOutputRoot) }
    metadataFiles.forEach { it.writeTo(metadataOutputRoot) }
    val names = (serverFiles + metadataFiles).joinToString { "${it.packageName}.${it.name}" }
    println("Wrote $names to $serverOutputRoot and $metadataOutputRoot")
}

internal fun buildStandaloneServerFiles(
    standaloneAuthority: StandaloneToolAuthority,
): List<FileSpec> =
    listOf(buildDispatcherFile(standaloneAuthority))

internal fun buildStandaloneCliMetadataFiles(
    tools: List<CliTool>,
    products: CliProductRegistry = HexanaCliProductRegistry,
): List<FileSpec> =
    listOf(
        buildCliMetadataToolsetFile(tools),
        buildCliProductSelectionFile(tools, products),
    )

internal fun buildDispatcherFile(authority: StandaloneToolAuthority): FileSpec {
    val mcpTools = authority.registrations
    val registerFun = FunSpec.builder("registerHexanaTools")
        .receiver(SERVER_CLASS)
        .addParameter(ParameterSpec.builder("handlers", TOOL_HANDLERS).build())
        .addParameter(
            ParameterSpec.builder("profile", TOOL_PROFILE)
                .defaultValue("%T.DEFAULT", TOOL_PROFILE)
                .build()
        )
        .addKdoc(
            "Registers all Hexana MCP tools on the receiver Server.\n\n" +
                "Generated by `:mcp:tools-generator`. Do not edit by hand.",
        )
        .apply {
            mcpTools.forEach { spec ->
                addStatement("if (profile.includesTool(%S)) %N(handlers)", spec.name, spec.registerFunctionName())
            }
        }
        .build()

    val fileBuilder = FileSpec.builder(SERVER_PACKAGE, "HexanaToolDispatcher")
        .addFileComment("Generated by :mcp:tools-generator. Do not edit by hand.")
        .addType(buildCompatibilityObject())
        .addType(
            buildToolProfilesObject(authority)
        )
    buildInputDefaultsObject(mcpTools)?.let { fileBuilder.addType(it) }
    return fileBuilder
        .addFunction(registerFun)
        .apply { mcpTools.forEach { addFunction(buildRegisterToolFunction(it)) } }
        .build()
}

internal fun buildCliMetadataToolsetFile(tools: List<CliTool>): FileSpec {
    return FileSpec.builder(CLI_METADATA_PACKAGE, "HexanaCliToolset")
        .addFileComment("Generated by :mcp:tools-generator. Do not edit by hand.")
        .addType(buildCliInputModeEnum())
        .addTypeAlias(buildCliOutputFormatAlias())
        .addType(buildCliToolGroupEnum())
        .addType(buildCliOutputPolicyEnum())
        .addType(buildCliExecutionStatusEnum())
        .addType(buildCliToolSpecClass())
        .addType(buildCliToolRegistryObject(tools))
        .build()
}

internal fun buildCliProductSelectionFile(tools: List<CliTool>, products: CliProductRegistry): FileSpec {
    validateCliProducts(tools, products, requireCompleteSelection = false)
    return FileSpec.builder(CLI_METADATA_PACKAGE, "HexanaCliProducts")
        .addFileComment("Generated by :mcp:tools-generator. Do not edit by hand.")
        .addType(buildCliProductDescriptorClass())
        .addType(buildCliProductToolSelectionClass())
        .addType(buildCliProductsObject(tools, products))
        .build()
}

private fun buildCliProductDescriptorClass(): TypeSpec {
    val constructor = FunSpec.constructorBuilder()
        .addParameter("productId", String::class)
        .addParameter("executableName", String::class)
        .addParameter("description", String::class)
        .build()
    return TypeSpec.classBuilder(CLI_PRODUCT_DESCRIPTOR)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Generated descriptor for one declared Hexana command-line product.\n")
        .primaryConstructor(constructor)
        .addProperty(PropertySpec.builder("productId", String::class).initializer("productId").build())
        .addProperty(PropertySpec.builder("executableName", String::class).initializer("executableName").build())
        .addProperty(PropertySpec.builder("description", String::class).initializer("description").build())
        .build()
}

private fun buildCliProductToolSelectionClass(): TypeSpec {
    val fields = listOf(
        "toolName",
        "commandName",
        "outputPolicy",
        "defaultOutput",
    )
    val constructor = FunSpec.constructorBuilder().apply {
        fields.forEach { addParameter(it, String::class) }
    }.build()
    return TypeSpec.classBuilder(CLI_PRODUCT_TOOL_SELECTION)
        .addModifiers(KModifier.PUBLIC, KModifier.DATA)
        .addKdoc("Generated product inventory metadata for one product-selected tool.\n")
        .primaryConstructor(constructor)
        .apply {
            fields.forEach { field ->
                addProperty(PropertySpec.builder(field, String::class).initializer(field).build())
            }
        }
        .build()
}

private fun buildCliProductsObject(tools: List<CliTool>, products: CliProductRegistry): TypeSpec {
    val selectionList = KOTLIN_LIST.parameterizedBy(CLI_PRODUCT_TOOL_SELECTION)
    val productMap = KOTLIN_MAP.parameterizedBy(String::class.asTypeName(), CLI_PRODUCT_DESCRIPTOR)
    val selectionsMap = KOTLIN_MAP.parameterizedBy(String::class.asTypeName(), selectionList)
    val selectionInitializer = CodeBlock.builder().add("mapOf(\n").indent()
    products.products.forEach { product ->
        selectionInitializer.add("%S to listOf(\n", product.id.value).indent()
        tools.filter { product.id in it.binding.products }.forEach { spec ->
            val cli = spec.binding
            selectionInitializer.add(
                "%T(%S, %S, %S, %S),\n",
                CLI_PRODUCT_TOOL_SELECTION,
                spec.name,
                cli.commandName,
                cli.outputPolicy.name,
                cli.defaultOutput.name,
            )
        }
        selectionInitializer.unindent().add("),\n")
    }
    selectionInitializer.unindent().add(")")
    val descriptorInitializer = CodeBlock.builder().add("mapOf(\n").indent().apply {
        products.products.forEach { product -> add("%S to %L,\n", product.id.value, product.generatedName()) }
    }.unindent().add(")").build()
    return TypeSpec.objectBuilder("HexanaGeneratedCliProducts")
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Generated product descriptors and selections derived from catalog CLI metadata.\n")
        .apply {
            products.products.forEach { product ->
                addProperty(
                    PropertySpec.builder(product.generatedName(), CLI_PRODUCT_DESCRIPTOR)
                        .initializer("%T(%S, %S, %S)", CLI_PRODUCT_DESCRIPTOR, product.id.value, product.executableName, product.description)
                        .build(),
                )
            }
        }
        .addProperty(PropertySpec.builder("PRODUCTS", productMap).initializer(descriptorInitializer).build())
        .addProperty(PropertySpec.builder("SELECTIONS", selectionsMap).initializer(selectionInitializer.build()).build())
        .build()
}

private fun buildCompatibilityObject(): TypeSpec {
    return TypeSpec.objectBuilder("HexanaMcpToolCompatibility")
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Generated MCP tool schema compatibility marker.\n")
        .addProperty(
            PropertySpec.builder("SCHEMA_VERSION", Int::class, KModifier.CONST)
                .initializer("%L", HexanaToolSchemaCompatibility.schemaVersion)
                .build()
        )
        .addProperty(
            PropertySpec.builder("POLICY", String::class, KModifier.CONST)
                .initializer("%S", HexanaToolSchemaCompatibility.policy)
                .build()
        )
        .build()
}

private fun buildToolProfilesObject(
    authority: StandaloneToolAuthority,
): TypeSpec {
    val default = authority.profile(ProfileId("default")).toolIds
    val advanced = authority.profile(ProfileId("advanced")).toolIds
    val scripting = authority.profile(ProfileId("scripting")).toolIds
    val setOfString = Set::class.parameterizedBy(String::class)
    return TypeSpec.objectBuilder("HexanaGeneratedMcpToolProfiles")
        .addModifiers(KModifier.INTERNAL)
        .addKdoc("Generated MCP profile membership derived from catalog surface metadata.\n")
        .addProperty(
            PropertySpec.builder("DEFAULT_TOOL_NAMES", setOfString, KModifier.INTERNAL)
                .initializer(toolNameSetInitializer(default.map(ToolId::value)))
                .build()
        )
        .addProperty(
            PropertySpec.builder("ADVANCED_TOOL_NAMES", setOfString, KModifier.INTERNAL)
                .initializer(toolNameSetInitializer(advanced.map(ToolId::value)))
                .build()
        )
        .addProperty(
            PropertySpec.builder("SCRIPTING_TOOL_NAMES", setOfString, KModifier.INTERNAL)
                .initializer(toolNameSetInitializer(scripting.map(ToolId::value)))
                .build()
        )
        .build()
}

private fun buildCliInputModeEnum(): TypeSpec =
    TypeSpec.enumBuilder(CLI_INPUT_MODE)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Generated CLI input modes declared by catalog input conversion metadata.\n")
        .addEnumConstant("JSON_OBJECT")
        .addEnumConstant("WASM_PATH")
        .addEnumConstant("WASM_FROM_STRING")
        .addEnumConstant("BINARY_PATH")
        .addEnumConstant("STDIN")
        .build()

private fun buildCliOutputFormatAlias(): TypeAliasSpec =
    TypeAliasSpec.builder(CLI_OUTPUT_FORMAT.simpleName, TOOL_OUTPUT_FORMAT)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Compatibility alias for neutral CLI output formats declared by catalog metadata.\n")
        .build()

private fun buildCliToolGroupEnum(): TypeSpec =
    TypeSpec.enumBuilder(CLI_TOOL_GROUP)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Generated CLI tool groups declared by catalog surface metadata.\n")
        .apply {
            ToolGroup.entries.forEach { group ->
                addEnumConstant(group.generatedCliGroupName())
            }
        }
        .build()

private fun buildCliOutputPolicyEnum(): TypeSpec =
    TypeSpec.enumBuilder(CLI_OUTPUT_POLICY)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Generated CLI default output policies declared by catalog surface metadata.\n")
        .addEnumConstant("TOOL_DEFINED")
        .build()

private fun buildCliExecutionStatusEnum(): TypeSpec =
    TypeSpec.enumBuilder(CLI_EXECUTION_STATUS)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Generated CLI execution status declared by catalog surface metadata.\n")
        .addEnumConstant("EXECUTABLE")
        .addEnumConstant("TODO")
        .build()

private fun buildCliToolSpecClass(): TypeSpec {
    val setOfInputMode = KOTLIN_SET.parameterizedBy(CLI_INPUT_MODE)
    val setOfOutputFormat = KOTLIN_SET.parameterizedBy(CLI_OUTPUT_FORMAT)
    val setOfToolGroup = KOTLIN_SET.parameterizedBy(CLI_TOOL_GROUP)
    val constructor = FunSpec.constructorBuilder()
        .addParameter(ParameterSpec.builder("commandName", String::class).build())
        .addParameter(ParameterSpec.builder("toolName", String::class).build())
        .addParameter(ParameterSpec.builder("summary", String::class).build())
        .addParameter(ParameterSpec.builder("groups", setOfToolGroup).build())
        .addParameter(ParameterSpec.builder("inputModes", setOfInputMode).build())
        .addParameter(ParameterSpec.builder("outputFormats", setOfOutputFormat).build())
        .addParameter(ParameterSpec.builder("defaultOutputFormat", CLI_OUTPUT_FORMAT).build())
        .addParameter(ParameterSpec.builder("defaultOutputPolicy", CLI_OUTPUT_POLICY).build())
        .addParameter(ParameterSpec.builder("executionStatus", CLI_EXECUTION_STATUS).build())
        .build()

    fun property(name: String, type: com.squareup.kotlinpoet.TypeName): PropertySpec =
        PropertySpec.builder(name, type)
            .initializer(name)
            .build()

    return TypeSpec.classBuilder(CLI_TOOL_SPEC)
        .addModifiers(KModifier.PUBLIC, KModifier.DATA)
        .addKdoc("Generated CLI-visible tool metadata derived from the catalog.\n")
        .primaryConstructor(constructor)
        .addProperty(property("commandName", String::class.asTypeName()))
        .addProperty(property("toolName", String::class.asTypeName()))
        .addProperty(property("summary", String::class.asTypeName()))
        .addProperty(property("groups", setOfToolGroup))
        .addProperty(property("inputModes", setOfInputMode))
        .addProperty(property("outputFormats", setOfOutputFormat))
        .addProperty(property("defaultOutputFormat", CLI_OUTPUT_FORMAT))
        .addProperty(property("defaultOutputPolicy", CLI_OUTPUT_POLICY))
        .addProperty(property("executionStatus", CLI_EXECUTION_STATUS))
        .build()
}

private fun buildCliToolRegistryObject(tools: List<CliTool>): TypeSpec {
    val listOfCliToolSpec = KOTLIN_LIST.parameterizedBy(CLI_TOOL_SPEC)
    return TypeSpec.objectBuilder("HexanaGeneratedCliToolRegistry")
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Generated registry for catalog-declared CLI-visible tools.\n")
        .addProperty(
            PropertySpec.builder("TOOLS", listOfCliToolSpec, KModifier.PUBLIC)
            .initializer(cliToolSpecsInitializer(tools))
                .build()
        )
        .build()
}

private fun cliToolSpecsInitializer(tools: List<CliTool>): CodeBlock {
    val builder = CodeBlock.builder()
    builder.add("listOf(\n")
    builder.indent()
    tools.forEach { spec ->
        builder.add(cliToolSpecInitializer(spec))
        builder.add(",\n")
    }
    builder.unindent()
    builder.add(")")
    return builder.build()
}

private fun cliToolSpecInitializer(spec: CliTool): CodeBlock {
    val cli = spec.binding
    val commandName = cli.commandName
    val outputPolicy = cli.outputPolicy
    val executionStatus = cli.executionStatus
    val outputFormats = spec.cliOutputFormats()
    require(outputFormats.isNotEmpty()) {
        "Visible CLI tool '${spec.name}' command '$commandName' has no supported CLI output format."
    }
    val defaultOutput = cli.defaultOutput
    require(defaultOutput in outputFormats) {
        "Visible CLI tool '${spec.name}' command '$commandName' default output $defaultOutput " +
            "is not in supported CLI output formats $outputFormats."
    }

    return CodeBlock.builder()
        .add("%T(\n", CLI_TOOL_SPEC)
        .add("  commandName = %S,\n", commandName)
        .add("  toolName = %S,\n", spec.name)
        .add("  summary = %S,\n", spec.summary)
        .add("  groups = %L,\n", enumSetInitializer(CLI_TOOL_GROUP, cli.groups.map { it.generatedCliGroupName() }))
        .add("  inputModes = %L,\n", enumSetInitializer(CLI_INPUT_MODE, spec.generatedCliInputModeNames()))
        .add("  outputFormats = %L,\n", enumSetInitializer(CLI_OUTPUT_FORMAT, outputFormats.map { it.generatedCliOutputFormatName() }))
        .add("  defaultOutputFormat = %T.%L,\n", CLI_OUTPUT_FORMAT, defaultOutput.generatedCliOutputFormatName())
        .add("  defaultOutputPolicy = %T.%L,\n", CLI_OUTPUT_POLICY, outputPolicy.generatedCliOutputPolicyName())
        .add("  executionStatus = %T.%L,\n", CLI_EXECUTION_STATUS, executionStatus.generatedCliExecutionStatusName())
        .add(")")
        .build()
}

private fun enumSetInitializer(enumType: ClassName, entries: List<String>): CodeBlock {
    val uniqueEntries = entries.distinct()
    val builder = CodeBlock.builder()
    builder.add("setOf(")
    uniqueEntries.forEachIndexed { index, entry ->
        if (index > 0) builder.add(", ")
        builder.add("%T.%L", enumType, entry)
    }
    builder.add(")")
    return builder.build()
}

private fun toolNameSetInitializer(names: List<String>): CodeBlock {
    if (names.isEmpty()) {
        return CodeBlock.of("emptySet()")
    }
    val builder = CodeBlock.builder()
    builder.add("setOf(\n")
    builder.indent()
    names.forEach { name ->
        builder.add("%S,\n", name)
    }
    builder.unindent()
    builder.add(")")
    return builder.build()
}

private fun buildRegisterToolFunction(spec: StandaloneTool): FunSpec {
    return FunSpec.builder(spec.registerFunctionName())
        .receiver(SERVER_CLASS)
        .addModifiers(KModifier.PRIVATE)
        .addParameter(ParameterSpec.builder("handlers", TOOL_HANDLERS).build())
        .addCode(renderAddToolCall(spec))
        .build()
}

private fun buildInputDefaultsObject(tools: List<StandaloneTool>): TypeSpec? {
    val properties = tools.flatMap { spec ->
        spec.input.fields.mapNotNull { field ->
            val default = field.default ?: return@mapNotNull null
            buildDefaultProperty(
                name = "${spec.name.toConstantSegment()}_${field.name.toConstantSegment()}",
                default = default,
            )
        }
    }
    if (properties.isEmpty()) return null

    return TypeSpec.objectBuilder("HexanaToolInputDefaults")
        .addModifiers(KModifier.INTERNAL)
        .addKdoc("Generated input defaults shared by standalone server handlers.\n")
        .apply { properties.forEach(::addProperty) }
        .build()
}

private fun buildDefaultProperty(name: String, default: Any): PropertySpec {
    val builder = when (default) {
        is Int -> PropertySpec.builder(name, Int::class, KModifier.CONST)
            .initializer("%L", default)
        is Long -> PropertySpec.builder(name, Long::class, KModifier.CONST)
            .initializer("%L", default)
        is Double -> PropertySpec.builder(name, Double::class, KModifier.CONST)
            .initializer("%L", default)
        is Boolean -> PropertySpec.builder(name, Boolean::class, KModifier.CONST)
            .initializer("%L", default)
        is String -> PropertySpec.builder(name, String::class, KModifier.CONST)
            .initializer("%S", default)
        else -> error("Unsupported generated default constant value `$default` (${default::class.simpleName}).")
    }
    return builder.build()
}

private fun renderAddToolCall(spec: StandaloneTool): CodeBlock {
    val handlerMethod = spec.standaloneHandler.methodName
    val inputSchema = buildToolSchema(spec.input)
    val outputSchema = buildToolSchema(spec.output, includeDescriptions = false, includeNestedClasses = false)

    return CodeBlock.builder()
        .add(
            "addTool(tool = %T(\n" +
                "  name = %S,\n" +
                "  description = %S,\n" +
                "  inputSchema = %L,\n" +
                "  outputSchema = %L,\n" +
                "  annotations = %T(\n" +
                "    readOnlyHint = true,\n" +
                "    destructiveHint = false,\n" +
                "    idempotentHint = true,\n" +
                "  ),\n" +
                "))",
            TOOL_CLASS, spec.name, spec.mcpDescription, inputSchema, outputSchema, TOOL_ANNOTATIONS,
        )
        .beginControlFlow(" ")
        .addStatement("request: %T -> handlers.%N(request.arguments)", CALL_TOOL_REQUEST, handlerMethod)
        .endControlFlow()
        .build()
}

/**
 * Emits a full `ToolSchema(...)` expression for the tool's input or output type.
 * Nested classes are inlined into the schema — the generated file is verbose
 * but self-contained.
 */
private fun buildToolSchema(
    cls: ClassSpec,
    includeDescriptions: Boolean = true,
    includeNestedClasses: Boolean = true,
): CodeBlock {
    val builder = CodeBlock.builder()
    builder.add("%T(\n", TOOL_SCHEMA)
    builder.add("  properties = %M {\n", BUILD_JSON_OBJECT)
    cls.fields.forEach { field ->
        builder.add("    put(%S, ", field.schemaKey)
        builder.add(
            fieldSchemaObject(
                field,
                includeDescriptions = includeDescriptions,
                includeNestedClasses = includeNestedClasses,
            )
        )
        builder.add(")\n")
    }
    builder.add("  },\n")
    builder.add("  required = listOf(")
    val required = cls.fields.filter { !it.nullable && it.default == null }.map { it.schemaKey }
    required.forEachIndexed { index, key ->
        if (index > 0) builder.add(", ")
        builder.add("%S", key)
    }
    builder.add(")\n)")
    return builder.build()
}

private fun fieldSchemaObject(field: FieldSpec): CodeBlock {
    return fieldSchemaObject(field, includeDescriptions = true)
}

private fun fieldSchemaObject(
    field: FieldSpec,
    schemaIndent: String = "      ",
    closingIndent: String = "    ",
    includeDescriptions: Boolean,
    includeNestedClasses: Boolean = true,
): CodeBlock {
    val b = CodeBlock.builder()
    b.add("%M {\n", BUILD_JSON_OBJECT)
    emitTypeSchema(
        field,
        b,
        indent = schemaIndent,
        includeDescriptions = includeDescriptions,
        includeNestedClasses = includeNestedClasses,
    )
    if (includeDescriptions) field.describe?.let {
        b.add("${schemaIndent}put(%S, %T(%S))\n", "description", JSON_PRIMITIVE, it)
    }
    field.default?.let {
        b.add("${schemaIndent}put(%S, %T(%L))\n", "default", JSON_PRIMITIVE, schemaDefaultValue(field))
    }
    emitFieldConstraints(field, b, indent = schemaIndent)
    b.add("$closingIndent}")
    return b.build()
}

/** Emits the JSON Schema properties (`type`, `items`, nested `properties`/`required`) for a field. */
private fun emitTypeSchema(
    field: FieldSpec,
    builder: CodeBlock.Builder,
    indent: String,
    includeDescriptions: Boolean,
    includeNestedClasses: Boolean,
) {
    when (val type = field.type) {
        is FieldType.ListOf -> {
            emitJsonType(builder, indent, "array", nullable = field.nullable)
            builder.add("${indent}put(%S, %M {\n", "items", BUILD_JSON_OBJECT)
            emitTypeSchema(
                type.element,
                builder,
                indent = "$indent  ",
                includeDescriptions = includeDescriptions,
                includeNestedClasses = includeNestedClasses,
            )
            field.itemMinimum?.let {
                builder.add("$indent  put(%S, %T(%L))\n", "minimum", JSON_PRIMITIVE, it)
            }
            field.itemMaximum?.let {
                builder.add("$indent  put(%S, %T(%L))\n", "maximum", JSON_PRIMITIVE, it)
            }
            builder.add("${indent}})\n")
        }
        is FieldType.MapOf -> {
            emitJsonType(builder, indent, "object", nullable = field.nullable)
            builder.add("${indent}put(%S, %M {\n", "additionalProperties", BUILD_JSON_OBJECT)
            emitTypeSchema(
                type.value,
                builder,
                indent = "$indent  ",
                includeDescriptions = includeDescriptions,
                includeNestedClasses = includeNestedClasses,
            )
            builder.add("${indent}})\n")
        }

        else -> emitTypeSchema(
            type,
            builder,
            indent,
            nullable = field.nullable,
            includeDescriptions = includeDescriptions,
            includeNestedClasses = includeNestedClasses,
        )
    }
}

private fun emitTypeSchema(
    type: FieldType,
    builder: CodeBlock.Builder,
    indent: String,
    nullable: Boolean = false,
    includeDescriptions: Boolean,
    includeNestedClasses: Boolean,
) {
    when (type) {
        FieldType.Int32,
        FieldType.Int64,
        FieldType.UInt32,
        FieldType.ByteT -> emitJsonType(builder, indent, "integer", nullable)

        FieldType.Float64 -> emitJsonType(builder, indent, "number", nullable)

        FieldType.BooleanT ->
            emitJsonType(builder, indent, "boolean", nullable)

        FieldType.StringT,
        FieldType.ByteArrayT ->
            emitJsonType(builder, indent, "string", nullable)

        FieldType.JsonObjectT -> emitJsonType(builder, indent, "object", nullable)

        is FieldType.ListOf -> {
            emitJsonType(builder, indent, "array", nullable)
            builder.add("${indent}put(%S, %M {\n", "items", BUILD_JSON_OBJECT)
            emitTypeSchema(
                type.element,
                builder,
                indent = "$indent  ",
                includeDescriptions = includeDescriptions,
                includeNestedClasses = includeNestedClasses,
            )
            builder.add("${indent}})\n")
        }
        is FieldType.MapOf -> {
            emitJsonType(builder, indent, "object", nullable)
            builder.add("${indent}put(%S, %M {\n", "additionalProperties", BUILD_JSON_OBJECT)
            emitTypeSchema(
                type.value,
                builder,
                indent = "$indent  ",
                includeDescriptions = includeDescriptions,
                includeNestedClasses = includeNestedClasses,
            )
            builder.add("${indent}})\n")
        }

        is FieldType.ClassRef -> {
            emitJsonType(builder, indent, "object", nullable)
            if (!includeNestedClasses) return
            builder.add("${indent}put(%S, %M {\n", "properties", BUILD_JSON_OBJECT)
            type.clazz.fields.forEach { nested ->
                builder.add("$indent  put(%S, ", nested.schemaKey)
                builder.add(
                    fieldSchemaObject(
                        nested,
                        schemaIndent = "$indent    ",
                        closingIndent = "$indent  ",
                        includeDescriptions = includeDescriptions,
                        includeNestedClasses = includeNestedClasses,
                    )
                )
                builder.add(")\n")
            }
            builder.add("${indent}})\n")
            val requiredFields = type.clazz.fields.filter { !it.nullable && it.default == null }
            if (requiredFields.isNotEmpty()) {
                builder.add("${indent}put(%S, %M {\n", "required", BUILD_JSON_ARRAY)
                requiredFields.forEach { req ->
                    builder.add("$indent  add(%T(%S))\n", JSON_PRIMITIVE, req.schemaKey)
                }
                builder.add("${indent}})\n")
            }
        }
    }
}

private fun emitJsonType(
    builder: CodeBlock.Builder,
    indent: String,
    jsonType: String,
    nullable: Boolean,
) {
    if (!nullable) {
        builder.add("${indent}put(%S, %T(%S))\n", "type", JSON_PRIMITIVE, jsonType)
        return
    }

    builder.add("${indent}put(%S, %M {\n", "type", BUILD_JSON_ARRAY)
    builder.add("$indent  add(%T(%S))\n", JSON_PRIMITIVE, jsonType)
    builder.add("$indent  add(%T(%S))\n", JSON_PRIMITIVE, "null")
    builder.add("${indent}})\n")
}

private fun emitFieldConstraints(field: FieldSpec, builder: CodeBlock.Builder, indent: String) {
    field.minimum?.let {
        builder.add("${indent}put(%S, %T(%L))\n", "minimum", JSON_PRIMITIVE, it)
    }
    field.maximum?.let {
        builder.add("${indent}put(%S, %T(%L))\n", "maximum", JSON_PRIMITIVE, it)
    }
    field.maxItems?.let {
        builder.add("${indent}put(%S, %T(%L))\n", "maxItems", JSON_PRIMITIVE, schemaIntValue(it))
    }
    field.maxLength?.let {
        builder.add("${indent}put(%S, %T(%L))\n", "maxLength", JSON_PRIMITIVE, schemaIntValue(it))
    }
}

private fun schemaIntValue(value: SchemaIntValue): CodeBlock {
    return when (value) {
        is SchemaIntValue.Literal -> CodeBlock.of("%L", value.value)
        SchemaIntValue.MaxInlineWasmBytes -> CodeBlock.of("%T.MAX_INLINE_WASM_BYTES", HEXANA_ANALYSIS_LIMITS)
        SchemaIntValue.MaxInlineWasmBase64Length ->
            CodeBlock.of("((%T.MAX_INLINE_WASM_BYTES + 2) / 3) * 4", HEXANA_ANALYSIS_LIMITS)
        SchemaIntValue.MaxInlineBinaryBytes -> CodeBlock.of("%T.MAX_INLINE_BINARY_BYTES", HEXANA_ANALYSIS_LIMITS)
        SchemaIntValue.MaxInlineBinaryBase64Length ->
            CodeBlock.of("((%T.MAX_INLINE_BINARY_BYTES + 2) / 3) * 4", HEXANA_ANALYSIS_LIMITS)
    }
}

private fun schemaDefaultValue(field: FieldSpec): CodeBlock {
    return when (val value = field.default) {
        is Int,
        is Long,
        is Double,
        is Boolean -> CodeBlock.of("%L", value)
        is String -> CodeBlock.of("%S", value)
        else -> error("Field '${field.name}' has unsupported schema default value `${field.default}`.")
    }
}

private fun StandaloneTool.registerFunctionName(): String =
    "register${name.toPascalCase()}Tool"

private fun String.toPascalCase(): String {
    val parts = split('_').filter { it.isNotEmpty() }
    require(parts.isNotEmpty()) { "tool name `$this` has no non-empty segments" }
    return parts.joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
}

private fun String.toConstantSegment(): String =
    replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").uppercase()

private fun InputConversionCapability.generatedCliInputModeName(): String =
    when (this) {
        InputConversionCapability.JsonObject -> "JSON_OBJECT"
        InputConversionCapability.WasmPath -> "WASM_PATH"
        InputConversionCapability.WasmFromString -> "WASM_FROM_STRING"
        InputConversionCapability.BinaryPath -> "BINARY_PATH"
        InputConversionCapability.Stdin -> "STDIN"
    }

private fun CliTool.generatedCliInputModeNames(): List<String> =
    when (binding.executionStatus) {
        CliExecutionStatus.Executable -> requireNotNull(inputModel)
            .sources
            .flatMap { it.generatedCliInputCapabilities() }
            .distinct()
            .map { it.generatedCliInputModeName() }
        CliExecutionStatus.Todo -> emptyList()
    }

private fun SourceSlotSpec.generatedCliInputCapabilities(): List<InputConversionCapability> {
    val capabilities = mutableListOf<InputConversionCapability>()
    if (cli.positionalPathName != null || cli.pathOption != null) {
        capabilities += pathInputCapability()
    }
    if (usesGeneratedSourceCommand() && (cli.stdinOption != null || cli.autoStdin)) {
        capabilities += InputConversionCapability.Stdin
    }
    return capabilities
}

private fun SourceSlotSpec.pathInputCapability(): InputConversionCapability =
    when (kind) {
        SourceKind.Wasm -> InputConversionCapability.WasmPath
        SourceKind.Binary -> InputConversionCapability.BinaryPath
        else -> throw IllegalArgumentException("Generated executable CLI source '$name' must be Wasm or Binary, got $kind.")
    }

private fun SourceSlotSpec.usesGeneratedSourceCommand(): Boolean =
    (kind == SourceKind.Wasm || kind == SourceKind.Binary) &&
        (cli.stdinOption != null || cli.base64Option != null || cli.autoStdin)

private fun CliTool.cliOutputFormats(): List<OutputConversionCapability> {
    val cliOutput = binding.outputOverride?.let(::listOf) ?: output.outputCapabilities.toList()
    return cliOutput.filter { it.generatedCliOutputFormatNameOrNull() != null }.distinct()
}

private fun OutputConversionCapability.generatedCliOutputFormatName(): String =
    requireNotNull(generatedCliOutputFormatNameOrNull()) {
        "Output conversion capability $this is not a CLI output format."
    }

private fun OutputConversionCapability.generatedCliOutputFormatNameOrNull(): String? =
    when (this) {
        OutputConversionCapability.SemanticJson -> "JSON"
        OutputConversionCapability.Pretty -> "PRETTY"
        OutputConversionCapability.Table -> "TABLE"
        OutputConversionCapability.RawText,
        OutputConversionCapability.RawBytes -> "RAW"
        OutputConversionCapability.McpWrapper -> null
    }

private fun ToolGroup.generatedCliGroupName(): String =
    name.toConstantSegment()

private fun ToolOutputPolicy.generatedCliOutputPolicyName(): String =
    when (this) {
        ToolOutputPolicy.ToolDefined -> "TOOL_DEFINED"
    }

private fun CliExecutionStatus.generatedCliExecutionStatusName(): String =
    when (this) {
        CliExecutionStatus.Executable -> "EXECUTABLE"
        CliExecutionStatus.Todo -> "TODO"
    }

private fun CliProductSpec.generatedName(): String = id.value.replace('-', '_').toConstantSegment()
