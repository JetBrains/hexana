package org.jetbrains.hexana.mcp.server

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.NoSuchSubcommand
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import java.io.File
import kotlin.test.*

class HexanaCliTest {
    private fun cli() = HexanaCli()

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
        assertEquals(HexanaMcpToolProfile.PRODUCTION, command.toolProfile)
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
        val command = HexanaCli { name ->
            if (name == "HEXANA_MCP_ALLOWED_ROOTS") {
                listOf("/env/project", "/env/deps").joinToString(File.pathSeparator)
            } else {
                null
            }
        }.parse(arrayOf("stdio"))

        assertIs<HexanaCommand.Stdio>(command)
        assertEquals(listOf("/env/project", "/env/deps"), command.allowedRoots)
    }

    @Test
    fun parseCombinesAllowedRootsFromCliAndEnvForHttp() {
        val command = HexanaCli { name ->
            if (name == "HEXANA_MCP_ALLOWED_ROOTS") "/env/project,/env/deps" else null
        }.parse(
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
        val command = HexanaCli { name ->
            if (name == "HEXANA_MCP_ALLOWED_ROOTS") "/env/project,/env/deps" else null
        }.parse(
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
        val command = cli().parse(arrayOf("summarize-module", "app.wasm"))

        assertIs<HexanaCommand.SummarizeModule>(command)
        assertEquals("app.wasm", command.path)
        assertEquals(HexanaCliOutputFormat.PRETTY, command.format)
        assertEquals(emptyList(), command.allowedRoots)
        assertEquals(HexanaAnalysisLimits.MAX_FILE_WASM_BYTES, command.resourceLimits.maxFileWasmBytes)
    }

    @Test
    fun parseAcceptsExplicitSummarizeModuleJsonFormat() {
        val command = cli().parse(arrayOf("summarize-module", "app.wasm", "--format", "json"))

        assertIs<HexanaCommand.SummarizeModule>(command)
        assertEquals(HexanaCliOutputFormat.JSON, command.format)
    }

    @Test
    fun parseAcceptsExplicitSummarizeModulePrettyFormat() {
        val command = cli().parse(arrayOf("summarize-module", "app.wasm", "--format", "pretty"))

        assertIs<HexanaCommand.SummarizeModule>(command)
        assertEquals(HexanaCliOutputFormat.PRETTY, command.format)
    }

    @Test
    fun parseCombinesAllowedRootsFromCliAndEnvForSummarizeModule() {
        val command = HexanaCli { name ->
            if (name == "HEXANA_MCP_ALLOWED_ROOTS") "/env/project,/env/deps" else null
        }.parse(
            arrayOf(
                "summarize-module",
                "app.wasm",
                "--allowed-root",
                "/cli/project",
            )
        )

        assertIs<HexanaCommand.SummarizeModule>(command)
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
        val command = cli().parse(arrayOf("wasm-to-wat", "app.wasm"))

        assertIs<HexanaCommand.WasmToWat>(command)
        assertEquals("app.wasm", command.path)
        assertEquals(HexanaCliOutputFormat.RAW, command.format)
        assertEquals(emptyList(), command.allowedRoots)
        assertEquals(HexanaAnalysisLimits.MAX_FILE_WASM_BYTES, command.resourceLimits.maxFileWasmBytes)
    }

    @Test
    fun parseAcceptsExplicitWasmToWatRawAndJsonFormats() {
        val raw = cli().parse(arrayOf("wasm-to-wat", "app.wasm", "--format", "raw"))
        val json = cli().parse(arrayOf("wasm-to-wat", "app.wasm", "--format", "json"))

        assertIs<HexanaCommand.WasmToWat>(raw)
        assertEquals(HexanaCliOutputFormat.RAW, raw.format)
        assertIs<HexanaCommand.WasmToWat>(json)
        assertEquals(HexanaCliOutputFormat.JSON, json.format)
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
    fun parseAcceptsGeneratedTodoCommandAsTodoToolVariant() {
        val expectedSpec = generatedCliToolSpec("list-enabled-features")
        val command = cli().parse(arrayOf("list-enabled-features"))

        assertIs<HexanaCommand.TodoTool>(command)
        assertEquals(expectedSpec, command.spec)
        assertEquals("list_enabled_features", command.spec.toolName)
        assertEquals(HexanaCliExecutionStatus.TODO, command.spec.executionStatus)
    }

    @Test
    fun parseGeneratedTodoCommandAbsorbsFutureArguments() {
        val expectedSpec = generatedCliToolSpec("summarize-binary")
        val command = cli().parse(
            arrayOf(
                "summarize-binary",
                "--input",
                "app.wasm",
                "--format=json",
                "extra",
            )
        )

        assertIs<HexanaCommand.TodoTool>(command)
        assertEquals(expectedSpec, command.spec)
        assertEquals("summarize_binary", command.spec.toolName)
    }

    @Test
    fun parseRegistersEveryExecutableGeneratedCliToolAsRuntimeCommandWithHelp() {
        HexanaGeneratedCliToolRegistry.TOOLS
            .filter { it.executionStatus == HexanaCliExecutionStatus.EXECUTABLE }
            .forEach { spec ->
                val command = parseExecutableGeneratedCliTool(spec)

                assertFalse(command is HexanaCommand.TodoTool, "${spec.commandName} must use a runtime command")
                assertNotEquals(HexanaCommand.Help, command, "${spec.commandName} must be registered as a subcommand")

                val help = cli().helpText(arrayOf(spec.commandName, "--help"))

                assertTrue(
                    help.normalizedWhitespace().contains(spec.summary.normalizedWhitespace()),
                    "${spec.commandName} help must include generated summary"
                )
                assertTrue(help.contains("Usage:"), "${spec.commandName} help must include normal command usage")
                assertTrue(help.contains("Path to a WebAssembly module."), "${spec.commandName} help must document wasm path")
                assertTrue(help.contains("--format"), "${spec.commandName} help must document format option")
                assertTrue(help.contains(spec.supportedFormatNames()), "${spec.commandName} help must document generated formats")
            }
    }

    @Test
    fun parseRegistersEveryTodoGeneratedCliToolAsTodoWithFutureArguments() {
        HexanaGeneratedCliToolRegistry.TOOLS
            .filter { it.executionStatus == HexanaCliExecutionStatus.TODO }
            .forEach { spec ->
                val command = cli().parse(
                    arrayOf(
                        spec.commandName,
                        "--input",
                        "app.wasm",
                        "--format=json",
                        "--future-option",
                        "value",
                        "extra",
                    )
                )

                assertIs<HexanaCommand.TodoTool>(command, "${spec.commandName} must use the TODO command path")
                assertEquals(spec, command.spec)

                val output = renderTodoToolNotImplementedMessage(command)

                assertTrue(output.contains(spec.commandName), "${spec.commandName} TODO output must name command")
                assertTrue(output.contains(spec.toolName), "${spec.commandName} TODO output must name tool")
                assertTrue(output.contains("not implemented"), "${spec.commandName} TODO output must say not implemented")
                assertTrue(output.contains("TODO"), "${spec.commandName} TODO output must include TODO status")
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
    fun parseKeepsExecutableGeneratedCommandsOnHandWrittenVariants() {
        val summarize = cli().parse(arrayOf("summarize-module", "app.wasm"))
        val wat = cli().parse(arrayOf("wasm-to-wat", "app.wasm"))

        assertIs<HexanaCommand.SummarizeModule>(summarize)
        assertEquals("app.wasm", summarize.path)
        assertIs<HexanaCommand.WasmToWat>(wat)
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
                "full",
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
        assertTrue(help.contains("tools"))
        assertTrue(help.contains("doctor"))
        assertTrue(help.contains("version"))
        assertTrue(help.contains("help"))
    }

    @Test
    fun toolsHelpDocumentsGroupsAsMetadataNotMcpProfiles() {
        val help = cli().helpText(arrayOf("tools", "--help"))

        assertTrue(help.contains("Groups are metadata, not MCP profiles."))
        assertTrue(help.contains("--group"))
        assertTrue(help.contains("converters"))
        assertTrue(help.contains("module"))
        assertTrue(help.contains("function"))
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

        assertTrue(error.message.orEmpty().contains("production"))
        assertTrue(error.message.orEmpty().contains("advanced"))
    }

    @Test
    fun parseRejectsInvalidResourceLimit() {
        val error = assertFailsWith<CliktError> {
            cli().parse(arrayOf("stdio", "--max-candidate-count", "0"))
        }

        assertTrue(error.message.orEmpty().contains("greater than 0"))
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

        fun generatedCliToolSpec(commandName: String): HexanaCliToolSpec =
            HexanaGeneratedCliToolRegistry.TOOLS.single { it.commandName == commandName }

        fun parseExecutableGeneratedCliTool(spec: HexanaCliToolSpec): HexanaCommand =
            when (spec.commandName) {
                "summarize-module",
                "wasm-to-wat" -> HexanaCli().parse(arrayOf(spec.commandName, "app.wasm"))

                else -> fail("No runtime parse assertion for generated executable CLI command: ${spec.commandName}")
            }

        fun HexanaCliToolSpec.supportedFormatNames(): String =
            outputFormats.joinToString { it.name.lowercase() }

        fun String.normalizedWhitespace(): String =
            trim().replace(Regex("\\s+"), " ")
    }
}
