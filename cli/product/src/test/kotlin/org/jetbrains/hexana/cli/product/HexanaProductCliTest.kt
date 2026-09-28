package org.jetbrains.hexana.cli.product

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.metadata.HexanaCliExecutionStatus
import org.jetbrains.hexana.cli.metadata.HexanaCliInputOptionSpec
import org.jetbrains.hexana.cli.metadata.HexanaCliProductToolSelection
import org.jetbrains.hexana.cli.metadata.HexanaCliParameterCardinality
import org.jetbrains.hexana.cli.metadata.HexanaCliParameterSpec
import org.jetbrains.hexana.cli.metadata.HexanaGeneratedCliProducts
import org.jetbrains.hexana.cli.metadata.HexanaGeneratedCliToolRegistry
import org.jetbrains.hexana.cli.metadata.validateHexanaCliUsageExamples
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlin.test.fail

class HexanaProductCliTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun declaredParametersPreserveRepeatOrderAndSingleUseWithEitherSource() {
        val executor = RecordingProductExecutor()
        val cli = parameterFixtureCli(executor)
        val pathResult = cli.run(
            arrayOf("summarize-module", "/fixture.wasm", "--fixture-tag", "first", "--fixture-mode", "strict", "--fixture-tag", "second"),
            ByteArrayInputStream(ByteArray(0)), fixtureEnv,
        )
        assertEquals(SUCCESS_EXIT_CODE, pathResult.exitCode, pathResult.stderr)
        assertEquals(mapOf("tags" to listOf("first", "second"), "mode" to listOf("strict")), executor.singleRequest().parameterValues)
        assertEquals(ToolInputSourceSelection.Path("/fixture.wasm", "wasm"), executor.singleRequest().sourceSelection)

