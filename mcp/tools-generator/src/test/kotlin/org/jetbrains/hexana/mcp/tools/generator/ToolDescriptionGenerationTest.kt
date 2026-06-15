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
        assertBoundaryPhrase(
            source,
            "Use this aggregate workflow for crash triage when logs or reports contain mixed frame identifiers",
            "resolve_wasm_frames should be described as aggregate frame triage",
        )
        assertBoundaryPhrase(
            source,
            "Use this focused lookup when you already have `funcidx` values",
            "resolve_function_symbols should be described as focused lookup",
        )
        assertBoundaryPhrase(
            source,
            "This is source-location lookup only; it does not perform full frame triage",
            "resolve_source_locations should not look like full frame triage",
        )
        assertBoundaryPhrase(
            source,
            "direct and transitive dependencies",
            "resolve_component_dependencies should mention transitive resolution",
        )
        assertBoundaryPhrase(
            source,
            "Use this aggregate dependency-resolution workflow when provider files may need to be discovered",
            "resolve_component_dependencies should be described as provider discovery",
        )
        assertBoundaryPhrase(
            source,
            "extern-description or sort mismatches",
            "check_component_compatibility should mention compatibility mismatch classes",
        )
        assertBoundaryPhrase(
            source,
            "This is an inspection lookup only; it does not select provider files or compute compatibility verdicts",
            "list_component_surface should stay separate from resolution workflows",
        )
    }

    private fun assertBoundaryPhrase(source: String, phrase: String, message: String) {
        assertTrue(source.contains(phrase), message)
    }
}
