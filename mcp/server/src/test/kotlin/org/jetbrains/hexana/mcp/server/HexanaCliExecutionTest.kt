package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.graalwasm.wasmprinter.WatWithWarnings
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
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
        val output = renderSummarizeModuleOutput(
            summarizeModuleCommand(
                path = wasm.toString(),
                format = HexanaCliOutputFormat.JSON,
            )
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
    fun renderSummarizeModulePrettyReturnsDeterministicPlainText() {
        val wasm = createWasmFile(
            "pretty-summary.wasm",
            """
            (module
              (import "env" "host" (func))
              (memory 1)
              (global i32 (i32.const 0))
              (func (export "main")))
            """.trimIndent()
        )
        val moduleSize = Files.size(wasm)

        val output = renderSummarizeModuleOutput(
            summarizeModuleCommand(
                path = wasm.toString(),
                format = HexanaCliOutputFormat.PRETTY,
            )
        )

        assertEquals(
            """
            WebAssembly module summary
            Functions: 1
            Exports: 1
            Memories: 1
            Globals: 1
            Tables: 0
            Types: 1
            Imports:
              Functions: 1
              Memories: 0
              Tables: 0
              Globals: 0
            Module size: $moduleSize bytes
            """.trimIndent(),
            output,
        )
    }

    @Test
    fun renderSummarizeModuleEnforcesOutputLimitForJsonAndPrettyFormats() {
        val wasm = createWasmFile("limited-summary.wasm", "(module (memory 1) (func (export \"main\")))")

        listOf(HexanaCliOutputFormat.JSON, HexanaCliOutputFormat.PRETTY).forEach { format ->
            val error = assertFailsWith<HexanaAnalysisException> {
                renderSummarizeModuleOutput(
                    summarizeModuleCommand(
                        path = wasm.toString(),
                        format = format,
                        resourceLimits = HexanaResourceLimits(maxOutputBytes = 4),
                    )
                )
            }

            assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
            assertTrue(error.message.orEmpty().contains("maxOutputBytes=4"))
        }
    }

    @Test
    fun renderSummarizeModuleJsonRejectsFileOutsideAllowedRoot() {
        val wasm = createWasmFile("outside.wasm", "(module)")
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()

        val error = assertFailsWith<HexanaAnalysisException> {
            renderSummarizeModuleOutput(
                summarizeModuleCommand(
                    path = wasm.toString(),
                    format = HexanaCliOutputFormat.JSON,
                    allowedRoots = listOf(allowedRoot.toString()),
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun renderWasmToWatRawReturnsWatTextWithoutJsonWrapper() {
        val wasm = createWasmFile(
            "raw.wasm",
            """
            (module
              (func (export "main")))
            """.trimIndent()
        )

        val output = renderWasmToWatOutput(
            wasmToWatCommand(
                path = wasm.toString(),
                format = HexanaCliOutputFormat.RAW,
            )
        )

        assertTrue(output.contains("(module"))
        assertTrue(output.contains("(func"))
        assertFalse(output.trimStart().startsWith("{"))
        assertFalse(output.contains("\"wat\""))
    }

    @Test
    fun renderWasmToWatRawReturnsWarningsOnStderr() {
        val wasm = createWasmFile("raw-warning.wasm", "(module)")

        val output = renderWasmToWatCommandOutput(
            wasmToWatCommand(
                path = wasm.toString(),
                format = HexanaCliOutputFormat.RAW,
            ),
            wasmPrinter = {
                WatWithWarnings(
                    wat = "(module)",
                    warnings = "first warning\n\nsecond warning",
                )
            },
        )

        assertEquals("(module)", output.stdout)
        assertTrue(output.stderr.contains("wasm-to-wat warning: first warning"))
        assertTrue(output.stderr.contains("wasm-to-wat warning: second warning"))
    }

    @Test
    fun renderWasmToWatJsonReturnsWatAndWarningsKeys() {
        val wasm = createWasmFile("json.wasm", "(module)")

        val output = renderWasmToWatOutput(
            wasmToWatCommand(
                path = wasm.toString(),
                format = HexanaCliOutputFormat.JSON,
            )
        )

        val json = Json.parseToJsonElement(output).jsonObject
        assertTrue(json.getValue("wat").jsonPrimitive.content.contains("(module"))
        assertTrue("warnings" in json)
        json.getValue("warnings").jsonArray
    }

    @Test
    fun renderWasmToWatEnforcesOutputLimitForRawAndJsonFormats() {
        val wasm = createWasmFile("limited-output.wasm", "(module)")

        listOf(HexanaCliOutputFormat.RAW, HexanaCliOutputFormat.JSON).forEach { format ->
            val error = assertFailsWith<HexanaAnalysisException> {
                renderWasmToWatOutput(
                    wasmToWatCommand(
                        path = wasm.toString(),
                        format = format,
                        resourceLimits = HexanaResourceLimits(maxOutputBytes = 4),
                    )
                )
            }

            assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
            assertTrue(error.message.orEmpty().contains("maxOutputBytes=4"))
        }
    }

    @Test
    fun renderWasmToWatRejectsFileOutsideAllowedRoot() {
        val wasm = createWasmFile("outside-converter.wasm", "(module)")
        val allowedRoot = temporaryFolder.newFolder("converter-allowed").toPath()

        val error = assertFailsWith<HexanaAnalysisException> {
            renderWasmToWatOutput(
                wasmToWatCommand(
                    path = wasm.toString(),
                    format = HexanaCliOutputFormat.RAW,
                    allowedRoots = listOf(allowedRoot.toString()),
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun renderToolsListingReturnsDeterministicGeneratedCliMetadata() {
        val output = renderCliToolsListing(HexanaCommand.Tools(group = null))

        assertTrue(output.startsWith("Hexana MCP CLI tools\n"))
        assertTrue(output.contains("Groups are generated CLI metadata, not MCP tool profiles.\n"))
        assertToolBlockContains(
            output,
            "summarize-module",
            "  groups: module\n",
            "  status: executable\n",
            "  formats: json, pretty\n",
            "  default: pretty\n",
            "  summary: Summarize a WebAssembly module",
        )
        assertToolBlockContains(
            output,
            "summarize-binary",
            "  status: todo\n",
        )
        assertToolBlockContains(
            output,
            "wasm-to-wat",
            "  groups: converters\n",
            "  formats: json, raw\n",
            "  default: raw\n",
        )
        assertTrue(output.indexOf("summarize-binary\n") < output.indexOf("summarize-module\n"))
        assertTrue(output.indexOf("summarize-module\n") < output.indexOf("wasm-to-wat\n"))
    }

    @Test
    fun renderToolsListingFiltersByGeneratedGroupMetadataNotMcpProfile() {
        val output = renderCliToolsListing(HexanaCommand.Tools(group = HexanaCliToolGroup.CONVERTERS))

        assertTrue(output.contains("Groups are generated CLI metadata, not MCP tool profiles.\n"))
        assertEquals(listOf("wasm-to-wat"), listedCommandNames(output))
        assertToolBlockContains(
            output,
            "wasm-to-wat",
            "  groups: converters\n",
            "  status: executable\n",
        )
        assertFalse(output.contains("summarize-module\n"))
        assertFalse(output.contains("  groups: module\n"))
    }

    @Test
    fun renderToolsListingFiltersMultiCommandModuleGroup() {
        val output = renderCliToolsListing(HexanaCommand.Tools(group = HexanaCliToolGroup.MODULE))

        assertTrue(output.contains("summarize-module\n"))
        assertTrue(output.contains("list-enabled-features\n"))
        assertTrue(output.contains("  groups: module\n"))
        assertFalse(output.contains("wasm-to-wat\n"))
        assertFalse(output.contains("  groups: converters\n"))
    }

    @Test
    fun renderTodoToolNotImplementedMessageNamesCommandAndTool() {
        val spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.commandName == "summarize-binary" }
        val output = renderTodoToolNotImplementedMessage(HexanaCommand.TodoTool(spec))

        assertTrue(output.contains("summarize-binary"))
        assertTrue(output.contains("summarize_binary"))
        assertTrue(output.contains("not implemented"))
        assertTrue(output.contains("TODO"))
    }

    private fun assertToolBlockContains(output: String, commandName: String, vararg expectedLines: String) {
        val blockStart = output.indexOf("$commandName\n")
        assertTrue(blockStart >= 0, "Expected listing to contain $commandName")
        val nextBlockStart = output.indexOf("\n\n", startIndex = blockStart).let { index ->
            if (index >= 0) index else output.length
        }
        val block = output.substring(blockStart, nextBlockStart)
        expectedLines.forEach { expected ->
            assertTrue(block.contains(expected), "Expected $commandName block to contain $expected")
        }
    }

    private fun listedCommandNames(output: String): List<String> =
        output
            .lineSequence()
            .drop(2)
            .filter { it.isNotBlank() && !it.startsWith("  ") }
            .toList()

    private fun summarizeModuleCommand(
        path: String,
        format: HexanaCliOutputFormat,
        allowedRoots: List<String> = emptyList(),
        resourceLimits: HexanaResourceLimits = HexanaResourceLimits(),
    ) = executableToolCommand(
        commandName = "summarize-module",
        path = path,
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun wasmToWatCommand(
        path: String,
        format: HexanaCliOutputFormat,
        allowedRoots: List<String> = emptyList(),
        resourceLimits: HexanaResourceLimits = HexanaResourceLimits(),
    ) = executableToolCommand(
        commandName = "wasm-to-wat",
        path = path,
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun executableToolCommand(
        commandName: String,
        path: String,
        format: HexanaCliOutputFormat,
        allowedRoots: List<String>,
        resourceLimits: HexanaResourceLimits,
    ) = HexanaCommand.ExecutableTool(
        spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.commandName == commandName },
        path = path,
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
    }
}
