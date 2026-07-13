package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaJson
import org.jetbrains.hexana.mcp.HexanaResourceLimits
import org.jetbrains.hexana.mcp.model.binaries.BinaryModuleIdentityResult
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryCountsResult
import org.jetbrains.hexana.mcp.model.binaries.BinarySummaryResult
import org.jetbrains.hexana.mcp.tool.runtime.RenderedToolOutput
import org.jetbrains.hexana.mcp.tool.runtime.ToolExecutionRequest
import org.jetbrains.hexana.mcp.tool.runtime.ToolExecutionResult
import org.jetbrains.hexana.mcp.tool.runtime.ToolExecutor
import org.jetbrains.hexana.mcp.tool.runtime.ToolInputConversionRequest
import org.jetbrains.hexana.mcp.tool.runtime.ToolInputConverter
import org.jetbrains.hexana.mcp.tool.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.mcp.tool.runtime.ToolInputSources
import org.jetbrains.hexana.mcp.tool.runtime.ToolOutputFormat
import org.jetbrains.hexana.mcp.tool.runtime.ToolOutputRenderRequest
import org.jetbrains.hexana.mcp.tool.runtime.ToolOutputRenderer
import org.jetbrains.hexana.mcp.tool.runtime.ToolRenderOptions
import org.jetbrains.hexana.mcp.tool.runtime.ToolRuntimeContext
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SummarizeBinaryToolRuntimeTest {
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
            SummarizeBinaryJsonRenderer.render(renderRequest(result, ToolOutputFormat.Json))
        )

        assertEquals("{\"ok\":true}", output.text)
        assertEquals(SummarizeBinaryJsonRenderer.CONTENT_TYPE, output.contentType)
        assertFalse(output.text.endsWith("\n"))
        assertFalse(output.text.contains("content"))
        assertFalse(output.text.contains("structuredContent"))

        listOf(ToolOutputFormat.Pretty, ToolOutputFormat.Raw).forEach { format ->
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
                ServerToolRuntimeRegistry.create(
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
    fun legacyAdapterInvokesInjectedRuntimeOnceAndAppliesOutputLimit() {
        val command = command(maxOutputBytes = 8)
        val analysisService = createStandaloneHexanaAnalysisService(command.allowedRoots, command.resourceLimits)
        val sentinel = RenderedToolOutput.Text("sentinel")
        var invocations = 0
        val output = renderSummarizeBinaryCommandOutput(command, analysisService) { receivedCommand, receivedService ->
            invocations++
            assertSame(command, receivedCommand)
            assertSame(analysisService, receivedService)
            sentinel
        }

        assertEquals(1, invocations)
        assertEquals(HexanaCliCommandOutput(stdout = "sentinel"), output)
        val error = assertFailsWith<HexanaAnalysisException> {
            renderSummarizeBinaryCommandOutput(command(maxOutputBytes = 7), analysisService) { _, _ -> sentinel }
        }
        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertTrue(error.message.orEmpty().contains("maxOutputBytes=7"))
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

    private fun command(maxOutputBytes: Int = HexanaResourceLimits().maxOutputBytes) = HexanaCommand.ExecutableTool(
        spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.toolName == SUMMARIZE_BINARY_TOOL_NAME },
        sourceSelection = ToolInputSourceSelection.ByteList(listOf(1, 2, 3), "binary"),
        format = HexanaCliOutputFormat.JSON,
        allowedRoots = emptyList(),
        resourceLimits = HexanaResourceLimits(maxOutputBytes = maxOutputBytes),
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
