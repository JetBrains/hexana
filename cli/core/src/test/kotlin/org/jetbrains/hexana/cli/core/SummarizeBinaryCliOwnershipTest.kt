package org.jetbrains.hexana.cli.core

import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SummarizeBinaryCliOwnershipTest {
    private val root = Path.of("..", "..").normalize()

    @Test
    fun generatedMetadataAndLegacyServerResolveToNeutralAdapter() {
        val dsl = root.resolve("mcp/tools-generator/src/main/kotlin/org/jetbrains/hexana/mcp/tools/BinaryTools.kt")
            .readText()
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
        assertTrue(generatedToolset.contains(""""summarize_binary" -> renderSummarizeBinaryCliCommandOutput(command)"""))
        assertTrue(generatedProducts.contains("renderSummarizeBinaryCliCommandOutput"))
        assertTrue(serverAdapter.contains("summarizeBinaryCli("))
        assertTrue(serverAdapter.contains("SummarizeBinaryCliRequest("))

        assertFalse(generatedToolset.contains(""""summarize_binary" -> renderSummarizeBinaryCommandOutput(command)"""))
        assertFalse(generatedProducts.contains("renderSummarizeBinaryCommandOutput"))
    }

    @Test
    fun migratedRuntimeImplementationDoesNotExistInServerSources() {
        val serverSources = listOf(
            root.resolve("mcp/server/src/main/kotlin/org/jetbrains/hexana/mcp/server/HexanaCliExecution.kt"),
        ).joinToString(separator = "\n") { it.readText() }

        listOf(
            "class SummarizeBinaryInputConverter",
            "class SummarizeBinaryExecutor",
            "object SummarizeBinaryJsonRenderer",
            "class ServerToolRuntimeRegistry",
            "fun executeSummarizeBinaryRuntime",
            "JsonElement.serializer(), request.result",
        ).forEach { forbidden ->
            assertFalse(
                serverSources.contains(forbidden),
                "Server sources must not own migrated summarize-binary runtime implementation: $forbidden",
            )
        }
    }
}
