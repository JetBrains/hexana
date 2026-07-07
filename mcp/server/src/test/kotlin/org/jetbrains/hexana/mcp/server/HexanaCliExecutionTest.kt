package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaResourceLimits
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaCliExecutionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun renderSummarizeModuleJsonReturnsSemanticJsonWithoutMcpWrapper() {
        val wasm = createWasmFile(
            "summary.wasm",
            """
            (module
              (memory 1)
              (func (export "main")))
            """.trimIndent()
        )

        val output = renderSummarizeModuleJson(
            summarizeModuleCommand(path = wasm.toString())
        )

        val json = Json.parseToJsonElement(output).jsonObject
        assertEquals("1", json["functions"]!!.jsonPrimitive.content)
        assertEquals("1", json["exports"]!!.jsonPrimitive.content)
        assertTrue(json["moduleSize"]!!.jsonPrimitive.content.toInt() > 0)
        assertTrue("imports" in json)
        assertFalse("content" in json)
        assertFalse("structuredContent" in json)
        assertFalse("isError" in json)
    }

    @Test
    fun renderSummarizeModuleJsonRejectsFileOutsideAllowedRoot() {
        val wasm = createWasmFile("outside.wasm", "(module)")
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()

        val error = assertFailsWith<HexanaAnalysisException> {
            renderSummarizeModuleJson(
                summarizeModuleCommand(
                    path = wasm.toString(),
                    allowedRoots = listOf(allowedRoot.toString())
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    private fun summarizeModuleCommand(
        path: String,
        allowedRoots: List<String> = emptyList(),
    ) = HexanaCommand.SummarizeModule(
        path = path,
        format = HexanaCliOutputFormat.JSON,
        allowedRoots = allowedRoots,
        resourceLimits = HexanaResourceLimits(),
    )

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
    }
}
