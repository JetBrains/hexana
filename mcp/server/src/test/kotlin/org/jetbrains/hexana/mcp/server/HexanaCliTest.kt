package org.jetbrains.hexana.mcp.server

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.NoSuchSubcommand
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.cli.metadata.*
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.test.*

class HexanaCliTest {
    private fun cli(
        envReader: (String) -> String? = { null },
        stdin: HexanaCliStdin = FakeCliStdin(),
    ) = HexanaCli(envReader = envReader, stdin = stdin)

    @Test
    fun parseReturnsHelpWhenNoArgumentsAreProvided() {
        assertEquals(HexanaCommand.Help, cli().parse(emptyArray()))
    }

    @Test
    fun parseUsesLibraryBackedDefaultsForStdio() {
        val command = cli().parse(arrayOf("stdio"))

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(HexanaLogLevel.INFO, command.logLevel)
        assertTrue(command.analyticsEnabled)
        assertEquals(HexanaMcpToolProfile.DEFAULT, command.toolProfile)
        assertEquals(emptyList(), command.allowedRoots)
        assertEquals(HexanaAnalysisLimits.MAX_FILE_WASM_BYTES, command.resourceLimits.maxFileWasmBytes)
        assertEquals(HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES, command.resourceLimits.maxInlineWasmBytes)
    }

    @Test
    fun parseAcceptsResourceLimitOptionsForStdio() {
        val command = cli().parse(
            arrayOf(
                "stdio",
                "--max-file-bytes", "1048576",
                "--max-inline-bytes", "4096",
                "--max-text-file-bytes", "8192",
                "--max-output-bytes", "2000",
                "--max-candidate-count", "7",
                "--max-scan-roots", "2",
                "--max-scan-depth", "3",
            )
        )

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(1_048_576, command.resourceLimits.maxFileWasmBytes)
        assertEquals(1_048_576, command.resourceLimits.maxFileBinaryBytes)
        assertEquals(4_096, command.resourceLimits.maxInlineWasmBytes)
        assertEquals(4_096, command.resourceLimits.maxInlineBinaryBytes)
        assertEquals(8_192, command.resourceLimits.maxTextFileBytes)
        assertEquals(2_000, command.resourceLimits.maxOutputBytes)
        assertEquals(7, command.resourceLimits.maxCandidateCount)
        assertEquals(2, command.resourceLimits.maxScanRoots)
        assertEquals(3, command.resourceLimits.maxScanDepth)
    }

    @Test
    fun parseEnablesTelemetryByDefaultInNativeImageRuntime() {
        withNativeImageRuntimeProperty {
            val command = cli().parse(arrayOf("stdio"))

            assertIs<HexanaCommand.Stdio>(command)
            assertTrue(command.analyticsEnabled)
        }
    }

    @Test
    fun parseAcceptsExplicitTelemetryOptInInNativeImageRuntime() {
        withNativeImageRuntimeProperty {
            val command = cli().parse(arrayOf("stdio", "--telemetry-enabled", "true"))

            assertIs<HexanaCommand.Stdio>(command)
            assertTrue(command.analyticsEnabled)
        }
    }

    @Test
    fun parseAcceptsTelemetryOptOutForStdio() {
        val command = cli().parse(arrayOf("stdio", "--telemetry-enabled", "false"))

        assertIs<HexanaCommand.Stdio>(command)
        assertFalse(command.analyticsEnabled)
    }

    @Test
    fun parseAcceptsAdvancedToolProfileForStdio() {
        val command = cli().parse(arrayOf("stdio", "--tool-profile", "advanced"))

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(HexanaMcpToolProfile.ADVANCED, command.toolProfile)
    }

    @Test
    fun parseAcceptsCaseInsensitiveDefaultToolProfileForStdio() {
        val command = cli().parse(arrayOf("stdio", "--tool-profile", "DeFaUlT"))

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(HexanaMcpToolProfile.DEFAULT, command.toolProfile)
    }

    @Test
    fun parseAcceptsScriptingToolProfileForStdio() {
        val command = cli().parse(arrayOf("stdio", "--tool-profile", "scripting"))

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(HexanaMcpToolProfile.SCRIPTING, command.toolProfile)
    }

    @Test
    fun parseAcceptsRepeatedAllowedRootsForStdio() {
        val command = cli().parse(
            arrayOf(
                "stdio",
                "--allowed-root",
                "/work/project",
                "--allowed-root",
                "/work/deps",
            )
        )

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(listOf("/work/project", "/work/deps"), command.allowedRoots)
    }

    @Test
    fun parseAcceptsAllowedRootsFromEnvForStdio() {
        val command = HexanaCli(envReader = { name ->
            if (name == "HEXANA_MCP_ALLOWED_ROOTS") {
                listOf("/env/project", "/env/deps").joinToString(File.pathSeparator)
            } else {
                null
            }
        }).parse(arrayOf("stdio"))

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(listOf("/env/project", "/env/deps"), command.allowedRoots)
    }

    @Test
    fun parseCombinesAllowedRootsFromCliAndEnvForHttp() {
        val command = HexanaCli(envReader = { name ->
            if (name == "HEXANA_MCP_ALLOWED_ROOTS") "/env/project,/env/deps" else null
        }).parse(
            arrayOf(
                "http",
                "--allowed-root",
                "/cli/project",
            )
        )

        assertIs<HexanaCommand.Http>(command)
        assertEquals(listOf("/cli/project", "/env/project", "/env/deps"), command.allowedRoots)
    }

