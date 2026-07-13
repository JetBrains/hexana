package org.jetbrains.hexana.cli.core

import org.jetbrains.hexana.mcp.DefaultHexanaAnalysisService
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummarizeBinaryCliOwnershipTest {
    private val root = Path.of("..", "..").normalize()

    @Test
    fun generatedMetadataAndLegacyServerResolveStandardCommandsToNeutralAdapter() {
        val dsl = root.resolve("mcp/tools-generator/src/main/kotlin/org/jetbrains/hexana/mcp/tools/BinaryTools.kt")
            .readText() +
            root.resolve("mcp/tools-generator/src/main/kotlin/org/jetbrains/hexana/mcp/tools/ModuleTools.kt").readText() +
            root.resolve("mcp/tools-generator/src/main/kotlin/org/jetbrains/hexana/mcp/tools/ConverterTools.kt").readText()
        val generatedToolset = root.resolve(
            "mcp/server/src/main/gen/org/jetbrains/hexana/mcp/server/HexanaCliToolset.kt"
        ).readText()
        val generatedProducts = root.resolve(
            "mcp/server/src/main/gen/org/jetbrains/hexana/mcp/server/HexanaCliProducts.kt"
        ).readText()
        val serverAdapter = root.resolve(
            "mcp/server/src/main/kotlin/org/jetbrains/hexana/mcp/server/HexanaCliExecution.kt"
        ).readText()

        assertTrue(dsl.contains("""executableCliRenderer("renderSummarizeBinaryCliCommandOutput")"""))
        assertTrue(dsl.contains("""executableCliRenderer("renderSummarizeModuleCommandOutput")"""))
        assertTrue(dsl.contains("""executableCliRenderer("renderWasmToWatCommandOutput")"""))
        assertTrue(generatedToolset.contains(""""summarize_binary" -> renderSummarizeBinaryCliCommandOutput(command)"""))
        assertTrue(generatedToolset.contains(""""summarize_module" -> renderSummarizeModuleCommandOutput(command)"""))
        assertTrue(generatedToolset.contains(""""wasm_to_wat" -> renderWasmToWatCommandOutput(command)"""))
        assertTrue(generatedProducts.contains("renderSummarizeBinaryCliCommandOutput"))
        assertTrue(generatedProducts.contains("renderSummarizeModuleCommandOutput"))
        assertTrue(generatedProducts.contains("renderWasmToWatCommandOutput"))
        assertTrue(serverAdapter.contains("summarizeBinaryCli("))
        assertTrue(serverAdapter.contains("SummarizeBinaryCliRequest("))
        assertTrue(serverAdapter.contains("standardCli("))
        assertTrue(serverAdapter.contains("StandardCliRequest("))

        assertFalse(generatedToolset.contains(""""summarize_binary" -> renderSummarizeBinaryCommandOutput(command)"""))
        assertFalse(generatedProducts.contains("renderSummarizeBinaryCommandOutput"))
    }

    @Test
    fun legacyMcpSelectedStandardExecutablesHaveNeutralRuntimeClosure() {
        val selectedStandardTools = root.resolve(
            "mcp/server/src/main/gen/org/jetbrains/hexana/mcp/server/HexanaCliProducts.kt"
        ).readText()
            .productToolNames("HEXANA_MCP")
            .filterTo(sortedSetOf()) { it in STANDARD_ANALYSIS_TOOL_NAMES }

        assertEquals(STANDARD_ANALYSIS_TOOL_NAMES.toSortedSet(), selectedStandardTools)

        val registry = standardRuntimeRegistry(DefaultHexanaAnalysisService())
        selectedStandardTools.forEach { toolName ->
            val binding = registry.binding(toolName)
            assertTrue(
                binding.converter::class.qualifiedName.orEmpty().startsWith("org.jetbrains.hexana.cli.core."),
                "$toolName converter must be owned by cli:core",
            )
            assertTrue(
                binding.executor::class.qualifiedName.orEmpty().startsWith("org.jetbrains.hexana.cli.core."),
                "$toolName executor must be owned by cli:core",
            )
            assertTrue(
                binding.renderer::class.qualifiedName.orEmpty().startsWith("org.jetbrains.hexana.cli.core."),
                "$toolName renderer must be owned by cli:core",
            )
        }
    }

    @Test
    fun migratedStandardRuntimeImplementationDoesNotExistInServerSources() {
        val serverSources = listOf(
            root.resolve("mcp/server/src/main/kotlin/org/jetbrains/hexana/mcp/server/HexanaCliExecution.kt"),
        ).joinToString(separator = "\n") { it.readText() }

        listOf(
            "class SummarizeBinaryInputConverter",
            "class SummarizeModuleInputConverter",
            "class WasmToWatInputConverter",
            "class SummarizeBinaryExecutor",
            "class SummarizeModuleExecutor",
            "class WasmToWatExecutor",
            "object SummarizeBinaryJsonRenderer",
            "object SummarizeModuleRenderer",
            "object WasmToWatRenderer",
            "class ServerToolRuntimeRegistry",
            "fun executeSummarizeBinaryRuntime",
            "fun renderSummarizeModulePretty(result",
            "WasmInputBytesLoader(",
            "printWat(",
            "JsonElement.serializer(), request.result",
        ).forEach { forbidden ->
            assertFalse(
                serverSources.contains(forbidden),
                "Server sources must not own migrated standard runtime implementation: $forbidden",
            )
        }
    }

    @Test
    fun productClosureSelectsExactlyStandardCommandsAndRejectsScriptServiceAndTodoCommands() {
        val generatedProducts = root.resolve(
            "mcp/server/src/main/gen/org/jetbrains/hexana/mcp/server/HexanaCliProducts.kt"
        ).readText()
        val generatedToolset = root.resolve(
            "mcp/server/src/main/gen/org/jetbrains/hexana/mcp/server/HexanaCliToolset.kt"
        ).readText()

        assertTrue(generatedProducts.contains("""HexanaCliReleaseProduct.HEXANA_CLI to listOf("""))
        listOf("summarize_binary", "summarize_module", "wasm_to_wat").forEach { toolName ->
            assertTrue(generatedProducts.contains("HexanaCliProductToolSelection(\"$toolName\""))
            assertTrue(STANDARD_ANALYSIS_TOOL_NAMES.contains(toolName))
        }
        listOf("run_binary_script", "list_enabled_features", "get_entrypoint_policy", "stdio", "http", "doctor").forEach {
            assertFalse(
                generatedProducts.hexanaCliProductBlock().contains(it),
                "hexana-cli product closure must reject script, TODO, and service command: $it",
            )
        }

        Regex(
            pattern = """HexanaCliToolSpec\((.*?)\),""",
            options = setOf(RegexOption.DOT_MATCHES_ALL),
        ).findAll(generatedToolset)
            .map { it.groupValues[1] }
            .filter { it.contains("executionStatus = HexanaCliExecutionStatus.TODO") }
            .forEach { todoBlock ->
                val todoToolName = Regex("""toolName = "([^"]+)"""").find(todoBlock)!!.groupValues[1]
                val todoCommandName = Regex("""commandName = "([^"]+)"""").find(todoBlock)!!.groupValues[1]
                assertFalse(
                    generatedProducts.hexanaCliProductBlock().contains("HexanaCliProductToolSelection(\"$todoToolName\""),
                    "hexana-cli product closure must reject TODO tool: $todoToolName",
                )
                assertFalse(
                    generatedProducts.hexanaCliProductBlock().contains("\"$todoCommandName\""),
                    "hexana-cli product closure must reject TODO command: $todoCommandName",
                )
            }
    }

    private fun String.hexanaCliProductBlock(): String {
        val start = indexOf("HexanaCliReleaseProduct.HEXANA_CLI to listOf(")
        assertTrue(start >= 0)
        val end = indexOf("HexanaCliReleaseProduct.HEXANA_CLI_SCRIPT to listOf(", start)
        assertTrue(end > start)
        return substring(start, end)
    }

    private fun String.productToolNames(productName: String): List<String> {
        val start = indexOf("HexanaCliReleaseProduct.$productName to listOf(")
        assertTrue(start >= 0, "Generated product block $productName must exist")
        val remaining = substring(start)
        val nextProduct = Regex("""\n\s*HexanaCliReleaseProduct\.""").find(remaining, startIndex = 1)
        val end = nextProduct?.range?.first ?: remaining.indexOf("\n      )")
        assertTrue(end > 0, "Generated product block $productName must have an end")
        val block = remaining.substring(0, end)

        return Regex("""HexanaCliProductToolSelection\("([^"]+)""")
            .findAll(block)
            .map { it.groupValues[1] }
            .toList()
    }
}
