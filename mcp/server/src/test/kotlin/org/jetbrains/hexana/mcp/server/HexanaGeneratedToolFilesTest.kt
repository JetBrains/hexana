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
    fun checkedInServerGenerationContainsOnlyTheMcpDispatcher() {
        val generatedFiles = generatedKotlinFiles()

        assertEquals(listOf("HexanaToolDispatcher.kt"), generatedFiles.map { it.fileName.toString() })

        val dispatcher = Files.readString(generatedFiles.single())
        assertContainsAll(
            dispatcher,
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
            listOf(
                "org.jetbrains.hexana.cli",
                "HexanaCliToolSpec",
                "HexanaGeneratedCliToolRegistry",
                "generatedCliToolCommands",
                "generatedExecutableCliToolCommandOutput",
                "CliktCommand",
                "com.github.ajalt.clikt",
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

    private fun assertContainsAll(source: String, fragments: List<String>) {
        fragments.forEach { fragment ->
            assertTrue(source.contains(fragment), "HexanaToolDispatcher.kt must contain `$fragment`")
        }
    }

    private fun assertContainsNone(source: String, fragments: List<String>) {
        fragments.forEach { fragment ->
            assertFalse(source.contains(fragment), "HexanaToolDispatcher.kt must not contain `$fragment`")
        }
    }
}
