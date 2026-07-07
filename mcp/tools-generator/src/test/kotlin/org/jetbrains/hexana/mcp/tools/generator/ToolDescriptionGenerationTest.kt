package org.jetbrains.hexana.mcp.tools.generator

import org.jetbrains.hexana.mcp.tools.BinaryInputClass
import org.jetbrains.hexana.mcp.tools.ClassSpec
import org.jetbrains.hexana.mcp.tools.OutputConversionCapability
import org.jetbrains.hexana.mcp.tools.ToolInputPattern
import org.jetbrains.hexana.mcp.tools.ToolGroup
import org.jetbrains.hexana.mcp.tools.ToolOutputPolicy
import org.jetbrains.hexana.mcp.tools.ToolSpec
import org.jetbrains.hexana.mcp.tools.class_
import org.jetbrains.hexana.mcp.tools.list
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
