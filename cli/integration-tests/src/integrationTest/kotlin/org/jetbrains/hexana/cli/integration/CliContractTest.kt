package org.jetbrains.hexana.cli.integration

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class CliContractTest {
    @TempDir
    lateinit var tempDir: Path

    private val target = CliTarget.fromSystemProperties()

    @Test
    fun `products expose the exact version and tools inventories`() {
        target.products.forEach { product ->
            val version = target.run(product, listOf("version"))
            assertEquals(0, version.exitCode, version.describe())
            assertEquals("${product.executableName} ${target.version}\n", version.stdout)
            assertEquals("", version.stderr)

            val tools = target.run(product, listOf("tools"))
            assertEquals(0, tools.exitCode, tools.describe())
            assertEquals(product.toolNames, toolNames(tools.stdout))
            assertEquals("", tools.stderr)
        }
    }

    @Test
    fun `products reject commands owned by the other CLI with diagnostics on stderr`() {
        target.products.forEach { product ->
            product.otherOwnedCommands.forEach { command ->
                val result = target.run(product, listOf(command))
                assertEquals(2, result.exitCode, result.describe())
                assertEquals("", result.stdout, result.describe())
                assertTrue(result.stderr.contains("no such subcommand: $command"), result.describe())
            }
        }
    }

    @Test
    fun `standard CLI converts committed minimal wasm bytes delivered through stdin`() {
        val result = target.run(
            product = CliProduct.STANDARD,
            args = listOf("wasm-to-wat", "--stdin", "--format", "raw"),
            stdin = MINIMAL_WASM_MODULE,
        )

        assertEquals(0, result.exitCode, result.describe())
        assertEquals("", result.stderr)
        assertTrue(result.stdout.contains("(module"), result.describe())
    }

    @Test
    fun `standard CLI accepts stdin base64 and path sources`() {
        val wasm = wasmFixture("standard.wasm")
        val base64 = Base64.getEncoder().encodeToString(MINIMAL_WASM_MODULE)

        val binary = target.run(CliProduct.STANDARD, listOf("summarize-binary", "--stdin", "--format", "json"), stdin = MINIMAL_WASM_MODULE)
        assertCliSuccess(binary)
        assertTrue(binary.stdout.contains("\"format\":\"wasm\""), binary.describe())

        val module = target.run(CliProduct.STANDARD, listOf("summarize-module", "--wasm-base64", base64, "--format", "json"))
        assertCliSuccess(module)
        assertEquals(0, JsonParser.parseString(module.stdout).asJsonObject["functions"].asInt)

        val wat = target.run(CliProduct.STANDARD, listOf("wasm-to-wat", wasm.toString(), "--format", "raw"))
        assertCliSuccess(wat)
        assertTrue(wat.stdout.contains("(module"), wat.describe())
    }

    @Test
    fun `standard CLI writes requested output file`() {
        val wasm = wasmFixture("summary.wasm")
        val output = tempDir.resolve("summary.json")
        val written = target.run(CliProduct.STANDARD, listOf("summarize-binary", wasm.toString(), "--output", output.toString()))
        assertCliSuccess(written)
        assertEquals("", written.stdout)
        assertTrue(Files.readString(output).contains("\"format\":\"wasm\""))
    }

    @Test
    fun `standard CLI enforces allowed roots for path input`() {
        val wasm = wasmFixture("denied.wasm")
        val deniedRoot = Files.createDirectories(tempDir.resolve("other"))
        assertCliFailure(target.run(CliProduct.STANDARD, listOf("summarize-binary", wasm.toString(), "--allowed-root", deniedRoot.toString())), 1, "outside allowed roots")
    }

    @Test
    fun `standard CLI rejects invalid formats and source selections`() {
        val base64 = Base64.getEncoder().encodeToString(MINIMAL_WASM_MODULE)
        assertCliFailure(target.run(CliProduct.STANDARD, listOf("summarize-module", "--format", "raw")), 2, "Unsupported summarize-module format")
        assertCliFailure(target.run(CliProduct.STANDARD, listOf("summarize-binary", "")), 2, "Blank source value is not allowed")
        assertCliFailure(target.run(CliProduct.STANDARD, listOf("summarize-binary", "--binary-base64", base64, "--bytes-base64", base64)), 2, "Repeated summarize-binary base64 source option")
    }

    @Test
    fun `standard CLI reports missing path as an operational failure`() {
        assertCliFailure(target.run(CliProduct.STANDARD, listOf("wasm-to-wat", tempDir.resolve("missing.wasm").toString(), "--format", "raw")), 1, "hexana-cli wasm-to-wat failed")
    }

    @Test
    fun `script CLI prints exact multiline raw output for a path binary`() {
        val binary = binaryFixture("script.bin")
        val result = target.run(CliProduct.SCRIPT, listOf("run-script", binary.toString(), "--script-text", "print(binary.bytes.size); print(binary.bytes.get(1));", "--format", "raw"))
        assertCliSuccess(result)
        assertEquals("3\n2", result.stdout)
    }

    @Test
    fun `script CLI accepts binary base64 and legacy bytes base64 sources`() {
        val binaryBase64 = target.run(CliProduct.SCRIPT, listOf("run-script", "--binary-base64", Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6)), "--script-text", "print(binary.bytes.get(2));", "--format", "raw"))
        assertCliSuccess(binaryBase64)
        assertEquals("6", binaryBase64.stdout)

        val legacyBytesBase64 = target.run(CliProduct.SCRIPT, listOf("run-script", "--bytes-base64", Base64.getEncoder().encodeToString(byteArrayOf(7, 8, 9)), "--script-text", "print(binary.bytes.get(2));", "--format", "raw"))
        assertCliSuccess(legacyBytesBase64)
        assertEquals("9", legacyBytesBase64.stdout)
    }

    @Test
    fun `script CLI reports complete stdin JSON result`() {
        val result = target.run(CliProduct.SCRIPT, listOf("run-script", "--stdin", "--script-text", "print(binary.bytes.size);", "--format", "json"), stdin = byteArrayOf(7, 8, 9))
        assertCliSuccess(result)
        val json = JsonParser.parseString(result.stdout).asJsonObject
        assertEquals("SUCCESS", json["status"].asString)
        assertTrue(json["success"].asBoolean)
        assertEquals(listOf("3"), json["output"].asJsonObject["lines"].asJsonArray.map { it.asString })
        val binary = json["binary"].asJsonObject
        assertEquals("generic", binary["format"].asString)
        assertEquals(3, binary["byteLength"].asInt)
        assertFalse(binary.has("path"))
    }

    @Test
    fun `script CLI reads a script file`() {
        val binary = binaryFixture("script-file.bin")
        val script = tempDir.resolve("script.js").also { Files.writeString(it, "print('from-file');") }
        val result = target.run(CliProduct.SCRIPT, listOf("run-script", binary.toString(), "--script", script.toString(), "--format", "raw"))
        assertCliSuccess(result)
        assertEquals("from-file", result.stdout)
    }

    @Test
    fun `script CLI writes requested output file`() {
        val binary = binaryFixture("script-output.bin")
        val output = tempDir.resolve("script-output.txt")
        val written = target.run(CliProduct.SCRIPT, listOf("run-script", binary.toString(), "--script-text", "print('to-file');", "--format", "raw", "--output", output.toString()))
        assertCliSuccess(written)
        assertEquals("", written.stdout)
        assertEquals("to-file", Files.readString(output))
    }

    @Test
    fun `script CLI enforces allowed roots for binary paths`() {
        val binary = binaryFixture("denied-binary.bin")
        val deniedRoot = Files.createDirectories(tempDir.resolve("binary-root"))
        assertCliFailure(target.run(CliProduct.SCRIPT, listOf("run-script", binary.toString(), "--script-text", "print(1);", "--allowed-root", deniedRoot.toString())), 1, "outside allowed roots")
    }

    @Test
    fun `script CLI enforces allowed roots for script files`() {
        val allowedRoot = Files.createDirectories(tempDir.resolve("allowed"))
        val binary = allowedRoot.resolve("input.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val script = tempDir.resolve("outside.js").also { Files.writeString(it, "print('must-not-run');") }
        assertCliFailure(target.run(CliProduct.SCRIPT, listOf("run-script", binary.toString(), "--script", script.toString(), "--allowed-root", allowedRoot.toString())), 1, "outside allowed roots")
    }

    @Test
    fun `script CLI rejects a missing script source before writing output`() {
        val binary = binaryFixture("missing-script.bin")
        val output = tempDir.resolve("must-not-exist.txt")
        assertScriptSourceFailure(listOf("run-script", binary.toString(), "--format", "raw", "--output", output.toString()), "Exactly one of --script or --script-text is required.", output)
    }

    @Test
    fun `script CLI rejects duplicate script files before writing output`() {
        val binary = binaryFixture("duplicate-script.bin")
        val first = tempDir.resolve("first.js").also { Files.writeString(it, "print('first');") }
        val second = tempDir.resolve("second.js").also { Files.writeString(it, "print('second');") }
        val output = tempDir.resolve("must-not-exist.txt")
        assertScriptSourceFailure(listOf("run-script", binary.toString(), "--script", first.toString(), "--script", second.toString(), "--output", output.toString()), "Provide exactly one of --script or --script-text.", output)
    }

    @Test
    fun `script CLI rejects duplicate inline scripts before writing output`() {
        val binary = binaryFixture("duplicate-inline.bin")
        val output = tempDir.resolve("must-not-exist.txt")
        assertScriptSourceFailure(listOf("run-script", binary.toString(), "--script-text", "print(1);", "--script-text", "print(2);", "--output", output.toString()), "Provide exactly one of --script or --script-text.", output)
    }

    @Test
    fun `script CLI rejects conflicting script sources before writing output`() {
        val binary = binaryFixture("conflicting.bin")
        val script = tempDir.resolve("validation.js").also { Files.writeString(it, "print('executed');") }
        val output = tempDir.resolve("must-not-exist.txt")
        assertScriptSourceFailure(listOf("run-script", binary.toString(), "--script", script.toString(), "--script-text", "print(1);", "--output", output.toString()), "Provide exactly one of --script or --script-text.", output)
    }

    @Test
    fun `script CLI maps script throws to runtime errors`() {
        val binary = binaryFixture("runtime-error.bin")
        assertCliFailure(target.run(CliProduct.SCRIPT, listOf("run-script", binary.toString(), "--script-text", "throw new Error('contract-failure');", "--format", "raw")), 1, "RUNTIME_ERROR")
    }

    @Test
    fun `script CLI permits exact output limits and rejects overflow`() {
        val binary = binaryFixture("output-limit.bin")
        val exactLimit = target.run(CliProduct.SCRIPT, listOf("run-script", binary.toString(), "--script-text", "print('abc');", "--format", "raw", "--max-output-bytes", "3"))
        assertCliSuccess(exactLimit)
        assertEquals("abc", exactLimit.stdout)
        assertCliFailure(target.run(CliProduct.SCRIPT, listOf("run-script", binary.toString(), "--script-text", "print('abc');", "--format", "raw", "--max-output-bytes", "2")), 1, "maxOutputBytes=2")
    }

    @Test
    fun `script CLI denies host class access`() {
        val binary = binaryFixture("sandbox.bin")
        assertCliFailure(target.run(CliProduct.SCRIPT, listOf("run-script", binary.toString(), "--script-text", "Java.type('java.lang.System');", "--format", "raw")), 1, "SANDBOX_DENIED")
    }

    @Test
    fun `telemetry reports local product and command identities for both products`() {
        val home = Files.createDirectories(tempDir.resolve("telemetry-home"))
        FakeTelemetryServer().use { server ->
            val telemetry = mapOf(
                "HEXANA_CLI_TELEMETRY_ENABLED" to "true",
                "HEXANA_CLI_POSTHOG_HOST" to server.url,
                "HEXANA_CLI_POSTHOG_KEY" to "contract-key",
                "HOME" to home.toString(),
            ) + target.jvmUserHome(home)
            val standard = target.run(CliProduct.STANDARD, listOf("version"), telemetry)
            val script = target.run(CliProduct.SCRIPT, listOf("version"), telemetry)
            assertCliSuccess(standard)
            assertCliSuccess(script)

            val events = server.awaitEvents(2)
            val standardEvent = events.single { it.productId == "hexana-cli" && it.commandName == "version" }
            val scriptEvent = events.single { it.productId == "hexana-cli-script" && it.commandName == "version" }
            assertEquals("version", standardEvent.commandName)
            assertEquals("version", scriptEvent.commandName)
            assertEquals(setOf("product_id", "command_name"), standardEvent.customPropertyKeys)
            assertEquals(setOf("product_id", "command_name"), scriptEvent.customPropertyKeys)
            assertEquals(standardEvent.distinctId, scriptEvent.distinctId)
            assertNotEquals("", standardEvent.distinctId)
            assertTrue(Files.isRegularFile(home.resolve(".hexana/analytics-id")))
        }
        FakeTelemetryServer().use { server ->
            val home = Files.createDirectories(tempDir.resolve("telemetry-disabled-home"))
            val disabled = target.run(
                CliProduct.SCRIPT,
                listOf("version"),
                mapOf(
                    "HEXANA_CLI_TELEMETRY_ENABLED" to "false",
                    "HEXANA_CLI_POSTHOG_HOST" to server.url,
                    "HEXANA_CLI_POSTHOG_KEY" to "contract-key",
                    "HOME" to home.toString(),
                ) + target.jvmUserHome(home),
            )
            assertCliSuccess(disabled)
            server.assertNoRequests()
        }
    }

    @Test
    fun `native executables do not rely on JAVA_HOME`() {
        assumeTrue(target.kind == "native", "native-only contract")
        target.products.forEach { product ->
            val absent = target.run(product, listOf("version"))
            assertCliSuccess(absent)
            val result = target.run(product, listOf("version"), extraEnvironment = mapOf("JAVA_HOME" to tempDir.resolve("absent-java").toString()))
            assertCliSuccess(result)
            assertEquals("${product.executableName} ${target.version}\n", result.stdout)
        }
    }

    @Test
    fun `runner kills a non-terminating process tree on timeout`() {
        val result = CliRunner.run(
            command = listOf(javaLauncher(), "-cp", System.getProperty("java.class.path"), TimeoutFixture::class.java.name),
            timeout = Duration.ofSeconds(3),
            disableTelemetry = false,
        )
        assertTrue(result.timedOut, result.describe())
        val childPid = result.stdout.trim().toLongOrNull() ?: fail("Fixture did not report its child pid: ${result.describe()}")
        assertTrue(childPid in result.destroyedProcessIds, result.describe())
        assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false), "child $childPid survived timeout teardown")
    }

    @Test
    fun `runner times out while oversized stdin is blocked by a non-reader`() {
        val result = CliRunner.run(
            command = listOf(javaLauncher(), "-cp", System.getProperty("java.class.path"), TimeoutFixture::class.java.name, "non-reader"),
            stdin = ByteArray(2 * 1024 * 1024),
            timeout = Duration.ofMillis(500),
            disableTelemetry = false,
        )
        assertTrue(result.timedOut, result.describe())
        assertTrue(result.destroyedProcessIds.isNotEmpty(), result.describe())
    }

    private fun wasmFixture(name: String): Path = tempDir.resolve(name).also { it.writeBytes(MINIMAL_WASM_MODULE) }

    private fun binaryFixture(name: String): Path = tempDir.resolve(name).also { it.writeBytes(byteArrayOf(1, 2, 3)) }

    private fun assertScriptSourceFailure(args: List<String>, expected: String, output: Path) {
        Files.deleteIfExists(output)
        val result = target.run(CliProduct.SCRIPT, args)
        assertCliFailure(result, 2, expected)
        assertFalse(Files.exists(output), "invalid script source must not write output")
    }
}

