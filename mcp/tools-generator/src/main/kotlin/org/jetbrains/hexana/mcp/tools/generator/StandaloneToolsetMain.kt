package org.jetbrains.hexana.mcp.tools.generator

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import org.jetbrains.hexana.mcp.tools.ClassSpec
import org.jetbrains.hexana.mcp.tools.CliExecutionStatus
import org.jetbrains.hexana.mcp.tools.FieldSpec
import org.jetbrains.hexana.mcp.tools.FieldType
import org.jetbrains.hexana.mcp.tools.HexanaToolSchemaCompatibility
import org.jetbrains.hexana.mcp.tools.HexanaTools
import org.jetbrains.hexana.mcp.tools.InputConversionCapability
import org.jetbrains.hexana.mcp.tools.McpToolAudience
import org.jetbrains.hexana.mcp.tools.OutputConversionCapability
import org.jetbrains.hexana.mcp.tools.SchemaIntValue
import org.jetbrains.hexana.mcp.tools.ToolGroup
import org.jetbrains.hexana.mcp.tools.ToolInputPattern
import org.jetbrains.hexana.mcp.tools.ToolOutputPolicy
import org.jetbrains.hexana.mcp.tools.ToolSpec
import org.jetbrains.hexana.mcp.tools.mcpDescription
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val SERVER_PACKAGE = "org.jetbrains.hexana.mcp.server"
private val SERVER_CLASS = ClassName("io.modelcontextprotocol.kotlin.sdk.server", "Server")
private val TOOL_CLASS = ClassName("io.modelcontextprotocol.kotlin.sdk.types", "Tool")
private val TOOL_ANNOTATIONS = ClassName("io.modelcontextprotocol.kotlin.sdk.types", "ToolAnnotations")
private val CALL_TOOL_REQUEST = ClassName("io.modelcontextprotocol.kotlin.sdk.types", "CallToolRequest")
private val CLIKT_COMMAND = ClassName("com.github.ajalt.clikt.core", "CliktCommand")
private val CLIKT_CONTEXT = ClassName("com.github.ajalt.clikt.core", "Context")
private val CLIKT_COMPLETION_CANDIDATES = ClassName("com.github.ajalt.clikt.completion", "CompletionCandidates")
private val TOOL_HANDLERS = ClassName(SERVER_PACKAGE, "HexanaToolHandlers")
private val HEXANA_COMMAND = ClassName(SERVER_PACKAGE, "HexanaCommand")
private val HEXANA_EXECUTABLE_TOOL_COMMAND = HEXANA_COMMAND.nestedClass("ExecutableTool")
private val HEXANA_CLI_COMMAND_OUTPUT = ClassName(SERVER_PACKAGE, "HexanaCliCommandOutput")
private val TOOL_PROFILE = ClassName(SERVER_PACKAGE, "HexanaMcpToolProfile")
private val CLI_TOOL_SPEC = ClassName(SERVER_PACKAGE, "HexanaCliToolSpec")
private val CLI_INPUT_MODE = ClassName(SERVER_PACKAGE, "HexanaCliInputMode")
private val CLI_OUTPUT_FORMAT = ClassName(SERVER_PACKAGE, "HexanaCliOutputFormat")
private val CLI_TOOL_GROUP = ClassName(SERVER_PACKAGE, "HexanaCliToolGroup")
private val CLI_OUTPUT_POLICY = ClassName(SERVER_PACKAGE, "HexanaCliOutputPolicy")
private val CLI_EXECUTION_STATUS = ClassName(SERVER_PACKAGE, "HexanaCliExecutionStatus")
private val KOTLIN_LIST = ClassName("kotlin.collections", "List")
private val KOTLIN_SET = ClassName("kotlin.collections", "Set")
private val HEXANA_ANALYSIS_LIMITS = ClassName("org.jetbrains.hexana.mcp", "HexanaAnalysisLimits")
private val TOOL_SCHEMA = ClassName("io.modelcontextprotocol.kotlin.sdk.types", "ToolSchema")
private val JSON_PRIMITIVE = ClassName("kotlinx.serialization.json", "JsonPrimitive")
private val BUILD_JSON_OBJECT = MemberName("kotlinx.serialization.json", "buildJsonObject")
private val BUILD_JSON_ARRAY = MemberName("kotlinx.serialization.json", "buildJsonArray")
private val ARGUMENT = MemberName("com.github.ajalt.clikt.parameters.arguments", "argument")
private val OPTION = MemberName("com.github.ajalt.clikt.parameters.options", "option")

