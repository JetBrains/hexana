package org.jetbrains.hexana.mcp.tools.generator

import org.jetbrains.hexana.mcp.tools.BinaryInputClass
import org.jetbrains.hexana.mcp.tools.ClassSpec
import org.jetbrains.hexana.mcp.tools.CliExecutionStatus
import org.jetbrains.hexana.mcp.tools.OutputConversionCapability
import org.jetbrains.hexana.mcp.tools.StandaloneHandlerSpec
import org.jetbrains.hexana.mcp.tools.ToolInputModel
import org.jetbrains.hexana.mcp.tools.ToolInputPattern
import org.jetbrains.hexana.mcp.tools.ToolGroup
import org.jetbrains.hexana.mcp.tools.ToolOutputPolicy
import org.jetbrains.hexana.mcp.tools.ToolSpec
import org.jetbrains.hexana.mcp.tools.WasmInputClass
import org.jetbrains.hexana.mcp.tools.class_
import org.jetbrains.hexana.mcp.tools.executableCliRenderer
import org.jetbrains.hexana.mcp.tools.HexanaTools
import org.jetbrains.hexana.mcp.tools.list
import org.jetbrains.hexana.mcp.tools.noMcpCliSurface
import org.jetbrains.hexana.mcp.tools.productionCliSurface
import org.jetbrains.hexana.mcp.tools.scriptingHiddenCliSurface
import org.jetbrains.hexana.mcp.tools.string
import org.jetbrains.hexana.mcp.tools.toolInputModel
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolDescriptionGenerationTest {
    private val standaloneDispatcherPath = "org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt"
    private val standaloneCliToolsetPath = "org/jetbrains/hexana/mcp/server/HexanaCliToolset.kt"
    private val standaloneCliProductsPath = "org/jetbrains/hexana/mcp/server/HexanaCliProducts.kt"

    private val binarySummaryResult = class_(
        "BinarySummaryResult",
        kotlinPackage = "org.jetbrains.hexana.mcp.model.binaries",
    ) {
        string("format").describe("Detected binary format.")
    }
    private val queryBinaryInput = BinaryInputClass.with("QueryBinaryInput") {
        string("operation", default = "overview")
            .describe("Binary query operation.")
        nullable("entityTypes", of = list(of = string()))
            .describe("Binary record kinds used by the selected operation.")
            .maxItems(20)
        nullable("query", of = list(of = string()))
            .describe("Search terms used by the selected operation.")
        int("offset", default = 0)
            .describe("Zero-based result offset.")
            .minimum(0)
        int("limit", default = 25)
            .describe("Maximum number of records to return.")
            .minimum(1)
            .maximum(100)
        string("detail", default = "summary")
            .describe("Detail level for returned records.")
        int("maxBytes", default = 24_000)
            .describe("Maximum serialized record payload bytes.")
            .minimum(1)
            .maximum(60_000)
    }
    private val summarizeBinarySpec = ToolSpec(
        name = "summarize_binary",
        summary = "Summarize a binary.",
        guidance = null,
        input = BinaryInputClass,
        inputPattern = ToolInputPattern.BinaryOnly,
        output = binarySummaryResult,
        surfaces = productionCliSurface("summarize-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
        handlerFqn = "org.jetbrains.hexana.mcp.query.summarizeBinary",
    )
    private val queryBinarySpec = summarizeBinarySpec.copy(
        name = "query_binary",
        input = queryBinaryInput,
        inputPattern = ToolInputPattern.BoundedBinaryQuery,
        surfaces = productionCliSurface("query-binary", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
        handlerFqn = "org.jetbrains.hexana.mcp.query.queryBinary",
        standaloneHandler = StandaloneHandlerSpec.defaultForToolName("query_binary"),
    )
    private val summarizeModuleSpec = summarizeBinarySpec.copy(
        name = "summarize_module",
        input = WasmInputClass,
        inputPattern = ToolInputPattern.WasmOnly,
        surfaces = productionCliSurface(
            "summarize-module",
            ToolGroup.Module,
            ToolOutputPolicy.ToolDefined,
            executionStatus = CliExecutionStatus.Executable,
            executableRenderer = executableCliRenderer("renderSummarizeModuleCommandOutput"),
        ),
        handlerFqn = "org.jetbrains.hexana.mcp.query.readModuleSummary",
    )

    @Test
    fun `standalone dispatcher tool descriptions include DSL guidance`() {
        val source = renderGeneratedSource(
            mainClassName = "org.jetbrains.hexana.mcp.tools.generator.StandaloneToolsetMainKt",
            generatedFile = Path.of(standaloneDispatcherPath),
        )

        assertAggregateToolGuidance(source)
    }

    @Test
    fun `IDEA toolset descriptions include DSL guidance`() {
        val source = renderGeneratedSource(
            mainClassName = "org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt",
            generatedFile = Path.of("org/jetbrains/hexana/mcp/HexanaToolset.kt"),
        )

        assertAggregateToolGuidance(source)
    }

    @Test
    fun `IDEA toolset excludes scripting only tools while standalone emits them`() {
        val ideaSource = renderGeneratedSource(
            mainClassName = "org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt",
            generatedFile = Path.of("org/jetbrains/hexana/mcp/HexanaToolset.kt"),
        )
        val standalone = renderStandaloneSources()
        val dispatcher = standalone.sources.getValue(standaloneDispatcherPath)
        val cliToolset = standalone.sources.getValue(standaloneCliToolsetPath)

        assertFalse(ideaSource.contains("run_binary_script"))
        assertFalse(ideaSource.contains("runBinaryScript"))
        assertTrue(dispatcher.contains("profile.includesTool(\"run_binary_script\")"))
        assertTrue(dispatcher.contains("handlers.runBinaryScript(request.arguments)"))
        assertEquals(setOf("run_binary_script"), generatedProfileNames(dispatcher, "SCRIPTING_TOOL_NAMES"))
        assertTrue(cliToolset.contains("commandName = \"run-script\""))
        assertTrue(cliToolset.contains("toolName = \"run_binary_script\""))
        assertTrue(cliToolset.contains("optionName = \"--script\""))
        assertTrue(cliToolset.contains("optionName = \"--script-text\""))
        assertTrue(cliToolset.contains("\"run_binary_script\" -> renderRunBinaryScriptCommandOutput(command)"))
    }

    @Test
    fun `IDEA binary schemas expose path base64 and raw byte inputs`() {
        val schema = invokePrivateGeneratorFunction(
            className = "org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt",
            methodName = "buildIdeaInputSchema",
            parameterTypes = arrayOf(ToolSpec::class.java),
            args = arrayOf(queryBinarySpec),
        )

        val source = schema.toString()
        assertTrue(source.contains("\"path\""))
        assertTrue(source.contains("\"bytesBase64\""))
        assertTrue(source.contains("\"bytes\""))
        assertTrue(source.contains("\"operation\""))
        assertTrue(source.contains("\"maxBytes\""))
        assertFalse(source.contains("requiredProperties = setOf(\"path\")"))
    }

    @Test
    fun `IDEA binary bodies resolve generic binary providers instead of wasm files`() {
        val body = invokePrivateGeneratorFunction(
            className = "org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt",
            methodName = "buildToolBody",
            parameterTypes = arrayOf(ToolSpec::class.java),
            args = arrayOf(summarizeBinarySpec),
        )

        val source = body.toString()
        assertTrue(source.contains("requireBinaryProviderInput"))
        assertTrue(source.contains("val provider = input.provider"))
        assertTrue(source.contains("pathLabel = pathLabel"))
        assertFalse(source.contains("requireWasmFile"))
        assertFalse(source.contains("requireCoreWasmBinaryProvider"))
    }

    @Test
    fun `standalone binary schemas expose path base64 and raw byte inputs`() {
        val schema = invokePrivateGeneratorFunction(
            className = "org.jetbrains.hexana.mcp.tools.generator.StandaloneToolsetMainKt",
            methodName = "buildToolSchema",
            parameterTypes = arrayOf(ClassSpec::class.java, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            args = arrayOf(queryBinarySpec.input, true, true),
        )

        val source = schema.toString()
        assertTrue(source.contains("\"path\""))
        assertTrue(source.contains("\"bytesBase64\""))
        assertTrue(source.contains("\"bytes\""))
        assertTrue(source.contains("\"operation\""))
        assertTrue(source.contains("\"maxBytes\""))
    }

    @Test
    fun `standalone schemas emit JSON object fields as object schemas`() {
        val schema = invokePrivateGeneratorFunction(
            className = "org.jetbrains.hexana.mcp.tools.generator.StandaloneToolsetMainKt",
            methodName = "buildToolSchema",
            parameterTypes = arrayOf(ClassSpec::class.java, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            args = arrayOf(jsonPolicyInput(), true, true),
        )

        val source = schema.toString()
        assertTrue(source.contains("\"policyJson\""))
        assertTrue(source.contains("JsonPrimitive(\"object\")"))
    }

    @Test
    fun `IDEA schemas emit JSON object fields as object schemas`() {
        val schema = invokePrivateGeneratorFunction(
            className = "org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt",
            methodName = "buildToolSchema",
            parameterTypes = arrayOf(ClassSpec::class.java, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            args = arrayOf(jsonPolicyInput(), true, true),
        )

        val source = schema.toString()
        assertTrue(source.contains("\"policyJson\""))
        assertTrue(source.contains("JsonPrimitive(\"object\")"))
    }

    @Test
    fun `standalone CLI registry rejects visible tools without CLI output formats`() {
        val mcpWrapperOnlyResult = class_(
            "McpWrapperOnlyResult",
            kotlinPackage = "org.jetbrains.hexana.mcp.model.test",
        ) {
            outputCapabilities(OutputConversionCapability.McpWrapper)
            string("value").describe("Result value.")
        }
        val spec = summarizeBinarySpec.copy(
            output = mcpWrapperOnlyResult,
            surfaces = productionCliSurface("mcp-wrapper-only", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            buildCliToolsetFile(listOf(spec))
        }

        assertTrue(error.message.orEmpty().contains("has no supported CLI output format"))
        assertTrue(error.message.orEmpty().contains("mcp-wrapper-only"))
    }

    @Test
    fun `standalone generator declares exactly dispatcher and CLI toolset files`() {
        val files = buildStandaloneServerFiles(HexanaTools)

        assertEquals(
            listOf("HexanaToolDispatcher", "HexanaCliToolset", "HexanaCliProducts"),
            files.map { it.name },
        )
        assertEquals(
            List(3) { "org.jetbrains.hexana.mcp.server" },
            files.map { it.packageName },
        )
    }

    @Test
    fun `standalone generator writes CLI toolset separately from MCP dispatcher`() {
        val generated = renderStandaloneSources()
        val dispatcher = generated.sources.getValue(standaloneDispatcherPath)
        val cliToolset = generated.sources.getValue(standaloneCliToolsetPath)
        val cliProducts = generated.sources.getValue(standaloneCliProductsPath)

        assertEquals(
            listOf(standaloneCliProductsPath, standaloneCliToolsetPath, standaloneDispatcherPath),
            generated.files,
        )
        assertContainsAll(
            cliProducts,
            "HexanaCliProducts.kt",
            listOf(
                "public enum class HexanaCliReleaseProduct",
                "HEXANA_CLI_SCRIPT(\"hexana-cli-script\", \"hexana-cli-script\")",
                "public object HexanaGeneratedCliProducts",
                "\"run_binary_script\"",
                "\"renderRunBinaryScriptCommandOutput\"",
            ),
        )
        assertContainsAll(
            dispatcher,
            "HexanaToolDispatcher.kt",
            listOf(
                "package org.jetbrains.hexana.mcp.server",
                "public object HexanaMcpToolCompatibility",
                "internal object HexanaGeneratedMcpToolProfiles",
                "SCRIPTING_TOOL_NAMES",
                "internal object HexanaToolInputDefaults",
                "public fun Server.registerHexanaTools",
                "private fun Server.registerSummarizeModuleTool",
                "addTool(tool = Tool(",
                "inputSchema = ToolSchema(",
                "request: CallToolRequest -> handlers.summarizeModule",
                "profile.includesTool(\"summarize_module\")",
            ),
        )
        assertContainsNone(
            dispatcher,
            "HexanaToolDispatcher.kt",
            listOf(
                "HexanaCliToolSpec",
                "HexanaGeneratedCliToolRegistry",
                "HexanaCliInputMode",
                "HexanaCliOutputFormat",
                "HexanaCliToolGroup",
                "HexanaCliOutputPolicy",
                "HexanaCliExecutionStatus",
                "generatedCliToolCommands",
                "generatedExecutableCliToolCommandOutput",
                "GeneratedPathCliToolCommand",
                "GeneratedPathOnlyCliToolCommand",
                "GeneratedSourceCliToolCommand",
                "GeneratedTodoCliToolCommand",
                "CliktCommand",
                "com.github.ajalt.clikt",
            ),
        )
        assertContainsAll(
            cliToolset,
            "HexanaCliToolset.kt",
            listOf(
                "package org.jetbrains.hexana.mcp.server",
                "import com.github.ajalt.clikt.core.CliktCommand",
                "public data class HexanaCliToolSpec",
                "public object HexanaGeneratedCliToolRegistry",
                "private class GeneratedPathOnlyCliToolCommand",
                "private class GeneratedSourceCliToolCommand",
                "private class GeneratedTodoCliToolCommand",
                "internal fun generatedCliToolCommands",
                "internal fun generatedExecutableCliToolCommandOutput",
                "base64OptionNames = listOf(\"--binary-base64\", \"--bytes-base64\")",
                "base64OptionNames = listOf(\"--wasm-base64\", \"--bytes-base64\")",
                "HexanaCliExecutionStatus.EXECUTABLE",
                "HexanaCliExecutionStatus.TODO",
                "\"summarize_binary\" -> renderSummarizeBinaryCliCommandOutput(command)",
                "\"summarize_module\" -> renderSummarizeModuleCommandOutput(command)",
                "\"wasm_to_wat\" -> renderWasmToWatCommandOutput(command)",
            ),
        )
        assertContainsNone(
            cliToolset,
            "HexanaCliToolset.kt",
            listOf(
                "Server.registerHexanaTools",
                "HexanaMcpToolCompatibility",
                "HexanaGeneratedMcpToolProfiles",
                "HexanaToolInputDefaults",
                "ToolSchema",
                "CallToolRequest",
                "ToolAnnotations",
                "addTool(",
                "profile.includesTool",
                "io.modelcontextprotocol.kotlin.sdk.server.Server",
                "io.modelcontextprotocol.kotlin.sdk.types",
                "GeneratedPathCliToolCommand",
            ),
        )
    }

    @Test
    fun `standalone dispatcher keeps scripting profile separate from production and advanced`() {
        val scriptingSpec = summarizeBinarySpec.copy(
            name = "script_fixture",
            surfaces = scriptingHiddenCliSurface(),
        )

        val dispatcher = buildDispatcherFile(listOf(summarizeBinarySpec, scriptingSpec)).toString()

        assertEquals(setOf("summarize_binary"), generatedProfileNames(dispatcher, "PRODUCTION_TOOL_NAMES"))
        assertEquals(setOf("summarize_binary"), generatedProfileNames(dispatcher, "ADVANCED_TOOL_NAMES"))
        assertEquals(setOf("script_fixture"), generatedProfileNames(dispatcher, "SCRIPTING_TOOL_NAMES"))
        assertTrue(dispatcher.contains("profile.includesTool(\"script_fixture\")"))
    }

    @Test
    fun `standalone CLI generator maps executable binary sources to path stdin and base64`() {
        val executableBinary = summarizeBinarySpec.copy(
            surfaces = productionCliSurface(
                "summarize-binary",
                ToolGroup.Binary,
                ToolOutputPolicy.ToolDefined,
                executionStatus = CliExecutionStatus.Executable,
                executableRenderer = executableCliRenderer("renderSummarizeBinaryCliCommandOutput"),
            ),
        )

        val source = buildCliToolsetFile(listOf(executableBinary, summarizeModuleSpec)).toString()

        assertContainsAll(
            source,
            "HexanaCliToolset.kt",
            listOf(
                "toolName = \"summarize_binary\"",
                "inputModes = setOf(HexanaCliInputMode.BINARY_PATH, HexanaCliInputMode.STDIN)",
                "GeneratedSourceCliToolCommand(",
                "sourceSlotName = \"binary\"",
                "pathArgumentName = \"binary\"",
                "stdinOptionName = \"--stdin\"",
                "base64OptionNames = listOf(\"--binary-base64\", \"--bytes-base64\")",
                "autoStdin = true",
                "maxInlineInputBytes = { it.maxInlineBinaryBytes }",
                "private val bytesBase64: List<String> by",
                ".multiple()",
                "if (bytesBase64.size > 1)",
                "ToolInputSourceSelection.Path(path = pathSource, slotName = sourceSlotName)",
                "ToolInputSourceSelection.Base64(bytesBase64 = base64Source, slotName = sourceSlotName)",
                "ToolInputSourceSelection.StdinBytes(bytes = readExplicitStdinBytes(resourceLimits), slotName = sourceSlotName)",
                "stdin.readExplicitBytes(maxInlineBytes)",
                "\"summarize_binary\" -> renderSummarizeBinaryCliCommandOutput(command)",
                "toolName = \"summarize_module\"",
                "inputModes = setOf(HexanaCliInputMode.WASM_PATH, HexanaCliInputMode.STDIN)",
                "sourceSlotName = \"wasm\"",
                "pathArgumentName = \"wasm\"",
                "pathHelp = \"Path to a WebAssembly module, or '-' to read raw stdin bytes.\"",
                "base64OptionNames = listOf(\"--wasm-base64\", \"--bytes-base64\")",
                "maxInlineInputBytes = { it.maxInlineWasmBytes }",
            ),
        )
        assertTrue(source.contains("GeneratedPathOnlyCliToolCommand("))
        assertFalse(source.contains("GeneratedPathCliToolCommand"))
    }

    @Test
    fun `standalone CLI toolset keeps no MCP CLI registry metadata outside dispatcher`() {
        val converterResult = class_(
            "ConverterResult",
            kotlinPackage = "org.jetbrains.hexana.mcp.model.test",
        ) {
            outputCapabilities(OutputConversionCapability.SemanticJson, OutputConversionCapability.RawText)
            string("wat").describe("WAT text.")
        }
        val converter = summarizeBinarySpec.copy(
            name = "wasm_to_wat",
            output = converterResult,
            surfaces = noMcpCliSurface(
                "wasm-to-wat",
                ToolGroup.Converters,
                ToolOutputPolicy.ToolDefined,
                defaultOutput = OutputConversionCapability.RawText,
            ),
            handlerFqn = "org.jetbrains.hexana.mcp.server.wasmToWat",
        )

        val dispatcherSource = buildDispatcherFile(listOf(summarizeBinarySpec, converter)).toString()
        val cliSource = buildCliToolsetFile(listOf(summarizeBinarySpec, converter)).toString()

        assertFalse(dispatcherSource.contains("commandName = \"wasm-to-wat\""))
        assertFalse(dispatcherSource.contains("toolName = \"wasm_to_wat\""))
        assertFalse(dispatcherSource.contains("registerWasmToWatTool"))
        assertFalse(dispatcherSource.contains("profile.includesTool(\"wasm_to_wat\")"))
        assertFalse(dispatcherSource.contains("name = \"wasm_to_wat\""))
        assertTrue(cliSource.contains("commandName = \"wasm-to-wat\""))
        assertTrue(cliSource.contains("toolName = \"wasm_to_wat\""))
    }

    @Test
    fun `standalone dispatcher uses explicit standalone handler metadata`() {
        val custom = summarizeBinarySpec.copy(
            name = "custom_tool",
            surfaces = productionCliSurface("custom-tool", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
            standaloneHandler = StandaloneHandlerSpec("renamedStandaloneHandler"),
        )

        val source = buildDispatcherFile(listOf(custom)).toString()

        assertTrue(source.contains("handlers.renamedStandaloneHandler(request.arguments)"))
        assertFalse(source.contains("handlers.customTool(request.arguments)"))
    }

    @Test
    fun `standalone dispatcher derives default handler from copied tool name`() {
        val custom = summarizeBinarySpec.copy(
            name = "custom_tool",
            surfaces = productionCliSurface("custom-tool", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
        )

        val source = buildDispatcherFile(listOf(custom)).toString()

        assertTrue(source.contains("handlers.customTool(request.arguments)"))
        assertFalse(source.contains("handlers.summarizeBinary(request.arguments)"))
    }

    @Test
    fun `standalone CLI registry emits execution status independently from MCP visibility`() {
        val cliOnlyTodo = summarizeBinarySpec.copy(
            name = "future_cli_only",
            surfaces = noMcpCliSurface("future-cli-only", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
        )

        val source = buildCliToolsetFile(listOf(summarizeModuleSpec, cliOnlyTodo)).toString()

        assertTrue(source.contains("public enum class HexanaCliExecutionStatus"))
        assertTrue(source.contains("executionStatus = HexanaCliExecutionStatus.EXECUTABLE"))
        assertTrue(source.contains("inputModes = setOf(HexanaCliInputMode.WASM_PATH, HexanaCliInputMode.STDIN)"))
        assertTrue(source.contains("commandName = \"future-cli-only\""))
        assertTrue(source.contains("toolName = \"future_cli_only\""))
        assertTrue(source.contains("executionStatus = HexanaCliExecutionStatus.TODO"))
        assertTrue(source.contains("inputModes = setOf()"))
        assertFalse(source.contains("inputModes = setOf(HexanaCliInputMode.JSON_OBJECT"))
        assertFalse(source.contains("HexanaCliInputMode.WASM_FROM_STRING"))
        assertFalse(source.contains("profile.includesTool(\"future_cli_only\")"))
    }

    @Test
    fun `standalone CLI toolset generates commands only for executable catalog metadata`() {
        val cliOnlyTodo = summarizeBinarySpec.copy(
            name = "future_cli_only",
            surfaces = noMcpCliSurface("future-cli-only", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
        )

        val source = buildCliToolsetFile(listOf(summarizeModuleSpec, cliOnlyTodo)).toString()

        assertTrue(source.contains("internal fun generatedCliToolCommands"))
        assertTrue(source.contains("GeneratedSourceCliToolCommand("))
        val generatedCommands = source.substringAfter("internal fun generatedCliToolCommands")
            .substringBefore("internal fun generatedExecutableCliToolCommandOutput")
        assertTrue(generatedCommands.contains("spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.toolName == \"summarize_module\" }"))
        assertFalse(generatedCommands.contains("GeneratedTodoCliToolCommand("))
        assertFalse(generatedCommands.contains("spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.toolName == \"future_cli_only\" }"))
    }

    @Test
    fun `standalone CLI toolset generates executable CLI renderer mapping from catalog metadata`() {
        val converterResult = class_(
            "ConverterResult",
            kotlinPackage = "org.jetbrains.hexana.mcp.model.test",
        ) {
            outputCapabilities(OutputConversionCapability.SemanticJson, OutputConversionCapability.RawText)
            string("wat").describe("WAT text.")
        }
        val converter = summarizeModuleSpec.copy(
            name = "wasm_to_wat",
            output = converterResult,
            surfaces = noMcpCliSurface(
                "wasm-to-wat",
                ToolGroup.Converters,
                ToolOutputPolicy.ToolDefined,
                executionStatus = CliExecutionStatus.Executable,
                defaultOutput = OutputConversionCapability.RawText,
                executableRenderer = executableCliRenderer("renderWasmToWatCommandOutput"),
            ),
            handlerFqn = "org.jetbrains.hexana.mcp.server.wasmToWat",
        )

        val source = buildCliToolsetFile(listOf(summarizeModuleSpec, converter)).toString()

        assertTrue(source.contains("internal fun generatedExecutableCliToolCommandOutput"))
        assertTrue(source.contains("\"summarize_module\" -> renderSummarizeModuleCommandOutput(command)"))
        assertTrue(source.contains("\"wasm_to_wat\" -> renderWasmToWatCommandOutput(command)"))
    }

    @Test
    fun `standalone CLI toolset rejects executable CLI tools missing renderer metadata`() {
        val error = assertFailsWith<IllegalArgumentException> {
            productionCliSurface(
                "missing-renderer",
                ToolGroup.Module,
                ToolOutputPolicy.ToolDefined,
                executionStatus = CliExecutionStatus.Executable,
            )
        }

        assertTrue(error.message.orEmpty().contains("must declare an executable renderer"))
        assertTrue(error.message.orEmpty().contains("missing-renderer"))
    }

    @Test
    fun `standalone CLI toolset supports executable BinaryOnly source tools`() {
        val executableBinary = summarizeBinarySpec.copy(
            surfaces = productionCliSurface(
                "summarize-binary",
                ToolGroup.Binary,
                ToolOutputPolicy.ToolDefined,
                executionStatus = CliExecutionStatus.Executable,
                executableRenderer = executableCliRenderer("renderSummarizeBinaryCliCommandOutput"),
            ),
        )

        val source = buildCliToolsetFile(listOf(executableBinary)).toString()

        assertTrue(source.contains("inputModes = setOf(HexanaCliInputMode.BINARY_PATH, HexanaCliInputMode.STDIN)"))
        assertTrue(source.contains("GeneratedSourceCliToolCommand("))
        assertTrue(source.contains("pathArgumentName = \"binary\""))
        assertTrue(source.contains("pathHelp = \"Path to a binary file, or '-' to read raw stdin bytes.\""))
        assertTrue(source.contains("stdinOptionName = \"--stdin\""))
        assertTrue(source.contains("base64OptionNames = listOf(\"--binary-base64\", \"--bytes-base64\")"))
        assertTrue(source.contains("\"summarize_binary\" -> renderSummarizeBinaryCliCommandOutput(command)"))
    }

    @Test
    fun `standalone CLI toolset supports executable WasmOnly source tools`() {
        val source = buildCliToolsetFile(listOf(summarizeModuleSpec)).toString()

        assertTrue(source.contains("inputModes = setOf(HexanaCliInputMode.WASM_PATH, HexanaCliInputMode.STDIN)"))
        assertTrue(source.contains("GeneratedSourceCliToolCommand("))
        assertTrue(source.contains("pathArgumentName = \"wasm\""))
        assertTrue(source.contains("pathHelp = \"Path to a WebAssembly module, or '-' to read raw stdin bytes.\""))
        assertTrue(source.contains("stdinOptionName = \"--stdin\""))
        assertTrue(source.contains("base64OptionNames = listOf(\"--wasm-base64\", \"--bytes-base64\")"))
        assertTrue(source.contains("\"summarize_module\" -> renderSummarizeModuleCommandOutput(command)"))
    }

    @Test
    fun `standalone CLI toolset rejects executable CLI tools without generated command support`() {
        val executableQuery = queryBinarySpec.copy(
            surfaces = productionCliSurface(
                "query-binary",
                ToolGroup.Binary,
                ToolOutputPolicy.ToolDefined,
                executionStatus = CliExecutionStatus.Executable,
                executableRenderer = executableCliRenderer("renderQueryBinaryCommandOutput"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            buildCliToolsetFile(listOf(executableQuery)).toString()
        }

        assertTrue(error.message.orEmpty().contains("does not have a CLI input model"))
        assertTrue(error.message.orEmpty().contains("query_binary"))
    }

    @Test
    fun `standalone CLI registry can carry TODO explicit multi-source and param metadata`() {
        val futureCompare = futureCompareModulesSpec(twoWasmSourcesWithParamsModel())

        val source = buildCliToolsetFile(listOf(futureCompare)).toString()

        assertTrue(source.contains("commandName = \"compare-modules\""))
        assertTrue(source.contains("toolName = \"compare_modules\""))
        assertTrue(source.contains("executionStatus = HexanaCliExecutionStatus.TODO"))
        assertTrue(source.contains("inputModes = setOf()"))
        val generatedCommands = source.substringAfter("internal fun generatedCliToolCommands")
            .substringBefore("internal fun generatedExecutableCliToolCommandOutput")
        assertFalse(generatedCommands.contains("compare_modules"))
    }

    @Test
    fun `standalone executable CLI generator supports future binary script params`() {
        val executableScript = futureRunBinaryScriptSpec(binaryScriptInputModel()).copy(
            surfaces = productionCliSurface(
                "run-script",
                ToolGroup.Binary,
                ToolOutputPolicy.ToolDefined,
                executionStatus = CliExecutionStatus.Executable,
                executableRenderer = executableCliRenderer("renderRunBinaryScriptCommandOutput"),
            ),
        )

        val source = buildCliToolsetFile(listOf(executableScript)).toString()

        assertContainsAll(
            source,
            "HexanaCliToolset.kt",
            listOf(
                "toolName = \"run_binary_script\"",
                "GeneratedSourceCliToolCommand(",
                "sourceSlotName = \"binary\"",
                "paramBindings = listOf(",
                "targetField = \"scriptPath\"",
                "optionName = \"--script\"",
                "kind = GeneratedCliParamKind.STRING",
                "required = true",
                "targetField = \"maxFindings\"",
                "optionName = \"--max-findings\"",
                "kind = GeneratedCliParamKind.INT",
                "intDefault = 25",
                "targetField = \"dryRun\"",
                "optionName = \"--dry-run\"",
                "kind = GeneratedCliParamKind.BOOLEAN",
                "booleanDefault = false",
                "params = generatedCliParamValues(paramOptions)",
                "\"run_binary_script\" -> renderRunBinaryScriptCommandOutput(command)",
            ),
        )
    }

    @Test
    fun `standalone executable CLI generator rejects JSON params clearly`() {
        val executableCompare = futureCompareModulesSpec(singleWasmSourceWithJsonParamModel()).copy(
            surfaces = productionCliSurface(
                "compare-modules",
                ToolGroup.Module,
                ToolOutputPolicy.ToolDefined,
                executionStatus = CliExecutionStatus.Executable,
                executableRenderer = executableCliRenderer("renderCompareModulesCommandOutput"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            buildCliToolsetFile(listOf(executableCompare)).toString()
        }

        assertTrue(error.message.orEmpty().contains("only value params are supported"))
        assertTrue(error.message.orEmpty().contains("compare_modules"))
    }

    @Test
    fun `standalone executable CLI generator rejects multi-source models clearly`() {
        val executableCompare = futureCompareModulesSpec(twoWasmSourcesOnlyModel()).copy(
            surfaces = productionCliSurface(
                "compare-modules",
                ToolGroup.Module,
                ToolOutputPolicy.ToolDefined,
                executionStatus = CliExecutionStatus.Executable,
                executableRenderer = executableCliRenderer("renderCompareModulesCommandOutput"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            buildCliToolsetFile(listOf(executableCompare)).toString()
        }

        assertTrue(error.message.orEmpty().contains("must declare exactly one CLI source slot"))
        assertTrue(error.message.orEmpty().contains("current"))
        assertTrue(error.message.orEmpty().contains("baseline"))
    }

    private fun jsonPolicyInput(): ClassSpec =
        class_("JsonPolicyInput") {
            jsonObject("policyJson").describe("Policy JSON object.")
        }

    private fun futureCompareModulesSpec(inputModel: ToolInputModel): ToolSpec =
        summarizeModuleSpec.copy(
            name = "compare_modules",
            input = WasmInputClass.with("CompareModulesInput") {
                embed("current", WasmInputClass).describe("Current WebAssembly module source.")
                nullable("baseline", of = WasmInputClass).describe("Baseline WebAssembly module source.")
                int("limit", default = 25).describe("Maximum comparison findings.")
                string("query", default = "").describe("Optional query filter.")
                string("operation", default = "diff").describe("Comparison operation.")
                jsonObject("policyJson").describe("Comparison policy JSON object.")
            },
            inputModel = inputModel,
            output = class_("CompareModulesResult") {
                string("status").describe("Comparison status.")
            },
            surfaces = productionCliSurface("compare-modules", ToolGroup.Module, ToolOutputPolicy.ToolDefined),
            handlerFqn = "org.jetbrains.hexana.mcp.query.compareModules",
        )

    private fun twoWasmSourcesWithParamsModel(): ToolInputModel =
        toolInputModel {
            wasmSource("current") {
                cli {
                    positional("current")
                    stdin("--stdin")
                    base64("--current-base64")
                    autoStdin()
                }
            }
            wasmSource("baseline", required = false) {
                cli {
                    pathOption("--baseline")
                    stdin("--baseline-stdin")
                    base64("--baseline-base64")
                }
            }
            param(name = "limit", cliOption = "--limit")
            param(name = "query", cliOption = "--query")
            param(name = "operation", cliOption = "--operation")
            jsonParam(name = "policy", targetField = "policyJson", cliOption = "--policy-json")
        }

    private fun twoWasmSourcesOnlyModel(): ToolInputModel =
        toolInputModel {
            wasmSource("current") {
                cli {
                    positional("current")
                    stdin("--stdin")
                    base64("--current-base64")
                    autoStdin()
                }
            }
            wasmSource("baseline", required = false) {
                cli {
                    pathOption("--baseline")
                    stdin("--baseline-stdin")
                    base64("--baseline-base64")
                }
            }
        }

    private fun singleWasmSourceWithParamModel(): ToolInputModel =
        toolInputModel {
            wasmSource("current") {
                cli {
                    positional("current")
                    stdin("--stdin")
                    base64("--current-base64")
                    autoStdin()
                }
            }
            param(name = "limit", cliOption = "--limit")
        }

    private fun singleWasmSourceWithJsonParamModel(): ToolInputModel =
        toolInputModel {
            wasmSource("current") {
                cli {
                    positional("current")
                    stdin("--stdin")
                    base64("--current-base64")
                    autoStdin()
                }
            }
            jsonParam(name = "policy", targetField = "policyJson", cliOption = "--policy-json")
        }

    private fun binaryScriptInputModel(): ToolInputModel =
        toolInputModel {
            binarySource("binary") {
                cli {
                    positional("binary")
                    stdin("--stdin")
                    base64("--binary-base64")
                    autoStdin()
                }
            }
            param(name = "scriptPath", cliOption = "--script")
            param(name = "maxFindings", cliOption = "--max-findings")
            param(name = "dryRun", cliOption = "--dry-run")
        }

    private fun futureRunBinaryScriptSpec(inputModel: ToolInputModel): ToolSpec =
        summarizeBinarySpec.copy(
            name = "run_binary_script",
            input = BinaryInputClass.with("RunBinaryScriptInput") {
                embed("binary", BinaryInputClass).describe("Binary source.")
                string("scriptPath").describe("Required script file path.")
                int("maxFindings", default = 25).describe("Maximum finding count.")
                boolean("dryRun", default = false).describe("Validate without running mutations.")
            },
            inputModel = inputModel,
            output = class_("RunBinaryScriptResult") {
                string("status").describe("Script run status.")
            },
            surfaces = productionCliSurface("run-script", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
            handlerFqn = "org.jetbrains.hexana.mcp.query.runBinaryScript",
        )

    private data class RenderedStandaloneSources(
        val files: List<String>,
        val sources: Map<String, String>,
    )

    private fun renderStandaloneSources(): RenderedStandaloneSources {
        val outputRoot = Files.createTempDirectory("hexana-tool-generator-test")
        return try {
            invokeGeneratorMain("org.jetbrains.hexana.mcp.tools.generator.StandaloneToolsetMainKt", outputRoot)
            val files = listRegularFiles(outputRoot)
            RenderedStandaloneSources(
                files = files,
                sources = files.associateWith { relativePath ->
                    Files.readString(outputRoot.resolve(relativePath))
                },
            )
        } finally {
            outputRoot.toFile().deleteRecursively()
        }
    }

    private fun renderGeneratedSource(mainClassName: String, generatedFile: Path): String {
        val outputRoot = Files.createTempDirectory("hexana-tool-generator-test")
        return try {
            invokeGeneratorMain(mainClassName, outputRoot)
            Files.readString(outputRoot.resolve(generatedFile))
        } finally {
            outputRoot.toFile().deleteRecursively()
        }
    }

    private fun generatedProfileNames(source: String, propertyName: String): Set<String> {
        val marker = "internal val $propertyName: Set<String> = setOf("
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "Missing generated profile set $propertyName")
        val bodyStart = start + marker.length
        val bodyEnd = source.indexOf(")", bodyStart)
        assertTrue(bodyEnd > bodyStart, "Malformed generated profile set $propertyName")
        return Regex("\"([^\"]+)\"")
            .findAll(source.substring(bodyStart, bodyEnd))
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun invokeGeneratorMain(mainClassName: String, outputRoot: Path) {
        try {
            val main = Class.forName(mainClassName).getMethod("main", Array<String>::class.java)
            main.invoke(null, arrayOf(outputRoot.toString()) as Any)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun listRegularFiles(root: Path): List<String> {
        val stream = Files.walk(root)
        return try {
            stream
                .filter { Files.isRegularFile(it) }
                .map { root.relativize(it).toString().replace('\\', '/') }
                .sorted()
                .toList()
        } finally {
            stream.close()
        }
    }

    private fun assertAggregateToolGuidance(source: String) {
        assertBoundaryPhrase(
            source,
            "for previous/current build regression triage, call this once for each build's module, crash report, and source map",
            "resolve_wasm_frames should be described as aggregate frame triage",
        )
        assertBoundaryPhrase(
            source,
            "For crash reports, stack traces, top application frame selection, or previous/current frame regression analysis, call `resolve_wasm_frames` instead",
            "lower-level function/source tools should redirect crash-frame triage",
        )
        assertBoundaryPhrase(
            source,
            "This is source-location lookup only; it does not perform full frame triage",
            "resolve_source_locations should not look like full frame triage",
        )
        assertBoundaryPhrase(
            source,
            "Use this aggregate dependency-resolution workflow first when a component run/build/link step has a main component plus project root or candidate directories",
            "resolve_component_dependencies should mention transitive resolution",
        )
        assertBoundaryPhrase(
            source,
            "Prefer this over `list_component_surface`, `list_imports`, or `list_exports` for component provider selection",
            "resolve_component_dependencies should be favored over low-level inventories",
        )
        assertBoundaryPhrase(
            source,
            "Returns report-ready `required_non_runtime_imports`, `ignored_runtime_imports`, `resolved_dependency_paths`, `rejected_candidates`, and `minimal_run_build_fix` fields",
            "resolve_component_dependencies should be described as provider discovery",
        )
        assertBoundaryPhrase(
            source,
            "Use this first for component compatibility drift, compose failure logs, provider candidate triage, and dependency lock/config repair",
            "check_component_compatibility should be described as the compatibility drift workflow",
        )
        assertBoundaryPhrase(
            source,
            "Returns report-ready `failing_import`, `candidate_providers_inspected`, `provider_paths_involved`, `minimal_dependency_lock_fix`, `evidence`, and `recommendation` fields",
            "check_component_compatibility should advertise report-ready compatibility fields",
        )
        assertBoundaryPhrase(
            source,
            "For component run/link failures, project-root scans, or dependency resolution across candidate component files, call `resolve_component_dependencies` instead",
            "list_component_surface should stay separate from resolution workflows",
        )
        assertBoundaryPhrase(
            source,
            "For previous/current component drift, compose failure logs, provider-candidate compatibility, or lock/config repair, call `check_component_compatibility` instead",
            "list_component_surface should redirect compatibility drift workflows",
        )
        assertBoundaryPhrase(
            source,
            "Do not use this low-level function-import inventory as the main workflow for selecting component dependency files",
            "list_imports should redirect component dependency workflows",
        )
        assertBoundaryPhrase(
            source,
            "Do not use this low-level export inventory as the main workflow for selecting component dependency files",
            "list_exports should redirect component dependency workflows",
        )
    }

    private fun assertBoundaryPhrase(source: String, phrase: String, message: String) {
        assertTrue(source.contains(phrase), message)
    }

    private fun assertContainsAll(source: String, sourceName: String, fragments: List<String>) {
        fragments.forEach { fragment ->
            assertTrue(
                source.contains(fragment),
                "$sourceName must contain `$fragment`",
            )
        }
    }

    private fun assertContainsNone(source: String, sourceName: String, fragments: List<String>) {
        fragments.forEach { fragment ->
            assertFalse(
                source.contains(fragment),
                "$sourceName must not contain `$fragment`",
            )
        }
    }

    private fun invokePrivateGeneratorFunction(
        className: String,
        methodName: String,
        parameterTypes: Array<Class<*>>,
        args: Array<Any>,
    ): Any {
        val method = Class.forName(className).getDeclaredMethod(methodName, *parameterTypes)
        method.isAccessible = true
        return method.invoke(null, *args)
    }
}
