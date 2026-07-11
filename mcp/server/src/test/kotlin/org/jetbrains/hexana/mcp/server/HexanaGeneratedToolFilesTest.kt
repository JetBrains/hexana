package org.jetbrains.hexana.mcp.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaGeneratedToolFilesTest {
    private val generatedPackageDir = generatedPackageDir()

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

        assertContainsAll(
            dispatcher,
            "HexanaToolDispatcher.kt",
            listOf(
                "public object HexanaMcpToolCompatibility",
                "internal object HexanaGeneratedMcpToolProfiles",
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
            cliToolset,
            "HexanaCliToolset.kt",
            listOf(
                "public data class HexanaCliToolSpec",
                "public object HexanaGeneratedCliToolRegistry",
                "private class GeneratedPathOnlyCliToolCommand",
                "private class GeneratedSourceCliToolCommand",
                "private class GeneratedTodoCliToolCommand",
                "internal fun generatedCliToolCommands",
                "internal fun generatedExecutableCliToolCommandOutput",
                "base64OptionNames = listOf(\"--binary-base64\", \"--bytes-base64\")",
                "private val bytesBase64: List<String> by",
                "if (bytesBase64.size > 1)",
                "stdin.readExplicitBytes(maxInlineBytes)",
                "maxInlineInputBytes = { it.maxInlineBinaryBytes }",
                "\"summarize_binary\" -> renderSummarizeBinaryCommandOutput(command)",
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
