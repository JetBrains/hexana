package org.jetbrains.hexana.cli.hexanacli

import com.google.gson.JsonParser
import org.jetbrains.hexana.graalwasm.wat2wasm.wat2wasm
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HexanaCliIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun installedStandardProductExposesOnlyApprovedCommands() {
        val help = runCli()
        assertEquals(0, help.exitCode, help.stderr)
        assertTrue(help.stdout.startsWith("hexana-cli <command> [options]\n"), help.stdout)
        listOf("-h", "--help").forEach { helpFlag ->
            val aliasHelp = runCli(helpFlag)
            assertEquals(0, aliasHelp.exitCode, aliasHelp.stderr)
            assertEquals(help.stdout, aliasHelp.stdout)
        }
        listOf("summarize-binary", "summarize-module", "wasm-to-wat", "tools", "version", "help").forEach {
            assertTrue(help.stdout.contains(it), help.stdout)
            listOf("-h", "--help").forEach { helpFlag ->
                val commandHelp = runCli(it, helpFlag)
                assertEquals(0, commandHelp.exitCode, commandHelp.stderr)
                assertTrue(commandHelp.stdout.startsWith("Usage: hexana-cli $it"), commandHelp.stdout)
                assertFalse(commandHelp.stdout.contains("run-script"), commandHelp.stdout)
            }
        }
        listOf("run-script", "stdio", "http", "doctor").forEach {
            assertFalse(help.stdout.contains(it), help.stdout)
            val rejected = runCli(it)
            assertEquals(2, rejected.exitCode, "stdout=${rejected.stdout}, stderr=${rejected.stderr}")
            assertTrue(rejected.stdout.isBlank(), rejected.stdout)
            assertTrue(rejected.stderr.contains("no such subcommand"), rejected.stderr)
        }

        val tools = runCli("tools")
        assertEquals(0, tools.exitCode, tools.stderr)
        assertEquals(1, trailingNewlineCount(tools.stdout), tools.stdout)
        assertTrue(tools.stdout.startsWith("hexana-cli tools\n"), tools.stdout)
        assertTrue(tools.stdout.contains("summarize-binary\n"), tools.stdout)
        assertTrue(tools.stdout.contains("summarize-module\n"), tools.stdout)
        assertTrue(tools.stdout.contains("wasm-to-wat\n"), tools.stdout)
        assertFalse(tools.stdout.contains("run-script\n"), tools.stdout)

        val helpCommand = runCli("help", "summarize-module")
        assertEquals(0, helpCommand.exitCode, helpCommand.stderr)
        assertTrue(helpCommand.stdout.startsWith("Usage: hexana-cli summarize-module"), helpCommand.stdout)

        val version = runCli("version")
        val versionFlag = runCli("--version")
        assertEquals(0, version.exitCode, version.stderr)
        assertEquals(0, versionFlag.exitCode, versionFlag.stderr)
        assertEquals(version.stdout, versionFlag.stdout)
    }

    @Test
    fun installedStandardCommandsSupportPathBase64StdinOutputAndNewlines() {
        val wasm = wasmFile("(module (memory 1) (func (export \"main\")))")
        val wasmBytes = Files.readAllBytes(wasm)

        val summarizeModule = runCli("summarize-module", wasm.toString(), "--format", "json")
        assertEquals(0, summarizeModule.exitCode, summarizeModule.stderr)
        assertEquals(1, trailingNewlineCount(summarizeModule.stdout), summarizeModule.stdout)
        val moduleJson = JsonParser.parseString(summarizeModule.stdout).asJsonObject
        assertEquals(1, moduleJson.get("functions").asInt)
        assertEquals(1, moduleJson.get("exports").asInt)

        val wat = runCli(
            "wasm-to-wat",
            "--wasm-base64",
            Base64.getEncoder().encodeToString(wasmBytes),
            "--format",
            "raw",
        )
        assertEquals(0, wat.exitCode, wat.stderr)
        assertTrue(wat.stdout.contains("(module"), wat.stdout)
        assertFalse(wat.stdout.endsWith("\n\n"), wat.stdout)

        val legacyWasmAlias = runCli(
            "summarize-module",
            "--bytes-base64",
            Base64.getEncoder().encodeToString(wasmBytes),
            "--format",
            "json",
        )
        assertEquals(0, legacyWasmAlias.exitCode, legacyWasmAlias.stderr)
        assertTrue(legacyWasmAlias.stdout.contains("\"functions\":1"), legacyWasmAlias.stdout)

        val binary = runCli("summarize-binary", "--stdin", "--format", "json", stdin = wasmBytes)
        assertEquals(0, binary.exitCode, binary.stderr)
        assertEquals(1, trailingNewlineCount(binary.stdout), binary.stdout)
        assertTrue(binary.stdout.contains("\"format\":\"wasm\""), binary.stdout)

        val output = temporaryFolder.newFile("summary.json").toPath()
        val toFile = runCli("summarize-binary", wasm.toString(), "--output", output.toString())
        assertEquals(0, toFile.exitCode, toFile.stderr)
        assertTrue(toFile.stdout.isBlank(), toFile.stdout)
        assertEquals(1, trailingNewlineCount(Files.readString(output)), Files.readString(output))

        val allowedRoot = temporaryFolder.newFolder("allowed-root").toPath()
        val allowedWasm = allowedRoot.resolve("allowed.wasm")
        Files.write(allowedWasm, wasmBytes)
        val allowed = runCli("summarize-binary", allowedWasm.toString(), "--allowed-root", allowedRoot.toString())
        assertEquals(0, allowed.exitCode, allowed.stderr)

        val envAllowedRoot = temporaryFolder.newFolder("env-allowed-root").toPath()
        val envAllowedWasm = envAllowedRoot.resolve("env-allowed.wasm")
        Files.write(envAllowedWasm, wasmBytes)
        val additiveAllowedRoots = runCli(
            "summarize-binary",
            envAllowedWasm.toString(),
            "--allowed-root",
            allowedRoot.toString(),
            env = mapOf("HEXANA_CLI_ALLOWED_ROOTS" to envAllowedRoot.toString()),
        )
        assertEquals(0, additiveAllowedRoots.exitCode, additiveAllowedRoots.stderr)

        val denied = runCli("summarize-binary", wasm.toString(), "--allowed-root", allowedRoot.toString())
        assertEquals(1, denied.exitCode, denied.stderr)
        assertTrue(denied.stderr.contains("outside allowed roots"), denied.stderr)
    }

    @Test
    fun cliResourceEnvFlagsOverrideMcpEnvAndConfigFilesAreIgnored() {
        val wasm = wasmFile("(module (memory 1) (func (export \"main\")))")
        val home = temporaryFolder.newFolder("home").toPath()
        Files.createDirectories(home.resolve(".hexana"))
        Files.writeString(home.resolve(".hexana/config"), "maxOutputBytes=1\n")

        val cliLimited = runCli(
            "summarize-module",
            wasm.toString(),
            "--format",
            "json",
            env = mapOf("HEXANA_CLI_MAX_OUTPUT_BYTES" to "4", "HOME" to home.toString()),
        )
        assertEquals(1, cliLimited.exitCode, cliLimited.stderr)
        assertTrue(cliLimited.stderr.contains("maxOutputBytes=4"), cliLimited.stderr)

        val flagOverrides = runCli(
            "summarize-module",
            wasm.toString(),
            "--format",
            "json",
            "--max-output-bytes",
            "10000",
            env = mapOf("HEXANA_CLI_MAX_OUTPUT_BYTES" to "4", "HOME" to home.toString()),
        )
        assertEquals(0, flagOverrides.exitCode, flagOverrides.stderr)

        val mcpIgnored = runCli(
            "summarize-module",
            wasm.toString(),
            "--format",
            "json",
            env = mapOf("HEXANA_MCP_MAX_OUTPUT_BYTES" to "4", "HOME" to home.toString()),
        )
        assertEquals(0, mcpIgnored.exitCode, mcpIgnored.stderr)

        val invalidCliEnv = runCli(
            "summarize-module",
            wasm.toString(),
            env = mapOf("HEXANA_CLI_MAX_OUTPUT_BYTES" to "0"),
        )
        assertEquals(2, invalidCliEnv.exitCode, invalidCliEnv.stderr)
        assertTrue(
            invalidCliEnv.stderr.contains("HEXANA_CLI_MAX_OUTPUT_BYTES must be a positive integer"),
            invalidCliEnv.stderr,
        )
    }

    @Test
    fun usageAndDomainFailuresUseExpectedExitCodes() {
        val usage = runCli("summarize-module", "--format", "raw")
        assertEquals(2, usage.exitCode, usage.stderr)
        assertTrue(usage.stderr.contains("Unsupported summarize-module format"), usage.stderr)

        val operational = runCli("wasm-to-wat", missingPath("missing.wasm").toString(), "--format", "raw")
        assertEquals(1, operational.exitCode, operational.stderr)
        assertTrue(operational.stdout.isBlank(), operational.stdout)
        assertTrue(operational.stderr.contains("hexana-cli wasm-to-wat failed"), operational.stderr)
        assertFalse(operational.stderr.contains("Exception"), operational.stderr)

        val wasm = wasmFile("(module)")
        val wasmBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(wasm))

        val scriptOption = runCli("summarize-binary", wasm.toString(), "--script-text", "print(1);")
        assertEquals(2, scriptOption.exitCode, scriptOption.stderr)
        assertTrue(scriptOption.stderr.contains("Unsupported summarize-binary option: --script-text"), scriptOption.stderr)

        val wrongSourceAlias = runCli("summarize-module", "--binary-base64", wasmBase64)
        assertEquals(2, wrongSourceAlias.exitCode, wrongSourceAlias.stderr)
        assertTrue(wrongSourceAlias.stderr.contains("Unsupported summarize-module option: --binary-base64"), wrongSourceAlias.stderr)

        val repeatedBase64 = runCli("summarize-binary", "--binary-base64", wasmBase64, "--bytes-base64", wasmBase64)
        assertEquals(2, repeatedBase64.exitCode, repeatedBase64.stderr)
        assertTrue(
            repeatedBase64.stderr.contains("Repeated summarize-binary base64 source option: --bytes-base64"),
            repeatedBase64.stderr,
        )
    }

    @Test
    fun finalNormalizedOutputLimitBoundariesApplyToStdoutAndOutputFiles() {
        val wasm = wasmFile("(module)")
        val args = arrayOf("summarize-module", wasm.toString(), "--format", "json")

        val baseline = runCli(*args)
        assertEquals(0, baseline.exitCode, baseline.stderr)
        assertEquals(1, trailingNewlineCount(baseline.stdout), baseline.stdout)
        val finalBytes = baseline.stdout.toByteArray(Charsets.UTF_8).size

        val stdoutUnderLimit = runCli(*args, "--max-output-bytes", (finalBytes - 1).toString())
        assertOutputLimitFailure(stdoutUnderLimit, finalBytes - 1)
        val stdoutExactLimit = runCli(*args, "--max-output-bytes", finalBytes.toString())
        assertEquals(0, stdoutExactLimit.exitCode, stdoutExactLimit.stderr)
        assertEquals(baseline.stdout, stdoutExactLimit.stdout)
        val stdoutOverLimit = runCli(*args, "--max-output-bytes", (finalBytes + 1).toString())
        assertEquals(0, stdoutOverLimit.exitCode, stdoutOverLimit.stderr)
        assertEquals(baseline.stdout, stdoutOverLimit.stdout)

        val fileUnderLimit = temporaryFolder.root.toPath().resolve("under-limit.json")
        val fileUnderResult = runCli(
            *args,
            "--output",
            fileUnderLimit.toString(),
            "--max-output-bytes",
            (finalBytes - 1).toString(),
        )
        assertOutputLimitFailure(fileUnderResult, finalBytes - 1)
        assertFalse(Files.exists(fileUnderLimit), "output file must not be written when normalized output exceeds limit")

        val fileExactLimit = temporaryFolder.root.toPath().resolve("exact-limit.json")
        val fileExactResult = runCli(
            *args,
            "--output",
            fileExactLimit.toString(),
            "--max-output-bytes",
            finalBytes.toString(),
        )
        assertEquals(0, fileExactResult.exitCode, fileExactResult.stderr)
        assertEquals("", fileExactResult.stdout)
        assertEquals(baseline.stdout, Files.readString(fileExactLimit))

        val fileOverLimit = temporaryFolder.root.toPath().resolve("over-limit.json")
        val fileOverResult = runCli(
            *args,
            "--output",
            fileOverLimit.toString(),
            "--max-output-bytes",
            (finalBytes + 1).toString(),
        )
        assertEquals(0, fileOverResult.exitCode, fileOverResult.stderr)
        assertEquals("", fileOverResult.stdout)
        assertEquals(baseline.stdout, Files.readString(fileOverLimit))
    }

    @Test
    fun blankInstalledStandardSourcesAreUsageErrorsWithoutStackTraces() {
        listOf(
            listOf("summarize-binary", ""),
            listOf("summarize-binary", "--binary-base64", ""),
            listOf("summarize-binary", "--bytes-base64", ""),
            listOf("summarize-module", ""),
            listOf("summarize-module", "--wasm-base64", ""),
            listOf("summarize-module", "--bytes-base64", ""),
            listOf("wasm-to-wat", ""),
            listOf("wasm-to-wat", "--wasm-base64", ""),
            listOf("wasm-to-wat", "--bytes-base64", ""),
        ).forEach { args ->
            assertBlankSourceUsage(runCli(*args.toTypedArray()))
        }
    }

    private fun assertBlankSourceUsage(result: ProcessResult) {
        assertEquals(2, result.exitCode, result.stderr)
        assertTrue(result.stdout.isBlank(), result.stdout)
        assertEquals("Blank source value is not allowed.\n", result.stderr)
        assertNoStackTraceMarkers(result.stderr)
    }

    private fun assertOutputLimitFailure(result: ProcessResult, maxOutputBytes: Int) {
        assertEquals(1, result.exitCode, result.stderr)
        assertEquals("", result.stdout)
        assertTrue(result.stderr.contains("maxOutputBytes=$maxOutputBytes"), result.stderr)
        assertNoStackTraceMarkers(result.stderr)
    }

    private fun runCli(
        vararg args: String,
        stdin: ByteArray? = null,
        env: Map<String, String> = emptyMap(),
    ): ProcessResult = runInstalled(
        installDir = Path.of(requireNotNull(System.getProperty("hexana.cli.integration.installDir"))),
        executableName = "hexana-cli",
        args = args.toList(),
        stdin = stdin,
        env = env + ("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
    )

    private fun wasmFile(wat: String): Path {
        val file = temporaryFolder.newFile("fixture-${System.nanoTime()}.wasm").toPath()
        Files.write(file, wat2wasm(wat))
        return file
    }

    private fun missingPath(name: String): Path = temporaryFolder.root.toPath().resolve(name)
}