        val stdinExecutor = RecordingProductExecutor()
        val stdinResult = parameterFixtureCli(stdinExecutor).run(
            arrayOf("summarize-module", "--stdin", "--fixture-tag", "stdin"),
            ByteArrayInputStream(MINIMAL_WASM_MODULE), fixtureEnv,
        )
        assertEquals(SUCCESS_EXIT_CODE, stdinResult.exitCode, stdinResult.stderr)
        assertEquals(listOf("stdin"), stdinExecutor.singleRequest().parameterValues["tags"])
        stdinExecutor.singleRequest().assertStdinSource(MINIMAL_WASM_MODULE, "wasm")
    }

    @Test
    fun declaredParametersRejectDuplicateCapUnsupportedOptionAndMultipleSources() {
        val cli = parameterFixtureCli(RecordingProductExecutor())
        val invalid = listOf(
            arrayOf("summarize-module", "file.wasm", "--fixture-mode", "first", "--fixture-mode", "second") to "Repeated",
            arrayOf("summarize-module", "file.wasm", "--fixture-tag", "same", "--fixture-tag", "same") to "Duplicate",
            arrayOf("summarize-module", "file.wasm", "--fixture-tag", "a", "--fixture-tag", "b", "--fixture-tag", "c") to "at most 2",
            arrayOf("summarize-module", "file.wasm", "--fixture-other", "value") to "Unknown",
            arrayOf("summarize-module", "file.wasm", "--stdin") to "exactly one",
        )
        invalid.forEach { (args, message) ->
            val result = cli.run(args, ByteArrayInputStream(MINIMAL_WASM_MODULE), fixtureEnv)
            assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
            assertTrue(result.stderr.contains(message), result.stderr)
        }
    }

    @Test
    fun declaredJsonParametersRequireObjectsBeforeExecution() {
        val executor = RecordingProductExecutor()
        val cli = parameterFixtureCli(executor)
        val objectValue = """{"enabled":true,"values":[1,null]}"""
        val valid = cli.run(
            arrayOf("summarize-module", "/fixture.wasm", "--fixture-policy", objectValue),
            FailOnReadInputStream, fixtureEnv,
        )
        assertEquals(SUCCESS_EXIT_CODE, valid.exitCode, valid.stderr)

        listOf("[]", "null", "42", "true", "\"text\"", "{invalid").forEach { value ->
            val result = cli.run(
                arrayOf("summarize-module", "/fixture.wasm", "--fixture-policy", value),
                FailOnReadInputStream, fixtureEnv,
            )
            assertEquals(USAGE_EXIT_CODE, result.exitCode, "value=$value: ${result.stderr}")
            assertTrue(result.stderr.contains("--fixture-policy requires JSON object value"), result.stderr)
        }
        assertEquals(listOf(objectValue), executor.singleRequest().parameterValues["policyJson"])
    }

    @Test
    fun declaredParameterHelpShowsTypeLimitDescriptionAndPreservesLegacyScriptEntries() {
        val help = parameterFixtureCli(RecordingProductExecutor()).run(
            arrayOf("help", "summarize-module"), FailOnReadInputStream, fixtureEnv,
        )
        assertEquals(SUCCESS_EXIT_CODE, help.exitCode, help.stderr)
        assertTrue(help.stdout.contains("--fixture-tag <string>  Ordered tags (repeatable, max 2)."), help.stdout)
        assertTrue(help.stdout.contains("--fixture-mode <string>  Execution mode."), help.stdout)
        assertEquals(1, help.stdout.lines().count { it.trim().startsWith("--fixture-tag <string>") })
        assertEquals(1, help.stdout.lines().count { it.trim().startsWith("--fixture-mode <string>") })

        val scriptHelp = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI_SCRIPT, FailOnScriptExecuteExecutor).run(
            arrayOf("help", "run-script"), FailOnReadInputStream, fixtureEnv,
        )
        assertEquals(SUCCESS_EXIT_CODE, scriptHelp.exitCode, scriptHelp.stderr)
        assertEquals(1, scriptHelp.stdout.lines().count { it.trim().startsWith("--script <string>") })
        assertEquals(1, scriptHelp.stdout.lines().count { it.trim().startsWith("--script-text <string>") })
    }

    private fun parameterFixtureCli(executor: RecordingProductExecutor): HexanaProductCli {
        val registry = HexanaGeneratedCliToolRegistry.TOOLS.map { spec ->
            if (spec.toolName == "summarize_module") spec.copy(
                inputOptions = spec.inputOptions + listOf(
                    HexanaCliInputOptionSpec("--fixture-tag", "list<string>", "Ordered tags.", null, null, false),
                    HexanaCliInputOptionSpec("--fixture-mode", "string", "Execution mode.", null, null, false),
                    HexanaCliInputOptionSpec("--fixture-policy", "json", "Policy object.", null, null, false),
                ),
                parameterOptions = listOf(
                    HexanaCliParameterSpec("--fixture-tag", "tags", "string", HexanaCliParameterCardinality.REPEATABLE, 2),
                    HexanaCliParameterSpec("--fixture-mode", "mode", "string", HexanaCliParameterCardinality.SINGLE, 1),
                    HexanaCliParameterSpec("--fixture-policy", "policyJson", "json", HexanaCliParameterCardinality.SINGLE, 1),
                ),
            ) else spec
        }
        return HexanaProductCli(
            HexanaGeneratedCliProducts.HEXANA_CLI, executor,
            HexanaGeneratedCliProducts.SELECTIONS.getValue(HexanaGeneratedCliProducts.HEXANA_CLI.productId), registry,
        )
    }

    private val fixtureEnv = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false")

    @Test
    fun generatedProductSelectionsMatchApprovedCommandInventory() {
        val commands = HexanaGeneratedCliProducts.PRODUCTS.values.associate { product ->
            product.productId to HexanaGeneratedCliProducts.SELECTIONS.getValue(product.productId).map { it.commandName }.sorted()
        }
        assertEquals(
            mapOf(
                "hexana-cli" to listOf("inspect-binary", "summarize-binary", "summarize-module", "wasm-to-wat"),
                "hexana-cli-script" to listOf("run-script", "test"),
                "hexana-wasm-tools" to listOf("component-wit-report", "metadata-audit", "validate-and-explain"),
            ),
            commands,
        )
    }

    @Test
    fun wasmProductUsesGeneratedSelectionAndProjectionGuards() {
        val product = HexanaGeneratedCliProducts.HEXANA_WASM_TOOLS
        val selections = HexanaGeneratedCliProducts.SELECTIONS.getValue(product.productId)
        val bindings = selections.map { testBinding(it.toolName) }
        assertEquals(3, selectedProductTools(product, bindings).size)
        assertFailsWith<IllegalArgumentException> { selectedProductTools(product, bindings.dropLast(1)) }
        assertFailsWith<IllegalArgumentException> {
            selectedProductTools(product, bindings.map { binding ->
                if (binding.toolName == "metadata_audit") binding.copy(
                    supportedOutputFormats = binding.supportedOutputFormats - org.jetbrains.hexana.cli.runtime.ToolOutputFormat.PRETTY,
                ) else binding
            })
        }
        val metadata = HexanaGeneratedCliToolRegistry.TOOLS.filter { spec -> selections.any { it.toolName == spec.toolName } }
        assertTrue(metadata.all { it.outputFormats == setOf(org.jetbrains.hexana.cli.runtime.ToolOutputFormat.JSON,
            org.jetbrains.hexana.cli.runtime.ToolOutputFormat.PRETTY) })
        assertEquals(listOf("--runtime" to 500, "--dependency" to 500), metadata.flatMap { spec ->
            spec.parameterOptions.map { it.option to it.maxOccurrences }
        })
        assertTrue(metadata.none { it.autoStdin })
        val cli = HexanaProductCli(product, object : HexanaProductToolExecutor {
            override val bindings = selections.map { testBinding(it.toolName) }
        })
        val missingSource = cli.run(arrayOf("metadata-audit"), FailOnReadInputStream, fixtureEnv)
        assertEquals(USAGE_EXIT_CODE, missingSource.exitCode)
        assertTrue(missingSource.stderr.contains("Provide a path or --stdin"), missingSource.stderr)

        val recording = RecordingProductExecutor(toolNames = selections.map { it.toolName })
        val executable = HexanaProductCli(product, recording)
        val repeated = executable.run(arrayOf("validate-and-explain", "fixture.wasm", "--runtime", "wasmtime",
            "--runtime", "wasmer"), FailOnReadInputStream, fixtureEnv)
        assertEquals(SUCCESS_EXIT_CODE, repeated.exitCode, repeated.stderr)
        assertEquals(listOf("wasmtime", "wasmer"), recording.singleRequest().parameterValues["runtimes"])
        val unsupported = executable.run(arrayOf("metadata-audit", "fixture.wasm", "--runtime", "wasmtime"),
            FailOnReadInputStream, fixtureEnv)
        assertEquals(USAGE_EXIT_CODE, unsupported.exitCode)
    }

    @Test
    fun selectedProductToolsRejectInvalidMetadataAndExecutorBindings() {
        val selections = HexanaGeneratedCliProducts.SELECTIONS.getValue(HexanaGeneratedCliProducts.HEXANA_CLI.productId)
        val registry = HexanaGeneratedCliToolRegistry.TOOLS
        val bindings = selections.map { testBinding(it.toolName) }

        assertFailsWith<IllegalArgumentException> {
            selectedProductTools(HexanaGeneratedCliProducts.HEXANA_CLI, bindings, selections + selections.first(), registry)
        }
        assertFailsWith<IllegalArgumentException> {
            selectedProductTools(
                HexanaGeneratedCliProducts.HEXANA_CLI,
                bindings,
                selections + HexanaCliProductToolSelection("unknown", "unknown", "ToolDefined", "SemanticJson"),
                registry,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            selectedProductTools(HexanaGeneratedCliProducts.HEXANA_CLI, bindings, selections, registry + registry.first())
        }
        val todo = registry.first { it.executionStatus == HexanaCliExecutionStatus.TODO }
        assertFailsWith<IllegalArgumentException> {
            selectedProductTools(
                HexanaGeneratedCliProducts.HEXANA_CLI,
                bindings + testBinding(todo.toolName),
                selections + HexanaCliProductToolSelection(todo.toolName, todo.commandName, "ToolDefined", "SemanticJson"),
                registry,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            selectedProductTools(HexanaGeneratedCliProducts.HEXANA_CLI, bindings.dropLast(1), selections, registry)
        }
        assertFailsWith<IllegalArgumentException> {
            selectedProductTools(HexanaGeneratedCliProducts.HEXANA_CLI, bindings + bindings.first(), selections, registry)
        }
        assertFailsWith<IllegalArgumentException> {
            selectedProductTools(HexanaGeneratedCliProducts.HEXANA_CLI, bindings + testBinding("run_binary_script"), selections, registry)
        }
    }

    @Test
    fun selectedToolWithoutExecutorBindingFailsAtConstruction() {
        val incompleteExecutor = object : HexanaProductToolExecutor {
            override val bindings = STANDARD_TOOL_NAMES.dropLast(1).map(::testBinding)
        }

        assertFailsWith<IllegalArgumentException> {
            HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI, incompleteExecutor)
        }
    }

    @Test
    fun advertisedFormatWithoutConcreteProjectionFailsAtConstruction() {
        val bindings = HexanaGeneratedCliProducts.SELECTIONS.getValue(HexanaGeneratedCliProducts.HEXANA_CLI.productId).map { selection ->
            val formats = formatsFor(selection.toolName)
            HexanaProductToolExecutorBinding(
                selection.toolName,
                if (selection.toolName == "summarize_module") formats - org.jetbrains.hexana.cli.runtime.ToolOutputFormat.TABLE else formats,
            ) { error("must not execute") }
        }

        val error = assertFailsWith<IllegalArgumentException> {
            selectedProductTools(HexanaGeneratedCliProducts.HEXANA_CLI, bindings)
        }
        assertTrue(error.message.orEmpty().contains("summarize_module' output projections"), error.message)
    }

    @Test
    fun commandHelpDoesNotReadStdinOrRequireSource() {
        listOf("-h", "--help").forEach { helpFlag ->
            val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI, FailOnExecuteExecutor).run(
                args = arrayOf("summarize-binary", helpFlag),
                stdin = FailOnReadInputStream,
                env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
            )

            assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
            assertTrue(result.stdout.startsWith("Usage: hexana-cli summarize-binary"), result.stdout)
        }
    }

    @Test
    fun commandHelpShowsGeneratedFieldsConstraintsSourcesAndExamples() {
        validateHexanaCliUsageExamples()
        val help = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI, FailOnExecuteExecutor).run(
            arrayOf("help", "summarize-module"), FailOnReadInputStream, emptyMap(),
        )
        assertEquals(SUCCESS_EXIT_CODE, help.exitCode, help.stderr)
        assertTrue(help.stdout.contains("--format <json|pretty|table> (default: pretty)"), help.stdout)
        assertTrue(help.stdout.contains("--wasm-base64 <base64>"), help.stdout)
        assertTrue(help.stdout.contains("maximum length derived from --max-inline-bytes"), help.stdout)
        assertTrue(help.stdout.contains("wasm: choose exactly one of wasm, --stdin, --bytes-base64, --wasm-base64"), help.stdout)
        assertTrue(help.stdout.contains("hexana-cli summarize-module module.wasm"), help.stdout)
        assertTrue(help.stdout.contains("hexana-cli summarize-module --stdin"), help.stdout)
        assertTrue(help.stdout.contains("<wasm>  Readable local"), help.stdout)
        assertTrue(!help.stdout.contains("<wasm> <path>"), help.stdout)

        val scriptHelp = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI_SCRIPT, FailOnScriptExecuteExecutor).run(
            arrayOf("help", "test"), FailOnReadInputStream, emptyMap(),
        )
        assertEquals(SUCCESS_EXIT_CODE, scriptHelp.exitCode, scriptHelp.stderr)
        assertTrue(scriptHelp.stdout.contains("<suiteFiles...>  One or more .test.js"), scriptHelp.stdout)
        assertTrue(!scriptHelp.stdout.contains("<suiteFiles> <list<string>>"), scriptHelp.stdout)
    }

    @Test
    fun terminalWidthUsesEnvironmentUnlessTheCommandOptionOverridesIt() {
        val encodedModule = Base64.getEncoder().encodeToString(MINIMAL_WASM_MODULE)
        fun request(args: Array<String>, env: Map<String, String>): HexanaProductToolExecutionRequest {
            val executor = RecordingProductExecutor()
            val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI, executor).run(args, ByteArrayInputStream(ByteArray(0)), env)
            assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
            return executor.singleRequest()
        }

        val fromEnvironment = request(
            arrayOf("summarize-module", "--wasm-base64", encodedModule, "--format", "table"),
            mapOf("HEXANA_CLI_TERMINAL_WIDTH" to "29"),
        )
        assertEquals(29, fromEnvironment.terminalWidth)

        val fromOption = request(
            arrayOf("summarize-module", "--wasm-base64", encodedModule, "--format", "table", "--terminal-width", "17"),
            mapOf("HEXANA_CLI_TERMINAL_WIDTH" to "29"),
        )
        assertEquals(17, fromOption.terminalWidth)

        val jsonWithInvalidEnvironment = request(
            arrayOf("summarize-module", "--wasm-base64", encodedModule, "--format", "json"),
            mapOf("HEXANA_CLI_TERMINAL_WIDTH" to "invalid"),
        )
        assertEquals(null, jsonWithInvalidEnvironment.terminalWidth)
        val jsonWithOption = request(
            arrayOf("summarize-module", "--wasm-base64", encodedModule, "--format", "pretty", "--terminal-width", "17"),
            emptyMap(),
        )
        assertEquals(null, jsonWithOption.terminalWidth)

        listOf("json", "pretty").forEach { format ->
            val ignoredInvalidOption = request(
                arrayOf("summarize-module", "--wasm-base64", encodedModule, "--format", format, "--terminal-width", "invalid"),
                emptyMap(),
            )
            assertEquals(null, ignoredInvalidOption.terminalWidth)
            val ignoredInvalidEnvironment = request(
                arrayOf("summarize-module", "--wasm-base64", encodedModule, "--format", format),
                mapOf("HEXANA_CLI_TERMINAL_WIDTH" to "invalid"),
            )
            assertEquals(null, ignoredInvalidEnvironment.terminalWidth)
        }
        fun invalidTable(args: Array<String>, env: Map<String, String>): ProductCliResult =
            HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI, RecordingProductExecutor()).run(args, ByteArrayInputStream(ByteArray(0)), env)
        val invalidOption = invalidTable(
            arrayOf("summarize-module", "--wasm-base64", encodedModule, "--format", "table", "--terminal-width", "invalid"), emptyMap(),
        )
        assertEquals(USAGE_EXIT_CODE, invalidOption.exitCode)
        assertEquals("--terminal-width must be a positive integer\n", invalidOption.stderr)
        val invalidEnvironment = invalidTable(
            arrayOf("summarize-module", "--wasm-base64", encodedModule, "--format", "table"), mapOf("HEXANA_CLI_TERMINAL_WIDTH" to "invalid"),
        )
        assertEquals(USAGE_EXIT_CODE, invalidEnvironment.exitCode)
        assertEquals("HEXANA_CLI_TERMINAL_WIDTH must be a positive integer\n", invalidEnvironment.stderr)
    }

    @Test
    fun implicitStdinDoesNotRequireAvailableBytes() {
        assumeTrue("Implicit stdin is disabled when a console is attached", System.console() == null)

        val executor = RecordingProductExecutor()
        val result = runStandardSummary(ZeroAvailableInputStream(MINIMAL_WASM_MODULE), executor)

        assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
        assertTrue(result.stdout.contains("\"format\":\"wasm\""), result.stdout)
        executor.singleRequest().assertStdinSource(MINIMAL_WASM_MODULE, slotName = "binary")
    }

    @Test
    fun implicitStdinWaitsForDelayedFirstByte() {
        assumeTrue("Implicit stdin is disabled when a console is attached", System.console() == null)

        val stdin = BlockingFirstReadInputStream(MINIMAL_WASM_MODULE)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val productExecutor = RecordingProductExecutor()
            val future = executor.submit(Callable { runStandardSummary(stdin, productExecutor) })

            assertTrue(stdin.readStarted.await(1, TimeUnit.SECONDS), "Expected CLI to start reading implicit stdin")
            assertFutureStillRunning(future, timeoutMillis = 150)
            stdin.release()

            val result = future.get(1, TimeUnit.SECONDS)
            assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
            assertTrue(result.stdout.contains("\"format\":\"wasm\""), result.stdout)
            productExecutor.singleRequest().assertStdinSource(MINIMAL_WASM_MODULE, slotName = "binary")
        } finally {
            stdin.release()
            executor.shutdownNow()
        }
    }

    @Test
    fun implicitStdinWaitsForSlowChunks() {
        assumeTrue("Implicit stdin is disabled when a console is attached", System.console() == null)

        val stdin = SlowChunkInputStream(MINIMAL_WASM_MODULE, firstChunkSize = 4)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val productExecutor = RecordingProductExecutor()
            val future = executor.submit(Callable { runStandardSummary(stdin, productExecutor) })

            assertTrue(stdin.firstChunkDelivered.await(1, TimeUnit.SECONDS), "Expected CLI to read the first chunk")
            assertTrue(stdin.nextChunkReadStarted.await(1, TimeUnit.SECONDS), "Expected CLI to keep reading until EOF")
            assertFutureStillRunning(future, timeoutMillis = 150)
            stdin.releaseNextChunks()

            val result = future.get(1, TimeUnit.SECONDS)
            assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
            assertTrue(result.stdout.contains("\"format\":\"wasm\""), result.stdout)
            productExecutor.singleRequest().assertStdinSource(MINIMAL_WASM_MODULE, slotName = "binary")
        } finally {
            stdin.releaseNextChunks()
            executor.shutdownNow()
        }
    }

    @Test
    fun implicitStdinPreservesInlineByteLimitFailures() {
        assumeTrue("Implicit stdin is disabled when a console is attached", System.console() == null)

        val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI, FailOnExecuteExecutor).run(
            args = arrayOf("summarize-binary", "--max-inline-bytes", "1"),
            stdin = ZeroAvailableInputStream(MINIMAL_WASM_MODULE),
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )

        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertTrue(result.stderr.contains("Inline bytes exceed maximum size of 1 bytes."), result.stderr)
    }

    @Test
    fun missingImplicitStdinSourceReportsUsageWhenStdinIsEmpty() {
        val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI, FailOnExecuteExecutor).run(
            args = arrayOf("summarize-binary"),
            stdin = ByteArrayInputStream(ByteArray(0)),
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )

        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertEquals(
            "Missing summarize-binary source. Provide a path, --stdin, or a base64 source option.\n",
            result.stderr,
        )
        assertEquals("", result.stdout)
    }

    @Test
    fun missingRunScriptSourceFailsBeforeReadingBinarySource() {
        val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI_SCRIPT, FailOnScriptExecuteExecutor).run(
            args = arrayOf("run-script", "--stdin"),
            stdin = FailOnReadInputStream,
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )

        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertEquals("Exactly one of --script or --script-text is required.\n", result.stderr)
        assertEquals("", result.stdout)
    }

    @Test
    fun artifactTokenWithoutEqualsSignReportsUsageError() {
        val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI_SCRIPT, FailOnScriptExecuteExecutor).run(
            args = arrayOf("test", "--artifact", "norole"),
            stdin = ByteArrayInputStream(ByteArray(0)),
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )
        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertTrue(result.stderr.contains("role=path"), result.stderr)
    }

    @Test
    fun nonHexSeedReportsUsageError() {
        val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI_SCRIPT, FailOnScriptExecuteExecutor).run(
            args = arrayOf("test", "--seed", "notahexstring!"),
            stdin = ByteArrayInputStream(ByteArray(0)),
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )
        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertTrue(result.stderr.contains("hex"), result.stderr)
    }

    @Test
    fun nonPositiveTestTimeoutReportsUsageError() {
        for (bad in listOf("0", "-1", "notanumber")) {
            val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI_SCRIPT, FailOnScriptExecuteExecutor).run(
                args = arrayOf("test", "--test-timeout", bad),
                stdin = ByteArrayInputStream(ByteArray(0)),
                env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
            )
            assertEquals(USAGE_EXIT_CODE, result.exitCode, "expected usage error for --test-timeout $bad: ${result.stderr}")
            assertTrue(result.stderr.contains("positive integer"), result.stderr)
        }
    }

    @Test
    fun reportFormatOtherThanJunitReportsUsageError() {
        val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI_SCRIPT, FailOnScriptExecuteExecutor).run(
            args = arrayOf("test", "--report", "yaml", "out.xml"),
            stdin = ByteArrayInputStream(ByteArray(0)),
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )
        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertTrue(result.stderr.contains("junit"), result.stderr)
    }

    @Test
    fun suiteFlagOnRunScriptReportsUsageError() {
        val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI_SCRIPT, FailOnScriptExecuteExecutor).run(
            args = arrayOf("run-script", "--seed", "aabbccdd", "--script-text", "print(1)", "--stdin"),
            stdin = ByteArrayInputStream(ByteArray(0)),
            env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
        )
        assertEquals(USAGE_EXIT_CODE, result.exitCode, result.stderr)
        assertTrue(result.stderr.contains("Unknown"), result.stderr)
        assertTrue(result.stderr.contains("--seed"), result.stderr)
    }

    @Test
    fun telemetryDoesNotConsumeStreamingResponseBody() {
        val home = temporaryFolder.newFolder("telemetry-home")
        withUserHome(home.absolutePath) {
            StreamingTelemetryServer.start().use { server ->
                val result = HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI_SCRIPT, FailOnScriptExecuteExecutor).run(
                    args = arrayOf("version"),
                    stdin = ByteArrayInputStream(ByteArray(0)),
                    env = mapOf(
                        "HEXANA_CLI_POSTHOG_HOST" to server.url,
                        "HEXANA_CLI_POSTHOG_KEY" to "test-key",
                    ),
                )

                assertEquals(SUCCESS_EXIT_CODE, result.exitCode, result.stderr)
                server.assertRequestRecorded()
                server.assertResponseClosed()
            }
        }
    }
}