private enum class CliProduct(
    val executableName: String,
    val toolNames: List<String>,
    val otherOwnedCommands: List<String>,
) {
    STANDARD(
        "hexana-cli",
        listOf("summarize-binary", "summarize-module", "wasm-to-wat"),
        listOf("run-script"),
    ),
    SCRIPT(
        "hexana-cli-script",
        listOf("run-script"),
        listOf("summarize-binary", "summarize-module", "wasm-to-wat"),
    ),
}

private class CliTarget private constructor(
    val kind: String,
    val version: String,
    private val launchers: Map<CliProduct, Path>,
) {
    val products: List<CliProduct> = CliProduct.entries

    fun run(
        product: CliProduct,
        args: List<String>,
        extraEnvironment: Map<String, String> = emptyMap(),
        stdin: ByteArray? = null,
    ): CliProcessResult = CliRunner.run(
        command = listOf(launchers.getValue(product).toString()) + args,
        stdin = stdin,
        environment = extraEnvironment,
        unsetJavaHome = kind == "native",
    )

    fun jvmUserHome(home: Path): Map<String, String> =
        if (kind == "jvm") mapOf("JAVA_OPTS" to "-Duser.home=$home") else emptyMap()

    companion object {
        fun fromSystemProperties(): CliTarget {
            val kind = requireNotNull(System.getProperty("hexana.cli.integration.target"))
            val standard = Path.of(requireNotNull(System.getProperty("hexana.cli.integration.standardLauncher")))
            val script = Path.of(requireNotNull(System.getProperty("hexana.cli.integration.scriptLauncher")))
            check(Files.isExecutable(standard)) { "Missing executable launcher: $standard" }
            check(Files.isExecutable(script)) { "Missing executable launcher: $script" }
            return CliTarget(kind, requireNotNull(System.getProperty("hexana.cli.integration.version")), mapOf(CliProduct.STANDARD to standard, CliProduct.SCRIPT to script))
        }
    }
}