private data class ProcessResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

private fun runInstalled(
    installDir: Path,
    executableName: String,
    args: List<String>,
    stdin: ByteArray?,
    env: Map<String, String>,
): ProcessResult {
    val executable = installedLauncher(installDir, executableName)
    val process = ProcessBuilder(listOf(executable.toString()) + args)
        .redirectErrorStream(false)
        .apply {
            environment().keys.removeAll { it.startsWith("HEXANA_CLI_") || it.startsWith("HEXANA_MCP_") }
            environment().putAll(env)
            environment()["JAVA_HOME"] = System.getProperty("java.home")
        }
        .start()
    if (stdin != null) {
        process.outputStream.use { it.write(stdin) }
    } else {
        process.outputStream.close()
    }
    val executor = Executors.newFixedThreadPool(2)
    try {
        val stdout = executor.readAsync { process.inputStream.readBytes() }
        val stderr = executor.readAsync { process.errorStream.readBytes() }
        check(process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Timed out running $executableName ${args.joinToString(" ")}"
        }
        return ProcessResult(
            exitCode = process.exitValue(),
            stdout = stdout.get().toString(Charsets.UTF_8),
            stderr = stderr.get().toString(Charsets.UTF_8),
        )
    } finally {
        executor.shutdownNow()
    }
}

private fun installedLauncher(installDir: Path, executableName: String): Path =
    installDir.resolve("bin").resolve(if (isWindows()) "$executableName.bat" else executableName)

private fun isWindows(): Boolean =
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private fun ExecutorService.readAsync(block: () -> ByteArray): CompletableFuture<ByteArray> =
    CompletableFuture.supplyAsync(block, this)

private fun trailingNewlineCount(value: String): Int =
    value.reversed().takeWhile { it == '\n' }.length

private fun assertNoStackTraceMarkers(stderr: String) {
    listOf("Exception", "Caused by:", "\tat ").forEach { marker ->
        assertFalse(stderr.contains(marker), stderr)
    }
}