private fun runStandardSummary(
    stdin: InputStream,
    executor: RecordingProductExecutor,
): ProductCliResult =
    HexanaProductCli(HexanaGeneratedCliProducts.HEXANA_CLI, executor).run(
        args = arrayOf("summarize-binary", "--format", "json"),
        stdin = stdin,
        env = mapOf("HEXANA_CLI_TELEMETRY_ENABLED" to "false"),
    )

private class RecordingProductExecutor(
    private val result: ProductCliResult = ProductCliResult(stdout = """{"format":"wasm"}"""),
    private val toolNames: List<String> = STANDARD_TOOL_NAMES,
) : HexanaProductToolExecutor {
    override val bindings: List<HexanaProductToolExecutorBinding> = toolNames.map { toolName ->
        HexanaProductToolExecutorBinding(toolName, formatsFor(toolName), ::record)
    }
    private val requests = mutableListOf<HexanaProductToolExecutionRequest>()

    private fun record(request: HexanaProductToolExecutionRequest): ProductCliResult {
        requests += request
        return result
    }

    fun singleRequest(): HexanaProductToolExecutionRequest {
        assertEquals(1, requests.size, "Expected exactly one product executor request")
        return requests.single()
    }
}

private object FailOnExecuteExecutor : HexanaProductToolExecutor {
    override val bindings: List<HexanaProductToolExecutorBinding> = STANDARD_TOOL_NAMES.map(::testBinding)
}

