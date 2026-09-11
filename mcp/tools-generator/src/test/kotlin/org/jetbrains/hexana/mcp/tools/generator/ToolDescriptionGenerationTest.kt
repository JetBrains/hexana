package org.jetbrains.hexana.mcp.tools.generator

import org.jetbrains.hexana.mcp.tools.CliExecutionStatus
import org.jetbrains.hexana.mcp.tools.CliProductRegistry
import org.jetbrains.hexana.mcp.tools.CliProductSpec
import org.jetbrains.hexana.mcp.tools.HexanaCliTools
import org.jetbrains.hexana.mcp.tools.HexanaCliProductRegistry
import org.jetbrains.hexana.mcp.tools.HexanaIdeaTools
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneAuthority
import org.jetbrains.hexana.mcp.tools.HexanaStandaloneTools
import org.jetbrains.hexana.mcp.tools.HexanaTools
import org.jetbrains.hexana.mcp.tools.McpProductRegistry
import org.jetbrains.hexana.mcp.tools.McpProductSpec
import org.jetbrains.hexana.mcp.tools.McpProfileSpec
import org.jetbrains.hexana.mcp.tools.McpRuntimeBinding
import org.jetbrains.hexana.mcp.tools.ProfileId
import org.jetbrains.hexana.mcp.tools.ProductId
import org.jetbrains.hexana.mcp.tools.StandaloneHandlerSpec
import org.jetbrains.hexana.mcp.tools.StandaloneToolAuthority
import org.jetbrains.hexana.mcp.tools.mcpDescription
import org.jetbrains.hexana.mcp.tools.id
import org.jetbrains.hexana.mcp.tools.name
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
    private fun metadataSource(): String = buildCliMetadataToolsetFile(HexanaCliTools).toString()

    @Test
    fun `resolved IDEA and standalone generators preserve descriptions and surface isolation`() {
        val idea = ideaSource()
        val standalone = dispatcherSource()
        val representative = HexanaStandaloneTools.single { it.name == "query_artifacts" }

        assertFalse(idea.contains(representative.mcpDescription.lines().first()))
        assertTrue(standalone.contains(representative.mcpDescription.lines().first()))
        profile("default").toolIds.forEach { defaultTool ->
            assertTrue(idea.contains("name = \"${defaultTool.value}\""))
        }
        assertEquals(29, Regex("fullyQualifiedName =").findAll(idea).count())
        assertFalse(idea.contains("name = \"query_binary\""))
        assertFalse(idea.contains("name = \"list_binary_functions\""))
        assertFalse(idea.contains("name = \"query_artifacts\""))
        assertFalse(idea.contains("run_binary_script"))
        assertFalse(idea.contains("wasm_to_wat"))
        assertTrue(standalone.contains("handlers.runBinaryScript(request.arguments)"))
        assertFalse(standalone.contains("wasm_to_wat"))
    }

    @Test
    fun `IDEA descriptions reference only registered IDEA tools`() {
        val knownToolNames = HexanaTools.mapTo(mutableSetOf()) { it.name }
        val ideaToolNames = HexanaIdeaTools.mapTo(mutableSetOf()) { it.name }
        val toolReference = Regex("`([a-z][a-z0-9_]*)`")

        HexanaIdeaTools.forEach { tool ->
            val unavailableReferences = toolReference.findAll(tool.mcpDescription)
                .map { it.groupValues[1] }
                .filter { it in knownToolNames && it !in ideaToolNames }
                .toSet()
            assertTrue(
                unavailableReferences.isEmpty(),
                "IDEA tool '${tool.name}' references unavailable tools: $unavailableReferences",
            )
        }
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
        profile("default").toolIds.forEach { assertTrue(source.contains("\"${it.value}\"")) }
        profile("advanced").toolIds.forEach { assertTrue(source.contains("\"${it.value}\"")) }
        profile("scripting").toolIds.forEach { assertTrue(source.contains("\"${it.value}\"")) }
    }

    @Test
    fun `CLI metadata preserves executable and TODO separation`() {
        val metadata = metadataSource()

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
            standaloneAuthority(listOf(rebound), listOf(rebound.contract.id), emptyList(), emptyList()),
        ).toString()

        assertTrue(source.contains("handlers.customSummaryHandler(request.arguments)"))
        assertFalse(source.contains("handlers.summarizeModule(request.arguments)"))
    }

    @Test
    fun `standalone generator uses only validated authority profile registrations`() {
        val registered = HexanaStandaloneTools.first()
        val unregistered = HexanaStandaloneTools.last()
        val minimal = standaloneAuthority(listOf(registered), listOf(registered.contract.id), emptyList(), emptyList())

        val source = buildDispatcherFile(minimal).toString()

        assertTrue(source.contains("\"${registered.name}\""))
        assertFalse(source.contains("\"${unregistered.name}\""))

        val error = assertFailsWith<IllegalArgumentException> {
            standaloneAuthority(listOf(registered), listOf(unregistered.contract.id), emptyList(), emptyList())
        }

        assertTrue(error.message.orEmpty().contains("profile 'default' references unregistered tool IDs"))
        assertTrue(error.message.orEmpty().contains(unregistered.name))
    }

    @Test
    fun `executable CLI bindings require an explicit input model`() {
        val binding = HexanaCliTools.single { it.name == "summarize_binary" }.binding

        val inputError = assertFailsWith<IllegalArgumentException> {
            binding.copy(inputModel = null)
        }

        assertTrue(inputError.message.orEmpty().contains("must declare an explicit input model"))
    }

    @Test
    fun `standalone generator emits server and metadata artifacts separately`() {
        val serverFiles = buildStandaloneServerFiles(HexanaStandaloneAuthority)
        val metadataFiles = buildStandaloneCliMetadataFiles(HexanaCliTools)
        assertEquals(listOf("HexanaToolDispatcher"), serverFiles.map { it.name })
        assertEquals(setOf("HexanaCliToolset", "HexanaCliProducts"), metadataFiles.map { it.name }.toSet())
        assertTrue(serverFiles.single { it.name == "HexanaToolDispatcher" }.toString().contains("registerHexanaTools"))
        assertFalse(metadataFiles.joinToString().contains("registerHexanaTools"))
    }

    @Test
    fun `CLI metadata emits declared fixture products without a closed enum`() {
        val fixture = CliProductSpec(ProductId("fixture-cli"), "fixture-cli", "Non-published fixture product.")
        val products = CliProductRegistry(HexanaCliProductRegistry.products + fixture)
        val target = HexanaCliTools.first { it.binding.executionStatus == CliExecutionStatus.Executable }
        val tools = HexanaCliTools.map { tool ->
            if (tool.id == target.id) tool.copy(binding = tool.binding.copy(products = setOf(fixture.id))) else tool
        }

        val source = buildCliProductSelectionFile(tools, products).toString()

        assertTrue(source.contains("FIXTURE_CLI: HexanaCliProductDescriptor"))
        assertTrue(source.contains("\"fixture-cli\" to listOf("))
        assertFalse(source.contains("enum class HexanaCliReleaseProduct"))
        assertFalse(source.contains("when ("))
    }

    private fun profile(id: String) = HexanaStandaloneAuthority.profile(ProfileId(id))

    private fun standaloneAuthority(
        registrations: List<org.jetbrains.hexana.mcp.tools.StandaloneTool>, default: List<org.jetbrains.hexana.mcp.tools.ToolId>,
        advanced: List<org.jetbrains.hexana.mcp.tools.ToolId>, scripting: List<org.jetbrains.hexana.mcp.tools.ToolId>,
    ): StandaloneToolAuthority {
        val product = McpProductSpec(
            ProductId("fixture-mcp"),
            listOf(McpProfileSpec(ProfileId("default"), default), McpProfileSpec(ProfileId("advanced"), advanced), McpProfileSpec(ProfileId("scripting"), scripting)),
            ProfileId("default"), true,
        )
        return StandaloneToolAuthority(registrations, McpProductRegistry(listOf(product)), McpRuntimeBinding(product.id))
    }

}
