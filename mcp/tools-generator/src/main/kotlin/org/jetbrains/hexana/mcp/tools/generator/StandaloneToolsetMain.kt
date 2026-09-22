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
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.jetbrains.hexana.mcp.tools.CliExecutionStatus
import org.jetbrains.hexana.mcp.tools.CliProductRegistry
import org.jetbrains.hexana.mcp.tools.CliProductSpec
import org.jetbrains.hexana.mcp.tools.CliTool
import org.jetbrains.hexana.mcp.tools.FieldDefaultValue
import org.jetbrains.hexana.mcp.tools.FieldPresence
import org.jetbrains.hexana.mcp.tools.HexanaToolSchemaCompatibility
import org.jetbrains.hexana.mcp.tools.HexanaCliProductRegistry
import org.jetbrains.hexana.mcp.tools.HexanaCliTools
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneAuthority
import org.jetbrains.hexana.mcp.tools.InputConversionCapability
import org.jetbrains.hexana.mcp.tools.OutputConversionCapability
import org.jetbrains.hexana.mcp.tools.SchemaClassPlan
import org.jetbrains.hexana.mcp.tools.SchemaFieldPlan
import org.jetbrains.hexana.mcp.tools.SchemaIntValue
import org.jetbrains.hexana.mcp.tools.SchemaScalarKind
import org.jetbrains.hexana.mcp.tools.SchemaTypePlan
import org.jetbrains.hexana.mcp.tools.SourceKind
import org.jetbrains.hexana.mcp.tools.SourceSlotSpec
import org.jetbrains.hexana.mcp.tools.StandaloneTool
import org.jetbrains.hexana.mcp.tools.StandaloneToolAuthority
import org.jetbrains.hexana.mcp.tools.StandaloneInvocationSpec
import org.jetbrains.hexana.mcp.tools.ToolId
import org.jetbrains.hexana.mcp.tools.ToolGroup
import org.jetbrains.hexana.mcp.tools.ToolOutputPolicy
import org.jetbrains.hexana.mcp.tools.input
import org.jetbrains.hexana.mcp.tools.mcpDescription
import org.jetbrains.hexana.mcp.tools.name
import org.jetbrains.hexana.mcp.tools.output
import org.jetbrains.hexana.mcp.tools.summary
import org.jetbrains.hexana.mcp.tools.compileSchemaPlan
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
private val JSON_OBJECT = ClassName("kotlinx.serialization.json", "JsonObject")
private val JSON_ARRAY = ClassName("kotlinx.serialization.json", "JsonArray")
private val JSON_NULL = ClassName("kotlinx.serialization.json", "JsonNull")
private val JSON_ELEMENT = ClassName("kotlinx.serialization.json", "JsonElement")
private val TOOL_INPUT_EXCEPTION = ClassName(SERVER_PACKAGE, "ToolInputException")
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
    val compatibilityObject = buildCompatibilityObject()
    val profilesObject = buildToolProfilesObject(authority)
    val inputDefaultsObject = buildInputDefaultsObject(mcpTools)
    val typedAdapters = mcpTools.mapNotNull(::buildTypedRequestAdapter)
    val registerFun = FunSpec.builder("registerHexanaTools")
        .receiver(SERVER_CLASS)
        .addParameter(ParameterSpec.builder("handlers", TOOL_HANDLERS).build())
        .addParameter(
            ParameterSpec.builder("profile", TOOL_PROFILE)
                .defaultValue("%T.defaultProfile()", TOOL_PROFILE)
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
    val registerToolFunctions = mcpTools.map(::buildRegisterToolFunction)
    validateUniqueTopLevelDeclarations(
        types = buildList {
            add(compatibilityObject)
            add(profilesObject)
            inputDefaultsObject?.let(::add)
            typedAdapters.forEach { adapter ->
                add(adapter.request)
                add(adapter.scalars)
            }
        },
        functions = buildList {
            add(registerFun)
            registerToolFunctions.forEach(::add)
            typedAdapters.forEach { adapter ->
                add(adapter.decoder)
                add(adapter.scalarDecoder)
                adapter.sourceSelection.forEach(::add)
            }
        },
    )

    val fileBuilder = FileSpec.builder(SERVER_PACKAGE, "HexanaToolDispatcher")
        .addFileComment("Generated by :mcp:tools-generator. Do not edit by hand.")
        .addImport("kotlinx.serialization.json", "contentOrNull", "intOrNull", "jsonPrimitive")
        .addType(compatibilityObject)
        .addType(profilesObject)
    inputDefaultsObject?.let(fileBuilder::addType)
    typedAdapters.forEach { adapter ->
        fileBuilder.addType(adapter.request)
        fileBuilder.addFunction(adapter.decoder)
        fileBuilder.addType(adapter.scalars)
        fileBuilder.addFunction(adapter.scalarDecoder)
        adapter.sourceSelection.forEach(fileBuilder::addFunction)
    }
    return fileBuilder
        .addFunction(registerFun)
        .apply { registerToolFunctions.forEach(::addFunction) }
        .build()
}