private object FailOnScriptExecuteExecutor : HexanaProductToolExecutor {
    override val bindings: List<HexanaProductToolExecutorBinding> = listOf(
        testBinding("run_binary_script"),
        testBinding("run_script_suite"),
    )
}

private val STANDARD_TOOL_NAMES = listOf("inspect_binary", "summarize_binary", "summarize_module", "wasm_to_wat")

private fun testBinding(toolName: String): HexanaProductToolExecutorBinding =
    HexanaProductToolExecutorBinding(toolName, formatsFor(toolName)) { request ->
        error("CLI product test must not execute ${request.spec.commandName}")
    }

private fun formatsFor(toolName: String) = HexanaGeneratedCliToolRegistry.TOOLS.single { it.toolName == toolName }.outputFormats

private fun HexanaProductToolExecutionRequest.assertStdinSource(bytes: ByteArray, slotName: String) {
    val source = sourceSelection as? ToolInputSourceSelection.StdinBytes
        ?: fail("Expected stdin source, got $sourceSelection")
    assertEquals(slotName, source.slotName)
    assertContentEquals(bytes, source.bytes())
}

private fun assertFutureStillRunning(future: Future<*>, timeoutMillis: Long) {
    try {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        fail("Expected CLI to keep waiting for stdin")
    } catch (_: TimeoutException) {
    }
}