    @Test
    fun parseCombinesAllowedRootsFromCliAndEnvForDoctor() {
        val command = HexanaCli(envReader = { name ->
            if (name == "HEXANA_MCP_ALLOWED_ROOTS") "/env/project,/env/deps" else null
        }).parse(
            arrayOf(
                "doctor",
                "--allowed-root",
                "/cli/project",
            )
        )

        assertIs<HexanaCommand.Doctor>(command)
        assertEquals(listOf("/cli/project", "/env/project", "/env/deps"), command.allowedRoots)
    }

    @Test
    fun parseUsesGeneratedDefaultFormatForSummarizeModule() {
        val command = assertGeneratedExecutableTool(
            cli().parse(arrayOf("summarize-module", "app.wasm")),
            "summarize-module",
        )
        assertEquals("app.wasm", command.path)
        assertEquals(HexanaCliOutputFormat.PRETTY, command.format)
        assertEquals(emptyList(), command.allowedRoots)
        assertEquals(HexanaAnalysisLimits.MAX_FILE_WASM_BYTES, command.resourceLimits.maxFileWasmBytes)
    }

    @Test
    fun parseAcceptsExplicitSummarizeModuleJsonFormat() {
        val command = assertGeneratedExecutableTool(
            cli().parse(arrayOf("summarize-module", "app.wasm", "--format", "json")),
            "summarize-module",
        )
        assertEquals(HexanaCliOutputFormat.JSON, command.format)
    }

    @Test
    fun parseAcceptsExplicitSummarizeModulePrettyFormat() {
        val command = assertGeneratedExecutableTool(
            cli().parse(arrayOf("summarize-module", "app.wasm", "--format", "pretty")),
            "summarize-module",
        )
        assertEquals(HexanaCliOutputFormat.PRETTY, command.format)
    }

    @Test
    fun parseAcceptsWasmExecutableDashAsRawStdinBytes() {
        wasmExecutableCommandNames.forEach { commandName ->
            val stdin = FakeCliStdin(explicitBytes = byteArrayOf(1, 2, 3))
            val command = assertGeneratedExecutableTool(
                cli(stdin = stdin).parse(arrayOf(commandName, "-", "--format", "json")),
                commandName,
            )

            val source = assertIs<ToolInputSourceSelection.StdinBytes>(command.sourceSelection)
            assertEquals("wasm", source.slotName)
            assertContentEquals(byteArrayOf(1, 2, 3), source.bytes())
            assertEquals(1, stdin.explicitReadCount)
            assertEquals(listOf(HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES), stdin.explicitMaxBytes)
            assertEquals(0, stdin.autoReadCount)
        }
    }

    @Test
    fun parseAcceptsWasmExecutableStdinOptionAsRawStdinBytes() {
        wasmExecutableCommandNames.forEach { commandName ->
            val stdin = FakeCliStdin(explicitBytes = byteArrayOf(4, 5, 6))
            val command = assertGeneratedExecutableTool(
                cli(stdin = stdin).parse(arrayOf(commandName, "--stdin", "--format", "json")),
                commandName,
            )

            val source = assertIs<ToolInputSourceSelection.StdinBytes>(command.sourceSelection)
            assertEquals("wasm", source.slotName)
            assertContentEquals(byteArrayOf(4, 5, 6), source.bytes())
            assertEquals(1, stdin.explicitReadCount)
            assertEquals(listOf(HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES), stdin.explicitMaxBytes)
            assertEquals(0, stdin.autoReadCount)
        }
    }

    @Test
    fun parseAcceptsWasmExecutableAutoStdinWhenNoSourceIsProvided() {
        wasmExecutableCommandNames.forEach { commandName ->
            val stdin = FakeCliStdin(autoBytes = byteArrayOf(7, 8, 9))
            val command = assertGeneratedExecutableTool(
                cli(stdin = stdin).parse(arrayOf(commandName, "--format", "json")),
                commandName,
            )

            val source = assertIs<ToolInputSourceSelection.StdinBytes>(command.sourceSelection)
            assertEquals("wasm", source.slotName)
            assertContentEquals(byteArrayOf(7, 8, 9), source.bytes())
            assertEquals(0, stdin.explicitReadCount)
            assertEquals(1, stdin.autoReadCount)
            assertEquals(listOf(HexanaAnalysisLimits.MAX_INLINE_WASM_BYTES), stdin.autoMaxBytes)
        }
    }

    @Test
    fun parseRejectsWasmExecutableStdinOverInlineLimit() {
        wasmExecutableCommandNames.forEach { commandName ->
            val stdin = FakeCliStdin(explicitBytes = byteArrayOf(1, 2, 3))
            val error = assertFailsWith<CliktError> {
                cli(stdin = stdin).parse(
                    arrayOf(commandName, "--stdin", "--format", "json", "--max-inline-bytes", "2")
                )
            }

            assertEquals(listOf(2), stdin.explicitMaxBytes)
            assertTrue(error.message.orEmpty().contains("Inline bytes exceed maximum size of 2 bytes"))
        }
    }

    @Test
    fun parseRejectsWasmExecutableAutoStdinOverInlineLimit() {
        wasmExecutableCommandNames.forEach { commandName ->
            val stdin = FakeCliStdin(autoBytes = byteArrayOf(1, 2, 3))
            val error = assertFailsWith<CliktError> {
                cli(stdin = stdin).parse(
                    arrayOf(commandName, "--format", "json", "--max-inline-bytes", "2")
                )
            }

            assertEquals(0, stdin.explicitReadCount)
            assertEquals(listOf(2), stdin.autoMaxBytes)
            assertTrue(error.message.orEmpty().contains("Inline bytes exceed maximum size of 2 bytes"))
        }
    }