private class FakeTelemetryServer : AutoCloseable {
    private val requests = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val raw = exchange.requestBody.use { it.readBytes() }
            val body = if (exchange.requestHeaders["Content-Encoding"].orEmpty().any { it.equals("gzip", true) }) {
                GZIPInputStream(raw.inputStream()).use { it.readBytes() }
            } else raw
            requests += body.toString(Charsets.UTF_8)
            val response = "{}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    fun awaitEvents(count: Int): List<TelemetryEvent> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val events = requests.flatMap(::events)
            if (events.size >= count) return events
            Thread.sleep(20)
        }
        fail("Expected $count local telemetry events, got $requests")
    }

    fun assertNoRequests() {
        Thread.sleep(250)
        assertTrue(requests.isEmpty(), "Expected telemetry opt-out to make no local request, got $requests")
    }

    override fun close() = server.stop(0)

    private fun events(body: String): List<TelemetryEvent> {
        val root = JsonParser.parseString(body).asJsonObject
        val entries = root["batch"]?.asJsonArray?.map { it.asJsonObject } ?: listOf(root)
        return entries.mapNotNull { event -> event.toTelemetryEvent() }
    }
}

private data class TelemetryEvent(
    val productId: String,
    val commandName: String,
    val distinctId: String,
    val customPropertyKeys: Set<String>,
)

