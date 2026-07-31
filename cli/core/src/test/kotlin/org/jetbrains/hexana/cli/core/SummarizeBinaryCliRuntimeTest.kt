package org.jetbrains.hexana.cli.core

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
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
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.DefaultHexanaAnalysisService
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.model.binaries.BinaryModuleIdentityResult
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryCountsResult
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryResult
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SummarizeBinaryCliRuntimeTest {
    private val context = ToolRuntimeContext(SUMMARIZE_BINARY_TOOL_NAME)
    private val converter = SummarizeBinaryInputConverter()

    @Test
    fun converterKeepsEveryLegacySourceOutOfSemanticJson() {
        val sources = listOf(
            ToolInputSourceSelection.Path("sample.bin", "binary"),
            ToolInputSourceSelection.Base64("AQID", "binary"),
            ToolInputSourceSelection.StdinBytes(byteArrayOf(0, 127, -1), "binary"),
            ToolInputSourceSelection.ByteList(listOf(0, 127, 255), "binary"),
        )

        sources.forEach { source ->
            assertEquals(JsonObject(emptyMap()), converter.convert(conversionRequest(source)))
        }
    }

    @Test
    fun converterRejectsMissingConflictingWrongSlotAndSemanticParams() {
        val missing = assertFailsWith<IllegalArgumentException> {
            converter.convert(conversionRequest())
        }
        assertTrue(missing.message.orEmpty().contains("exactly one"))

        val conflicting = assertFailsWith<IllegalArgumentException> {
            converter.convert(
                conversionRequest(
                    ToolInputSourceSelection.Path("one.bin", "binary"),
                    ToolInputSourceSelection.Base64("AQ==", "baseline"),
                )
            )
        }
        assertTrue(conflicting.message.orEmpty().contains("exactly one"))

        val wrongSlot = assertFailsWith<IllegalArgumentException> {
            converter.convert(conversionRequest(ToolInputSourceSelection.Path("one.bin", "wasm")))
        }
        assertTrue(wrongSlot.message.orEmpty().contains("slot 'binary'"))

        val params = assertFailsWith<IllegalArgumentException> {
            converter.convert(
                conversionRequest(
                    ToolInputSourceSelection.Path("one.bin", "binary"),
                    rawArguments = JsonObject(mapOf("extra" to JsonPrimitive(true))),
                )
            )
        }
        assertTrue(params.message.orEmpty().contains("does not accept semantic parameters"))
    }

    @Test
    fun executorInvokesExactlyOnceForEveryBinaryInputAndReturnsSemanticJson() {
        val result = sampleResult()
        val received = mutableListOf<BinaryInput>()
        val executor = SummarizeBinaryExecutor { input ->
            received += input
            result
        }
        val cases = listOf(
            ToolInputSourceSelection.Path("sample.bin", "binary") to BinaryInput.FilePath("sample.bin"),
            ToolInputSourceSelection.Base64("AQID", "binary") to BinaryInput.InlineBytes("AQID"),
            ToolInputSourceSelection.ByteList(listOf(1, 2, 255), "binary") to
                BinaryInput.RawBytes(byteArrayOf(1, 2, -1)),
        )

        cases.forEach { (source, expectedInput) ->
            val execution = executor.execute(
                ToolExecutionRequest(
                    context,
                    JsonObject(emptyMap()),
                    ToolInputSources(sourceSelections = listOf(source)),
                )
            )

            assertBinaryInputEquals(expectedInput, received.last())
            assertEquals(
                HexanaJson.compact.encodeToJsonElement(BinarySummaryResult.serializer(), result),
                execution.semanticResult,
            )
        }
        assertEquals(cases.size, received.size)
    }

    @Test
    fun largeStdinStaysOutOfArgumentsAndReachesExecutorLosslessly() {
        val bytes = ByteArray(1024 * 1024 + 1) { it.toByte() }
        val sources = ToolInputSources(
            sourceSelections = listOf(ToolInputSourceSelection.StdinBytes(bytes, "binary"))
        )
        val arguments = converter.convert(ToolInputConversionRequest(context, JsonObject(emptyMap()), sources))
        var received: BinaryInput? = null
        val executor = SummarizeBinaryExecutor { input ->
            received = input
            sampleResult()
        }

        executor.execute(ToolExecutionRequest(context, arguments, sources))

        assertTrue(arguments.isEmpty())
        assertContentEquals(bytes, assertIs<BinaryInput.RawBytes>(received).bytes)
    }

    @Test
    fun executorPropagatesDomainExceptionWithoutWrapping() {
        val expected = HexanaAnalysisException(HexanaAnalysisErrorCode.FILE_NOT_FOUND, "missing")
        val executor = SummarizeBinaryExecutor { throw expected }

        val actual = assertFailsWith<HexanaAnalysisException> {
            executor.execute(
                ToolExecutionRequest(
                    context,
                    JsonObject(emptyMap()),
                    ToolInputSources(
                        sourceSelections = listOf(ToolInputSourceSelection.Path("missing.bin", "binary"))
                    ),
                )
            )
        }

        assertSame(expected, actual)
    }

    @Test
    fun rendererEmitsExactCompactJsonContentTypeAndNoWrapperOrNewline() {
        val result = JsonObject(mapOf("ok" to JsonPrimitive(true)))
        val output = assertIs<RenderedToolOutput.Text>(
            SummarizeBinaryJsonRenderer.render(renderRequest(result, ToolOutputFormat.JSON))
        )

        assertEquals("{\"ok\":true}", output.text)
        assertEquals(SummarizeBinaryJsonRenderer.CONTENT_TYPE, output.contentType)
        assertFalse(output.text.endsWith("\n"))
        assertFalse(output.text.contains("content"))
        assertFalse(output.text.contains("structuredContent"))

        listOf(ToolOutputFormat.PRETTY, ToolOutputFormat.RAW).forEach { format ->
            assertFailsWith<IllegalArgumentException> {
                SummarizeBinaryJsonRenderer.render(renderRequest(result, format))
            }
        }
    }

    @Test
    fun registryRejectsEveryMissingBindingBeforeExecution() {
        val converter = SummarizeBinaryInputConverter()
        val executor = ToolExecutor { ToolExecutionResult(JsonNull) }
        val renderer = object : ToolOutputRenderer {
            override fun render(request: ToolOutputRenderRequest): RenderedToolOutput =
                RenderedToolOutput.Text("null")
        }
        fun assertMissing(
            converters: Map<String, ToolInputConverter> = mapOf(SUMMARIZE_BINARY_TOOL_NAME to converter),
            executors: Map<String, ToolExecutor> = mapOf(SUMMARIZE_BINARY_TOOL_NAME to executor),
            renderers: Map<String, ToolOutputRenderer> = mapOf(SUMMARIZE_BINARY_TOOL_NAME to renderer),
        ) {
            assertFailsWith<IllegalArgumentException> {
                CliToolRuntimeRegistry.create(
                    expectedTools = setOf(SUMMARIZE_BINARY_TOOL_NAME),
                    converters = converters,
                    executors = executors,
                    renderers = renderers,
                )
            }
        }
        assertMissing(converters = emptyMap())
        assertMissing(executors = emptyMap())
        assertMissing(renderers = emptyMap())
    }

    @Test
    fun neutralCliAdapterRendersCompactJson() {
        val output = assertIs<RenderedToolOutput.Text>(
            summarizeBinaryCli(
                SummarizeBinaryCliRequest(
                    sourceSelections = listOf(ToolInputSourceSelection.ByteList(listOf(1, 2, 3), "binary")),
                ),
                DefaultHexanaAnalysisService(),
            )
        )

        assertTrue(output.text.startsWith("{\"module\":"))
        assertTrue(output.text.contains("\"byteLength\":3"))
        assertFalse(output.text.endsWith("\n"))
    }

    private fun conversionRequest(
        vararg sources: ToolInputSourceSelection,
        rawArguments: JsonObject = JsonObject(emptyMap()),
    ) = ToolInputConversionRequest(
        context = context,
        rawArguments = rawArguments,
        sources = ToolInputSources(sourceSelections = sources.toList()),
    )

    private fun renderRequest(result: JsonObject, format: ToolOutputFormat) = ToolOutputRenderRequest(
        context = context,
        arguments = JsonObject(emptyMap()),
        result = result,
        options = ToolRenderOptions(format = format),
    )

    private fun sampleResult() = BinarySummaryResult(
        module = BinaryModuleIdentityResult(
            path = "sample.bin",
            format = "generic",
            displayFormat = "Generic binary",
            byteLength = 3,
        ),
        counts = BinarySummaryCountsResult(functions = 1),
    )

    private fun assertBinaryInputEquals(expected: BinaryInput, actual: BinaryInput) {
        if (expected is BinaryInput.RawBytes) {
            assertContentEquals(expected.bytes, assertIs<BinaryInput.RawBytes>(actual).bytes)
        } else {
            assertEquals(expected, actual)
        }
    }
}