    @Test
    fun parseAcceptsWasmExecutableBase64Source() {
        wasmExecutableCommandNames.forEach { commandName ->
            val command = assertGeneratedExecutableTool(
                cli().parse(arrayOf(commandName, "--wasm-base64", "AQID", "--format", "json")),
                commandName,
            )

            val source = assertIs<ToolInputSourceSelection.Base64>(command.sourceSelection)
            assertEquals("wasm", source.slotName)
            assertEquals("AQID", source.bytesBase64)
        }
    }

    @Test
    fun parseRejectsMissingWasmExecutableSourceWhenNoStdinIsPiped() {
        wasmExecutableCommandNames.forEach { commandName ->
            val error = assertFailsWith<CliktError> {
                cli(stdin = FakeCliStdin(autoBytes = null)).parse(arrayOf(commandName, "--format", "json"))
            }

            assertTrue(error.message.orEmpty().contains("Missing $commandName source"))
            assertTrue(error.message.orEmpty().contains("--stdin"))
            assertTrue(error.message.orEmpty().contains("--wasm-base64"))
        }
    }

    @Test
    fun parseRejectsMutuallyExclusiveWasmExecutableSources() {
        wasmExecutableCommandNames.forEach { commandName ->
            val conflictingArgs = listOf(
                arrayOf(commandName, "app.wasm", "--stdin", "--format", "json"),
                arrayOf(commandName, "-", "--wasm-base64", "AQID", "--format", "json"),
                arrayOf(commandName, "--stdin", "--wasm-base64", "AQID", "--format", "json"),
                arrayOf(commandName, "--wasm-base64", "AQID", "--bytes-base64", "BAUG", "--format", "json"),
                arrayOf(commandName, "--wasm-base64", "AQID", "--wasm-base64", "BAUG", "--format", "json"),
            )

            conflictingArgs.forEach { args ->
                val error = assertFailsWith<CliktError> {
                    cli(stdin = FakeCliStdin(explicitBytes = byteArrayOf(1))).parse(args)
                }

                assertTrue(error.message.orEmpty().contains("Provide exactly one $commandName source"))
                assertTrue(error.message.orEmpty().contains("--wasm-base64"))
            }
        }
    }

    @Test
    fun parseUsesGeneratedDefaultFormatForSummarizeBinary() {
        val command = assertGeneratedExecutableTool(
            cli().parse(arrayOf("summarize-binary", "app.bin")),
            "summarize-binary",
        )
        val source = assertIs<ToolInputSourceSelection.Path>(command.sourceSelection)
        assertEquals("binary", source.slotName)
        assertEquals("app.bin", command.path)
        assertEquals(HexanaCliOutputFormat.JSON, command.format)
        assertEquals(emptyList(), command.allowedRoots)
        assertEquals(HexanaAnalysisLimits.MAX_FILE_BINARY_BYTES, command.resourceLimits.maxFileBinaryBytes)
    }

    @Test
    fun parseAcceptsSummarizeBinaryDashAsRawStdinBytes() {
        val stdin = FakeCliStdin(explicitBytes = byteArrayOf(1, 2, 3))
        val command = assertGeneratedExecutableTool(
            cli(stdin = stdin).parse(arrayOf("summarize-binary", "-", "--format", "json")),
            "summarize-binary",
        )

        val source = assertIs<ToolInputSourceSelection.StdinBytes>(command.sourceSelection)
        assertEquals("binary", source.slotName)
        assertContentEquals(byteArrayOf(1, 2, 3), source.bytes())
        assertEquals(1, stdin.explicitReadCount)
        assertEquals(listOf(HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES), stdin.explicitMaxBytes)
        assertEquals(0, stdin.autoReadCount)
    }

    @Test
    fun parseAcceptsSummarizeBinaryStdinOptionAsRawStdinBytes() {
        val stdin = FakeCliStdin(explicitBytes = byteArrayOf(4, 5, 6))
        val command = assertGeneratedExecutableTool(
            cli(stdin = stdin).parse(arrayOf("summarize-binary", "--stdin", "--format", "json")),
            "summarize-binary",
        )

        val source = assertIs<ToolInputSourceSelection.StdinBytes>(command.sourceSelection)
        assertEquals("binary", source.slotName)
        assertContentEquals(byteArrayOf(4, 5, 6), source.bytes())
        assertEquals(1, stdin.explicitReadCount)
        assertEquals(listOf(HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES), stdin.explicitMaxBytes)
        assertEquals(0, stdin.autoReadCount)
    }

    @Test
    fun parseAcceptsSummarizeBinaryAutoStdinWhenNoSourceIsProvided() {
        val stdin = FakeCliStdin(autoBytes = byteArrayOf(7, 8, 9))
        val command = assertGeneratedExecutableTool(
            cli(stdin = stdin).parse(arrayOf("summarize-binary", "--format", "json")),
            "summarize-binary",
        )

        val source = assertIs<ToolInputSourceSelection.StdinBytes>(command.sourceSelection)
        assertEquals("binary", source.slotName)
        assertContentEquals(byteArrayOf(7, 8, 9), source.bytes())
        assertEquals(0, stdin.explicitReadCount)
        assertEquals(1, stdin.autoReadCount)
        assertEquals(listOf(HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES), stdin.autoMaxBytes)
    }

