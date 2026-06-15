package org.jetbrains.hexana.mcp.tools.generator

import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class ToolDescriptionGenerationTest {

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
        assertTrue(
            source.contains("Resolve WebAssembly stack or crash frames from `funcidx` values"),
            "resolve_wasm_frames guidance is missing",
        )
        assertTrue(
            source.contains("Search `scanRoots`, `projectRoot`, and the input component directory"),
            "resolve_component_dependencies guidance is missing",
        )
        assertTrue(
            source.contains("Compare non-runtime imports with `dependencyPaths`"),
            "check_component_compatibility guidance is missing",
        )
    }
}
