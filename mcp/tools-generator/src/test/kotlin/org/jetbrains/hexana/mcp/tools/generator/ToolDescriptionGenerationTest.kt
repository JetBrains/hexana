package org.jetbrains.hexana.mcp.tools.generator

import org.jetbrains.hexana.mcp.tools.BinaryInputClass
import org.jetbrains.hexana.mcp.tools.ClassSpec
import org.jetbrains.hexana.mcp.tools.CliExecutionStatus
import org.jetbrains.hexana.mcp.tools.OutputConversionCapability
import org.jetbrains.hexana.mcp.tools.StandaloneHandlerSpec
import org.jetbrains.hexana.mcp.tools.ToolInputPattern
import org.jetbrains.hexana.mcp.tools.ToolGroup
import org.jetbrains.hexana.mcp.tools.ToolOutputPolicy
import org.jetbrains.hexana.mcp.tools.ToolSpec
import org.jetbrains.hexana.mcp.tools.WasmInputClass
import org.jetbrains.hexana.mcp.tools.class_
import org.jetbrains.hexana.mcp.tools.executableCliRenderer
import org.jetbrains.hexana.mcp.tools.list
import org.jetbrains.hexana.mcp.tools.noMcpCliSurface
import org.jetbrains.hexana.mcp.tools.productionCliSurface
import org.jetbrains.hexana.mcp.tools.string
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolDescriptionGenerationTest {

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
            generatedFile = Path.of("org/jetbrains/hexana/mcp/server/HexanaToolDispatcher.kt"),
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
            buildDispatcherFile(listOf(spec))
        }

        assertTrue(error.message.orEmpty().contains("has no supported CLI output format"))
        assertTrue(error.message.orEmpty().contains("mcp-wrapper-only"))
    }

    @Test
    fun `standalone dispatcher excludes no MCP tools but keeps CLI registry metadata`() {
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

        val source = buildDispatcherFile(listOf(summarizeBinarySpec, converter)).toString()

        assertTrue(source.contains("commandName = \"wasm-to-wat\""))
        assertTrue(source.contains("toolName = \"wasm_to_wat\""))
        assertFalse(source.contains("registerWasmToWatTool"))
        assertFalse(source.contains("profile.includesTool(\"wasm_to_wat\")"))
        assertFalse(source.contains("name = \"wasm_to_wat\""))
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

        val source = buildDispatcherFile(listOf(summarizeModuleSpec, cliOnlyTodo)).toString()

        assertTrue(source.contains("public enum class HexanaCliExecutionStatus"))
        assertTrue(source.contains("executionStatus = HexanaCliExecutionStatus.EXECUTABLE"))
        assertTrue(source.contains("commandName = \"future-cli-only\""))
        assertTrue(source.contains("toolName = \"future_cli_only\""))
        assertTrue(source.contains("executionStatus = HexanaCliExecutionStatus.TODO"))
        assertFalse(source.contains("profile.includesTool(\"future_cli_only\")"))
    }

    @Test
    fun `standalone dispatcher generates CLI tool commands from catalog metadata`() {
        val cliOnlyTodo = summarizeBinarySpec.copy(
            name = "future_cli_only",
            surfaces = noMcpCliSurface("future-cli-only", ToolGroup.Binary, ToolOutputPolicy.ToolDefined),
        )

        val source = buildDispatcherFile(listOf(summarizeModuleSpec, cliOnlyTodo)).toString()

        assertTrue(source.contains("internal fun generatedCliToolCommands"))
        assertTrue(source.contains("GeneratedWasmPathCliToolCommand("))
        assertTrue(source.contains("spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.toolName == \"summarize_module\" }"))
        assertTrue(source.contains("GeneratedTodoCliToolCommand("))
        assertTrue(source.contains("spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.toolName == \"future_cli_only\" }"))
    }

    @Test
    fun `standalone dispatcher generates executable CLI renderer mapping from catalog metadata`() {
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

        val source = buildDispatcherFile(listOf(summarizeModuleSpec, converter)).toString()

        assertTrue(source.contains("internal fun generatedExecutableCliToolCommandOutput"))
        assertTrue(source.contains("\"summarize_module\" -> renderSummarizeModuleCommandOutput(command)"))
        assertTrue(source.contains("\"wasm_to_wat\" -> renderWasmToWatCommandOutput(command)"))
    }

    @Test
    fun `standalone dispatcher rejects executable CLI tools missing renderer metadata`() {
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
    fun `standalone dispatcher rejects executable CLI tools without generated command support`() {
        val executableBinary = summarizeBinarySpec.copy(
            surfaces = productionCliSurface(
                "summarize-binary",
                ToolGroup.Binary,
                ToolOutputPolicy.ToolDefined,
                executionStatus = CliExecutionStatus.Executable,
                executableRenderer = executableCliRenderer("renderSummarizeBinaryCommandOutput"),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            buildDispatcherFile(listOf(executableBinary)).toString()
        }

        assertTrue(error.message.orEmpty().contains("ToolInputPattern.WasmOnly"))
        assertTrue(error.message.orEmpty().contains("summarize_binary"))
    }

    private fun renderGeneratedSource(mainClassName: String, generatedFile: Path): String {
        val outputRoot = Files.createTempDirectory("hexana-tool-generator-test")
        return try {
            val main = Class.forName(mainClassName).getMethod("main", Array<String>::class.java)
            main.invoke(null, arrayOf(outputRoot.toString()) as Any)
            Files.readString(outputRoot.resolve(generatedFile))
        } catch (error: InvocationTargetException) {
            throw error.targetException
        } finally {
            outputRoot.toFile().deleteRecursively()
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
