package org.jetbrains.hexana.cli.hexanawasmtools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.jetbrains.hexana.cli.integration.CliProcessResult
import org.jetbrains.hexana.cli.integration.CliRunner
import org.jetbrains.hexana.cli.integration.CliTestTarget
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaWasmToolsContractTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val target = CliTestTarget.fromSystemProperties()

    @Test
    fun installedProductsHaveExactIdentitiesAndSeparateInventories() {
        assertSuccess(target.run(listOf("version")), "hexana-wasm-tools ${target.version}\n")
        assertSuccess(target.run(listOf("--version")), "hexana-wasm-tools ${target.version}\n")
        val help = target.run(listOf("help"))
        assertEquals(0, help.exitCode, help.describe())
        assertTrue(help.stdout.startsWith("hexana-wasm-tools <command>"), help.describe())
        val tools = target.run(listOf("tools"))
        assertEquals(0, tools.exitCode, tools.describe())
        assertEquals(listOf("component-wit-report", "metadata-audit", "validate-and-explain"), toolNames(tools.stdout))
        assertFalse(tools.stdout.contains("run-script"), tools.describe())
        listOf("validate-and-explain", "metadata-audit", "component-wit-report").forEach { command ->
            val commandHelp = target.run(listOf(command, "--help"))
            assertEquals(0, commandHelp.exitCode, commandHelp.describe())
            assertTrue(commandHelp.stdout.startsWith("Usage: hexana-wasm-tools $command"), commandHelp.describe())
            assertTrue(commandHelp.stdout.contains("--format <json|pretty>"), commandHelp.describe())
            assertTrue(commandHelp.stdout.contains("--max-input-bytes <n>"), commandHelp.describe())
            assertTrue(commandHelp.stdout.contains("--max-items <n>"), commandHelp.describe())
            assertFalse(commandHelp.stdout.contains("--wasm-base64"), commandHelp.describe())
        }

        listOf("summarize-binary", "summarize-module", "wasm-to-wat", "run-script", "test", "stdio", "http", "doctor")
            .forEach { assertRefused(target.run(listOf(it)), 2, "no such subcommand") }
        val standard = Path.of(requireNotNull(System.getProperty("hexana.cli.integration.standardLauncher")))
        val script = Path.of(requireNotNull(System.getProperty("hexana.cli.integration.scriptLauncher")))
        for (launcher in listOf(standard, script)) {
            for (command in listOf("validate-and-explain", "metadata-audit", "component-wit-report")) {
                assertRefused(CliRunner.run(listOf(launcher.toString(), command)), 2, "no such subcommand")
            }
        }
    }

    @Test
    fun everyWorkflowHasPathDashAndExplicitStdinParity() {
        for ((command, bytes) in listOf(
            "validate-and-explain" to CORE,
            "metadata-audit" to CORE,
            "component-wit-report" to COMPONENT,
        )) {
            val path = wasmFile("$command.wasm", bytes)
            val fromPath = target.run(listOf(command, path.toString()))
            val fromDash = target.run(listOf(command, "-"), stdin = bytes)
            val fromStdin = target.run(listOf(command, "--stdin"), stdin = bytes)
            listOf(fromPath, fromDash, fromStdin).forEach { result ->
                assertEquals(0, result.exitCode, result.describe())
                assertEquals("", result.stderr, result.describe())
                assertTrue(result.stdout.endsWith("\n") && !result.stdout.endsWith("\n\n"), result.describe())
            }
            val pathJson = json(fromPath)
            val dashJson = json(fromDash)
            val stdinJson = json(fromStdin)
            assertEquals("path", pathJson.getAsJsonObject("input")["kind"].asString)
            assertEquals(path.toString(), pathJson.getAsJsonObject("input")["path"].asString)
            assertEquals("stdin", dashJson.getAsJsonObject("input")["kind"].asString)
            assertEquals(dashJson, stdinJson)
            assertEquals(withoutSource(pathJson), withoutSource(dashJson))
            assertEquals("hexana-wasm-tools", pathJson.getAsJsonObject("product")["id"].asString)
            assertEquals(target.version, pathJson.getAsJsonObject("product")["version"].asString)
            assertEquals(command, pathJson.getAsJsonObject("report")["workflow"].asString)
            assertEquals(1, pathJson.getAsJsonObject("report")["schema_version"].asInt)
        }
    }

    @Test
    fun jsonAndPrettyCarryTheSameReportAndOutputFileIsExact() {
        val path = wasmFile("component.wasm", COMPONENT)
        val json = target.run(listOf("component-wit-report", path.toString(), "--format", "json"))
        val pretty = target.run(listOf("component-wit-report", path.toString(), "--format", "pretty"))
        assertEquals(0, json.exitCode, json.describe())
        assertEquals(0, pretty.exitCode, pretty.describe())
        assertEquals(json(json), json(pretty))
        assertTrue(pretty.stdout.contains("\n    \"product\""), pretty.describe())
        assertEquals("not_available", json(json).getAsJsonObject("wit")["status"].asString)

        val output = temporaryFolder.root.toPath().resolve("reports/report.json")
        val written = target.run(listOf("component-wit-report", path.toString(), "--output", output.toString()))
        assertEquals(0, written.exitCode, written.describe())
        assertTrue(written.stdout.isBlank(), written.describe())
        assertEquals(json.stdout, Files.readString(output))
    }

    @Test
    fun sourceFormatAndForeignOptionsAreUsageErrors() {
        val path = wasmFile("core.wasm", CORE).toString()
        val command = "validate-and-explain"
        assertRefused(target.run(listOf(command)), 2, "Missing validate-and-explain source")
        assertRefused(target.run(listOf(command, path, "--stdin")), 2, "exactly one")
        assertRefused(target.run(listOf(command, "-", "--stdin"), stdin = CORE), 2, "exactly one")
        assertRefused(target.run(listOf(command, path, path)), 2, "Unexpected")
        listOf("--wasm-base64", "--bytes-base64", "--binary-base64").forEach { option ->
            assertRefused(target.run(listOf(command, option, "AGFzbQEAAAA=")), 2, "Unsupported")
        }
        assertRefused(target.run(listOf(command, path, "--format", "table")), 2, "Unsupported")
        assertRefused(target.run(listOf(command, path, "--format", "raw")), 2, "Unsupported")
        assertRefused(target.run(listOf(command, path, "--dependency", "other.wasm")), 2, "Unknown")
        assertRefused(target.run(listOf("metadata-audit", path, "--runtime", "wasmtime")), 2, "Unknown")
        assertRefused(target.run(listOf(command, path, "--max-output-bytes", "0")), 2, "positive integer")
        assertRefused(target.run(listOf(command, path, "--max-input-bytes", "0")), 2, "positive integer")
    }

    @Test
    fun repeatedOptionsRetainValuesAndRejectDuplicatesAndCaps() {
        val core = wasmFile("core.wasm", CORE).toString()
        val validated = target.run(listOf("validate-and-explain", core, "--runtime", "wasmtime", "--runtime", "graalwasm"))
        assertEquals(0, validated.exitCode, validated.describe())
        assertEquals(2, json(validated).getAsJsonObject("runtime_compatibility").getAsJsonArray("targets").size())
        assertRefused(target.run(listOf("validate-and-explain", core, "--runtime", "wasmtime", "--runtime", "wasmtime")), 2, "Duplicate")
        val runtimes = (0..500).flatMap { listOf("--runtime", "runtime-$it") }
        assertRefused(target.run(listOf("validate-and-explain", core) + runtimes), 2, "at most 500")

        val component = wasmFile("component.wasm", COMPONENT).toString()
        val dependency = wasmFile("provider.wasm", COMPONENT).toString()
        val withDependency = target.run(listOf("component-wit-report", component, "--dependency", dependency))
        assertTrue(withDependency.exitCode in listOf(0, 1), withDependency.describe())
        assertEquals(1, json(withDependency).getAsJsonObject("dependencies").getAsJsonArray("paths").size())
        assertRefused(target.run(listOf("component-wit-report", component, "--dependency", dependency, "--dependency", dependency)), 2, "Duplicate")
        val alternateSpelling = Path.of(dependency).parent.resolve(".").resolve("provider.wasm").toString()
        assertRefused(target.run(listOf("component-wit-report", component, "--dependency", dependency,
            "--dependency", alternateSpelling)), 2, "distinct")
        val limitedDependencies = target.run(listOf("component-wit-report", component,
            "--dependency", "missing-a.wasm", "--dependency", "missing-b.wasm", "--max-items", "1"))
        assertEquals(1, limitedDependencies.exitCode, limitedDependencies.describe())
        assertTrue(json(limitedDependencies).getAsJsonObject("truncation").getAsJsonArray("causes")
            .any { it.asString == "max_items" }, limitedDependencies.describe())
    }

    @Test
    fun reportsAndRefusalsUseTheDocumentedExitClasses() {
        val core = wasmFile("core.wasm", CORE)
        assertEquals(0, target.run(listOf("validate-and-explain", core.toString())).exitCode)
        val partial = wasmFile("metadata.wasm", CORE + custom("a") + custom("b"))
        val truncated = target.run(listOf("metadata-audit", partial.toString(), "--max-items", "1"))
        assertEquals(1, truncated.exitCode, truncated.describe())
        val legacyLimit = target.run(listOf("metadata-audit", partial.toString(), "--max-candidate-count", "1"))
        assertEquals(truncated.stdout, legacyLimit.stdout)
        val report = json(truncated)
        assertTrue(report.getAsJsonObject("truncation")["truncated"].asBoolean, truncated.describe())
        assertTrue(report.getAsJsonArray("findings").any { it.asJsonObject["code"].asString == "output_truncated" })

        assertRefused(target.run(listOf("validate-and-explain", core.toString(), "--format", "raw")), 2, "Unsupported")
        val bad = wasmFile("bad.wasm", byteArrayOf(1, 2, 3))
        assertRefused(target.run(listOf("validate-and-explain", bad.toString())), 3, "Wasm header")
        assertRefused(target.run(listOf("metadata-audit", "missing.wasm")), 3, "File not found")
        assertRefused(target.run(listOf("metadata-audit", core.toString(), "--max-file-bytes", "7")), 3, "maximum size")
        assertRefused(target.run(listOf("metadata-audit", core.toString(), "--max-input-bytes", "7")), 3, "maximum size")
        assertRefused(target.run(listOf("metadata-audit", "--stdin", "--max-input-bytes", "7"), stdin = CORE), 3, "maximum size")
        assertRefused(target.run(listOf("metadata-audit", "--stdin", "--max-inline-bytes", "7"), stdin = CORE), 3, "maximum size")
        assertRefused(target.run(listOf("metadata-audit", "--stdin", "--max-input-bytes", "1",
            "--max-inline-bytes", "7"), stdin = CORE), 3, "maximum size")
        val oneByteRefusal = target.run(listOf("validate-and-explain", core.toString(), "--max-output-bytes", "1"))
        assertEquals(3, oneByteRefusal.exitCode, oneByteRefusal.describe())
        assertEquals("", oneByteRefusal.stdout, oneByteRefusal.describe())
        assertEquals(1, oneByteRefusal.stderr.toByteArray().size, oneByteRefusal.describe())
        val boundedFailure = target.run(listOf("metadata-audit", "missing.wasm", "--max-output-bytes", "10"))
        assertRefused(boundedFailure, 3, "Output")
        assertTrue(boundedFailure.stderr.toByteArray().size <= 10, boundedFailure.describe())
        val refusedOutput = temporaryFolder.root.toPath().resolve("refused.json")
        val refused = target.run(listOf("validate-and-explain", "missing.wasm", "--output", refusedOutput.toString()))
        assertRefused(refused, 3, "File not found")
        assertFalse(Files.exists(refusedOutput), refused.describe())
        val outputDirectory = temporaryFolder.newFolder("output-directory").toPath()
        assertRefused(target.run(listOf("metadata-audit", core.toString(), "--output", outputDirectory.toString())),
            3, "Output path cannot be written")
    }

    @Test
    fun byteLimitTrimsAValidReportAndAllowedRootsProtectPaths() {
        val sections = (0..9).fold(CORE) { bytes, index -> bytes + custom("section$index") }
        val path = wasmFile("many.wasm", sections)
        val full = target.run(listOf("metadata-audit", path.toString()))
        assertEquals(0, full.exitCode, full.describe())
        val cap = full.stdout.toByteArray().size - 100
        val limited = target.run(listOf("metadata-audit", path.toString(), "--max-output-bytes", cap.toString()))
        assertEquals(1, limited.exitCode, limited.describe())
        assertTrue(limited.stdout.toByteArray().size <= cap, limited.describe())
        assertTrue(json(limited).getAsJsonObject("truncation").getAsJsonArray("causes")
            .any { it.asString == "max_output_bytes" }, limited.describe())

        val otherRoot = Files.createDirectory(temporaryFolder.root.toPath().resolve("other"))
        assertRefused(target.run(listOf("metadata-audit", path.toString(), "--allowed-root", otherRoot.toString())), 3, "outside allowed roots")
        val component = wasmFile("component.wasm", COMPONENT)
        assertEquals(0, target.run(listOf("component-wit-report", component.toString(), "--max-inline-bytes", "7")).exitCode)
    }

    private fun wasmFile(name: String, bytes: ByteArray): Path =
        temporaryFolder.root.toPath().resolve(name).also { Files.write(it, bytes) }

    private fun json(result: CliProcessResult): JsonObject = JsonParser.parseString(result.stdout).asJsonObject

    private fun withoutSource(value: JsonObject): JsonObject = value.deepCopy().apply {
        getAsJsonObject("input").remove("kind")
        getAsJsonObject("input").remove("path")
    }
}

private fun toolNames(output: String): List<String> = output.lineSequence().drop(1)
    .filter { it.isNotBlank() && !it.first().isWhitespace() }.toList()

private fun assertSuccess(result: CliProcessResult, expected: String) {
    assertEquals(0, result.exitCode, result.describe())
    assertEquals(expected, result.stdout)
    assertEquals("", result.stderr, result.describe())
}

private fun assertRefused(result: CliProcessResult, code: Int, message: String) {
    assertEquals(code, result.exitCode, result.describe())
    assertTrue(result.stdout.isBlank(), result.describe())
    assertTrue(result.stderr.contains(message), result.describe())
    assertFalse(result.stderr.contains("Exception") || result.stderr.contains("\tat "), result.describe())
}

private val CORE = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
private val COMPONENT = byteArrayOf(0, 0x61, 0x73, 0x6d, 0x0d, 0, 1, 0)
private fun custom(name: String): ByteArray = byteArrayOf(0, (name.length + 1).toByte(), name.length.toByte()) + name.toByteArray()