private fun validateUniqueTopLevelDeclarations(types: List<TypeSpec>, functions: List<FunSpec>) {
    fun duplicates(names: List<String>): List<String> =
        names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()

    val duplicateTypes = duplicates(types.mapNotNull(TypeSpec::name))
    require(duplicateTypes.isEmpty()) {
        "Standalone dispatcher contains colliding top-level type declarations: ${duplicateTypes.joinToString { "`$it`" }}."
    }
    val duplicateFunctions = duplicates(functions.map(FunSpec::name))
    require(duplicateFunctions.isEmpty()) {
        "Standalone dispatcher contains colliding top-level function declarations: ${duplicateFunctions.joinToString { "`$it`" }}."
    }
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
    val product = authority.runtimeProduct
    val setOfString = Set::class.parameterizedBy(String::class)
    val stringToSet = KOTLIN_MAP.parameterizedBy(String::class.asTypeName(), setOfString)
    val stringToString = KOTLIN_MAP.parameterizedBy(String::class.asTypeName(), String::class.asTypeName())
    val profilesById = CodeBlock.builder().add("mapOf(\n").indent().apply {
        product.profiles.forEach { profile ->
            add("%S to %L,\n", profile.id.value, "${profile.id.value.uppercase().replace('-', '_')}_TOOL_NAMES")
        }
    }.unindent().add(")").build()
    val aliases = CodeBlock.builder().add("mapOf(\n").indent().apply {
        product.profiles.flatMap { profile -> profile.aliases.sorted().map { it to profile.id.value } }
            .forEach { (alias, id) -> add("%S to %S,\n", alias, id) }
    }.unindent().add(")").build()
    val enumIds = CodeBlock.builder().add("mapOf(\n").indent().apply {
        product.profiles.forEach { profile ->
            add("%S to %S,\n", profile.id.value.uppercase().replace('-', '_'), profile.id.value)
        }
    }.unindent().add(")").build()
    return TypeSpec.objectBuilder("HexanaGeneratedMcpToolProfiles")
        .addModifiers(KModifier.INTERNAL)
        .addKdoc("Generated MCP profile metadata for the explicitly bound runtime product.\n")
        .apply {
            product.profiles.forEach { profile ->
                addProperty(
                    PropertySpec.builder("${profile.id.value.uppercase().replace('-', '_')}_TOOL_NAMES", setOfString, KModifier.INTERNAL)
                        .initializer(toolNameSetInitializer(profile.toolIds.map(ToolId::value)))
                        .build(),
                )
            }
        }
        .addProperty(
            PropertySpec.builder("DEFAULT_PROFILE_ID", String::class, KModifier.INTERNAL, KModifier.CONST)
                .initializer("%S", product.defaultProfileId.value)
                .build()
        )
        .addProperty(PropertySpec.builder("TOOL_NAMES_BY_PROFILE_ID", stringToSet, KModifier.INTERNAL).initializer(profilesById).build())
        .addProperty(PropertySpec.builder("PROFILE_ID_BY_ALIAS", stringToString, KModifier.INTERNAL).initializer(aliases).build())
        .addProperty(PropertySpec.builder("PROFILE_ID_BY_COMPATIBILITY_ENUM", stringToString, KModifier.INTERNAL).initializer(enumIds).build())
        .addFunction(
            FunSpec.builder("toolNames").addModifiers(KModifier.INTERNAL)
                .addParameter("profileId", String::class).returns(setOfString)
                .addStatement("return TOOL_NAMES_BY_PROFILE_ID.getValue(profileId)").build()
        )
        .addFunction(
            FunSpec.builder("profileIdForAlias").addModifiers(KModifier.INTERNAL)
                .addParameter("value", String::class).returns(String::class.asTypeName().copy(nullable = true))
                .addStatement("return PROFILE_ID_BY_ALIAS[value.lowercase()]").build()
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

private data class TypedRequestAdapter(
    val request: TypeSpec,
    val decoder: FunSpec,
    val scalars: TypeSpec,
    val scalarDecoder: FunSpec,
    val sourceSelection: List<FunSpec>,
)

/**
 * Emits a host-local neutral request and decoder for the one standalone binding
 * that has opted into the typed invocation boundary.  The plan is intentionally
 * the sole schema input: the generated request carries only wire values and
 * presence, while host code keeps file access and resource policy decisions.
 */
private fun buildTypedRequestAdapter(spec: StandaloneTool): TypedRequestAdapter? {
    val invocation = spec.standaloneInvocation as? StandaloneInvocationSpec.TypedRequest ?: return null
    val input = compileSchemaPlan(spec.input).root
    val fields = input.fields
    val sourceFields = input.sourceGroups.flatMapTo(mutableSetOf()) { it.fields }
    val scalarFields = fields.filterNot { it.identity in sourceFields }
    val request = TypeSpec.classBuilder(invocation.requestTypeName)
        .addModifiers(KModifier.INTERNAL, KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder().apply {
                fields.forEach { field -> addParameter(field.name, JSON_ELEMENT.copy(nullable = true)) }
            }.build()
        )
        .apply {
            fields.forEach { field ->
                addProperty(PropertySpec.builder(field.name, JSON_ELEMENT.copy(nullable = true)).initializer(field.name).build())
            }
        }
        .build()

    val decoder = FunSpec.builder("decode${invocation.requestTypeName}")
        .addModifiers(KModifier.INTERNAL)
        .addParameter("arguments", JSON_OBJECT.copy(nullable = true))
        .returns(ClassName(SERVER_PACKAGE, invocation.requestTypeName))
        .apply {
            fields.forEach { field -> addCode(captureWireField(field, field.identity in sourceFields)) }
            addStatement("return %T(${fields.joinToString { it.name }})", ClassName(SERVER_PACKAGE, invocation.requestTypeName))
        }
        .build()
    val scalarsTypeName = "${invocation.requestTypeName}Scalars"
    val scalars = TypeSpec.classBuilder(scalarsTypeName)
        .addModifiers(KModifier.INTERNAL, KModifier.DATA)
        .primaryConstructor(
            FunSpec.constructorBuilder().apply {
                scalarFields.forEach { field -> addParameter(field.name, requestValueFieldType(field)) }
            }.build()
        )
        .apply {
            scalarFields.forEach { field ->
                addProperty(PropertySpec.builder(field.name, requestValueFieldType(field)).initializer(field.name).build())
            }
        }
        .build()
    val scalarDecoder = FunSpec.builder("decode${invocation.requestTypeName}Scalars")
        .addModifiers(KModifier.INTERNAL)
        .addParameter("request", ClassName(SERVER_PACKAGE, invocation.requestTypeName))
        .returns(ClassName(SERVER_PACKAGE, scalarsTypeName))
        .apply {
            scalarFields.forEach { field -> addCode(decodeScalarField(field)) }
            addStatement("return %T(${scalarFields.joinToString { it.name }})", ClassName(SERVER_PACKAGE, scalarsTypeName))
        }
        .build()
    return TypedRequestAdapter(
        request,
        decoder,
        scalars,
        scalarDecoder,
        input.sourceGroups.mapIndexed { index, group ->
            buildSourceSelection(invocation.requestTypeName, index, group, fields)
        },
    )
}

private fun requestValueFieldType(field: SchemaFieldPlan): TypeName {
    val base = when (val type = field.type) {
        is SchemaTypePlan.Scalar -> when (type.kind) {
            SchemaScalarKind.Int32 -> Int::class.asTypeName()
            SchemaScalarKind.String -> String::class.asTypeName()
            else -> error("Typed standalone request does not support ${type.kind} for `${field.wireName}`.")
        }
        is SchemaTypePlan.ListOf -> KOTLIN_LIST.parameterizedBy(
            when (val element = type.element) {
                is SchemaTypePlan.Scalar -> when (element.kind) {
                    SchemaScalarKind.Int32 -> Int::class.asTypeName()
                    else -> error("Typed standalone request does not support ${element.kind} list items for `${field.wireName}`.")
                }
                else -> error("Typed standalone request supports scalar list items only for `${field.wireName}`.")
            }
        )
        else -> error("Typed standalone request does not support ${field.type} for `${field.wireName}`.")
    }
    return if (field.presence == FieldPresence.Required) base else base.copy(nullable = true)
}

private fun captureWireField(field: SchemaFieldPlan, isSource: Boolean): CodeBlock {
    val elementName = "${field.name}Element"
    val source = CodeBlock.builder().addStatement("val %N = arguments?.get(%S)", elementName, field.wireName)
    if (isSource) {
        return source.addStatement(
            "val %N = %N?.takeUnless { it == %T }",
            field.name,
            elementName,
            JSON_NULL,
        ).build()
    }
    return source.addStatement("val %N = %N", field.name, elementName).build()
}

private fun decodeScalarField(field: SchemaFieldPlan): CodeBlock {
    val elementName = "${field.name}Element"
    val nullable = field.presence != FieldPresence.Required
    val source = CodeBlock.builder().addStatement("val %N = request.%N", elementName, field.name)
    when (val type = field.type) {
        is SchemaTypePlan.Scalar -> when (type.kind) {
            SchemaScalarKind.String -> source.add(
                "val %N = if (%N == null || %N == %T) %L else (%N as? %T)?.contentOrNull ?: throw %T(%S)\n",
                field.name, elementName, elementName, JSON_NULL, if (nullable) "null" else "throw ${TOOL_INPUT_EXCEPTION.simpleName}(\"`${field.wireName}` is required\")",
                elementName, JSON_PRIMITIVE, TOOL_INPUT_EXCEPTION, "`${field.wireName}` must be a string",
            )
            SchemaScalarKind.Int32 -> source.add(
                "val %N = if (%N == null) %L else %N.jsonPrimitive.intOrNull ?: throw %T(%S)\n",
                field.name, elementName,
                if (nullable) "null" else "throw ${TOOL_INPUT_EXCEPTION.simpleName}(\"`${field.wireName}` is required\")",
                elementName, TOOL_INPUT_EXCEPTION, "`${field.wireName}` must be an integer",
            ).apply { emitIntBounds(field, this) }
            else -> error("Typed standalone decoder does not support ${type.kind} for `${field.wireName}`.")
        }
        is SchemaTypePlan.ListOf -> {
            val item = type.element as? SchemaTypePlan.Scalar
                ?: error("Typed standalone decoder supports scalar list items only for `${field.wireName}`.")
            require(item.kind == SchemaScalarKind.Int32) {
                "Typed standalone decoder supports Int32 list items only for `${field.wireName}`."
            }
            source.add(
                "val %N = if (%N == null || %N == %T) %L else (%N as? %T ?: throw %T(%S)).mapIndexed { index, value ->\n" +
                    "  val item = (value as? %T)?.intOrNull ?: throw %T(\"`${field.wireName}[\${index}]` must be an integer\")\n" +
                    listItemValidation(field) +
                    "  item\n" +
                    "}\n",
                field.name, elementName, elementName, JSON_NULL,
                if (nullable) "null" else "throw ${TOOL_INPUT_EXCEPTION.simpleName}(\"`${field.wireName}` is required\")",
                elementName, JSON_ARRAY, TOOL_INPUT_EXCEPTION, "`${field.wireName}` must be an array", JSON_PRIMITIVE, TOOL_INPUT_EXCEPTION,
            )
        }
        else -> error("Typed standalone decoder does not support ${field.type} for `${field.wireName}`.")
    }
    return source.build()
}

private fun buildSourceSelection(
    requestTypeName: String,
    groupIndex: Int,
    group: org.jetbrains.hexana.mcp.tools.SchemaExactOneSourceGroupPlan,
    fields: List<SchemaFieldPlan>,
): FunSpec {
    val names = group.fields.map { identity -> fields.single { it.identity == identity }.wireName }
    val alternatives = formatBacktickedAlternatives(names)
    val functionName = "require${requestTypeName}SourceGroup$groupIndex"
    return FunSpec.builder(functionName)
        .addModifiers(KModifier.INTERNAL)
        .apply {
            names.indices.forEach { index -> addParameter("source${index}Present", Boolean::class) }
            addStatement("val sourceGroup${groupIndex}Count = listOf(${names.indices.joinToString { "source${it}Present" }}).count { it }")
            if (group.required) {
                addStatement(
                    "if (sourceGroup${groupIndex}Count == 0) throw %T(%S)",
                    TOOL_INPUT_EXCEPTION,
                    "Exactly one of $alternatives is required",
                )
            }
            addStatement(
                "if (sourceGroup${groupIndex}Count > 1) throw %T(%S)",
                TOOL_INPUT_EXCEPTION,
                "Provide exactly one of $alternatives",
            )
        }
        .build()
}

internal fun formatBacktickedAlternatives(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "`${names.single()}`"
    2 -> names.joinToString(" or ") { "`$it`" }
    else -> names.dropLast(1).joinToString(", ") { "`$it`" } + ", or `${names.last()}`"
}

private fun emitIntBounds(field: SchemaFieldPlan, builder: CodeBlock.Builder) {
    val minimum = field.constraints.minimum
    val maximum = field.constraints.maximum
    if (minimum != null || maximum != null) {
        val condition = when {
            minimum != null && maximum != null -> "${field.name} != null && (${field.name} < $minimum || ${field.name} > $maximum)"
            minimum != null -> "${field.name} != null && ${field.name} < $minimum"
            else -> "${field.name} != null && ${field.name} > $maximum"
        }
        val message = when {
            minimum != null && maximum != null -> "`${field.wireName}` must be between $minimum and $maximum"
            minimum != null -> "`${field.wireName}` must be >= $minimum"
            else -> "`${field.wireName}` must be <= $maximum"
        }
        builder.addStatement("if ($condition) throw %T(%S)", TOOL_INPUT_EXCEPTION, message)
    }
}

private fun listItemValidation(field: SchemaFieldPlan): String {
    val minimum = field.constraints.itemMinimum
    val maximum = field.constraints.itemMaximum
    if (minimum != null || maximum != null) {
        val condition = when {
            minimum != null && maximum != null -> "item < $minimum || item > $maximum"
            minimum != null -> "item < $minimum"
            else -> "item > $maximum"
        }
        val message = when {
            minimum != null && maximum != null -> "`${field.wireName}[\${index}]` must be between $minimum and $maximum"
            minimum != null -> "`${field.wireName}[\${index}]` must be >= $minimum"
            else -> "`${field.wireName}[\${index}]` must be <= $maximum"
        }
        return "  if ($condition) throw ToolInputException(\"$message\")\n"
    }
    return ""
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
    val inputSchema = buildToolSchema(compileSchemaPlan(spec.input).root)
    val outputSchema = buildToolSchema(compileSchemaPlan(spec.output).root, includeDescriptions = false, includeNestedClasses = false)

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
        .add(renderStandaloneInvocation(spec))
        .endControlFlow()
        .build()
}

private fun renderStandaloneInvocation(spec: StandaloneTool): CodeBlock = when (val invocation = spec.standaloneInvocation) {
    is StandaloneInvocationSpec.JsonArguments -> CodeBlock.of(
        "request: %T -> handlers.%N(request.arguments)\n",
        CALL_TOOL_REQUEST,
        invocation.methodName,
    )
    is StandaloneInvocationSpec.TypedRequest -> CodeBlock.builder()
        .add("request: %T -> try {\n", CALL_TOOL_REQUEST)
        .indent()
        .addStatement("handlers.%N(decode%L(request.arguments))", invocation.methodName, invocation.requestTypeName)
        .unindent()
        .add("} catch (error: %T) {\n", TOOL_INPUT_EXCEPTION)
        .indent()
        .addStatement("handlers.%N(error)", invocation.invalidInputMethodName)
        .unindent()
        .add("}\n")
        .build()
}

/**
 * Emits a full `ToolSchema(...)` expression for the tool's input or output type.
 * Nested classes are inlined into the schema — the generated file is verbose
 * but self-contained.
 */
private fun buildToolSchema(
    cls: SchemaClassPlan,
    includeDescriptions: Boolean = true,
    includeNestedClasses: Boolean = true,
): CodeBlock {
    val builder = CodeBlock.builder()
    builder.add("%T(\n", TOOL_SCHEMA)
    builder.add("  properties = %M {\n", BUILD_JSON_OBJECT)
    cls.fields.forEach { field ->
        builder.add("    put(%S, ", field.wireName)
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
    val required = cls.fields.filter { it.presence == FieldPresence.Required }.map { it.wireName }
    required.forEachIndexed { index, key ->
        if (index > 0) builder.add(", ")
        builder.add("%S", key)
    }
    builder.add(")\n)")
    return builder.build()
}

private fun fieldSchemaObject(field: SchemaFieldPlan): CodeBlock {
    return fieldSchemaObject(field, includeDescriptions = true)
}

private fun fieldSchemaObject(
    field: SchemaFieldPlan,
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
    if (includeDescriptions) field.description?.let {
        b.add("${schemaIndent}put(%S, %T(%S))\n", "description", JSON_PRIMITIVE, it)
    }
    field.defaultValue?.takeUnless { it == FieldDefaultValue.Null }?.let {
        b.add("${schemaIndent}put(%S, %L)\n", "default", schemaDefaultValue(it))
    }
    emitFieldConstraints(field, b, indent = schemaIndent)
    b.add("$closingIndent}")
    return b.build()
}

/** Emits the JSON Schema properties (`type`, `items`, nested `properties`/`required`) for a field. */
private fun emitTypeSchema(
    field: SchemaFieldPlan,
    builder: CodeBlock.Builder,
    indent: String,
    includeDescriptions: Boolean,
    includeNestedClasses: Boolean,
) {
    when (val type = field.type) {
        is SchemaTypePlan.ListOf -> {
            emitJsonType(builder, indent, "array", nullable = field.nullable)
            builder.add("${indent}put(%S, %M {\n", "items", BUILD_JSON_OBJECT)
            emitTypeSchema(
                type.element,
                builder,
                indent = "$indent  ",
                includeDescriptions = includeDescriptions,
                includeNestedClasses = includeNestedClasses,
            )
            field.constraints.itemMinimum?.let {
                builder.add("$indent  put(%S, %T(%L))\n", "minimum", JSON_PRIMITIVE, it)
            }
            field.constraints.itemMaximum?.let {
                builder.add("$indent  put(%S, %T(%L))\n", "maximum", JSON_PRIMITIVE, it)
            }
            builder.add("${indent}})\n")
        }
        is SchemaTypePlan.MapOf -> {
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
    type: SchemaTypePlan,
    builder: CodeBlock.Builder,
    indent: String,
    nullable: Boolean = false,
    includeDescriptions: Boolean,
    includeNestedClasses: Boolean,
) {
    when (type) {
        is SchemaTypePlan.Scalar -> emitJsonType(builder, indent, type.kind.jsonSchemaType(), nullable)
        is SchemaTypePlan.ListOf -> {
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
        is SchemaTypePlan.MapOf -> {
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

        is SchemaTypePlan.ClassRef -> {
            emitJsonType(builder, indent, "object", nullable)
            if (!includeNestedClasses) return
            builder.add("${indent}put(%S, %M {\n", "properties", BUILD_JSON_OBJECT)
            type.clazz.fields.forEach { nested ->
                builder.add("$indent  put(%S, ", nested.wireName)
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
            val requiredFields = type.clazz.fields.filter { it.presence == FieldPresence.Required }
            if (requiredFields.isNotEmpty()) {
                builder.add("${indent}put(%S, %M {\n", "required", BUILD_JSON_ARRAY)
                requiredFields.forEach { req ->
                    builder.add("$indent  add(%T(%S))\n", JSON_PRIMITIVE, req.wireName)
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

private fun SchemaScalarKind.jsonSchemaType(): String = when (this) {
    SchemaScalarKind.Int32,
    SchemaScalarKind.Int64,
    SchemaScalarKind.UInt32,
    SchemaScalarKind.Byte -> "integer"
    SchemaScalarKind.Float64 -> "number"
    SchemaScalarKind.Boolean -> "boolean"
    SchemaScalarKind.String,
    SchemaScalarKind.ByteArray -> "string"
    SchemaScalarKind.JsonObject -> "object"
}

private fun emitFieldConstraints(field: SchemaFieldPlan, builder: CodeBlock.Builder, indent: String) {
    field.constraints.minimum?.let {
        builder.add("${indent}put(%S, %T(%L))\n", "minimum", JSON_PRIMITIVE, it)
    }
    field.constraints.maximum?.let {
        builder.add("${indent}put(%S, %T(%L))\n", "maximum", JSON_PRIMITIVE, it)
    }
    field.constraints.maxItems?.let {
        builder.add("${indent}put(%S, %T(%L))\n", "maxItems", JSON_PRIMITIVE, schemaIntValue(it))
    }
    field.constraints.maxLength?.let {
        builder.add("${indent}put(%S, %T(%L))\n", "maxLength", JSON_PRIMITIVE, schemaIntValue(it))
    }
}

private fun schemaIntValue(value: org.jetbrains.hexana.mcp.tools.SchemaIntValue): CodeBlock {
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

private fun schemaDefaultValue(value: FieldDefaultValue): CodeBlock {
    return when (value) {
        FieldDefaultValue.Null -> error("Standalone schemas must omit null defaults.")
        is FieldDefaultValue.IntValue -> CodeBlock.of("%T(%L)", JSON_PRIMITIVE, value.value)
        is FieldDefaultValue.LongValue -> CodeBlock.of("%T(%L)", JSON_PRIMITIVE, value.value)
        is FieldDefaultValue.DoubleValue -> CodeBlock.of("%T(%L)", JSON_PRIMITIVE, value.value)
        is FieldDefaultValue.BooleanValue -> CodeBlock.of("%T(%L)", JSON_PRIMITIVE, value.value)
        is FieldDefaultValue.StringValue -> CodeBlock.of("%T(%S)", JSON_PRIMITIVE, value.value)
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
        OutputConversionCapability.Sarif -> "SARIF"
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