fun main(args: Array<String>) {
    val outputRoot = args.firstOrNull()?.let(Path::of) ?: run {
        System.err.println("Usage: standalone-toolset-generator <output-root>")
        exitProcess(1)
    }

    try {
        Files.createDirectories(outputRoot)
    } catch (error: Exception) {
        System.err.println("Failed to create output directory '$outputRoot': ${error.message}")
        exitProcess(1)
    }
    val fileSpecs = buildStandaloneServerFiles(HexanaTools)
    fileSpecs.forEach { it.writeTo(outputRoot) }
    val names = fileSpecs.joinToString { "${it.packageName}.${it.name}" }
    println("Wrote $names to $outputRoot")
}

internal fun buildStandaloneServerFiles(tools: List<ToolSpec>): List<FileSpec> =
    listOf(
        buildDispatcherFile(tools),
        buildCliToolsetFile(tools),
    )

internal fun buildDispatcherFile(tools: List<ToolSpec>): FileSpec {
    val mcpTools = tools.filter { it.surfaces.mcp.visible }
    val registerFun = FunSpec.builder("registerHexanaTools")
        .receiver(SERVER_CLASS)
        .addParameter(ParameterSpec.builder("handlers", TOOL_HANDLERS).build())
        .addParameter(
            ParameterSpec.builder("profile", TOOL_PROFILE)
                .defaultValue("%T.PRODUCTION", TOOL_PROFILE)
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
        .addType(buildToolProfilesObject(mcpTools))
    buildInputDefaultsObject(tools)?.let { fileBuilder.addType(it) }
    return fileBuilder
        .addFunction(registerFun)
        .apply { mcpTools.forEach { addFunction(buildRegisterToolFunction(it)) } }
        .build()
}

internal fun buildCliToolsetFile(tools: List<ToolSpec>): FileSpec {
    return FileSpec.builder(SERVER_PACKAGE, "HexanaCliToolset")
        .addFileComment("Generated by :mcp:tools-generator. Do not edit by hand.")
        .addImport("com.github.ajalt.clikt.parameters.arguments", "multiple")
        .addImport("com.github.ajalt.clikt.parameters.options", "convert", "default")
        .addType(buildCliInputModeEnum())
        .addType(buildCliOutputFormatEnum())
        .addType(buildCliToolGroupEnum())
        .addType(buildCliOutputPolicyEnum())
        .addType(buildCliExecutionStatusEnum())
        .addType(buildCliToolSpecClass())
        .addType(buildCliToolRegistryObject(tools))
        .addType(buildGeneratedPathCliToolCommandClass())
        .addType(buildGeneratedTodoCliToolCommandClass())
        .addFunction(buildGeneratedCliToolCommandsFunction(tools))
        .addFunction(buildGeneratedExecutableCliToolCommandOutputFunction(tools))
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

private fun buildToolProfilesObject(tools: List<ToolSpec>): TypeSpec {
    val setOfString = Set::class.parameterizedBy(String::class)
    return TypeSpec.objectBuilder("HexanaGeneratedMcpToolProfiles")
        .addModifiers(KModifier.INTERNAL)
        .addKdoc("Generated MCP profile membership derived from catalog surface metadata.\n")
        .addProperty(
            PropertySpec.builder("PRODUCTION_TOOL_NAMES", setOfString, KModifier.INTERNAL)
                .initializer(toolNameSetInitializer(tools.productionToolNames()))
                .build()
        )
        .addProperty(
            PropertySpec.builder("ADVANCED_TOOL_NAMES", setOfString, KModifier.INTERNAL)
                .initializer(toolNameSetInitializer(tools.map { it.name }))
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

private fun buildCliOutputFormatEnum(): TypeSpec =
    TypeSpec.enumBuilder(CLI_OUTPUT_FORMAT)
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Generated CLI output formats declared by catalog output conversion metadata.\n")
        .addEnumConstant("JSON")
        .addEnumConstant("PRETTY")
        .addEnumConstant("RAW")
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

private fun buildCliToolRegistryObject(tools: List<ToolSpec>): TypeSpec {
    val listOfCliToolSpec = KOTLIN_LIST.parameterizedBy(CLI_TOOL_SPEC)
    return TypeSpec.objectBuilder("HexanaGeneratedCliToolRegistry")
        .addModifiers(KModifier.PUBLIC)
        .addKdoc("Generated registry for catalog-declared CLI-visible tools.\n")
        .addProperty(
            PropertySpec.builder("TOOLS", listOfCliToolSpec, KModifier.PUBLIC)
                .initializer(cliToolSpecsInitializer(tools.filter { it.surfaces.cli.visible }))
                .build()
        )
        .build()
}

private fun buildGeneratedCliToolCommandsFunction(tools: List<ToolSpec>): FunSpec {
    val visibleTools = tools
        .filter { it.surfaces.cli.visible }
        .filter { it.surfaces.cli.executionStatus == CliExecutionStatus.Executable }
        .sortedBy { it.surfaces.cli.commandName }
    visibleTools.forEach(::validateGeneratedExecutableCliToolShape)

    val listOfCliktCommand = KOTLIN_LIST.parameterizedBy(CLIKT_COMMAND)
    val function = FunSpec.builder("generatedCliToolCommands")
        .addModifiers(KModifier.INTERNAL)
        .addKdoc("Generated CLI tool commands derived from catalog CLI surface metadata.\n")
        .addParameter(ParameterSpec.builder("envReader", envReaderType()).build())
        .addParameter(ParameterSpec.builder("sink", commandSinkType()).build())
        .returns(listOfCliktCommand)

    val code = CodeBlock.builder()
    code.add("return listOf(\n")
    code.indent()
    visibleTools.forEach { spec ->
        code.add("GeneratedPathCliToolCommand(\n")
        code.indent()
        code.add("spec = %L,\n", cliToolSpecLookup(spec))
        code.add("pathArgumentName = %S,\n", spec.generatedPathArgumentName())
        code.add("pathHelp = %S,\n", spec.generatedPathHelp())
        code.add("envReader = envReader,\n")
        code.add("sink = sink,\n")
        code.unindent()
        code.add("),\n")
    }
    code.unindent()
    code.add(")\n")
    return function.addCode(code.build()).build()
}

private fun buildGeneratedPathCliToolCommandClass(): TypeSpec {
    val nullableInt = Int::class.asTypeName().copy(nullable = true)
    val stringList = KOTLIN_LIST.parameterizedBy(String::class.asTypeName())
    return TypeSpec.classBuilder("GeneratedPathCliToolCommand")
        .addModifiers(KModifier.PRIVATE)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("spec", CLI_TOOL_SPEC)
                .addParameter("pathArgumentName", String::class)
                .addParameter("pathHelp", String::class)
                .addParameter("envReader", envReaderType())
                .addParameter("sink", commandSinkType())
                .build()
        )
        .superclass(CLIKT_COMMAND)
        .addSuperclassConstructorParameter("name = spec.commandName")
        .addProperty(PropertySpec.builder("spec", CLI_TOOL_SPEC, KModifier.PRIVATE).initializer("spec").build())
        .addProperty(PropertySpec.builder("pathArgumentName", String::class, KModifier.PRIVATE).initializer("pathArgumentName").build())
        .addProperty(PropertySpec.builder("pathHelp", String::class, KModifier.PRIVATE).initializer("pathHelp").build())
        .addProperty(PropertySpec.builder("envReader", envReaderType(), KModifier.PRIVATE).initializer("envReader").build())
        .addProperty(PropertySpec.builder("sink", commandSinkType(), KModifier.PRIVATE).initializer("sink").build())
        .addFunction(
            FunSpec.builder("help")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("context", CLIKT_CONTEXT)
                .returns(String::class)
                .addStatement("return spec.summary")
                .build()
        )
        .addProperty(
            PropertySpec.builder("path", String::class, KModifier.PRIVATE)
                .delegate(
                    CodeBlock.of(
                        "%M(name = pathArgumentName, help = pathHelp)",
                        ARGUMENT,
                    )
                )
                .build()
        )
        .addProperty(
            PropertySpec.builder("format", CLI_OUTPUT_FORMAT, KModifier.PRIVATE)
                .delegate(
                    CodeBlock.builder()
                        .add("%M(\n", OPTION)
                        .indent()
                        .add("%S,\n", "--format")
                        .add(
                            "help = \"Output format. Supported: \${spec.supportedFormatNames()}. " +
                                "Defaults to \${spec.defaultOutputFormat.cliName()}.\",\n"
                        )
                        .unindent()
                        .add(").convert(\n")
                        .indent()
                        .add("metavar = spec.supportedFormatNames(),\n")
                        .add("completionCandidates = spec.supportedFormatCompletionCandidates(),\n")
                        .unindent()
                        .add(") {\n")
                        .indent()
                        .add("parseCliOutputFormat(spec, it)\n")
                        .unindent()
                        .add("}.default(spec.defaultOutputFormat)")
                        .build()
                )
                .build()
        )
        .addProperty(
            PropertySpec.builder("allowedRoots", stringList, KModifier.PRIVATE)
                .delegate(CodeBlock.of("allowedRootOption()"))
                .build()
        )
        .addProperty(optionProperty("maxFileBytes", nullableInt, "maxFileBytesOption()"))
        .addProperty(optionProperty("maxInlineBytes", nullableInt, "maxInlineBytesOption()"))
        .addProperty(optionProperty("maxTextFileBytes", nullableInt, "maxTextFileBytesOption()"))
        .addProperty(optionProperty("maxOutputBytes", nullableInt, "maxOutputBytesOption()"))
        .addProperty(optionProperty("maxCandidateCount", nullableInt, "maxCandidateCountOption()"))
        .addProperty(optionProperty("maxScanRoots", nullableInt, "maxScanRootsOption()"))
        .addProperty(optionProperty("maxScanDepth", nullableInt, "maxScanDepthOption()"))
        .addFunction(
            FunSpec.builder("run")
                .addModifiers(KModifier.OVERRIDE)
                .addCode(
                    CodeBlock.builder()
                        .add("sink(\n")
                        .indent()
                        .add("%T.ExecutableTool(\n", HEXANA_COMMAND)
                        .indent()
                        .add("spec = spec,\n")
                        .add("path = path,\n")
                        .add("format = format,\n")
                        .add("allowedRoots = allowedRoots + allowedRootsFromEnv(envReader),\n")
                        .add(
                            "resourceLimits = buildResourceLimits(\n" +
                                "  maxFileBytes = maxFileBytes,\n" +
                                "  maxInlineBytes = maxInlineBytes,\n" +
                                "  maxTextFileBytes = maxTextFileBytes,\n" +
                                "  maxOutputBytes = maxOutputBytes,\n" +
                                "  maxCandidateCount = maxCandidateCount,\n" +
                                "  maxScanRoots = maxScanRoots,\n" +
                                "  maxScanDepth = maxScanDepth,\n" +
                                "),\n"
                        )
                        .unindent()
                        .add(")\n")
                        .unindent()
                        .add(")\n")
                        .build()
                )
                .build()
        )
        .build()
}

private fun buildGeneratedTodoCliToolCommandClass(): TypeSpec =
    TypeSpec.classBuilder("GeneratedTodoCliToolCommand")
        .addModifiers(KModifier.PRIVATE)
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameter("spec", CLI_TOOL_SPEC)
                .addParameter("sink", commandSinkType())
                .build()
        )
        .superclass(CLIKT_COMMAND)
        .addSuperclassConstructorParameter("name = spec.commandName")
        .addProperty(PropertySpec.builder("spec", CLI_TOOL_SPEC, KModifier.PRIVATE).initializer("spec").build())
        .addProperty(PropertySpec.builder("sink", commandSinkType(), KModifier.PRIVATE).initializer("sink").build())
        .addProperty(
            PropertySpec.builder("treatUnknownOptionsAsArgs", Boolean::class, KModifier.OVERRIDE)
                .initializer("true")
                .build()
        )
        .addFunction(
            FunSpec.builder("help")
                .addModifiers(KModifier.OVERRIDE)
                .addParameter("context", CLIKT_CONTEXT)
                .returns(String::class)
                .addStatement("return spec.summary")
                .build()
        )
        .addProperty(
            PropertySpec.builder("ignoredArguments", KOTLIN_LIST.parameterizedBy(String::class.asTypeName()), KModifier.PRIVATE)
                .addAnnotation(
                    com.squareup.kotlinpoet.AnnotationSpec.builder(Suppress::class)
                        .addMember("%S", "unused")
                        .build()
                )
                .delegate(CodeBlock.of("%M(name = %S, help = %S).multiple()", ARGUMENT, "args", "Future command arguments."))
                .build()
        )
        .addFunction(
            FunSpec.builder("run")
                .addModifiers(KModifier.OVERRIDE)
                .addStatement("sink(%T.TodoTool(spec))", HEXANA_COMMAND)
                .build()
        )
        .build()

private fun buildGeneratedExecutableCliToolCommandOutputFunction(tools: List<ToolSpec>): FunSpec {
    val executableTools = tools
        .filter { it.surfaces.cli.visible }
        .filter { it.surfaces.cli.executionStatus == CliExecutionStatus.Executable }
        .sortedBy { it.name }
    executableTools.forEach(::validateGeneratedExecutableCliToolShape)

    val function = FunSpec.builder("generatedExecutableCliToolCommandOutput")
        .addModifiers(KModifier.INTERNAL)
        .addKdoc("Generated executable CLI renderer dispatch derived from catalog CLI surface metadata.\n")
        .addParameter(ParameterSpec.builder("command", HEXANA_EXECUTABLE_TOOL_COMMAND).build())
        .returns(HEXANA_CLI_COMMAND_OUTPUT)

    if (executableTools.isEmpty()) {
        return function.addStatement("error(%S)", "No executable CLI tools are generated.").build()
    }

    val code = CodeBlock.builder()
    code.add("return when (command.spec.toolName) {\n")
    code.indent()
    executableTools.forEach { spec ->
        val renderer = requireNotNull(spec.surfaces.cli.executableRenderer) {
            "Executable CLI tool '${spec.name}' is missing renderer metadata."
        }
        code.add("%S -> %N(command)\n", spec.name, renderer.methodName)
    }
    code.add("else -> error(\"Executable CLI tool '\${command.spec.toolName}' has no generated runtime renderer.\")\n")
    code.unindent()
    code.add("}\n")
    return function.addCode(code.build()).build()
}

private fun optionProperty(
    name: String,
    type: com.squareup.kotlinpoet.TypeName,
    delegate: String,
): PropertySpec =
    PropertySpec.builder(name, type, KModifier.PRIVATE)
        .delegate(CodeBlock.of(delegate))
        .build()

private fun cliToolSpecLookup(spec: ToolSpec): CodeBlock =
    CodeBlock.of("%T.TOOLS.single { it.toolName == %S }", ClassName(SERVER_PACKAGE, "HexanaGeneratedCliToolRegistry"), spec.name)

private fun envReaderType(): LambdaTypeName =
    LambdaTypeName.get(
        null,
        String::class.asTypeName(),
        returnType = String::class.asTypeName().copy(nullable = true),
    )

private fun commandSinkType(): LambdaTypeName =
    LambdaTypeName.get(
        null,
        HEXANA_COMMAND,
        returnType = Unit::class.asTypeName(),
    )

private fun validateGeneratedExecutableCliToolShape(spec: ToolSpec) {
    val cli = spec.surfaces.cli
    val commandName = requireNotNull(cli.commandName) {
        "Executable CLI tool '${spec.name}' is missing commandName."
    }
    val pathInputCapability = spec.executablePathInputCapability()
    require(pathInputCapability in spec.input.inputCapabilities) {
        "Executable CLI tool '${spec.name}' command '$commandName' must use ToolInputPattern.WasmOnly or " +
            "ToolInputPattern.BinaryOnly and support $pathInputCapability input for generated CLI command support."
    }
    requireNotNull(cli.defaultOutput) {
        "Executable CLI tool '${spec.name}' command '$commandName' must declare a default CLI output."
    }
    requireNotNull(cli.executableRenderer) {
        "Executable CLI tool '${spec.name}' command '$commandName' must declare executable renderer metadata."
    }
    require(spec.cliOutputFormats().isNotEmpty()) {
        "Executable CLI tool '${spec.name}' command '$commandName' must expose at least one CLI output format."
    }
}

private fun cliToolSpecsInitializer(tools: List<ToolSpec>): CodeBlock {
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

private fun cliToolSpecInitializer(spec: ToolSpec): CodeBlock {
    val cli = spec.surfaces.cli
    val commandName = requireNotNull(cli.commandName) { "Visible CLI tool '${spec.name}' is missing commandName." }
    val outputPolicy = requireNotNull(cli.outputPolicy) { "Visible CLI tool '${spec.name}' is missing outputPolicy." }
    val executionStatus = requireNotNull(cli.executionStatus) {
        "Visible CLI tool '${spec.name}' is missing executionStatus."
    }
    val outputFormats = spec.cliOutputFormats()
    require(outputFormats.isNotEmpty()) {
        "Visible CLI tool '${spec.name}' command '$commandName' has no supported CLI output format."
    }
    val defaultOutput = requireNotNull(cli.defaultOutput) {
        "Visible CLI tool '${spec.name}' command '$commandName' has no default CLI output format."
    }
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

private fun List<ToolSpec>.productionToolNames(): List<String> =
    filter { McpToolAudience.Production in it.surfaces.mcp.audiences }
        .map { it.name }

private fun toolNameSetInitializer(names: List<String>): CodeBlock {
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

private fun buildRegisterToolFunction(spec: ToolSpec): FunSpec {
    return FunSpec.builder(spec.registerFunctionName())
        .receiver(SERVER_CLASS)
        .addModifiers(KModifier.PRIVATE)
        .addParameter(ParameterSpec.builder("handlers", TOOL_HANDLERS).build())
        .addCode(renderAddToolCall(spec))
        .build()
}

private fun buildInputDefaultsObject(tools: List<ToolSpec>): TypeSpec? {
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

private fun renderAddToolCall(spec: ToolSpec): CodeBlock {
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

private fun ToolSpec.registerFunctionName(): String =
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

private fun ToolSpec.generatedCliInputModeNames(): List<String> =
    when (surfaces.cli.executionStatus) {
        CliExecutionStatus.Executable -> listOf(executablePathInputCapability().generatedCliInputModeName())
        CliExecutionStatus.Todo -> emptyList()
        null -> emptyList()
    }

private fun ToolSpec.executablePathInputCapability(): InputConversionCapability =
    when (inputPattern) {
        ToolInputPattern.WasmOnly -> InputConversionCapability.WasmPath
        ToolInputPattern.BinaryOnly -> InputConversionCapability.BinaryPath
        else -> throw IllegalArgumentException(
            "Executable CLI tool '$name' must use ToolInputPattern.WasmOnly or ToolInputPattern.BinaryOnly " +
                "for generated CLI command support, got ${inputPattern::class.simpleName}."
        )
    }

private fun ToolSpec.generatedPathArgumentName(): String =
    when (executablePathInputCapability()) {
        InputConversionCapability.WasmPath -> "wasm"
        InputConversionCapability.BinaryPath -> "binary"
        else -> error("Unsupported executable path input for '$name'.")
    }

private fun ToolSpec.generatedPathHelp(): String =
    when (executablePathInputCapability()) {
        InputConversionCapability.WasmPath -> "Path to a WebAssembly module."
        InputConversionCapability.BinaryPath -> "Path to a binary file."
        else -> error("Unsupported executable path input for '$name'.")
    }

private fun ToolSpec.cliOutputFormats(): List<OutputConversionCapability> {
    val cliOutput = surfaces.cli.outputOverride?.let(::listOf) ?: output.outputCapabilities.toList()
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