private inline fun withUserHome(value: String, block: () -> Unit) {
    val original = System.getProperty("user.home")
    try {
        System.setProperty("user.home", value)
        block()
    } finally {
        if (original == null) {
            System.clearProperty("user.home")
        } else {
            System.setProperty("user.home", original)
        }
    }
}

private class StreamingTelemetryServer private constructor(
    private val server: HttpServer,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val requestRecorded = CountDownLatch(1)
    private val responseClosed = CountDownLatch(1)

    val url: String
        get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.recordAndStreamBody()
        }
        server.start()
    }

    fun assertRequestRecorded() {
        assertTrue(requestRecorded.await(1, TimeUnit.SECONDS), "Expected telemetry request to be recorded")
    }

    fun assertResponseClosed() {
        assertTrue(
            responseClosed.await(1, TimeUnit.SECONDS),
            "Expected telemetry client to close the streaming response without reading the body",
        )
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun HttpExchange.recordAndStreamBody() {
        requestBody.use { it.readBytes() }
        requestRecorded.countDown()
        sendResponseHeaders(200, 1_000_000_000L)
        val chunk = """{"status":"ok"}""".toByteArray(Charsets.UTF_8)
        try {
            while (true) {
                responseBody.write(chunk)
                responseBody.flush()
                Thread.sleep(25)
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            responseClosed.countDown()
            runCatching { responseBody.close() }
        }
    }

    companion object {
        fun start(): StreamingTelemetryServer =
            StreamingTelemetryServer(HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0))
    }
}

