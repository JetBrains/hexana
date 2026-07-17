package org.jetbrains.hexana.cli.scripting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.cli.runtime.RenderedToolOutput
import org.jetbrains.hexana.cli.runtime.ToolExecutionResult
import org.jetbrains.hexana.cli.runtime.ToolExecutor
import org.jetbrains.hexana.cli.runtime.ToolInputConversionRequest
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.runtime.ToolInputSources
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderRequest
import org.jetbrains.hexana.cli.runtime.ToolOutputRenderer
import org.jetbrains.hexana.cli.runtime.ToolRenderOptions
import org.jetbrains.hexana.cli.runtime.ToolRuntimeContext
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.scripting.HexanaScriptLimits
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunScriptCliRuntimeTest {
    @Rule
    @JvmField
    val temporaryFolder = TemporaryFolder()

    private val context = ToolRuntimeContext(RUN_BINARY_SCRIPT_TOOL_NAME)

    @Test
    fun contributionExposesExactlyRunScript() {
        val contribution = runScriptRuntimeContribution()

        assertEquals(setOf(RUN_BINARY_SCRIPT_TOOL_NAME), contribution.toolNames)
        assertEquals(setOf(RUN_SCRIPT_COMMAND_NAME), contribution.commandNames)
    }

    @Test
    fun legacyMcpSelectedScriptExecutableHasNeutralRuntimeClosure() {
        val selectedScriptTools = Path.of("..", "..").normalize()
            .resolve("cli/metadata/src/main/gen/org/jetbrains/hexana/cli/metadata/HexanaCliProducts.kt")
            .readText()
            .productToolNames("HEXANA_MCP")
            .filterTo(sortedSetOf()) { it == RUN_BINARY_SCRIPT_TOOL_NAME }

        assertEquals(setOf(RUN_BINARY_SCRIPT_TOOL_NAME), selectedScriptTools)

        val binding = runScriptRuntimeContribution().binding(RUN_BINARY_SCRIPT_TOOL_NAME)
        assertTrue(
            binding.converter::class.qualifiedName.orEmpty().startsWith("org.jetbrains.hexana.cli.scripting."),
            "run-script converter must be owned by cli:scripting",
        )
        assertTrue(
            binding.executor::class.qualifiedName.orEmpty().startsWith("org.jetbrains.hexana.cli.scripting."),
            "run-script executor must be owned by cli:scripting",
        )
        assertTrue(
            binding.renderer::class.qualifiedName.orEmpty().startsWith("org.jetbrains.hexana.cli.scripting."),
            "run-script renderer must be owned by cli:scripting",
        )
    }

    @Test
    fun closureRejectsAddedStandardCommandAndMissingScriptExecutor() {
        val converter = RunScriptInputConverter()
        val executor = ToolExecutor { ToolExecutionResult(JsonNull) }
        val renderer = object : ToolOutputRenderer {
            override fun render(request: ToolOutputRenderRequest): RenderedToolOutput =
                RenderedToolOutput.Text("null")
        }

        assertFailsWith<IllegalArgumentException> {
            ScriptCliRuntimeContribution.create(
                expectedTools = setOf(RUN_BINARY_SCRIPT_TOOL_NAME, "summarize_binary"),
                converters = mapOf(RUN_BINARY_SCRIPT_TOOL_NAME to converter, "summarize_binary" to converter),
                executors = mapOf(RUN_BINARY_SCRIPT_TOOL_NAME to executor, "summarize_binary" to executor),
                renderers = mapOf(RUN_BINARY_SCRIPT_TOOL_NAME to renderer, "summarize_binary" to renderer),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ScriptCliRuntimeContribution.create(
                expectedTools = setOf(RUN_BINARY_SCRIPT_TOOL_NAME),
                converters = mapOf(RUN_BINARY_SCRIPT_TOOL_NAME to converter),
                executors = emptyMap(),
                renderers = mapOf(RUN_BINARY_SCRIPT_TOOL_NAME to renderer),
            )
        }
    }

    @Test
    fun converterKeepsBinarySourcesOutOfSemanticJson() {
        val converter = RunScriptInputConverter()
        val params = JsonObject(mapOf("script" to JsonPrimitive("print(1);")))
        val sources = listOf(
            ToolInputSourceSelection.Path("sample.bin", "binary"),
            ToolInputSourceSelection.Base64("AQID", "binary"),
            ToolInputSourceSelection.StdinBytes(byteArrayOf(0, 127, -1), "binary"),
            ToolInputSourceSelection.ByteList(listOf(0, 127, 255), "binary"),
        )

        sources.forEach { source ->
            assertEquals(
                params,
                converter.convert(
                    ToolInputConversionRequest(
                        context = context,
                        rawArguments = params,
                        sources = ToolInputSources(sourceSelections = listOf(source)),
                    )
                )
            )
        }
    }

    @Test
    fun rawOutputUsesBinaryPathAndScriptPath() {
        val binary = temporaryFolder.newFile("script-binary.bin").toPath()
        Files.write(binary, byteArrayOf(10, 20, 30, 40))
        val script = temporaryFolder.newFile("script-file.js").toPath()
        Files.writeString(script, "print(binary.bytes.size); print(binary.bytes.get(2));")

        val output = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.RAW,
            )
        )

        assertEquals("4\n30", output.stdout)
        assertEquals("", output.stderr)
        assertEquals(0, output.exitCode)
    }

    @Test
    fun jsonOutputUsesStdinBinaryAndInlineScript() {
        val output = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), "binary")),
                params = mapOf("script" to JsonPrimitive("print(binary.bytes.get(1));")),
                format = ToolOutputFormat.JSON,
            )
        )

        val json = Json.parseToJsonElement(output.stdout).jsonObject
        assertEquals("SUCCESS", json.getValue("status").jsonPrimitive.content)
        assertEquals(true, json.getValue("success").jsonPrimitive.boolean)
        assertEquals(listOf("2"), json.getValue("output").jsonObject.getValue("lines").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("generic", json.getValue("binary").jsonObject.getValue("format").jsonPrimitive.content)
        assertFalse("path" in json.getValue("binary").jsonObject)
        assertEquals("", output.stderr)
        assertEquals(0, output.exitCode)
    }

    @Test
    fun rawOutputUsesBase64BinaryAndInlineScript() {
        val output = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(
                    ToolInputSourceSelection.Base64(
                        bytesBase64 = Base64.getEncoder().encodeToString(byteArrayOf(5, 6)),
                        slotName = "binary",
                    )
                ),
                params = mapOf("script" to JsonPrimitive("print(binary.bytes.size);")),
                format = ToolOutputFormat.RAW,
            )
        )

        assertEquals("2", output.stdout)
        assertEquals("", output.stderr)
        assertEquals(0, output.exitCode)
    }

    @Test
    fun scriptFailureMapsToExitOneAndDiagnosticsOnStderrForRawOnly() {
        val raw = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.StdinBytes(byteArrayOf(1), "binary")),
                params = mapOf("script" to JsonPrimitive("throw new Error('boom');")),
                format = ToolOutputFormat.RAW,
            )
        )
        val json = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.StdinBytes(byteArrayOf(1), "binary")),
                params = mapOf("script" to JsonPrimitive("throw new Error('boom');")),
                format = ToolOutputFormat.JSON,
            )
        )

        assertEquals("", raw.stdout)
        assertTrue(raw.stderr.startsWith("RUNTIME_ERROR: "))
        assertTrue(raw.stderr.contains("boom"))
        assertEquals(1, raw.exitCode)

        assertEquals("", json.stderr)
        assertEquals(1, json.exitCode)
        assertEquals(false, Json.parseToJsonElement(json.stdout).jsonObject.getValue("success").jsonPrimitive.boolean)
    }

    @Test
    fun rawOutputRequiresExactlyOneScriptSource() {
        val missing = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.StdinBytes(byteArrayOf(1), "binary")),
                params = emptyMap(),
                format = ToolOutputFormat.RAW,
            )
        )
        val duplicate = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.StdinBytes(byteArrayOf(1), "binary")),
                params = mapOf(
                    "scriptPath" to JsonPrimitive("script.js"),
                    "script" to JsonPrimitive("print(1);"),
                ),
                format = ToolOutputFormat.RAW,
            )
        )

        assertEquals("", missing.stdout)
        assertEquals("INVALID_INPUT: Exactly one of --script or --script-text is required.\n", missing.stderr)
        assertEquals(1, missing.exitCode)
        assertEquals("INVALID_INPUT: Provide exactly one of --script or --script-text.\n", duplicate.stderr)
        assertEquals(1, duplicate.exitCode)
    }

    @Test
    fun scriptPathOutsideAllowedRootFailsWithoutHostAccess() {
        val allowedRoot = temporaryFolder.newFolder("allowed").toPath()
        val binary = allowedRoot.resolve("inside.bin")
        Files.write(binary, byteArrayOf(1, 2, 3))
        val script = temporaryFolder.newFile("outside-script.js").toPath()
        Files.writeString(script, "print(1);")

        val output = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.RAW,
                allowedFilePolicy = AllowedFilePolicy.requiringAllowedRoots(listOf(allowedRoot.toString())),
            )
        )

        assertEquals("", output.stdout)
        assertTrue(output.stderr.contains("INVALID_INPUT: "), output.stderr)
        assertTrue(output.stderr.contains("outside allowed roots"), output.stderr)
        assertEquals(1, output.exitCode)
    }

    @Test
    fun hostClassLookupIsDeniedBySandbox() {
        val output = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.StdinBytes(byteArrayOf(1), "binary")),
                params = mapOf("script" to JsonPrimitive("Java.type('java.lang.System');")),
                format = ToolOutputFormat.RAW,
            )
        )

        assertEquals("", output.stdout)
        assertTrue(output.stderr.isNotBlank(), output.stderr)
        assertTrue(output.stderr.contains("host class java.lang.System"), output.stderr)
        assertEquals(1, output.exitCode)
    }

    @Test
    fun timeoutCancelsScriptAndMapsToExitOne() {
        val output = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.StdinBytes(byteArrayOf(1), "binary")),
                params = mapOf("script" to JsonPrimitive("while (true) {}")),
                format = ToolOutputFormat.RAW,
                limits = HexanaScriptLimits(timeoutMillis = 25),
            )
        )

        assertEquals("", output.stdout)
        assertTrue(output.stderr.contains("TIMEOUT: Script timed out after 25 ms"), output.stderr)
        assertEquals(1, output.exitCode)
    }

    @Test
    fun rawOutputTrimsStdoutToKeepDiagnosticsWithinLimit() {
        val output = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.StdinBytes(byteArrayOf(1), "binary")),
                params = mapOf("script" to JsonPrimitive("print('x'.repeat(1000));")),
                format = ToolOutputFormat.RAW,
                limits = HexanaScriptLimits(maxOutputBytes = 100),
            )
        )

        assertEquals(0, output.exitCode)
        assertTrue(output.stdout.isNotEmpty())
        assertTrue(output.stderr.contains("OUTPUT_TRUNCATED: Script output exceeded the configured output limit"))
        assertTrue(output.byteSize() <= 100, "Output size was ${output.byteSize()} bytes")
    }

    @Test
    fun rendererRejectsPrettyOutput() {
        assertFailsWith<IllegalStateException> {
            RunScriptOutputRenderer.render(
                ToolOutputRenderRequest(
                    context = context,
                    arguments = JsonObject(emptyMap()),
                    result = JsonObject(
                        mapOf(
                            "status" to JsonPrimitive("SUCCESS"),
                            "success" to JsonPrimitive(true),
                            "output" to JsonObject(
                                mapOf(
                                    "lines" to kotlinx.serialization.json.JsonArray(emptyList()),
                                    "truncated" to JsonPrimitive(false),
                                    "emittedBytes" to JsonPrimitive(0),
                                    "maxLines" to JsonPrimitive(1),
                                    "maxBytes" to JsonPrimitive(1),
                                )
                            ),
                            "diagnostics" to kotlinx.serialization.json.JsonArray(emptyList()),
                            "binary" to JsonNull,
                            "sourceName" to JsonPrimitive("<inline>"),
                            "sourcePath" to JsonNull,
                            "durationMillis" to JsonPrimitive(0),
                        )
                    ),
                    options = ToolRenderOptions(format = ToolOutputFormat.PRETTY),
                )
            )
        }
    }
}

private fun String.productToolNames(productName: String): List<String> {
    val start = indexOf("HexanaCliReleaseProduct.$productName to listOf(")
    assertTrue(start >= 0, "Generated product block $productName must exist")
    val remaining = substring(start)
    val nextProduct = Regex("""\n\s*HexanaCliReleaseProduct\.""").find(remaining, startIndex = 1)
    val end = nextProduct?.range?.first ?: remaining.indexOf("\n      )")
    assertTrue(end > 0, "Generated product block $productName must have an end")
    val block = remaining.substring(0, end)

    return Regex("""HexanaCliProductToolSelection\("([^"]+)""")
        .findAll(block)
        .map { it.groupValues[1] }
        .toList()
}

private fun RunScriptCliOutput.byteSize(): Int =
    stdout.toByteArray(Charsets.UTF_8).size + stderr.toByteArray(Charsets.UTF_8).size
