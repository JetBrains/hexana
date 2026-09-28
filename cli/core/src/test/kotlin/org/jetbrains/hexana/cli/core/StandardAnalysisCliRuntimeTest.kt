package org.jetbrains.hexana.cli.core

import org.jetbrains.hexana.cli.metadata.SummarizeModuleCliInputConverter

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.builtins.serializer
import org.jetbrains.hexana.cli.runtime.CliTable
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.ToolExecutionDiagnosticSeverity
import org.jetbrains.hexana.cli.runtime.ToolExecutionRequest
import org.jetbrains.hexana.cli.runtime.ToolExecutionResult
import org.jetbrains.hexana.cli.runtime.ToolExecutor
import org.jetbrains.hexana.cli.runtime.ToolInputConversionRequest
import org.jetbrains.hexana.cli.runtime.ToolInputConverter
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.runtime.ToolInputSources
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderRequest
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderer
import org.jetbrains.hexana.cli.runtime.ToolRenderOptions
import org.jetbrains.hexana.cli.runtime.ToolRuntimeContext
import org.jetbrains.hexana.cli.runtime.TypedToolOutput
import org.jetbrains.hexana.cli.runtime.TypedToolOutputPlan
import org.jetbrains.hexana.cli.runtime.TypedToolOutputProjection
import org.jetbrains.hexana.graalwasm.wasmprinter.WatWithWarnings
import org.jetbrains.hexana.mcp.DefaultHexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.WasmInput
import org.jetbrains.hexana.mcp.model.converters.WasmToWatResult
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleImports
import org.jetbrains.hexana.mcp.model.summary.SummarizeModuleResult
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StandardAnalysisCliRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun typedTableAdapterPassesExactRenderOptionsToLayout() {
        var receivedOptions: ToolRenderOptions? = null
        val renderer = JsonBackedTypedToolOutputRenderer(
            toolName = "sample",
            plan = TypedToolOutputPlan(
                serializer = String.serializer(),
                projections = listOf(
                    TypedToolOutputProjection(ToolOutputFormat.TABLE) { _, _ ->
                        TypedToolOutput.Table(CliTable(listOf("Value"), listOf(listOf("7"))))
                    },
                ),
            ),
            renderJson = { RenderedToolOutput.Text(it.toString()) },
            renderTable = { _, options ->
                receivedOptions = options
                "table"
            },
        )
        val options = ToolRenderOptions(
            format = ToolOutputFormat.TABLE,
            colorMode = org.jetbrains.hexana.cli.runtime.TerminalColorMode.Never,
            terminalWidth = 19,
            includeDiagnostics = true,
        )

        val rendered = renderer.render(
            ToolOutputRenderRequest(
                context = ToolRuntimeContext("sample"),
                arguments = JsonObject(emptyMap()),
                result = JsonPrimitive("7"),
                options = options,
            )
        )

        assertEquals("table", assertIs<RenderedToolOutput.Text>(rendered).text)
        assertEquals(options, receivedOptions)
    }

    @Test
    fun summarizeModuleConverterAcceptsPathBase64StdinAndRejectsWrongShape() {
        val converter = SummarizeModuleCliInputConverter
        listOf(
            ToolInputSourceSelection.Path("sample.wasm", "wasm"),
            ToolInputSourceSelection.Base64(MINIMAL_WASM_BASE64, "wasm"),
            ToolInputSourceSelection.StdinBytes(MINIMAL_WASM_BYTES, "wasm"),
            ToolInputSourceSelection.ByteList(MINIMAL_WASM_BYTES.map { it.toUByte().toInt() }, "wasm"),
        ).forEach { source ->
            assertEquals(JsonObject(emptyMap()), converter.convert(conversionRequest(SUMMARIZE_MODULE_TOOL_NAME, source)))
        }

        assertFailsWith<IllegalArgumentException> {
            converter.convert(conversionRequest(SUMMARIZE_MODULE_TOOL_NAME))
        }.also {
            assertTrue(it.message.orEmpty().contains("exactly one wasm source"))
        }
        assertFailsWith<IllegalArgumentException> {
            converter.convert(conversionRequest(
                SUMMARIZE_MODULE_TOOL_NAME,
                ToolInputSourceSelection.Path("sample.wasm", "wasm"),
                ToolInputSourceSelection.Base64(MINIMAL_WASM_BASE64, "other"),
            ))
        }.also {
            assertTrue(it.message.orEmpty().contains("exactly one wasm source"))
        }
        assertFailsWith<IllegalArgumentException> {
            converter.convert(
                conversionRequest(
                    SUMMARIZE_MODULE_TOOL_NAME,
                    ToolInputSourceSelection.Path("sample.wasm", "binary"),
                )
            )
        }.also {
            assertTrue(it.message.orEmpty().contains("slot 'wasm'"))
        }
        assertFailsWith<IllegalArgumentException> {
            converter.convert(
                conversionRequest(
                    SUMMARIZE_MODULE_TOOL_NAME,
                    ToolInputSourceSelection.Path("sample.wasm", "wasm"),
                    rawArguments = JsonObject(mapOf("extra" to JsonPrimitive(true))),
                )
            )
        }.also {
            assertTrue(it.message.orEmpty().contains("does not accept semantic parameters"))
        }
    }

    @Test
    fun summarizeModuleExecutorInvokesEverySourceAndRendererCoversJsonPrettyGolden() {
        val received = mutableListOf<WasmInput>()
        val executor = SummarizeModuleExecutor { input ->
            received += input
            sampleModuleResult()
        }
        val sources = listOf(
            ToolInputSourceSelection.Path("sample.wasm", "wasm") to WasmInput.FilePath("sample.wasm"),
            ToolInputSourceSelection.Base64(MINIMAL_WASM_BASE64, "wasm") to WasmInput.InlineBytes(MINIMAL_WASM_BASE64),
            ToolInputSourceSelection.StdinBytes(MINIMAL_WASM_BYTES, "wasm") to WasmInput.RawBytes(MINIMAL_WASM_BYTES),
        )

        sources.forEach { (source, expectedInput) ->
            val execution = executor.execute(executionRequest(SUMMARIZE_MODULE_TOOL_NAME, source))
            assertWasmInputEquals(expectedInput, received.last())
            assertEquals(
                HexanaJson.compact.encodeToJsonElement(SummarizeModuleResult.serializer(), sampleModuleResult()),
                execution.semanticResult,
            )
        }

        val result = HexanaJson.compact.encodeToJsonElement(SummarizeModuleResult.serializer(), sampleModuleResult())
        val json = assertIs<RenderedToolOutput.Text>(
            SummarizeModuleRenderer.render(renderRequest(SUMMARIZE_MODULE_TOOL_NAME, result, ToolOutputFormat.JSON))
        )
        assertEquals(
            """{"functions":2,"exports":1,"memories":1,"globals":3,"tables":4,"types":5,"imports":{"functions":6,"memories":7,"tables":8,"globals":9},"moduleSize":10}""",
            json.text,
        )
        val pretty = assertIs<RenderedToolOutput.Text>(
            SummarizeModuleRenderer.render(renderRequest(SUMMARIZE_MODULE_TOOL_NAME, result, ToolOutputFormat.PRETTY))
        )
        val table = assertIs<RenderedToolOutput.Text>(
            SummarizeModuleRenderer.render(renderRequest(SUMMARIZE_MODULE_TOOL_NAME, result, ToolOutputFormat.TABLE))
        )
        assertEquals(
            """
            Metric              | Value
            --------------------+------
            Functions           | 2
            Exports             | 1
            Memories            | 1
            Globals             | 3
            Tables              | 4
            Types               | 5
            Imported functions  | 6
            Imported memories   | 7
            Imported tables     | 8
            Imported globals    | 9
            Module size (bytes) | 10
            """.trimIndent(),
            table.text,
        )
        assertEquals(
            """
            WebAssembly module summary
            Functions: 2
            Exports: 1
            Memories: 1
            Globals: 3
            Tables: 4
            Types: 5
            Imports:
              Functions: 6
              Memories: 7
              Tables: 8
              Globals: 9
            Module size: 10 bytes
            """.trimIndent(),
            pretty.text,
        )
        assertFailsWith<IllegalArgumentException> {
            SummarizeModuleRenderer.render(renderRequest(SUMMARIZE_MODULE_TOOL_NAME, result, ToolOutputFormat.RAW))
        }
    }

    @Test
    fun wasmToWatConverterAcceptsPathBase64StdinAndRejectsWrongShape() {
        val converter = WasmToWatInputConverter()
        listOf(
            ToolInputSourceSelection.Path("sample.wasm", "wasm"),
            ToolInputSourceSelection.Base64(MINIMAL_WASM_BASE64, "wasm"),
            ToolInputSourceSelection.StdinBytes(MINIMAL_WASM_BYTES, "wasm"),
            ToolInputSourceSelection.ByteList(MINIMAL_WASM_BYTES.map { it.toUByte().toInt() }, "wasm"),
        ).forEach { source ->
            assertEquals(JsonObject(emptyMap()), converter.convert(conversionRequest(WASM_TO_WAT_TOOL_NAME, source)))
        }

        assertFailsWith<IllegalArgumentException> {
            converter.convert(conversionRequest(WASM_TO_WAT_TOOL_NAME))
        }.also {
            assertTrue(it.message.orEmpty().contains("exactly one wasm source"))
        }
        assertFailsWith<IllegalArgumentException> {
            converter.convert(
                conversionRequest(
                    WASM_TO_WAT_TOOL_NAME,
                    ToolInputSourceSelection.Path("sample.wasm", "binary"),
                )
            )
        }.also {
            assertTrue(it.message.orEmpty().contains("slot 'wasm'"))
        }
    }

    @Test
    fun wasmToWatExecutorInvokesEverySourceAndModelsWarningsAsDiagnostics() {
        val wasmFile = temporaryFolder.newFile("sample.wasm").toPath()
        Files.write(wasmFile, MINIMAL_WASM_BYTES)
        val received = mutableListOf<ByteArray>()
        val executor = WasmToWatExecutor { bytes ->
            received += bytes.copyOf()
            WatWithWarnings(wat = "(module)", warnings = "first warning\n\nsecond warning")
        }
        val sources = listOf(
            ToolInputSourceSelection.Path(wasmFile.toString(), "wasm"),
            ToolInputSourceSelection.Base64(MINIMAL_WASM_BASE64, "wasm"),
            ToolInputSourceSelection.StdinBytes(MINIMAL_WASM_BYTES, "wasm"),
        )

        sources.forEach { source ->
            val execution = executor.execute(executionRequest(WASM_TO_WAT_TOOL_NAME, source))
            assertContentEquals(MINIMAL_WASM_BYTES, received.last())
            val result = HexanaJson.compact.decodeFromJsonElement(WasmToWatResult.serializer(), execution.semanticResult)
            assertEquals("(module)", result.wat)
            assertEquals(listOf("first warning", "second warning"), result.warnings)
            assertEquals(
                listOf("first warning", "second warning"),
                execution.diagnostics.map { it.message },
            )
            assertTrue(execution.diagnostics.all { it.severity == ToolExecutionDiagnosticSeverity.WARNING })
        }
    }

    @Test
    fun wasmToWatRendererCoversJsonRawGoldenAndRawLeavesWarningsOutOfStdout() {
        val result = HexanaJson.compact.encodeToJsonElement(
            WasmToWatResult.serializer(),
            WasmToWatResult(wat = "(module)", warnings = listOf("warn")),
        )

        val json = assertIs<RenderedToolOutput.Text>(
            WasmToWatRenderer.render(renderRequest(WASM_TO_WAT_TOOL_NAME, result, ToolOutputFormat.JSON))
        )
        assertEquals("""{"wat":"(module)","warnings":["warn"]}""", json.text)

        val raw = assertIs<RenderedToolOutput.Text>(
            WasmToWatRenderer.render(renderRequest(WASM_TO_WAT_TOOL_NAME, result, ToolOutputFormat.RAW))
        )
        assertEquals("(module)", raw.text)
        assertFalse(raw.text.contains("warn"))

        assertFailsWith<IllegalStateException> {
            WasmToWatRenderer.render(renderRequest(WASM_TO_WAT_TOOL_NAME, result, ToolOutputFormat.PRETTY))
        }
    }

    @Test
    fun wasmToWatExecutorMapsPrinterFailureAndPropagatesDomainErrorsAndCancellation() {
        val invalid = assertFailsWith<HexanaAnalysisException> {
            WasmToWatExecutor { throw IllegalArgumentException("bad magic") }.execute(
                executionRequest(
                    WASM_TO_WAT_TOOL_NAME,
                    ToolInputSourceSelection.StdinBytes(MINIMAL_WASM_BYTES, "wasm"),
                )
            )
        }
        assertEquals(HexanaAnalysisErrorCode.NOT_WASM, invalid.code)

        val domain = HexanaAnalysisException(HexanaAnalysisErrorCode.INVALID_INPUT, "domain")
        val domainExecutor = WasmToWatExecutor { throw domain }
        assertSame(
            domain,
            assertFailsWith<HexanaAnalysisException> {
                domainExecutor.execute(
                    executionRequest(WASM_TO_WAT_TOOL_NAME, ToolInputSourceSelection.StdinBytes(MINIMAL_WASM_BYTES, "wasm"))
                )
            },
        )

        val cancellationExecutor = WasmToWatExecutor { throw CancellationException("cancelled") }
        assertEquals(
            "cancelled",
            assertFailsWith<CancellationException> {
                cancellationExecutor.execute(
                    executionRequest(WASM_TO_WAT_TOOL_NAME, ToolInputSourceSelection.StdinBytes(MINIMAL_WASM_BYTES, "wasm"))
                )
            }.message,
        )
    }

    @Test
    fun standardCliRendersDefaultJsonPrettyAndRawFormats() {
        val wasmFile = temporaryFolder.newFile("adapter.wasm").toPath()
        Files.write(wasmFile, MINIMAL_WASM_BYTES)

        val binaryJson = assertIs<RenderedToolOutput.Text>(
            standardCli(
                StandardCliRequest(
                    toolName = SUMMARIZE_BINARY_TOOL_NAME,
                    sourceSelections = listOf(ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), "binary")),
                    format = ToolOutputFormat.JSON,
                ),
                DefaultHexanaAnalysisService(),
            ).rendered
        )
        assertTrue(binaryJson.text.startsWith("{\"module\":"))

        val modulePretty = assertIs<RenderedToolOutput.Text>(
            standardCli(
                StandardCliRequest(
                    toolName = SUMMARIZE_MODULE_TOOL_NAME,
                    sourceSelections = listOf(ToolInputSourceSelection.Path(wasmFile.toString(), "wasm")),
                    format = ToolOutputFormat.PRETTY,
                ),
                DefaultHexanaAnalysisService(),
            ).rendered
        )
        assertTrue(modulePretty.text.startsWith("WebAssembly module summary"))

        val watRaw = assertIs<RenderedToolOutput.Text>(
            standardCli(
                StandardCliRequest(
                    toolName = WASM_TO_WAT_TOOL_NAME,
                    sourceSelections = listOf(ToolInputSourceSelection.Base64(MINIMAL_WASM_BASE64, "wasm")),
                    format = ToolOutputFormat.RAW,
                ),
                DefaultHexanaAnalysisService(),
            ).rendered
        )
        assertTrue(watRaw.text.contains("(module"))
    }

    @Test
    fun registryRejectsMissingConverterExecutorOrRendererForEveryStandardTool() {
        val converter = object : ToolInputConverter {
            override fun convert(request: ToolInputConversionRequest): JsonObject = JsonObject(emptyMap())
        }
        val executor = ToolExecutor { ToolExecutionResult(JsonObject(emptyMap())) }
        val renderer = object : ToolOutputRenderer {
            override fun render(request: ToolOutputRenderRequest): RenderedToolOutput =
                RenderedToolOutput.Text("{}")
        }
        STANDARD_ANALYSIS_TOOL_NAMES.forEach { missingTool ->
            assertFailsWith<IllegalArgumentException> {
                CliToolRuntimeRegistry.create(
                    expectedTools = STANDARD_ANALYSIS_TOOL_NAMES,
                    converters = STANDARD_ANALYSIS_TOOL_NAMES
                        .filterNot { it == missingTool }
                        .associateWith { converter },
                    executors = STANDARD_ANALYSIS_TOOL_NAMES.associateWith { executor },
                    renderers = STANDARD_ANALYSIS_TOOL_NAMES.associateWith { renderer },
                )
            }
            assertFailsWith<IllegalArgumentException> {
                CliToolRuntimeRegistry.create(
                    expectedTools = STANDARD_ANALYSIS_TOOL_NAMES,
                    converters = STANDARD_ANALYSIS_TOOL_NAMES.associateWith { converter },
                    executors = STANDARD_ANALYSIS_TOOL_NAMES
                        .filterNot { it == missingTool }
                        .associateWith { executor },
                    renderers = STANDARD_ANALYSIS_TOOL_NAMES.associateWith { renderer },
                )
            }
            assertFailsWith<IllegalArgumentException> {
                CliToolRuntimeRegistry.create(
                    expectedTools = STANDARD_ANALYSIS_TOOL_NAMES,
                    converters = STANDARD_ANALYSIS_TOOL_NAMES.associateWith { converter },
                    executors = STANDARD_ANALYSIS_TOOL_NAMES.associateWith { executor },
                    renderers = STANDARD_ANALYSIS_TOOL_NAMES
                        .filterNot { it == missingTool }
                        .associateWith { renderer },
                )
            }
        }
    }

    private fun conversionRequest(
        toolName: String,
        vararg sources: ToolInputSourceSelection,
        rawArguments: JsonObject = JsonObject(emptyMap()),
    ) = ToolInputConversionRequest(
        context = ToolRuntimeContext(toolName),
        rawArguments = rawArguments,
        sources = ToolInputSources(sourceSelections = sources.toList()),
    )

    private fun executionRequest(
        toolName: String,
        source: ToolInputSourceSelection,
    ) = ToolExecutionRequest(
        context = ToolRuntimeContext(toolName),
        arguments = JsonObject(emptyMap()),
        sources = ToolInputSources(sourceSelections = listOf(source)),
    )

    private fun renderRequest(
        toolName: String,
        result: kotlinx.serialization.json.JsonElement,
        format: ToolOutputFormat,
    ) = ToolOutputRenderRequest(
        context = ToolRuntimeContext(toolName),
        arguments = JsonObject(emptyMap()),
        result = result,
        options = ToolRenderOptions(format = format),
    )

    private fun sampleModuleResult() = SummarizeModuleResult(
        functions = 2,
        exports = 1,
        memories = 1,
        globals = 3,
        tables = 4,
        types = 5,
        imports = SummarizeModuleImports(functions = 6, memories = 7, tables = 8, globals = 9),
        moduleSize = 10,
    )

    private fun assertWasmInputEquals(expected: WasmInput, actual: WasmInput) {
        if (expected is WasmInput.RawBytes) {
            assertContentEquals(expected.bytes, assertIs<WasmInput.RawBytes>(actual).bytes)
        } else {
            assertEquals(expected, actual)
        }
    }

    private companion object {
        val MINIMAL_WASM_BYTES: ByteArray = byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0)
        val MINIMAL_WASM_BASE64: String = Base64.getEncoder().encodeToString(MINIMAL_WASM_BYTES)
    }
}
