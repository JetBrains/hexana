package org.jetbrains.hexana.mcp.tools.generator

import org.jetbrains.hexana.mcp.tools.BinaryInputClass
import org.jetbrains.hexana.mcp.tools.CliExecutionStatus
import org.jetbrains.hexana.mcp.tools.CliReleaseProduct
import org.jetbrains.hexana.mcp.tools.CliTool
import org.jetbrains.hexana.mcp.tools.HexanaCliTools
import org.jetbrains.hexana.mcp.tools.HexanaIdeaTools
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneAuthority
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneAdvancedToolIds
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneDefaultToolIds
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneScriptingToolIds
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneTools
import org.jetbrains.hexana.mcp.tools.ParamBindingKind
import org.jetbrains.hexana.mcp.tools.ParamBindingSpec
import org.jetbrains.hexana.mcp.tools.SourceTargetSpec
import org.jetbrains.hexana.mcp.tools.StandaloneHandlerSpec
import org.jetbrains.hexana.mcp.tools.StandaloneToolAuthority
import org.jetbrains.hexana.mcp.tools.ToolGroup
import org.jetbrains.hexana.mcp.tools.ToolInputModel
import org.jetbrains.hexana.mcp.tools.ToolOutputPolicy
import org.jetbrains.hexana.mcp.tools.WasmInputClass
import org.jetbrains.hexana.mcp.tools.class_
import org.jetbrains.hexana.mcp.tools.cliToolBinding
import org.jetbrains.hexana.mcp.tools.executableCliRenderer
import org.jetbrains.hexana.mcp.tools.mcpDescription
import org.jetbrains.hexana.mcp.tools.name
import org.jetbrains.hexana.mcp.tools.tools
import org.jetbrains.hexana.mcp.tools.validateCliBinding
import org.jetbrains.hexana.mcp.tools.wasmCliInputModel
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolDescriptionGenerationTest {
    private fun ideaSource(): String {
        val output = Files.createTempDirectory("hexana-idea-generator")
        val method = Class.forName("org.jetbrains.hexana.mcp.tools.generator.IdeaToolsetMainKt")
            .getDeclaredMethod("main", Array<String>::class.java)
        method.invoke(null, arrayOf(output.toString()))
        return Files.readString(output.resolve("org/jetbrains/hexana/mcp/HexanaToolset.kt"))
    }

    private fun dispatcherSource(): String = buildDispatcherFile(HexanaStandaloneAuthority).toString()
    private fun cliSource(): String = buildCliToolsetFile(HexanaCliTools).toString()
    private fun metadataSource(): String = buildCliMetadataToolsetFile(HexanaCliTools).toString()

    @Test
    fun `resolved IDEA and standalone generators preserve descriptions and surface isolation`() {
        val idea = ideaSource()
        val standalone = dispatcherSource()
        val representative = HexanaIdeaTools.single { it.name == "query_artifacts" }

        assertTrue(idea.contains(representative.mcpDescription.lines().first()))
        assertTrue(standalone.contains(representative.mcpDescription.lines().first()))
        assertFalse(idea.contains("run_binary_script"))
        assertFalse(idea.contains("wasm_to_wat"))
        assertTrue(standalone.contains("handlers.runBinaryScript(request.arguments)"))
        assertFalse(standalone.contains("wasm_to_wat"))
    }

    @Test
    fun `IDEA binary schemas and bodies preserve generic binary behavior`() {
        val idea = ideaSource()
        assertTrue(idea.contains("name = \"summarize_binary\""))
        assertTrue(idea.contains("\"bytesBase64\""))
        assertTrue(idea.contains("\"bytes\""))
        assertTrue(idea.contains("requireBinaryProviderInput"))
        assertTrue(idea.contains("val provider = input.provider"))
    }

    @Test
    fun `standalone schemas preserve binary and JSON object shapes`() {
        val dispatcher = dispatcherSource()
        assertTrue(dispatcher.contains("\"bytesBase64\""))
        assertTrue(dispatcher.contains("\"bytes\""))
        assertTrue(dispatcher.contains("buildJsonObject"))
        assertTrue(dispatcher.contains("maxItems"))
    }

    @Test
    fun `standalone profiles preserve explicit disjoint membership`() {
        val source = dispatcherSource()
        assertTrue(source.contains("DEFAULT_TOOL_NAMES"))
        assertTrue(source.contains("ADVANCED_TOOL_NAMES"))
        assertTrue(source.contains("SCRIPTING_TOOL_NAMES"))
        HexanaStandaloneDefaultToolIds.forEach { assertTrue(source.contains("\"${it.value}\"")) }
        HexanaStandaloneAdvancedToolIds.forEach { assertTrue(source.contains("\"${it.value}\"")) }
        HexanaStandaloneScriptingToolIds.forEach { assertTrue(source.contains("\"${it.value}\"")) }
    }

    @Test
    fun `standalone CLI generation preserves explicit source params renderers and TODO separation`() {
        val cli = cliSource()
        val metadata = metadataSource()
        assertTrue(cli.contains("binary-base64"))
        assertTrue(cli.contains("wasm-base64"))
        assertTrue(cli.contains("optionName = \"--script\""))
        assertTrue(cli.contains("optionName = \"--script-text\""))
        assertTrue(cli.contains("\"run_binary_script\" -> renderRunBinaryScriptCommandOutput(command)"))
        assertTrue(cli.contains("\"wasm_to_wat\" -> renderWasmToWatCommandOutput(command)"))
        assertFalse(cli.contains("GeneratedSourceCliToolCommand(\n    spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.toolName == \"list_functions\""))
        assertTrue(metadata.contains("executionStatus = HexanaCliExecutionStatus.TODO"))
        assertEquals(4, HexanaCliTools.count { it.binding.executionStatus == CliExecutionStatus.Executable })
    }

    @Test
    fun `standalone dispatcher uses the explicit handler binding`() {
        val original = HexanaStandaloneTools.single { it.name == "summarize_module" }
        val rebound = original.copy(
            binding = original.binding.copy(handler = StandaloneHandlerSpec("customSummaryHandler")),
        )

        val source = buildDispatcherFile(
            StandaloneToolAuthority(
                registrations = listOf(rebound),
                defaultToolIds = listOf(rebound.contract.id),
                advancedToolIds = emptyList(),
                scriptingToolIds = emptyList(),
            ),
        ).toString()

        assertTrue(source.contains("handlers.customSummaryHandler(request.arguments)"))
        assertFalse(source.contains("handlers.summarizeModule(request.arguments)"))
    }

    @Test
    fun `standalone generator uses only validated authority profile registrations`() {
        val registered = HexanaStandaloneTools.first()
        val unregistered = HexanaStandaloneTools.last()
        val minimal = StandaloneToolAuthority(
            registrations = listOf(registered),
            defaultToolIds = listOf(registered.contract.id),
            advancedToolIds = emptyList(),
            scriptingToolIds = emptyList(),
        )

        val source = buildDispatcherFile(minimal).toString()

        assertTrue(source.contains("\"${registered.name}\""))
        assertFalse(source.contains("\"${unregistered.name}\""))

        val error = assertFailsWith<IllegalArgumentException> {
            minimal.copy(
                defaultToolIds = listOf(unregistered.contract.id),
            )
        }

        assertTrue(error.message.orEmpty().contains("default profile references unregistered tool IDs"))
        assertTrue(error.message.orEmpty().contains(unregistered.name))
    }

    @Test
    fun `executable CLI bindings require a renderer and explicit input model`() {
        val binding = HexanaCliTools.single { it.name == "summarize_binary" }.binding

        val rendererError = assertFailsWith<IllegalArgumentException> {
            binding.copy(executableRenderer = null)
        }
        val inputError = assertFailsWith<IllegalArgumentException> {
            binding.copy(inputModel = null)
        }

        assertTrue(rendererError.message.orEmpty().contains("must declare an executable renderer"))
        assertTrue(inputError.message.orEmpty().contains("must declare an explicit input model"))
    }

    @Test
    fun `standalone CLI generator preserves binary and Wasm source commands`() {
        val source = buildCliToolsetFile(
            HexanaCliTools.filter { it.name == "summarize_binary" || it.name == "summarize_module" },
        ).toString()

        assertTrue(source.contains("pathArgumentName = \"binary\""))
        assertTrue(source.contains("base64OptionNames = listOf(\"--binary-base64\", \"--bytes-base64\")"))
        assertTrue(source.contains("pathArgumentName = \"wasm\""))
        assertTrue(source.contains("base64OptionNames = listOf(\"--wasm-base64\", \"--bytes-base64\")"))
        assertTrue(source.contains("\"summarize_binary\" -> renderSummarizeBinaryCliCommandOutput(command)"))
        assertTrue(source.contains("\"summarize_module\" -> renderSummarizeModuleCommandOutput(command)"))
    }

    @Test
    fun `standalone executable CLI generator rejects JSON params clearly`() {
        val model = wasmCliInputModel().copy(
            params = listOf(
                ParamBindingSpec(
                    name = "policy",
                    kind = ParamBindingKind.Json,
                    targetField = "policyJson",
                    cliOption = "--policy-json",
                )
            ),
        )
        val tool = executableCompareModulesTool(model, includeBaseline = false)

        val error = assertFailsWith<IllegalArgumentException> {
            buildCliToolsetFile(listOf(tool)).toString()
        }

        assertTrue(error.message.orEmpty().contains("only value params are supported"))
        assertTrue(error.message.orEmpty().contains("compare_modules"))
    }

    @Test
    fun `standalone executable CLI generator rejects multi-source models clearly`() {
        val model = ToolInputModel(
            sources = listOf(
                wasmCliInputModel().sources.single().copy(
                    name = "current",
                    target = SourceTargetSpec.Field("current"),
                ),
                wasmCliInputModel().sources.single().copy(
                    name = "baseline",
                    target = SourceTargetSpec.Field("baseline"),
                    required = false,
                    cli = wasmCliInputModel().sources.single().cli.copy(
                        positionalPathName = null,
                        pathOption = "--baseline",
                        stdinOption = "--baseline-stdin",
                        base64Option = "--baseline-base64",
                        autoStdin = false,
                    ),
                ),
            ),
            params = emptyList(),
        )
        val tool = executableCompareModulesTool(model, includeBaseline = true)

        val error = assertFailsWith<IllegalArgumentException> {
            buildCliToolsetFile(listOf(tool)).toString()
        }

        assertTrue(error.message.orEmpty().contains("must declare exactly one CLI source slot"))
        assertTrue(error.message.orEmpty().contains("current"))
        assertTrue(error.message.orEmpty().contains("baseline"))
    }

    @Test
    fun `standalone generator emits server and metadata artifacts separately`() {
        val serverFiles = buildStandaloneServerFiles(HexanaStandaloneAuthority, HexanaCliTools)
        val metadataFiles = buildStandaloneCliMetadataFiles(HexanaCliTools)
        assertEquals(setOf("HexanaToolDispatcher", "HexanaCliToolset"), serverFiles.map { it.name }.toSet())
        assertEquals(setOf("HexanaCliToolset", "HexanaCliProducts"), metadataFiles.map { it.name }.toSet())
        assertTrue(serverFiles.single { it.name == "HexanaToolDispatcher" }.toString().contains("registerHexanaTools"))
        assertFalse(metadataFiles.joinToString().contains("registerHexanaTools"))
    }

    private fun executableCompareModulesTool(inputModel: ToolInputModel, includeBaseline: Boolean): CliTool {
        val contract = tools {
            tool("compare_modules") {
                summary = "Compare WebAssembly modules."
                input = WasmInputClass.with("CompareModulesInput") {
                    if (includeBaseline) {
                        embed("current", WasmInputClass).describe("Current WebAssembly module source.")
                        nullable("baseline", of = WasmInputClass).describe("Baseline WebAssembly module source.")
                    }
                    jsonObject("policyJson").describe("Comparison policy JSON object.")
                }
                output = class_("CompareModulesResult") {
                    string("status").describe("Comparison status.")
                }
            }
        }.single()
        val binding = cliToolBinding(
            "compare_modules",
            "compare-modules",
            ToolGroup.Module,
            ToolOutputPolicy.ToolDefined,
            executionStatus = CliExecutionStatus.Executable,
            executableRenderer = executableCliRenderer("renderCompareModulesCommandOutput"),
            releaseProducts = setOf(CliReleaseProduct.HexanaMcp, CliReleaseProduct.HexanaCli),
            inputModel = inputModel,
        )
        validateCliBinding(contract, binding)
        return CliTool(contract, binding)
    }

}
