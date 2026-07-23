package org.jetbrains.hexana.cli.hexanacliscript

import com.google.gson.JsonParser
import org.jetbrains.hexana.cli.integration.CliProcessResult
import org.jetbrains.hexana.cli.integration.CliTestTarget
import org.jetbrains.hexana.cli.integration.FakeCliTelemetryServer
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HexanaCliScriptProductContractTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val target = CliTestTarget.fromSystemProperties()
    private val standardTarget = CliTestTarget(
        kind = target.kind,
        version = target.version,
        launcher = Path.of(requireNotNull(System.getProperty("hexana.cli.integration.standardLauncher"))),
    )

    @Test
    fun productExposesExactInventoryVersionAndForeignCommandDiagnostics() {
        val version = target.run(listOf("version"))
        assertSuccess(version)
        assertEquals("hexana-cli-script ${target.version}\n", version.stdout)

        val tools = target.run(listOf("tools"))
        assertSuccess(tools)
        assertEquals(listOf("run-script"), toolNames(tools.stdout))

        listOf("summarize-binary", "summarize-module", "wasm-to-wat").forEach { command ->
            assertFailure(target.run(listOf(command)), 2, "no such subcommand: $command")
        }
    }

    @Test
    fun productAcceptsPathSourceWithRawOutput() {
        val binary = binaryFile("script.bin")
        val path = target.run(listOf("run-script", binary.toString(), "--script-text", "print(binary.bytes.size); print(binary.bytes.get(1));", "--format", "raw"))
        assertSuccess(path)
        assertEquals("3\n2", path.stdout)
    }

    @Test
    fun productAcceptsBase64AndLegacyBase64Sources() {
        val base64 = target.run(listOf("run-script", "--binary-base64", Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6)), "--script-text", "print(binary.bytes.get(2));", "--format", "raw"))
        assertSuccess(base64)
        assertEquals("6", base64.stdout)

        val legacy = target.run(listOf("run-script", "--bytes-base64", Base64.getEncoder().encodeToString(byteArrayOf(7, 8, 9)), "--script-text", "print(binary.bytes.get(2));", "--format", "raw"))
        assertSuccess(legacy)
        assertEquals("9", legacy.stdout)
    }

    @Test
    fun productAcceptsStdinSourceWithJsonOutput() {
        val stdin = target.run(listOf("run-script", "--stdin", "--script-text", "print(binary.bytes.size);", "--format", "json"), stdin = byteArrayOf(7, 8, 9))
        assertSuccess(stdin)
        val json = JsonParser.parseString(stdin.stdout).asJsonObject
        assertEquals("SUCCESS", json["status"].asString)
        assertTrue(json["success"].asBoolean)
        assertEquals(listOf("3"), json["output"].asJsonObject["lines"].asJsonArray.map { it.asString })
        val module = json["binary"].asJsonObject
        assertEquals("generic", module["format"].asString)
        assertEquals(3, module["byteLength"].asInt)
        assertFalse(module.has("path"))
    }

    @Test
    fun productReadsScriptFiles() {
        val binary = binaryFile("script-file.bin")
        val script = temporaryFolder.root.toPath().resolve("script.js").also { Files.writeString(it, "print('from-file');") }
        val fromFile = target.run(listOf("run-script", binary.toString(), "--script", script.toString(), "--format", "raw"))
        assertSuccess(fromFile)
        assertEquals("from-file", fromFile.stdout)
    }

    @Test
    fun productWritesOutputFile() {
        val binary = binaryFile("script-output.bin")
        val output = temporaryFolder.root.toPath().resolve("script-output.txt")
        val written = target.run(listOf("run-script", binary.toString(), "--script-text", "print('to-file');", "--format", "raw", "--output", output.toString()))
        assertSuccess(written)
        assertEquals("", written.stdout)
        assertEquals("to-file", Files.readString(output))
    }

    @Test
    fun productEnforcesAllowedRootForBinaryInput() {
        val binary = binaryFile("binary-root-input.bin")
        val deniedRoot = Files.createDirectories(temporaryFolder.root.toPath().resolve("binary-root"))
        assertFailure(target.run(listOf("run-script", binary.toString(), "--script-text", "print(1);", "--allowed-root", deniedRoot.toString())), 1, "outside allowed roots")
    }

    @Test
    fun productEnforcesAllowedRootForScriptInput() {
        val script = temporaryFolder.root.toPath().resolve("outside-script.js").also { Files.writeString(it, "print('from-file');") }
        val allowedRoot = Files.createDirectories(temporaryFolder.root.toPath().resolve("allowed"))
        val allowedBinary = allowedRoot.resolve("input.bin").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        assertFailure(target.run(listOf("run-script", allowedBinary.toString(), "--script", script.toString(), "--allowed-root", allowedRoot.toString())), 1, "outside allowed roots")
    }

    @Test
    fun productRejectsMissingScriptSource() {
        val binary = binaryFile("validation.bin")
        assertScriptSourceFailure(binary, listOf("run-script", binary.toString()), "Exactly one of --script or --script-text is required.")
    }

    @Test
    fun productRejectsDuplicateScriptSource() {
        val binary = binaryFile("duplicate-script.bin")
        val first = temporaryFolder.root.toPath().resolve("first.js").also { Files.writeString(it, "print('first');") }
        val second = temporaryFolder.root.toPath().resolve("second.js").also { Files.writeString(it, "print('second');") }
        assertScriptSourceFailure(binary, listOf("run-script", binary.toString(), "--script", first.toString(), "--script", second.toString()), "Provide exactly one of --script or --script-text.")
    }

    @Test
    fun productRejectsDuplicateInlineScriptSource() {
        val binary = binaryFile("duplicate-inline.bin")
        assertScriptSourceFailure(binary, listOf("run-script", binary.toString(), "--script-text", "print(1);", "--script-text", "print(2);"), "Provide exactly one of --script or --script-text.")
    }

    @Test
    fun productRejectsConflictingScriptSources() {
        val binary = binaryFile("conflicting-sources.bin")
        val script = temporaryFolder.root.toPath().resolve("validation.js").also { Files.writeString(it, "print('executed');") }
        assertScriptSourceFailure(binary, listOf("run-script", binary.toString(), "--script", script.toString(), "--script-text", "print(1);"), "Provide exactly one of --script or --script-text.")
    }

    @Test
    fun productMapsRuntimeErrors() {
        val binary = binaryFile("runtime-error.bin")
        assertFailure(target.run(listOf("run-script", binary.toString(), "--script-text", "throw new Error('contract-failure');", "--format", "raw")), 1, "RUNTIME_ERROR")
    }

    @Test
    fun productDeniesSandboxEscapes() {
        val binary = binaryFile("sandbox-denial.bin")
        assertFailure(target.run(listOf("run-script", binary.toString(), "--script-text", "Java.type('java.lang.System');", "--format", "raw")), 1, "SANDBOX_DENIED")
    }

    @Test
    fun productEnforcesOutputLimits() {
        val binary = binaryFile("output-limit.bin")
        val exact = target.run(listOf("run-script", binary.toString(), "--script-text", "print('abc');", "--format", "raw", "--max-output-bytes", "3"))
        assertSuccess(exact)
        assertEquals("abc", exact.stdout)
        assertFailure(target.run(listOf("run-script", binary.toString(), "--script-text", "print('abc');", "--format", "raw", "--max-output-bytes", "2")), 1, "maxOutputBytes=2")
    }

    @Test
    fun productReportsTelemetryAndHonorsExplicitOptOut() {
        val home = Files.createDirectories(temporaryFolder.root.toPath().resolve("telemetry-home"))
        FakeCliTelemetryServer().use { server ->
            assertSuccess(target.run(listOf("version"), telemetryEnvironment(true, server.url, home)))
            val event = server.awaitEvents(1).single()
            assertEquals("hexana-cli-script", event.productId)
            assertEquals("version", event.commandName)
            assertEquals("cli", event.surface)
            assertEquals(setOf("product_id", "command_name", "surface"), event.customPropertyKeys)
            assertNotEquals("", event.distinctId)
            assertTrue(Files.isRegularFile(home.resolve(".hexana/analytics-id")))
        }
        FakeCliTelemetryServer().use { server ->
            assertSuccess(target.run(listOf("version"), telemetryEnvironment(false, server.url, Files.createDirectories(temporaryFolder.root.toPath().resolve("telemetry-disabled-home")))))
            server.assertNoRequests()
        }
    }

    @Test
    fun productSharesTelemetryIdentityWithStandardCli() {
        val home = Files.createDirectories(temporaryFolder.root.toPath().resolve("shared-telemetry-home"))
        FakeCliTelemetryServer().use { server ->
            val environment = telemetryEnvironment(true, server.url, home)
            assertSuccess(target.run(listOf("version"), environment))
            assertSuccess(standardTarget.run(listOf("version"), environment))

            val events = server.awaitEvents(2)
            val scriptEvent = events.single { it.productId == "hexana-cli-script" && it.commandName == "version" }
            val standardEvent = events.single { it.productId == "hexana-cli" && it.commandName == "version" }
            assertEquals("hexana-cli-script", scriptEvent.productId)
            assertEquals("version", scriptEvent.commandName)
            assertEquals("hexana-cli", standardEvent.productId)
            assertEquals("version", standardEvent.commandName)
            assertNotEquals("", scriptEvent.distinctId)
            assertNotEquals("", standardEvent.distinctId)
            assertEquals(scriptEvent.distinctId, standardEvent.distinctId)
            assertTrue(Files.isRegularFile(home.resolve(".hexana/analytics-id")))
        }
    }

    @Test
    fun nativeProductDoesNotRelyOnJavaHome() {
        assumeTrue("native-only contract", target.kind == "native")
        assertSuccess(target.run(listOf("version")))
        val result = target.run(listOf("version"), mapOf("JAVA_HOME" to temporaryFolder.root.toPath().resolve("absent-java").toString()))
        assertSuccess(result)
        assertEquals("hexana-cli-script ${target.version}\n", result.stdout)
    }

    private fun binaryFile(name: String) = temporaryFolder.root.toPath().resolve(name).also { Files.write(it, byteArrayOf(1, 2, 3)) }

    private fun assertScriptSourceFailure(binary: java.nio.file.Path, args: List<String>, expected: String) {
        val output = temporaryFolder.root.toPath().resolve("${binary.fileName}-$expected.txt")
        Files.deleteIfExists(output)
        assertFailure(target.run(args + listOf("--output", output.toString())), 2, expected)
        assertFalse(Files.exists(output), "invalid script source must not write output")
    }

    private fun telemetryEnvironment(enabled: Boolean, host: String, home: java.nio.file.Path) = mapOf(
        "HEXANA_CLI_TELEMETRY_ENABLED" to enabled.toString(),
        "HEXANA_CLI_POSTHOG_HOST" to host,
        "HEXANA_CLI_POSTHOG_KEY" to "contract-key",
        "HOME" to home.toString(),
    ) + target.jvmUserHome(home)
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
