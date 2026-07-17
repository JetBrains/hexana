package org.jetbrains.hexana.mcp.server

import org.jetbrains.hexana.cli.metadata.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaGeneratedToolFilesTest {
    private val generatedPackageDir = generatedPackageDir()
    private val generatedMetadataPackageDir = generatedMetadataPackageDir()

    @Test
    fun handWrittenToolHandlersDoNotReintroduceLegacyDefaultConstants() {
        val handlers = Files.readString(handlerSourceFile())

        assertFalse(
            handlers.contains("LEGACY_"),
            "HexanaToolHandlers.kt must use generated HexanaToolInputDefaults instead of LEGACY_ defaults",
        )
    }

    @Test
    fun checkedInGeneratedToolFilesKeepClosedMcpAndCliSplit() {
        val generatedFiles = generatedKotlinFiles()

        assertEquals(
            listOf("HexanaCliToolset.kt", "HexanaToolDispatcher.kt"),
            generatedFiles.map { it.fileName.toString() },
        )

        val dispatcher = Files.readString(generatedPackageDir.resolve("HexanaToolDispatcher.kt"))
        val cliToolset = Files.readString(generatedPackageDir.resolve("HexanaCliToolset.kt"))
        val cliProducts = Files.readString(generatedMetadataPackageDir.resolve("HexanaCliProducts.kt"))
        val cliMetadata = Files.readString(generatedMetadataPackageDir.resolve("HexanaCliToolset.kt"))

        assertContainsAll(
            dispatcher,
            "HexanaToolDispatcher.kt",
            listOf(
                "public object HexanaMcpToolCompatibility",
                "internal object HexanaGeneratedMcpToolProfiles",
                "SCRIPTING_TOOL_NAMES",
                "internal object HexanaToolInputDefaults",
                "public fun Server.registerHexanaTools",
                "addTool(tool = Tool(",
                "request: CallToolRequest -> handlers.summarizeModule",
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
            cliProducts,
            "HexanaCliProducts.kt",
            listOf(
                "package org.jetbrains.hexana.cli.metadata",
                "public enum class HexanaCliReleaseProduct",
                "HEXANA_MCP(\"hexana-mcp\", \"hexana-mcp\")",
                "HEXANA_CLI(\"hexana-cli\", \"hexana-cli\")",
                "HEXANA_CLI_SCRIPT(\"hexana-cli-script\", \"hexana-cli-script\")",
                "public data class HexanaCliProductToolSelection",
                "public object HexanaGeneratedCliProducts",
                "HexanaCliProductToolSelection(\"summarize_binary\", \"summarize-binary\"",
                "HexanaCliProductToolSelection(\"run_binary_script\", \"run-script\"",
            ),
        )
        assertContainsNone(
            cliProducts,
            "HexanaCliProducts.kt",
            listOf(
                "org.jetbrains.hexana.mcp.server",
                "handlerFqn",
                "standaloneHandlerMethod",
                "executableRendererMethod",
                "renderRunBinaryScriptCommandOutput",
                "wasmToWat",
            ),
        )

        assertContainsAll(
            cliToolset,
            "HexanaCliToolset.kt",
            listOf(
                "package org.jetbrains.hexana.mcp.server",
                "import org.jetbrains.hexana.cli.metadata.HexanaCliOutputFormat",
                "import org.jetbrains.hexana.cli.metadata.HexanaCliToolSpec",
                "import org.jetbrains.hexana.cli.metadata.HexanaGeneratedCliToolRegistry",
                "private class GeneratedPathOnlyCliToolCommand",
                "private class GeneratedSourceCliToolCommand",
                "private class GeneratedTodoCliToolCommand",
                "internal fun generatedCliToolCommands",
                "internal fun generatedExecutableCliToolCommandOutput",
                "base64OptionNames = listOf(\"--binary-base64\", \"--bytes-base64\")",
                "base64OptionNames = listOf(\"--wasm-base64\", \"--bytes-base64\")",
                "private val bytesBase64: List<String> by",
                "if (bytesBase64.size > 1)",
                "stdin.readExplicitBytes(maxInlineBytes)",
                "maxInlineInputBytes = { it.maxInlineBinaryBytes }",
                "maxInlineInputBytes = { it.maxInlineWasmBytes }",
                "\"summarize_binary\" -> renderSummarizeBinaryCliCommandOutput(command)",
                "\"summarize_module\" -> renderSummarizeModuleCommandOutput(command)",
                "\"wasm_to_wat\" -> renderWasmToWatCommandOutput(command)",
            ),
        )
        assertContainsAll(
            cliMetadata,
            "HexanaCliToolset.kt",
            listOf(
                "package org.jetbrains.hexana.cli.metadata",
                "public data class HexanaCliToolSpec",
                "public object HexanaGeneratedCliToolRegistry",
                "public enum class HexanaCliInputMode",
                "public enum class HexanaCliExecutionStatus",
            ),
        )
        assertContainsNone(
            cliToolset,
            "HexanaCliToolset.kt",
            listOf(
                "public data class HexanaCliToolSpec",
                "public object HexanaGeneratedCliToolRegistry",
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
    fun compiledGeneratedCliProductsKeepExactExecutableInventoriesAndNeutralMetadata() {
        val expectedCommands = mapOf(
            HexanaCliReleaseProduct.HEXANA_MCP to
                setOf("summarize-binary", "summarize-module", "wasm-to-wat", "run-script"),
            HexanaCliReleaseProduct.HEXANA_CLI to
                setOf("summarize-binary", "summarize-module", "wasm-to-wat"),
            HexanaCliReleaseProduct.HEXANA_CLI_SCRIPT to setOf("run-script"),
        )
        val selections = HexanaGeneratedCliProducts.PRODUCTS

        assertEquals(expectedCommands.keys, selections.keys)
        expectedCommands.forEach { (product, commands) ->
            assertEquals(commands, selections.getValue(product).map { it.commandName }.toSet(), product.productId)
        }
        selections.values.flatten().forEach { selection ->
            assertTrue(selection.toolName.isNotBlank())
            assertTrue(selection.commandName.isNotBlank())
            assertTrue(selection.outputPolicy.isNotBlank())
            assertTrue(selection.defaultOutput.isNotBlank())
        }
        val cliProducts = Files.readString(generatedMetadataPackageDir.resolve("HexanaCliProducts.kt"))
        assertContainsNone(
            cliProducts,
            "HexanaCliProducts.kt",
            listOf(
                "org.jetbrains.hexana.mcp.server",
                "handlerFqn",
                "standaloneHandlerMethod",
                "executableRendererMethod",
            ),
        )
    }

    private fun generatedKotlinFiles(): List<Path> {
        val stream = Files.list(generatedPackageDir)
        return try {
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".kt") }
                .sorted()
                .toList()
        } finally {
            stream.close()
        }
    }

    private fun handlerSourceFile(): Path {
        val relative = Path.of("src/main/kotlin/org/jetbrains/hexana/mcp/server/HexanaToolHandlers.kt")
        val candidates = listOf(
            Path.of("").toAbsolutePath().resolve(relative),
            Path.of("").toAbsolutePath().resolve("mcp/server").resolve(relative),
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("Could not locate HexanaToolHandlers.kt under $candidates")
    }

    private fun generatedPackageDir(): Path {
        val relative = Path.of("src/main/gen/org/jetbrains/hexana/mcp/server")
        val candidates = listOf(
            Path.of("").toAbsolutePath().resolve(relative),
            Path.of("").toAbsolutePath().resolve("mcp/server").resolve(relative),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("Could not locate generated standalone tool package under $candidates")
    }

    private fun generatedMetadataPackageDir(): Path {
        val relative = Path.of("cli/metadata/src/main/gen/org/jetbrains/hexana/cli/metadata")
        val candidates = listOf(
            Path.of("").toAbsolutePath().resolve(relative),
            Path.of("").toAbsolutePath().resolve("..").resolve("..").resolve(relative),
        )
        return candidates.firstOrNull { Files.isDirectory(it) }
            ?: error("Could not locate generated CLI metadata package under $candidates")
    }

    private fun assertContainsAll(source: String, sourceName: String, fragments: List<String>) {
        fragments.forEach { fragment ->
            assertTrue(source.contains(fragment), "$sourceName must contain `$fragment`")
        }
    }

    private fun assertContainsNone(source: String, sourceName: String, fragments: List<String>) {
        fragments.forEach { fragment ->
            assertFalse(source.contains(fragment), "$sourceName must not contain `$fragment`")
        }
    }
}
