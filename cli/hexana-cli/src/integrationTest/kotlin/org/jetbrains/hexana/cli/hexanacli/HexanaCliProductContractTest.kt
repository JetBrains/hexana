package org.jetbrains.hexana.cli.hexanacli

import com.google.gson.JsonParser
import org.jetbrains.hexana.cli.integration.CliProcessResult
import org.jetbrains.hexana.cli.integration.CliTestTarget
import org.jetbrains.hexana.cli.integration.FakeCliTelemetryServer
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun productReportsTelemetryAndHonorsExplicitOptOut() {
        val home = Files.createDirectories(temporaryFolder.root.toPath().resolve("telemetry-home"))
        FakeCliTelemetryServer().use { server ->
            val result = target.run(listOf("version"), telemetryEnvironment(true, server.url, home))
            assertSuccess(result)
            val event = server.awaitEvents(1).single()
            assertEquals("hexana-cli", event.productId)
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
    fun nativeProductDoesNotRelyOnJavaHome() {
        assumeTrue("native-only contract", target.kind == "native")
        assertSuccess(target.run(listOf("version")))
        val invalidJavaHome = temporaryFolder.root.toPath().resolve("absent-java")
        val result = target.run(listOf("version"), mapOf("JAVA_HOME" to invalidJavaHome.toString()))
        assertSuccess(result)
        assertEquals("hexana-cli ${target.version}\n", result.stdout)
    }

    private fun wasmFile(name: String) = temporaryFolder.root.toPath().resolve(name).also { Files.write(it, MINIMAL_WASM_MODULE) }

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

private val MINIMAL_WASM_MODULE = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)
