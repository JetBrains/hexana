package org.jetbrains.hexana.mcp.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.jetbrains.hexana.mcp.HexanaAnalysisErrorCode
import org.jetbrains.hexana.mcp.HexanaAnalysisException
import org.jetbrains.hexana.mcp.HexanaResourceLimits
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
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
    fun renderSummarizeBinaryJsonReturnsSemanticJsonWithoutMcpWrapper() {
        val binary = temporaryFolder.newFile("sample.bin").toPath()
        Files.write(binary, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        val output = generatedExecutableCliToolCommandOutput(
            summarizeBinaryCommand(
                path = binary.toString(),
                format = HexanaCliOutputFormat.JSON,
            )
        ).stdout

        val json = Json.parseToJsonElement(output).jsonObject
        val module = json.getValue("module").jsonObject
        assertEquals("generic", module.getValue("format").jsonPrimitive.content)
        assertEquals(binary.toString(), module.getValue("path").jsonPrimitive.content)
        assertTrue(module.getValue("byteLength").jsonPrimitive.content.toInt() > 0)
        assertTrue("counts" in json)
        assertTrue("warnings" in json)
        assertFalse("content" in json)
        assertFalse("structuredContent" in json)
        assertFalse("isError" in json)
    }

    @Test
    fun renderSummarizeBinaryJsonEnforcesOutputLimit() {
        val binary = temporaryFolder.newFile("limited-binary.bin").toPath()
        Files.write(binary, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        val error = assertFailsWith<HexanaAnalysisException> {
            generatedExecutableCliToolCommandOutput(
                summarizeBinaryCommand(
                    path = binary.toString(),
                    format = HexanaCliOutputFormat.JSON,
                    resourceLimits = HexanaResourceLimits(maxOutputBytes = 4),
                )
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertTrue(error.message.orEmpty().contains("maxOutputBytes=4"))
    }

    @Test
    fun renderSummarizeBinaryJsonRejectsFileOutsideAllowedRoot() {
        val binary = temporaryFolder.newFile("outside-binary.bin").toPath()
        Files.write(binary, byteArrayOf(1, 2, 3, 4))
        val allowedRoot = temporaryFolder.newFolder("binary-allowed").toPath()

        val error = assertFailsWith<HexanaAnalysisException> {
            generatedExecutableCliToolCommandOutput(
                summarizeBinaryCommand(
                    path = binary.toString(),
                    format = HexanaCliOutputFormat.JSON,
                    allowedRoots = listOf(allowedRoot.toString()),
                )
            )
        }

        assertTrue(error.message.orEmpty().contains("outside allowed roots"))
    }

    @Test
    fun renderSummarizeBinaryJsonReadsStdinBytesWithoutAllowedRoot() {
        val allowedRoot = temporaryFolder.newFolder("stdin-allowed").toPath()

        val output = generatedExecutableCliToolCommandOutput(
            summarizeBinaryCommand(
                sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "binary"),
                format = HexanaCliOutputFormat.JSON,
                allowedRoots = listOf(allowedRoot.toString()),
            )
        ).stdout

        val module = Json.parseToJsonElement(output).jsonObject.getValue("module").jsonObject
        assertEquals("generic", module.getValue("format").jsonPrimitive.content)
        assertFalse("path" in module)
        assertEquals("3", module.getValue("byteLength").jsonPrimitive.content)
    }

    @Test
    fun renderSummarizeBinaryJsonReadsBase64WithoutAllowedRoot() {
        val allowedRoot = temporaryFolder.newFolder("base64-allowed").toPath()

        val output = generatedExecutableCliToolCommandOutput(
            summarizeBinaryCommand(
                sourceSelection = ToolInputSourceSelection.Base64(
                    bytesBase64 = Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6, 7)),
                    slotName = "binary",
                ),
                format = HexanaCliOutputFormat.JSON,
                allowedRoots = listOf(allowedRoot.toString()),
            )
        ).stdout

        val module = Json.parseToJsonElement(output).jsonObject.getValue("module").jsonObject
        assertEquals("generic", module.getValue("format").jsonPrimitive.content)
        assertFalse("path" in module)
        assertEquals("4", module.getValue("byteLength").jsonPrimitive.content)
    }

    @Test
    fun renderSummarizeBinaryJsonEnforcesInlineLimitForStdinBytes() {
        val error = assertFailsWith<HexanaAnalysisException> {
            generatedExecutableCliToolCommandOutput(
                summarizeBinaryCommand(
                    sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "binary"),
                    format = HexanaCliOutputFormat.JSON,
                    resourceLimits = HexanaResourceLimits(maxInlineBinaryBytes = 2),
                )
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertTrue(error.message.orEmpty().contains("Inline bytes exceed maximum size of 2 bytes"))
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
    fun renderSummarizeModuleJsonReadsStdinBytesWithoutAllowedRoot() {
        val wasmBytes = wat2wasm("(module (func (export \"main\")))")
        val allowedRoot = temporaryFolder.newFolder("summary-stdin-allowed").toPath()

        val output = generatedExecutableCliToolCommandOutput(
            summarizeModuleCommand(
                sourceSelection = ToolInputSourceSelection.StdinBytes(wasmBytes, slotName = "wasm"),
                format = HexanaCliOutputFormat.JSON,
                allowedRoots = listOf(allowedRoot.toString()),
            )
        ).stdout

        val json = Json.parseToJsonElement(output).jsonObject
        assertEquals("1", json.getValue("functions").jsonPrimitive.content)
        assertEquals("1", json.getValue("exports").jsonPrimitive.content)
    }

    @Test
    fun renderSummarizeModuleJsonReadsBase64WithoutAllowedRoot() {
        val wasmBytes = wat2wasm("(module (memory 1))")
        val allowedRoot = temporaryFolder.newFolder("summary-base64-allowed").toPath()

        val output = generatedExecutableCliToolCommandOutput(
            summarizeModuleCommand(
                sourceSelection = ToolInputSourceSelection.Base64(
                    bytesBase64 = Base64.getEncoder().encodeToString(wasmBytes),
                    slotName = "wasm",
                ),
                format = HexanaCliOutputFormat.JSON,
                allowedRoots = listOf(allowedRoot.toString()),
            )
        ).stdout

        val json = Json.parseToJsonElement(output).jsonObject
        assertEquals("1", json.getValue("memories").jsonPrimitive.content)
    }

    @Test
    fun renderSummarizeModuleJsonEnforcesInlineLimitForStdinBytes() {
        val error = assertFailsWith<HexanaAnalysisException> {
            generatedExecutableCliToolCommandOutput(
                summarizeModuleCommand(
                    sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "wasm"),
                    format = HexanaCliOutputFormat.JSON,
                    resourceLimits = HexanaResourceLimits(maxInlineWasmBytes = 2),
                )
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertTrue(error.message.orEmpty().contains("Inline bytes exceed maximum size of 2 bytes"))
    }

    @Test
    fun renderSummarizeModuleRejectsInvalidStdinWasmWithDomainError() {
        val error = assertFailsWith<HexanaAnalysisException> {
            generatedExecutableCliToolCommandOutput(
                summarizeModuleCommand(
                    sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "wasm"),
                    format = HexanaCliOutputFormat.JSON,
                )
            )
        }

        assertTrue(error.code != HexanaAnalysisErrorCode.INTERNAL_ERROR)
        assertFalse(error.message.orEmpty().contains("server logs"))
    }

    @Test
    fun renderSummarizeModuleRejectsInvalidBase64WasmWithDomainError() {
        val error = assertFailsWith<HexanaAnalysisException> {
            generatedExecutableCliToolCommandOutput(
                summarizeModuleCommand(
                    sourceSelection = ToolInputSourceSelection.Base64(bytesBase64 = "AQID", slotName = "wasm"),
                    format = HexanaCliOutputFormat.JSON,
                )
            )
        }

        assertTrue(error.code != HexanaAnalysisErrorCode.INTERNAL_ERROR)
        assertFalse(error.message.orEmpty().contains("server logs"))
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
    fun renderWasmToWatRawReadsStdinBytesWithoutAllowedRoot() {
        val wasmBytes = wat2wasm("(module)")
        val allowedRoot = temporaryFolder.newFolder("wat-stdin-allowed").toPath()

        val output = renderWasmToWatCommandOutput(
            wasmToWatCommand(
                sourceSelection = ToolInputSourceSelection.StdinBytes(wasmBytes, slotName = "wasm"),
                format = HexanaCliOutputFormat.RAW,
                allowedRoots = listOf(allowedRoot.toString()),
            )
        )

        assertTrue(output.stdout.contains("(module"))
        assertEquals("", output.stderr)
    }

    @Test
    fun renderWasmToWatRawReadsBase64WithoutAllowedRoot() {
        val wasmBytes = wat2wasm("(module)")
        val allowedRoot = temporaryFolder.newFolder("wat-base64-allowed").toPath()

        val output = renderWasmToWatCommandOutput(
            wasmToWatCommand(
                sourceSelection = ToolInputSourceSelection.Base64(
                    bytesBase64 = Base64.getEncoder().encodeToString(wasmBytes),
                    slotName = "wasm",
                ),
                format = HexanaCliOutputFormat.RAW,
                allowedRoots = listOf(allowedRoot.toString()),
            )
        )

        assertTrue(output.stdout.contains("(module"))
        assertEquals("", output.stderr)
    }

    @Test
    fun renderWasmToWatRawEnforcesInlineLimitForStdinBytes() {
        val error = assertFailsWith<HexanaAnalysisException> {
            renderWasmToWatCommandOutput(
                wasmToWatCommand(
                    sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "wasm"),
                    format = HexanaCliOutputFormat.RAW,
                    resourceLimits = HexanaResourceLimits(maxInlineWasmBytes = 2),
                )
            )
        }

        assertEquals(HexanaAnalysisErrorCode.RESOURCE_LIMIT_EXCEEDED, error.code)
        assertTrue(error.message.orEmpty().contains("Inline bytes exceed maximum size of 2 bytes"))
    }

    @Test
    fun renderWasmToWatRejectsNonWasmPathWithDomainError() {
        val textFile = temporaryFolder.newFile("not-wasm.txt").toPath()
        Files.writeString(textFile, "not wasm")

        val error = assertFailsWith<HexanaAnalysisException> {
            renderWasmToWatCommandOutput(
                wasmToWatCommand(
                    path = textFile.toString(),
                    format = HexanaCliOutputFormat.RAW,
                )
            )
        }

        assertEquals(HexanaAnalysisErrorCode.NOT_WASM, error.code)
        assertEquals("Not a .wasm file", error.message)
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
    fun renderRunScriptRawUsesBinaryPathAndScriptPath() {
        val binary = temporaryFolder.newFile("script-binary.bin").toPath()
        Files.write(binary, byteArrayOf(10, 20, 30, 40))
        val script = temporaryFolder.newFile("script-file.js").toPath()
        Files.writeString(script, "print(binary.bytes.size); print(binary.bytes.get(2));")

        val output = generatedExecutableCliToolCommandOutput(
            runScriptCommand(
                sourceSelection = ToolInputSourceSelection.Path(path = binary.toString(), slotName = "binary"),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = HexanaCliOutputFormat.RAW,
            )
        )

        assertEquals("4\n30", output.stdout)
        assertEquals("", output.stderr)
        assertEquals(0, output.exitCode)
    }

    @Test
    fun renderRunScriptJsonUsesStdinBinaryAndInlineScript() {
        val output = generatedExecutableCliToolCommandOutput(
            runScriptCommand(
                sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "binary"),
                params = mapOf("script" to JsonPrimitive("print(binary.bytes.get(1));")),
                format = HexanaCliOutputFormat.JSON,
            )
        )

        val json = Json.parseToJsonElement(output.stdout).jsonObject
        assertEquals("SUCCESS", json.getValue("status").jsonPrimitive.content)
        assertEquals(true, json.getValue("success").jsonPrimitive.boolean)
        assertEquals(listOf("2"), json.getValue("output").jsonObject.getValue("lines").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("generic", json.getValue("binary").jsonObject.getValue("format").jsonPrimitive.content)
        assertFalse("path" in json.getValue("binary").jsonObject)
        assertEquals(0, output.exitCode)
    }

    @Test
    fun renderRunScriptRawUsesBase64BinaryAndInlineScript() {
        val output = generatedExecutableCliToolCommandOutput(
            runScriptCommand(
                sourceSelection = ToolInputSourceSelection.Base64(
                    bytesBase64 = Base64.getEncoder().encodeToString(byteArrayOf(5, 6)),
                    slotName = "binary",
                ),
                params = mapOf("script" to JsonPrimitive("print(binary.bytes.size);")),
                format = HexanaCliOutputFormat.RAW,
            )
        )

        assertEquals("2", output.stdout)
        assertEquals("", output.stderr)
        assertEquals(0, output.exitCode)
    }

    @Test
    fun renderRunScriptRawAcceptsExactOutputBudgetWithoutTrailingNewline() {
        val output = generatedExecutableCliToolCommandOutput(
            runScriptCommand(
                sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "binary"),
                params = mapOf("script" to JsonPrimitive("print('abcd');")),
                format = HexanaCliOutputFormat.RAW,
                resourceLimits = HexanaResourceLimits(maxOutputBytes = 4),
            )
        )

        assertEquals("abcd", output.stdout)
        assertEquals("", output.stderr)
        assertEquals(4, output.byteSize())
        assertEquals(0, output.exitCode)
    }

    @Test
    fun renderRunScriptRawTrimsStdoutToKeepTruncationDiagnosticWithinOutputBudget() {
        val maxOutputBytes = 100

        val output = generatedExecutableCliToolCommandOutput(
            runScriptCommand(
                sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "binary"),
                params = mapOf("script" to JsonPrimitive("print('x'.repeat(1000));")),
                format = HexanaCliOutputFormat.RAW,
                resourceLimits = HexanaResourceLimits(maxOutputBytes = maxOutputBytes),
            )
        )

        assertEquals(0, output.exitCode)
        assertTrue(output.stdout.isNotEmpty())
        assertTrue(output.stderr.contains("OUTPUT_TRUNCATED: Script output exceeded the configured output limit"))
        assertTrue(output.byteSize() <= maxOutputBytes, "Output size was ${output.byteSize()} bytes")
    }

    @Test
    fun renderRunScriptRawWritesDiagnosticsToStderr() {
        val output = generatedExecutableCliToolCommandOutput(
            runScriptCommand(
                sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "binary"),
                params = mapOf("script" to JsonPrimitive("throw new Error('boom');")),
                format = HexanaCliOutputFormat.RAW,
            )
        )

        assertEquals("", output.stdout)
        assertTrue(output.stderr.startsWith("RUNTIME_ERROR: "))
        assertTrue(output.stderr.contains("boom"))
        assertEquals(1, output.exitCode)
    }

    @Test
    fun renderRunScriptJsonMarksFailedScriptAsNonZeroExit() {
        val output = generatedExecutableCliToolCommandOutput(
            runScriptCommand(
                sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1, 2, 3), slotName = "binary"),
                params = mapOf("script" to JsonPrimitive("throw new Error('boom');")),
                format = HexanaCliOutputFormat.JSON,
            )
        )

        val json = Json.parseToJsonElement(output.stdout).jsonObject
        assertEquals(false, json.getValue("success").jsonPrimitive.boolean)
        assertEquals("SCRIPT_ERROR", json.getValue("status").jsonPrimitive.content)
        assertEquals("", output.stderr)
        assertEquals(1, output.exitCode)
    }

    @Test
    fun renderRunScriptRawRequiresExactlyOneScriptSource() {
        val missing = generatedExecutableCliToolCommandOutput(
            runScriptCommand(
                sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1), slotName = "binary"),
                params = emptyMap(),
                format = HexanaCliOutputFormat.RAW,
            )
        )
        val duplicate = generatedExecutableCliToolCommandOutput(
            runScriptCommand(
                sourceSelection = ToolInputSourceSelection.StdinBytes(byteArrayOf(1), slotName = "binary"),
                params = mapOf(
                    "scriptPath" to JsonPrimitive("script.js"),
                    "script" to JsonPrimitive("print(1);"),
                ),
                format = HexanaCliOutputFormat.RAW,
            )
        )

        assertEquals("", missing.stdout)
        assertEquals(1, missing.exitCode)
        assertEquals("INVALID_INPUT: Exactly one of --script or --script-text is required.\n", missing.stderr)
        assertEquals(1, duplicate.exitCode)
        assertEquals("INVALID_INPUT: Provide exactly one of --script or --script-text.\n", duplicate.stderr)
    }

    @Test
    fun renderRunScriptRawRejectsScriptPathOutsideAllowedRootWithNonZeroExit() {
        val allowedRoot = temporaryFolder.newFolder("run-script-allowed").toPath()
        val binary = allowedRoot.resolve("inside.bin")
        Files.write(binary, byteArrayOf(1, 2, 3))
        val script = temporaryFolder.newFile("outside-script.js").toPath()
        Files.writeString(script, "print(1);")

        val output = generatedExecutableCliToolCommandOutput(
            runScriptCommand(
                sourceSelection = ToolInputSourceSelection.Path(path = binary.toString(), slotName = "binary"),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = HexanaCliOutputFormat.RAW,
                allowedRoots = listOf(allowedRoot.toString()),
            )
        )

        assertEquals("", output.stdout)
        assertTrue(output.stderr.contains("INVALID_INPUT: "), output.stderr)
        assertTrue(output.stderr.contains("outside allowed roots"), output.stderr)
        assertEquals(1, output.exitCode)
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
            "  groups: binary\n",
            "  status: executable\n",
            "  formats: json\n",
            "  default: json\n",
        )
        assertToolBlockContains(
            output,
            "wasm-to-wat",
            "  groups: converters\n",
            "  formats: json, raw\n",
            "  default: raw\n",
        )
        assertToolBlockContains(
            output,
            "run-script",
            "  groups: scripting\n",
            "  formats: json, raw\n",
            "  default: raw\n",
        )
        assertTrue(output.indexOf("summarize-binary\n") < output.indexOf("summarize-module\n"))
        assertTrue(output.indexOf("run-script\n") < output.indexOf("summarize-binary\n"))
        assertTrue(output.indexOf("summarize-module\n") < output.indexOf("wasm-to-wat\n"))
    }

    @Test
    fun renderToolsListingHidesTodoCommandsByDefault() {
        val output = renderCliToolsListing(HexanaCommand.Tools(group = null))

        HexanaGeneratedCliToolRegistry.TOOLS
            .filter { it.executionStatus == HexanaCliExecutionStatus.TODO }
            .forEach { spec ->
                assertFalse(output.contains("${spec.commandName}\n"), "${spec.commandName} must stay out of tools listing")
            }
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
        assertFalse(output.contains("list-enabled-features\n"))
        assertTrue(output.contains("  groups: module\n"))
        assertFalse(output.contains("wasm-to-wat\n"))
        assertFalse(output.contains("  groups: converters\n"))
    }

    @Test
    fun renderTodoToolNotImplementedMessageNamesCommandAndTool() {
        val spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.commandName == "list-enabled-features" }
        val output = renderTodoToolNotImplementedMessage(HexanaCommand.TodoTool(spec))

        assertTrue(output.contains("list-enabled-features"))
        assertTrue(output.contains("list_enabled_features"))
        assertTrue(output.contains("not implemented"))
        assertTrue(output.contains("TODO"))
    }

    @Test
    fun executableToolCarriesNamedSourcesAndParamsWithSingleSourceCompatibilityGuard() {
        val spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.commandName == "summarize-binary" }
        val command = HexanaCommand.ExecutableTool(
            spec = spec,
            sourceSelections = listOf(
                ToolInputSourceSelection.Path(path = "app.bin", slotName = "binary"),
                ToolInputSourceSelection.Path(path = "script.js", slotName = "script"),
            ),
            params = mapOf("scriptPath" to JsonPrimitive("script.js")),
            format = HexanaCliOutputFormat.JSON,
            allowedRoots = emptyList(),
            resourceLimits = HexanaResourceLimits(),
        )

        assertEquals(listOf("binary", "script"), command.sourceSelections.map { it.slotName })
        assertEquals(JsonPrimitive("script.js"), command.params["scriptPath"])
        val error = assertFailsWith<IllegalStateException> {
            command.sourceSelection
        }
        assertTrue(error.message.orEmpty().contains("has 2 source selections"))
        assertTrue(error.message.orEmpty().contains("single-source accessor"))
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
    ) = summarizeModuleCommand(
        sourceSelection = ToolInputSourceSelection.Path(path = path, slotName = "wasm"),
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun summarizeModuleCommand(
        sourceSelection: ToolInputSourceSelection,
        format: HexanaCliOutputFormat,
        allowedRoots: List<String> = emptyList(),
        resourceLimits: HexanaResourceLimits = HexanaResourceLimits(),
    ) = executableToolCommand(
        commandName = "summarize-module",
        sourceSelection = sourceSelection,
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun summarizeBinaryCommand(
        path: String,
        format: HexanaCliOutputFormat,
        allowedRoots: List<String> = emptyList(),
        resourceLimits: HexanaResourceLimits = HexanaResourceLimits(),
    ) = executableToolCommand(
        commandName = "summarize-binary",
        sourceSelection = ToolInputSourceSelection.Path(path = path, slotName = "binary"),
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun summarizeBinaryCommand(
        sourceSelection: ToolInputSourceSelection,
        format: HexanaCliOutputFormat,
        allowedRoots: List<String> = emptyList(),
        resourceLimits: HexanaResourceLimits = HexanaResourceLimits(),
    ) = executableToolCommand(
        commandName = "summarize-binary",
        sourceSelection = sourceSelection,
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun wasmToWatCommand(
        path: String,
        format: HexanaCliOutputFormat,
        allowedRoots: List<String> = emptyList(),
        resourceLimits: HexanaResourceLimits = HexanaResourceLimits(),
    ) = wasmToWatCommand(
        sourceSelection = ToolInputSourceSelection.Path(path = path, slotName = "wasm"),
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun wasmToWatCommand(
        sourceSelection: ToolInputSourceSelection,
        format: HexanaCliOutputFormat,
        allowedRoots: List<String> = emptyList(),
        resourceLimits: HexanaResourceLimits = HexanaResourceLimits(),
    ) = executableToolCommand(
        commandName = "wasm-to-wat",
        sourceSelection = sourceSelection,
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun runScriptCommand(
        sourceSelection: ToolInputSourceSelection,
        params: Map<String, JsonPrimitive>,
        format: HexanaCliOutputFormat,
        allowedRoots: List<String> = emptyList(),
        resourceLimits: HexanaResourceLimits = HexanaResourceLimits(),
    ) = HexanaCommand.ExecutableTool(
        spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.commandName == "run-script" },
        sourceSelections = listOf(sourceSelection),
        params = params,
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun executableToolCommand(
        commandName: String,
        sourceSelection: ToolInputSourceSelection,
        format: HexanaCliOutputFormat,
        allowedRoots: List<String>,
        resourceLimits: HexanaResourceLimits,
    ) = HexanaCommand.ExecutableTool(
        spec = HexanaGeneratedCliToolRegistry.TOOLS.single { it.commandName == commandName },
        sourceSelection = sourceSelection,
        format = format,
        allowedRoots = allowedRoots,
        resourceLimits = resourceLimits,
    )

    private fun createWasmFile(name: String, wat: String) = temporaryFolder.newFile(name).toPath().also {
        Files.write(it, wat2wasm(wat))
    }

    private fun HexanaCliCommandOutput.byteSize(): Int =
        stdout.toByteArray(StandardCharsets.UTF_8).size + stderr.toByteArray(StandardCharsets.UTF_8).size
}
