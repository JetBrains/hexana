package org.jetbrains.hexana.cli.hexanacli

import com.google.gson.JsonParser
import org.jetbrains.hexana.cli.integration.CliProcessResult
import org.jetbrains.hexana.cli.integration.CliTestTarget
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaCliProductContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val target = CliTestTarget.fromSystemProperties()

    @Test
    fun productExposesExactInventoryVersionAndForeignCommandDiagnostics() {
        val version = target.run(listOf("version"))
        assertSuccess(version)
        assertEquals("hexana-cli ${target.version}\n", version.stdout)

        val tools = target.run(listOf("tools"))
        assertSuccess(tools)
        assertEquals(listOf("summarize-binary", "summarize-module", "wasm-to-wat"), toolNames(tools.stdout))

        val rejected = target.run(listOf("run-script"))
        assertFailure(rejected, 2, "no such subcommand: run-script")
    }

    @Test
    fun productAcceptsStdinBase64AndPathSources() {
        val wasm = wasmFile("standard.wasm")
        val base64 = Base64.getEncoder().encodeToString(MINIMAL_WASM_MODULE)

        val binary = target.run(listOf("summarize-binary", "--stdin", "--format", "json"), stdin = MINIMAL_WASM_MODULE)
        assertSuccess(binary)
        assertTrue(binary.stdout.contains("\"format\":\"wasm\""), binary.describe())

        val module = target.run(listOf("summarize-module", "--wasm-base64", base64, "--format", "json"))
        assertSuccess(module)
        assertEquals(0, JsonParser.parseString(module.stdout).asJsonObject["functions"].asInt)

        val wat = target.run(listOf("wasm-to-wat", wasm.toString(), "--format", "raw"))
        assertSuccess(wat)
        assertTrue(wat.stdout.contains("(module"), wat.describe())

        val stdinWat = target.run(listOf("wasm-to-wat", "--stdin", "--format", "raw"), stdin = MINIMAL_WASM_MODULE)
        assertSuccess(stdinWat)
        assertTrue(stdinWat.stdout.contains("(module"), stdinWat.describe())
    }

    @Test
    fun summarizeModuleSupportsTableOnlyForItsAdvertisedFormat() {
        val wasm = wasmFile("summary-table.wasm")
        val table = target.run(listOf("summarize-module", wasm.toString(), "--format", "table"))
        assertSuccess(table)
        assertTrue(table.stdout.contains("Metric"), table.describe())
        assertTrue(table.stdout.contains("Imported functions"), table.describe())
        assertTrue(table.stdout.contains("Module size (bytes)"), table.describe())

        val tools = target.run(listOf("tools"))
        assertSuccess(tools)
        assertTrue(tools.stdout.contains("formats: json, pretty, table"), tools.describe())
        val help = target.run(listOf("summarize-module", "--help"))
        assertSuccess(help)
        assertTrue(help.stdout.contains("--format <json|pretty|table>"), help.describe())
        assertFailure(target.run(listOf("summarize-binary", wasm.toString(), "--format", "table")), 2, "Unsupported summarize-binary format")
    }

    @Test
    fun summarizeModuleTableHonorsTerminalWidthEnvironmentOutputAndLimits() {
        val wasm = wasmFile("summary-table-width.wasm")
        val unbounded = target.run(listOf("summarize-module", wasm.toString(), "--format", "table"))
        assertSuccess(unbounded)

        val narrow = target.run(
            listOf("summarize-module", wasm.toString(), "--format", "table"),
            environment = mapOf("HEXANA_CLI_TERMINAL_WIDTH" to "20"),
        )
        assertSuccess(narrow)
        assertTrue(narrow.stdout.lineSequence().all { it.length <= 20 }, narrow.describe())
        fun line(metric: String, value: String) = metric.padEnd(12) + " | " + value
        val expectedNarrow = listOf("Metric".padEnd(12) + " | Value", "-".repeat(12) + "-+-" + "-".repeat(5), line("Functions", "0"), line("Exports", "0"), line("Memories", "0"), line("Globals", "0"), line("Tables", "0"), line("Types", "0"), line("Imported fun", "0"), line("ctions", ""), line("Imported mem", "0"), line("ories", ""), line("Imported tab", "0"), line("les", ""), line("Imported glo", "0"), line("bals", ""), line("Module size", "8"), line("(bytes)", "")).joinToString("\n") + "\n"
        assertEquals(expectedNarrow, narrow.stdout)

        val optionOverridesEnvironment = target.run(
            listOf("summarize-module", wasm.toString(), "--format", "table", "--terminal-width", "100"),
            environment = mapOf("HEXANA_CLI_TERMINAL_WIDTH" to "20"),
        )
        assertSuccess(optionOverridesEnvironment)
        assertEquals(unbounded.stdout, optionOverridesEnvironment.stdout)

        val output = temporaryFolder.root.toPath().resolve("summary-table.txt")
        val written = target.run(
            listOf("summarize-module", wasm.toString(), "--format", "table", "--terminal-width", "20", "--output", output.toString()),
        )
        assertSuccess(written)
        assertEquals("", written.stdout)
        assertEquals(narrow.stdout, Files.readString(output))
        assertEquals(narrow.stdout.toByteArray().toList(), Files.readAllBytes(output).toList())

        assertSuccess(target.run(listOf("summarize-module", wasm.toString(), "--format", "table", "--terminal-width", "20", "--max-output-bytes", narrow.stdout.toByteArray().size.toString())))

        assertFailure(
            target.run(
                listOf("summarize-module", wasm.toString(), "--format", "table", "--terminal-width", "20", "--max-output-bytes", (narrow.stdout.toByteArray().size - 1).toString()),
            ),
            1,
            "maxOutputBytes=${narrow.stdout.toByteArray().size - 1}",
        )

        val help = target.run(listOf("summarize-module", "--help"))
        assertSuccess(help)
        assertTrue(help.stdout.contains("--terminal-width <positive-int>"), help.describe())
        assertTrue(help.stdout.contains("HEXANA_CLI_TERMINAL_WIDTH"), help.describe())
    }

    @Test
    fun summarizeModuleRejectsImpossibleTerminalWidthAsUsage() {
        val wasm = wasmFile("summary-table-too-narrow.wasm")
        assertFailure(
            target.run(listOf("summarize-module", wasm.toString(), "--format", "table", "--terminal-width", "1")),
            2,
            "Requested terminal width 1 is below this table's minimum width",
        )
    }

    @Test
    fun productWritesOutputFile() {
        val wasm = wasmFile("summary.wasm")
        val output = temporaryFolder.root.toPath().resolve("summary.json")
        val written = target.run(listOf("summarize-binary", wasm.toString(), "--output", output.toString()))
        assertSuccess(written)
        assertEquals("", written.stdout)
        assertTrue(Files.readString(output).contains("\"format\":\"wasm\""))
    }

    @Test
    fun productEnforcesAllowedRootForPathInput() {
        val wasm = wasmFile("denied.wasm")
        val deniedRoot = Files.createDirectories(temporaryFolder.root.toPath().resolve("other"))
        assertFailure(target.run(listOf("summarize-binary", wasm.toString(), "--allowed-root", deniedRoot.toString())), 1, "outside allowed roots")
    }

    @Test
    fun productRejectsMissingBinarySource() {
        assertFailure(target.run(listOf("summarize-binary")), 2, "Missing summarize-binary source. Provide a path, --stdin, or a base64 source option.")
    }

    @Test
    fun productMapsUsageAndSourceValidationFailuresWithoutStackTraces() {
        val base64 = Base64.getEncoder().encodeToString(MINIMAL_WASM_MODULE)
        assertFailure(target.run(listOf("summarize-module", "--format", "raw")), 2, "Unsupported summarize-module format")
        assertFailure(target.run(listOf("summarize-binary", "")), 2, "Blank source value is not allowed")
        assertFailure(target.run(listOf("summarize-binary", "--binary-base64", base64, "--bytes-base64", base64)), 2, "Repeated summarize-binary base64 source option")
    }

    @Test
    fun productMapsMissingFilesToOperationalFailuresWithoutStackTraces() {
        assertFailure(target.run(listOf("wasm-to-wat", temporaryFolder.root.toPath().resolve("missing.wasm").toString(), "--format", "raw")), 1, "hexana-cli wasm-to-wat failed")
    }

    @Test
    fun nativeProductDoesNotRelyOnJavaHome() {
        assumeTrue("native-only contract", target.kind == "native")
        assertSuccess(target.run(listOf("version")))
        val invalidJavaHome = temporaryFolder.root.toPath().resolve("absent-java")
        val result = target.run(listOf("version"), mapOf("JAVA_HOME" to invalidJavaHome.toString()))
        assertSuccess(result)
        assertEquals("hexana-cli ${target.version}\n", result.stdout)
    }

    private fun wasmFile(name: String) = temporaryFolder.root.toPath().resolve(name).also { Files.write(it, MINIMAL_WASM_MODULE) }

}

private fun toolNames(output: String): List<String> = output.lineSequence().drop(1).filter { it.isNotBlank() && !it.first().isWhitespace() }.toList()

private fun assertSuccess(result: CliProcessResult) {
    assertEquals(0, result.exitCode, result.describe())
    assertEquals("", result.stderr, result.describe())
}

private fun assertFailure(result: CliProcessResult, exitCode: Int, diagnostic: String) {
    assertEquals(exitCode, result.exitCode, result.describe())
    assertEquals("", result.stdout, result.describe())
    assertTrue(result.stderr.contains(diagnostic), result.describe())
    listOf("Exception", "Caused by:", "\tat ").forEach { assertFalse(result.stderr.contains(it), result.describe()) }
}

private val MINIMAL_WASM_MODULE = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