    @Test
    fun parseRejectsSummarizeBinaryStdinOverInlineLimit() {
        val stdin = FakeCliStdin(explicitBytes = byteArrayOf(1, 2, 3))
        val error = assertFailsWith<CliktError> {
            cli(stdin = stdin).parse(
                arrayOf("summarize-binary", "--stdin", "--format", "json", "--max-inline-bytes", "2")
            )
        }

        assertEquals(listOf(2), stdin.explicitMaxBytes)
        assertTrue(error.message.orEmpty().contains("Inline bytes exceed maximum size of 2 bytes"))
    }

    @Test
    fun parseRejectsSummarizeBinaryAutoStdinOverInlineLimit() {
        val stdin = FakeCliStdin(autoBytes = byteArrayOf(1, 2, 3))
        val error = assertFailsWith<CliktError> {
            cli(stdin = stdin).parse(
                arrayOf("summarize-binary", "--format", "json", "--max-inline-bytes", "2")
            )
        }

        assertEquals(0, stdin.explicitReadCount)
        assertEquals(listOf(2), stdin.autoMaxBytes)
        assertTrue(error.message.orEmpty().contains("Inline bytes exceed maximum size of 2 bytes"))
    }

    @Test
    fun parseAcceptsSummarizeBinaryBase64Source() {
        val command = assertGeneratedExecutableTool(
            cli().parse(arrayOf("summarize-binary", "--binary-base64", "AQID", "--format", "json")),
            "summarize-binary",
        )

        val source = assertIs<ToolInputSourceSelection.Base64>(command.sourceSelection)
        assertEquals("binary", source.slotName)
        assertEquals("AQID", source.bytesBase64)
    }

    @Test
    fun parseRejectsMissingSummarizeBinarySourceWhenNoStdinIsPiped() {
        val error = assertFailsWith<CliktError> {
            cli(stdin = FakeCliStdin(autoBytes = null)).parse(arrayOf("summarize-binary", "--format", "json"))
        }

        assertTrue(error.message.orEmpty().contains("Missing summarize-binary source"))
        assertTrue(error.message.orEmpty().contains("--stdin"))
        assertTrue(error.message.orEmpty().contains("--binary-base64"))
    }

    @Test
    fun parseRejectsMutuallyExclusiveSummarizeBinarySources() {
        val conflictingArgs = listOf(
            arrayOf("summarize-binary", "app.bin", "--stdin", "--format", "json"),
            arrayOf("summarize-binary", "-", "--binary-base64", "AQID", "--format", "json"),
            arrayOf("summarize-binary", "--stdin", "--binary-base64", "AQID", "--format", "json"),
            arrayOf("summarize-binary", "--binary-base64", "AQID", "--bytes-base64", "BAUG", "--format", "json"),
            arrayOf("summarize-binary", "--binary-base64", "AQID", "--binary-base64", "BAUG", "--format", "json"),
        )

        conflictingArgs.forEach { args ->
            val error = assertFailsWith<CliktError> {
                cli(stdin = FakeCliStdin(explicitBytes = byteArrayOf(1))).parse(args)
            }

            assertTrue(error.message.orEmpty().contains("Provide exactly one summarize-binary source"))
            assertTrue(error.message.orEmpty().contains("--binary-base64"))
        }
    }

    @Test
    fun parseAcceptsExplicitSummarizeBinaryJsonFormat() {
        val command = assertGeneratedExecutableTool(
            cli().parse(arrayOf("summarize-binary", "app.bin", "--format", "json")),
            "summarize-binary",
        )
        assertEquals(HexanaCliOutputFormat.JSON, command.format)
    }

    @Test
    fun parsePassesRunScriptScriptPathParam() {
        val command = assertGeneratedExecutableTool(
            cli().parse(arrayOf("run-script", "app.bin", "--script", "script.js")),
            "run-script",
        )

        val source = assertIs<ToolInputSourceSelection.Path>(command.sourceSelection)
        assertEquals("binary", source.slotName)
        assertEquals("app.bin", source.path)
        assertEquals(HexanaCliOutputFormat.RAW, command.format)
        assertEquals("script.js", command.params.getValue("scriptPath").jsonPrimitive.content)
        assertFalse("script" in command.params)
    }

    @Test
    fun parsePassesRunScriptInlineScriptParam() {
        val command = assertGeneratedExecutableTool(
            cli().parse(arrayOf("run-script", "app.bin", "--script-text", "print(binary.bytes.size);")),
            "run-script",
        )

        assertEquals("print(binary.bytes.size);", command.params.getValue("script").jsonPrimitive.content)
        assertFalse("scriptPath" in command.params)
    }

    @Test
    fun parseAcceptsRunScriptStdinBinaryAndInlineScript() {
        val stdin = FakeCliStdin(explicitBytes = byteArrayOf(1, 2, 3))
        val command = assertGeneratedExecutableTool(
            cli(stdin = stdin).parse(arrayOf("run-script", "--stdin", "--script-text", "print(1);")),
            "run-script",
        )

        val source = assertIs<ToolInputSourceSelection.StdinBytes>(command.sourceSelection)
        assertEquals("binary", source.slotName)
        assertContentEquals(byteArrayOf(1, 2, 3), source.bytes())
        assertEquals("print(1);", command.params.getValue("script").jsonPrimitive.content)
        assertEquals(listOf(HexanaAnalysisLimits.MAX_INLINE_BINARY_BYTES), stdin.explicitMaxBytes)
    }