private class BlockingFirstReadInputStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    private val release = CountDownLatch(1)

    val readStarted = CountDownLatch(1)

    fun release() {
        release.countDown()
    }

    override fun available(): Int = 0

    override fun read(): Int {
        waitForRelease()
        return delegate.read()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        waitForRelease()
        return delegate.read(buffer, offset, length)
    }

    private fun waitForRelease() {
        readStarted.countDown()
        try {
            if (!release.await(1, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting to release stdin")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting to release stdin", error)
        }
    }
}

private class SlowChunkInputStream(
    private val bytes: ByteArray,
    private val firstChunkSize: Int,
) : InputStream() {
    private val releaseNextChunks = CountDownLatch(1)
    private var position = 0

    val firstChunkDelivered = CountDownLatch(1)
    val nextChunkReadStarted = CountDownLatch(1)

    init {
        require(firstChunkSize in 1..bytes.size)
    }

    fun releaseNextChunks() {
        releaseNextChunks.countDown()
    }

    override fun available(): Int = 0

    override fun read(): Int {
        val singleByte = ByteArray(1)
        val read = read(singleByte, 0, singleByte.size)
        return if (read < 0) -1 else singleByte[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) {
            return -1
        }
        if (position == 0) {
            val count = firstChunkSize.coerceAtMost(length)
            bytes.copyInto(buffer, destinationOffset = offset, startIndex = position, endIndex = position + count)
            position += count
            firstChunkDelivered.countDown()
            return count
        }

        nextChunkReadStarted.countDown()
        waitForNextChunks()
        val count = (bytes.size - position).coerceAtMost(length)
        bytes.copyInto(buffer, destinationOffset = offset, startIndex = position, endIndex = position + count)
        position += count
        return count
    }

    private fun waitForNextChunks() {
        try {
            if (!releaseNextChunks.await(1, TimeUnit.SECONDS)) {
                throw IOException("Timed out waiting to release stdin chunks")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting to release stdin chunks", error)
        }
    }
}

private object FailOnReadInputStream : InputStream() {
    override fun read(): Int =
        error("help must not read stdin")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        error("help must not read stdin")
}

private class ZeroAvailableInputStream(bytes: ByteArray) : InputStream() {
    private val delegate = ByteArrayInputStream(bytes)
    private var firstBulkRead = true

    override fun available(): Int = 0

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (firstBulkRead) {
            firstBulkRead = false
            Thread.sleep(10)
        }
        return delegate.read(buffer, offset, length)
    }
}

private val MINIMAL_WASM_MODULE = byteArrayOf(
    0x00, 0x61, 0x73, 0x6d,
    0x01, 0x00, 0x00, 0x00,
)