private fun JsonObject.toTelemetryEvent(): TelemetryEvent? {
    if (get("event")?.asString != "cli_command_invoked") return null
    val properties = getAsJsonObject("properties")
    return TelemetryEvent(
        productId = properties["product_id"]?.asString ?: return null,
        commandName = properties["command_name"]?.asString ?: return null,
        distinctId = get("distinct_id")?.asString ?: get("distinctId")?.asString ?: return null,
        customPropertyKeys = properties.keySet().filterNot { it.startsWith("$") || it == "token" }.toSet(),
    )
}

private fun toolNames(output: String): List<String> = output.lineSequence()
    .drop(1)
    .filter { it.isNotBlank() && !it.first().isWhitespace() }
    .toList()

private fun assertCliFailure(result: CliProcessResult, exitCode: Int, expectedDiagnostic: String) {
    assertEquals(exitCode, result.exitCode, result.describe())
    assertEquals("", result.stdout, result.describe())
    assertTrue(result.stderr.contains(expectedDiagnostic), result.describe())
    listOf("Exception", "Caused by:", "\tat ").forEach { marker ->
        assertFalse(result.stderr.contains(marker), result.describe())
    }
}

private fun assertCliSuccess(result: CliProcessResult) {
    assertEquals(0, result.exitCode, result.describe())
    assertEquals("", result.stderr, result.describe())
}

private val MINIMAL_WASM_MODULE = byteArrayOf(0, 0x61, 0x73, 0x6d, 1, 0, 0, 0)

internal fun javaLauncher(): String = Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString()

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