    @Test
    fun parseAcceptsRunScriptBase64BinaryAndInlineScript() {
        val command = assertGeneratedExecutableTool(
            cli().parse(arrayOf("run-script", "--binary-base64", "AQID", "--script-text", "print(1);")),
            "run-script",
        )

        val source = assertIs<ToolInputSourceSelection.Base64>(command.sourceSelection)
        assertEquals("binary", source.slotName)
        assertEquals("AQID", source.bytesBase64)
        assertEquals("print(1);", command.params.getValue("script").jsonPrimitive.content)
    }

    @Test
    fun parseAcceptsRunScriptRawAndJsonFormats() {
        val raw = assertGeneratedExecutableTool(
            cli().parse(arrayOf("run-script", "app.bin", "--script-text", "print(1);", "--format", "raw")),
            "run-script",
        )
        val json = assertGeneratedExecutableTool(
            cli().parse(arrayOf("run-script", "app.bin", "--script-text", "print(1);", "--format", "json")),
            "run-script",
        )

        assertEquals(HexanaCliOutputFormat.RAW, raw.format)
        assertEquals(HexanaCliOutputFormat.JSON, json.format)
    }

    @Test
    fun parseRejectsRunScriptPrettyFormat() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("run-script", "app.bin", "--script-text", "print(1);", "--format", "pretty"))
        }

        assertTrue(error.message.orEmpty().contains("Unsupported run-script format"))
        assertTrue(error.message.orEmpty().contains("json"))
        assertTrue(error.message.orEmpty().contains("raw"))
    }

    @Test
    fun parseRejectsSummarizeBinaryFormatsNotSupportedByGeneratedMetadata() {
        listOf("pretty", "raw").forEach { format ->
            val error = assertFailsWith<CliktError> {
                cli().parse(arrayOf("summarize-binary", "app.bin", "--format", format))
            }

            assertTrue(error.message.orEmpty().contains("Unsupported summarize-binary format"))
            assertTrue(error.message.orEmpty().contains("json"))
        }
    }

    @Test
    fun parseCombinesAllowedRootsFromCliAndEnvForSummarizeModule() {
        val command = assertGeneratedExecutableTool(
            HexanaCli(envReader = { name ->
                if (name == "HEXANA_MCP_ALLOWED_ROOTS") "/env/project,/env/deps" else null
            }).parse(
                arrayOf(
                    "summarize-module",
                    "app.wasm",
                    "--allowed-root",
                    "/cli/project",
                )
            ),
            "summarize-module",
        )
        assertEquals(listOf("/cli/project", "/env/project", "/env/deps"), command.allowedRoots)
    }

    @Test
    fun parseRejectsSummarizeModuleFormatNotSupportedByGeneratedMetadata() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("summarize-module", "app.wasm", "--format", "raw"))
        }

        assertTrue(error.message.orEmpty().contains("Unsupported summarize-module format"))
        assertTrue(error.message.orEmpty().contains("json"))
        assertTrue(error.message.orEmpty().contains("pretty"))
    }

    @Test
    fun parseUsesGeneratedDefaultFormatForWasmToWat() {
        val command = assertGeneratedExecutableTool(
            cli().parse(arrayOf("wasm-to-wat", "app.wasm")),
            "wasm-to-wat",
        )
        assertEquals("app.wasm", command.path)
        assertEquals(HexanaCliOutputFormat.RAW, command.format)
        assertEquals(emptyList(), command.allowedRoots)
        assertEquals(HexanaAnalysisLimits.MAX_FILE_WASM_BYTES, command.resourceLimits.maxFileWasmBytes)
    }

    @Test
    fun parseAcceptsExplicitWasmToWatRawAndJsonFormats() {
        val raw = cli().parse(arrayOf("wasm-to-wat", "app.wasm", "--format", "raw"))
        val json = cli().parse(arrayOf("wasm-to-wat", "app.wasm", "--format", "json"))

        val rawCommand = assertGeneratedExecutableTool(raw, "wasm-to-wat")
        assertEquals(HexanaCliOutputFormat.RAW, rawCommand.format)
        val jsonCommand = assertGeneratedExecutableTool(json, "wasm-to-wat")
        assertEquals(HexanaCliOutputFormat.JSON, jsonCommand.format)
    }

    @Test
    fun parseRejectsWasmToWatPrettyFormat() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("wasm-to-wat", "app.wasm", "--format", "pretty"))
        }

        assertTrue(error.message.orEmpty().contains("Unsupported wasm-to-wat format"))
        assertTrue(error.message.orEmpty().contains("json"))
        assertTrue(error.message.orEmpty().contains("raw"))
    }

    @Test
    fun generatedRegistryKeepsTodoMetadataWithoutRegisteringTodoRootCommands() {
        val rootHelp = cli().helpText()

        HexanaGeneratedCliToolRegistry.TOOLS
            .filter { it.executionStatus == HexanaCliExecutionStatus.TODO }
            .forEach { spec ->
                assertFalse(rootHelp.contains(spec.commandName), "${spec.commandName} must not appear in root help")

                val error = assertFailsWith<CliktError> {
                    cli().parse(arrayOf(spec.commandName))
                }

                assertIs<NoSuchSubcommand>(error, "${spec.commandName} must not parse as a root command")
                assertFalse(error.message.orEmpty().contains("TODO"))
                assertFalse(error.message.orEmpty().contains("not implemented"))
            }
    }

    @Test
    fun parseRegistersEveryExecutableGeneratedCliToolAsRuntimeCommandWithHelp() {
        HexanaGeneratedCliToolRegistry.TOOLS
            .filter { it.executionStatus == HexanaCliExecutionStatus.EXECUTABLE }
            .forEach { spec ->
                val command = assertGeneratedExecutableTool(
                    cli().parse(arrayOf(spec.commandName, "app.wasm")),
                    spec.commandName,
                )

                assertEquals(spec, command.spec)

                val help = cli().helpText(arrayOf(spec.commandName, "--help"))

                assertTrue(
                    help.normalizedWhitespace().contains(spec.summary.normalizedWhitespace()),
                    "${spec.commandName} help must include generated summary"
                )
                assertTrue(help.contains("Usage:"), "${spec.commandName} help must include normal command usage")
                val expectedPathHelp = if (HexanaCliInputMode.BINARY_PATH in spec.inputModes) {
                    if (HexanaCliInputMode.STDIN in spec.inputModes) {
                        "Path to a binary file, or '-' to read raw stdin bytes."
                    } else {
                        "Path to a binary file."
                    }
                } else if (HexanaCliInputMode.STDIN in spec.inputModes) {
                    "Path to a WebAssembly module, or '-' to read raw stdin bytes."
                } else {
                    "Path to a WebAssembly module."
                }
                assertTrue(help.contains(expectedPathHelp), "${spec.commandName} help must document path input")
                if (HexanaCliInputMode.BINARY_PATH in spec.inputModes) {
                    assertTrue(help.contains("--stdin"), "${spec.commandName} help must document stdin input")
                    assertTrue(help.contains("--binary-base64"), "${spec.commandName} help must document base64 input")
                } else if (HexanaCliInputMode.STDIN in spec.inputModes) {
                    assertTrue(help.contains("--stdin"), "${spec.commandName} help must document stdin input")
                    assertTrue(help.contains("--wasm-base64"), "${spec.commandName} help must document base64 input")
                }
                assertTrue(help.contains("--format"), "${spec.commandName} help must document format option")
                assertTrue(help.contains(spec.supportedFormatNames()), "${spec.commandName} help must document generated formats")
            }
    }

    @Test
    fun parseRejectsUnknownCommandWithoutTreatingItAsTodo() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("definitely-not-a-command"))
        }

        assertIs<NoSuchSubcommand>(error)
        assertFalse(error.message.orEmpty().contains("TODO"))
        assertFalse(error.message.orEmpty().contains("not implemented"))
    }

    @Test
    fun parseKeepsExecutableGeneratedCommandsOnGeneratedExecutableVariant() {
        val summarize = assertGeneratedExecutableTool(
            cli().parse(arrayOf("summarize-module", "app.wasm")),
            "summarize-module",
        )
        val wat = assertGeneratedExecutableTool(
            cli().parse(arrayOf("wasm-to-wat", "app.wasm")),
            "wasm-to-wat",
        )

        assertEquals("app.wasm", summarize.path)
        assertEquals("app.wasm", wat.path)
    }

    @Test
    fun rootCliCommandNameGuardAcceptsCurrentRegistration() {
        val command = cli().parse(emptyArray())

        assertEquals(HexanaCommand.Help, command)
    }

    @Test
    fun generatedCommandNamesAreUnique() {
        val duplicateNames = HexanaGeneratedCliToolRegistry.TOOLS
            .map { it.commandName }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        assertEquals(emptySet(), duplicateNames)
    }

    @Test
    fun rootCliCommandNameGuardRejectsGeneratedCommandCollidingWithManualServiceCommand() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateRootCliCommandNames(
                manualCommandNames = listOf("stdio", "tools"),
                generatedCommandNames = listOf("summarize-module", "stdio"),
            )
        }

        assertTrue(error.message.orEmpty().contains("manual service command names"))
        assertTrue(error.message.orEmpty().contains("stdio"))
    }

    @Test
    fun rootCliCommandNameGuardRejectsDuplicateGeneratedCommands() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateRootCliCommandNames(
                manualCommandNames = listOf("stdio", "tools"),
                generatedCommandNames = listOf("summarize-module", "summarize-module"),
            )
        }

        assertTrue(error.message.orEmpty().contains("Generated CLI command names must be unique"))
        assertTrue(error.message.orEmpty().contains("summarize-module"))
    }

    @Test
    fun parseAcceptsToolsCommandWithoutGroupFilter() {
        val command = cli().parse(arrayOf("tools"))

        assertEquals(HexanaCommand.Tools(group = null), command)
    }

    @Test
    fun parseAcceptsToolsCommandWithGeneratedKebabCaseGroupFilter() {
        val command = cli().parse(arrayOf("tools", "--group", "converters"))

        assertEquals(HexanaCommand.Tools(group = HexanaCliToolGroup.CONVERTERS), command)
    }

    @Test
    fun parseRejectsUnknownToolsGroupWithSupportedGeneratedGroups() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("tools", "--group", "mcp-profile-production"))
        }

        assertTrue(error.message.orEmpty().contains("Unsupported CLI tool metadata group"))
        assertTrue(error.message.orEmpty().contains("converters"))
        assertTrue(error.message.orEmpty().contains("module"))
        assertTrue(error.message.orEmpty().contains("function"))
        assertTrue(error.message.orEmpty().contains("scripting"))
    }

    @Test
    fun parseNormalizesHttpEndpointAndKeepsLoopbackDefaults() {
        val command = cli().parse(
            arrayOf(
                "http",
                "--endpoint",
                "api",
                "--log-level",
                "debug",
                "--telemetry-enabled",
                "false",
                "--tool-profile",
                "advanced",
            )
        )

        assertIs<HexanaCommand.Http>(command)
        assertEquals("127.0.0.1", command.host)
        assertEquals(3000, command.port)
        assertEquals("/api", command.endpoint)
        assertEquals(HexanaLogLevel.DEBUG, command.logLevel)
        assertFalse(command.analyticsEnabled)
        assertEquals(HexanaMcpToolProfile.ADVANCED, command.toolProfile)
        assertEquals(emptyList(), command.allowedRoots)
        assertEquals(HexanaAnalysisLimits.MAX_OUTPUT_BYTES, command.resourceLimits.maxOutputBytes)
    }

    @Test
    fun parseAcceptsScriptingToolProfileForHttp() {
        val command = cli().parse(arrayOf("http", "--tool-profile", "scripting"))

        assertIs<HexanaCommand.Http>(command)
        assertEquals(HexanaMcpToolProfile.SCRIPTING, command.toolProfile)
    }

    @Test
    fun parseRejectsPublicBindWithoutExplicitOptIn() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("http", "--host", "0.0.0.0"))
        }

        assertTrue(error.message.orEmpty().contains("--allow-public-bind"))
    }

    @Test
    fun parseAcceptsNonDefaultLoopbackAddress() {
        val command = cli().parse(arrayOf("http", "--host", "127.0.0.2"))

        assertIs<HexanaCommand.Http>(command)
        assertEquals("127.0.0.2", command.host)
    }

    @Test
    fun helpTextIncludesCurrentSubcommands() {
        val help = cli().helpText()

        assertTrue(help.contains("Standalone Hexana MCP server"))
        assertTrue(help.contains("stdio"))
        assertTrue(help.contains("http"))
        assertTrue(help.contains("summarize-module"))
        assertTrue(help.contains("summarize-binary"))
        assertTrue(help.contains("run-script"))
        HexanaGeneratedCliToolRegistry.TOOLS
            .filter { it.executionStatus == HexanaCliExecutionStatus.TODO }
            .forEach { spec ->
                assertFalse(help.contains(spec.commandName), "${spec.commandName} must stay out of root help")
            }
        assertTrue(help.contains("tools"))
        assertTrue(help.contains("doctor"))
        assertTrue(help.contains("version"))
        assertTrue(help.contains("help"))
    }

    @Test
    fun rootHelpExposesExactlyLegacyPublicCommands() {
        assertEquals(
            listOf(
                "stdio",
                "http",
                "tools",
                "doctor",
                "version",
                "help",
                "run-script",
                "summarize-binary",
                "summarize-module",
                "wasm-to-wat",
            ),
            rootCommandNames(cli().helpText()),
        )
    }

    @Test
    fun generatedReleaseProductsKeepExpectedExecutableSelections() {
        assertEquals(
            listOf("summarize-binary", "run-script", "wasm-to-wat", "summarize-module"),
            HexanaGeneratedCliProducts.PRODUCTS.getValue(HexanaCliReleaseProduct.HEXANA_MCP)
                .map { it.commandName },
        )
        assertEquals(
            listOf("summarize-binary", "wasm-to-wat", "summarize-module"),
            HexanaGeneratedCliProducts.PRODUCTS.getValue(HexanaCliReleaseProduct.HEXANA_CLI)
                .map { it.commandName },
        )
        assertEquals(
            listOf("run-script"),
            HexanaGeneratedCliProducts.PRODUCTS.getValue(HexanaCliReleaseProduct.HEXANA_CLI_SCRIPT)
                .map { it.commandName },
        )
    }

    @Test
    fun toolsHelpDocumentsGroupsAsMetadataNotMcpProfiles() {
        val help = cli().helpText(arrayOf("tools", "--help"))

        assertTrue(help.contains("Groups are metadata, not MCP profiles."))
        assertTrue(help.contains("--group"))
        assertTrue(help.contains("converters"))
        assertTrue(help.contains("module"))
        assertTrue(help.contains("function"))
        assertTrue(help.contains("scripting"))
    }

    @Test
    fun doctorHelpDocumentsExitCodes() {
        val help = cli().helpText(arrayOf("doctor", "--help"))

        assertTrue(help.contains("Run compact standalone server diagnostics."))
        assertTrue(help.contains("Exits 0 for OK or warning-only"))
        assertTrue(help.contains("1 for failure reports."))
    }

    @Test
    fun parseRejectsUnsupportedLogLevel() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("stdio", "--log-level", "trace"))
        }

        assertTrue(error.message.orEmpty().contains("quiet"))
        assertTrue(error.message.orEmpty().contains("info"))
        assertTrue(error.message.orEmpty().contains("debug"))
    }

    @Test
    fun parseRejectsUnsupportedTelemetryValue() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("stdio", "--telemetry-enabled", "maybe"))
        }

        assertTrue(error.message.orEmpty().contains("true"))
        assertTrue(error.message.orEmpty().contains("false"))
    }

    @Test
    fun parseRejectsUnsupportedToolProfile() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("stdio", "--tool-profile", "minimal"))
        }

        assertTrue(error.message.orEmpty().contains("default"))
        assertTrue(error.message.orEmpty().contains("advanced"))
        assertTrue(error.message.orEmpty().contains("scripting"))
    }

    @Test
    fun parseRejectsLegacyToolProfileAliases() {
        listOf("production", "full", "debug").forEach { alias ->
            val error = assertFailsWith<CliktError> {
                cli().parse(arrayOf("stdio", "--tool-profile", alias))
            }

            assertTrue(error.message.orEmpty().contains("Expected default, advanced, or scripting"))
        }
    }

    @Test
    fun toolProfileHelpAndCompletionExposeOnlyCanonicalValues() {
        val help = cli().helpText(arrayOf("stdio", "--help"))

        assertTrue(help.contains("default|advanced|scripting"))
        assertFalse(help.contains("production|advanced|scripting"))
    }

    @Test
    fun parseRejectsInvalidResourceLimit() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("stdio", "--max-candidate-count", "0"))
        }

        assertTrue(error.message.orEmpty().contains("greater than 0"))
    }

    @Test
    fun systemStdinReadsAtMostInlineLimitPlusOne() {
        withSystemIn(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))) {
            assertContentEquals(byteArrayOf(1, 2, 3), SystemHexanaCliStdin.readExplicitBytes(maxBytes = 2))
        }
    }

    @Test
    fun systemAutoStdinWaitsForDelayedPipeBytes() {
        val pipeInput = PipedInputStream()
        val pipeOutput = PipedOutputStream(pipeInput)
        val writer = Thread {
            Thread.sleep(250)
            pipeOutput.use { it.write(byteArrayOf(4, 5, 6)) }
        }

        withSystemIn(pipeInput) {
            writer.start()
            assertContentEquals(byteArrayOf(4, 5, 6), SystemHexanaCliStdin.readAutoBytesOrNull(maxBytes = 8))
            writer.join(1_000)
        }
    }

    @Test
    fun nativeImageRuntimeDetectionChecksGraalVmRuntimeMarker() {
        assertFalse(isRunningInGraalVmNativeImage(imageCodeProperty = null))
        assertFalse(isRunningInGraalVmNativeImage(imageCodeProperty = "buildtime"))
        assertTrue(isRunningInGraalVmNativeImage(imageCodeProperty = "runtime"))
    }

    private fun withNativeImageRuntimeProperty(block: () -> Unit) {
        val previousValue = System.getProperty(GRAALVM_NATIVE_IMAGE_CODE_PROPERTY)
        try {
            System.setProperty(GRAALVM_NATIVE_IMAGE_CODE_PROPERTY, "runtime")
            block()
        } finally {
            if (previousValue == null) {
                System.clearProperty(GRAALVM_NATIVE_IMAGE_CODE_PROPERTY)
            } else {
                System.setProperty(GRAALVM_NATIVE_IMAGE_CODE_PROPERTY, previousValue)
            }
        }
    }

    private companion object {
        const val GRAALVM_NATIVE_IMAGE_CODE_PROPERTY = "org.graalvm.nativeimage.imagecode"
        val wasmExecutableCommandNames = listOf("summarize-module", "wasm-to-wat")

        fun generatedCliToolSpec(commandName: String): HexanaCliToolSpec =
            HexanaGeneratedCliToolRegistry.TOOLS.single { it.commandName == commandName }

        fun assertGeneratedExecutableTool(
            command: HexanaCommand,
            commandName: String,
        ): HexanaCommand.ExecutableTool {
            val expectedSpec = generatedCliToolSpec(commandName)
            assertIs<HexanaCommand.ExecutableTool>(command, "$commandName must use generated executable command path")
            assertEquals(expectedSpec, command.spec)
            assertEquals(HexanaCliExecutionStatus.EXECUTABLE, command.spec.executionStatus)
            return command
        }

        fun HexanaCliToolSpec.supportedFormatNames(): String =
            outputFormats.joinToString { it.name.lowercase() }

        fun String.normalizedWhitespace(): String =
            trim().replace(Regex("\\s+"), " ")

        fun rootCommandNames(help: String): List<String> =
            help
                .lineSequence()
                .dropWhile { it.trim() != "Commands:" }
                .drop(1)
                .mapNotNull { Regex("""^  ([a-z0-9][a-z0-9-]+)\s{2,}""").find(it)?.groupValues?.get(1) }
                .filter { it.isNotBlank() }
                .toList()

        fun withSystemIn(input: InputStream, block: () -> Unit) {
            val previousInput = System.`in`
            try {
                System.setIn(input)
                block()
            } finally {
                System.setIn(previousInput)
                input.close()
            }
        }
    }
}

private class FakeCliStdin(
    private val explicitBytes: ByteArray = byteArrayOf(),
    private val autoBytes: ByteArray? = null,
) : HexanaCliStdin {
    var explicitReadCount: Int = 0
        private set
    var autoReadCount: Int = 0
        private set
    val explicitMaxBytes = mutableListOf<Int>()
    val autoMaxBytes = mutableListOf<Int>()

    override fun readExplicitBytes(maxBytes: Int): ByteArray {
        explicitReadCount++
        explicitMaxBytes += maxBytes
        return explicitBytes.copyOf()
    }

    override fun readAutoBytesOrNull(maxBytes: Int): ByteArray? {
        autoReadCount++
        autoMaxBytes += maxBytes
        return autoBytes?.copyOf()
    }
}
