package org.jetbrains.hexana.mcp.server

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.NoSuchSubcommand
import org.jetbrains.hexana.mcp.HexanaAnalysisLimits
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HexanaCliTest {
    @Test
    fun parseReturnsHelpWhenNoArgumentsAreProvided() {
        assertEquals(HexanaCommand.Help, cli().parse(emptyArray()))
    }

    @Test
    fun rootHelpExposesExactlyMcpServiceCommands() {
        val help = cli().helpText()

        assertTrue(help.contains("Standalone Hexana MCP server"))
        assertEquals(
            listOf("stdio", "http", "doctor", "version", "help"),
            rootCommandNames(help),
        )
    }

    @Test
    fun directCliCommandsAreRejectedAsUnknownSubcommands() {
        listOf(
            "summarize-binary",
            "summarize-module",
            "wasm-to-wat",
            "run-script",
            "tools",
        ).forEach { commandName ->
            assertFailsWith<NoSuchSubcommand>(commandName) {
                cli().parse(arrayOf(commandName))
            }
        }
    }

    @Test
    fun parseUsesMcpDefaultsForStdio() {
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
    fun parseAcceptsStdioProfilesRootsAndLimits() {
        val command = cli { name ->
            if (name == "HEXANA_MCP_ALLOWED_ROOTS") {
                listOf("/env/project", "/env/deps").joinToString(File.pathSeparator)
            } else {
                null
            }
        }.parse(
            arrayOf(
                "stdio",
                "--tool-profile", "scripting",
                "--allowed-root", "/cli/project",
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
        assertEquals(HexanaMcpToolProfile.SCRIPTING, command.toolProfile)
        assertEquals(listOf("/cli/project", "/env/project", "/env/deps"), command.allowedRoots)
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
    fun parseNormalizesHttpEndpointAndKeepsLoopbackDefaults() {
        val command = cli().parse(
            arrayOf(
                "http",
                "--endpoint", "api",
                "--log-level", "debug",
                "--telemetry-enabled", "false",
                "--tool-profile", "advanced",
            )
        )

        assertIs<HexanaCommand.Http>(command)
        assertEquals("127.0.0.1", command.host)
        assertEquals(3000, command.port)
        assertEquals("/api", command.endpoint)
        assertEquals(HexanaLogLevel.DEBUG, command.logLevel)
        assertFalse(command.analyticsEnabled)
        assertEquals(HexanaMcpToolProfile.ADVANCED, command.toolProfile)
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
    fun parseAcceptsPublicBindWithExplicitOptIn() {
        val command = cli().parse(arrayOf("http", "--host", "0.0.0.0", "--allow-public-bind"))

        assertIs<HexanaCommand.Http>(command)
        assertTrue(command.allowPublicBind)
    }

    @Test
    fun parseCombinesAllowedRootsForDoctor() {
        val command = cli { name ->
            if (name == "HEXANA_MCP_ALLOWED_ROOTS") "/env/project,/env/deps" else null
        }.parse(arrayOf("doctor", "--allowed-root", "/cli/project"))

        assertIs<HexanaCommand.Doctor>(command)
        assertEquals(listOf("/cli/project", "/env/project", "/env/deps"), command.allowedRoots)
    }

    @Test
    fun parseReturnsVersionAndHelpCommands() {
        assertEquals(HexanaCommand.Version, cli().parse(arrayOf("version")))
        assertEquals(HexanaCommand.Help, cli().parse(arrayOf("help")))
    }

    @Test
    fun doctorHelpDocumentsExitCodes() {
        val help = cli().helpText(arrayOf("doctor", "--help"))

        assertTrue(help.contains("Run compact standalone server diagnostics."))
        assertTrue(help.contains("Exits 0 for OK or warning-only"))
        assertTrue(help.contains("1 for failure reports."))
    }

    @Test
    fun parseRejectsInvalidMcpOptions() {
        val cases = listOf(
            arrayOf("stdio", "--log-level", "trace") to "quiet",
            arrayOf("stdio", "--telemetry-enabled", "maybe") to "true",
            arrayOf("stdio", "--tool-profile", "minimal") to "default",
            arrayOf("stdio", "--max-candidate-count", "0") to "greater than 0",
        )

        cases.forEach { (arguments, expectedMessage) ->
            val error = assertFailsWith<CliktError> { cli().parse(arguments) }
            assertTrue(error.message.orEmpty().contains(expectedMessage), error.message)
        }
    }

    @Test
    fun nativeImageRuntimeDetectionChecksGraalVmRuntimeMarker() {
        assertFalse(isRunningInGraalVmNativeImage(imageCodeProperty = null))
        assertFalse(isRunningInGraalVmNativeImage(imageCodeProperty = "buildtime"))
        assertTrue(isRunningInGraalVmNativeImage(imageCodeProperty = "runtime"))
    }

    private fun cli(envReader: (String) -> String? = { null }) = HexanaCli(envReader)

    private fun rootCommandNames(help: String): List<String> =
        help
            .lineSequence()
            .dropWhile { it.trim() != "Commands:" }
            .drop(1)
            .mapNotNull { Regex("""^  ([a-z0-9][a-z0-9-]+)\s{2,}""").find(it)?.groupValues?.get(1) }
            .filter { it.isNotBlank() }
            .toList()
}
