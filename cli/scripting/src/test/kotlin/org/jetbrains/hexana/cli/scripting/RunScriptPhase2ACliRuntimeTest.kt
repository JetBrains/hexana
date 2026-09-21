package org.jetbrains.hexana.cli.scripting

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.hexana.cli.runtime.ToolInputSourceSelection
import org.jetbrains.hexana.cli.runtime.ToolOutputFormat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CLI-level tests for scripting v2 extended features:
 * multi-input binaries, exit codes (0/1/2), baseline store, SARIF output,
 * --sarif-out side-file, and console stderr routing.
 */
class RunScriptPhase2ACliRuntimeTest {
    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private val smallBinary = byteArrayOf(1, 2, 3, 4)

    // ---------------------------------------------------------------------------
    // Multi-input
    // ---------------------------------------------------------------------------

    @Test
    fun extraInputPathIsAccessibleAsHexanaInputsIndex1() {
        val binary = tmp.newFile("primary.bin").also { it.writeBytes(smallBinary) }.toPath()
        val extra = tmp.newFile("secondary.bin").also { it.writeBytes(byteArrayOf(10, 20)) }.toPath()
        val script = tmp.newFile("inputs.js").also {
            it.writeText("print(hexana.inputs.length); print(hexana.inputs[1].bytes.size);")
        }.toPath()

        val out = runScriptCli(
            request(binary, script, extraInputPaths = listOf(extra.toString())),
        )

        assertEquals("2\n2", out.stdout.trim())
        assertEquals(0, out.exitCode)
        assertEquals("", out.stderr)
    }

    // ---------------------------------------------------------------------------
    // Script argv (hexana.args)
    // ---------------------------------------------------------------------------

    @Test
    fun scriptArgsAreAccessibleAsHexanaArgs() {
        val binary = tmp.newFile("args-binary.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("args.js").also {
            // args[0] = script path; args[1] = first user token
            it.writeText("print(hexana.args.length); print(hexana.args[1]);")
        }.toPath()

        val out = runScriptCli(
            request(binary, script, scriptArgs = listOf("hello", "world")),
        )

        // argv = [scriptPath, "hello", "world"]; length=3, args[1]="hello"
        assertTrue(out.stdout.contains("3"), out.stdout)
        assertTrue(out.stdout.contains("hello"), out.stdout)
        assertEquals(0, out.exitCode)
    }

    // ---------------------------------------------------------------------------
    // Console routing: always to stderr, never stdout
    // ---------------------------------------------------------------------------

    @Test
    fun consoleLogGoesToStderrInRawMode() {
        val binary = tmp.newFile("con-raw.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("con.js").also {
            it.writeText("console.log('logmsg'); print('output');")
        }.toPath()

        val out = runScriptCli(request(binary, script, format = ToolOutputFormat.RAW))

        assertEquals("output", out.stdout.trim())
        assertTrue(out.stderr.contains("[log] logmsg"), out.stderr)
        assertEquals(0, out.exitCode)
    }

    @Test
    fun consoleLogGoesToStderrInJsonMode() {
        val binary = tmp.newFile("con-json.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("con-json.js").also {
            it.writeText("console.error('errmsg'); print('out');")
        }.toPath()

        val out = runScriptCli(request(binary, script, format = ToolOutputFormat.JSON))

        // JSON stdout must be parseable and must NOT contain the console message
        val parsed = Json.parseToJsonElement(out.stdout).jsonObject
        assertEquals("SUCCESS", parsed.getValue("status").jsonPrimitive.content)
        assertFalse(out.stdout.contains("errmsg"), "console message must not appear in JSON stdout")
        assertTrue(out.stderr.contains("[error] errmsg"), out.stderr)
        assertEquals(0, out.exitCode)
    }

    @Test
    fun consoleSuffixVariantsHaveCorrectLevelPrefixes() {
        val binary = tmp.newFile("con-levels.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("levels.js").also {
            it.writeText("console.log('L'); console.error('E'); console.debug('D');")
        }.toPath()

        val out = runScriptCli(request(binary, script, format = ToolOutputFormat.RAW))

        assertTrue(out.stderr.contains("[log] L"), out.stderr)
        assertTrue(out.stderr.contains("[error] E"), out.stderr)
        assertTrue(out.stderr.contains("[debug] D"), out.stderr)
    }

    // ---------------------------------------------------------------------------
    // SARIF stdout format: byte-clean (no console in it)
    // ---------------------------------------------------------------------------

    @Test
    fun sarifFormatProducesValidJsonAndIsStdoutByteClean() {
        val binary = tmp.newFile("sarif-binary.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("sarif.js").also {
            it.writeText("console.log('console-line'); print('raw-line');")
        }.toPath()

        val out = runScriptCli(request(binary, script, format = ToolOutputFormat.SARIF))

        // stdout must be parseable SARIF JSON
        val sarif = Json.parseToJsonElement(out.stdout).jsonObject
        assertEquals("2.1.0", sarif.getValue("version").jsonPrimitive.content)
        // console message must not leak into stdout
        assertFalse(out.stdout.contains("console-line"), "console must not appear in SARIF stdout")
        // console message must appear in stderr
        assertTrue(out.stderr.contains("[log] console-line"), out.stderr)
        assertEquals(0, out.exitCode)
    }

    @Test
    fun jsonFormatStdoutIsByteClean() {
        val binary = tmp.newFile("clean-json.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("json-clean.js").also {
            it.writeText("console.log('noise'); print('data');")
        }.toPath()

        val out = runScriptCli(request(binary, script, format = ToolOutputFormat.JSON))

        // JSON stdout must parse cleanly
        val parsed = Json.parseToJsonElement(out.stdout).jsonObject
        assertTrue(parsed.getValue("success").jsonPrimitive.boolean)
        assertFalse(out.stdout.contains("noise"), "console noise must not appear in JSON stdout")
    }

    // ---------------------------------------------------------------------------
    // --sarif-out side file
    // ---------------------------------------------------------------------------

    @Test
    fun sarifOutWritesSideFileAlongsideRawOutput() {
        val binary = tmp.newFile("side-binary.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("side.js").also {
            it.writeText("print('ok');")
        }.toPath()
        val sarifFile = tmp.root.toPath().resolve("output.sarif")

        val out = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.RAW,
                sarifOut = sarifFile,
                baselinesDir = tmp.root.toPath().resolve("baselines"),
            )
        )

        assertEquals("ok", out.stdout.trim())
        assertEquals(0, out.exitCode)
        assertTrue(Files.exists(sarifFile), "SARIF side-file must exist at $sarifFile")
        val sarifContent = sarifFile.readText()
        val sarif = Json.parseToJsonElement(sarifContent).jsonObject
        assertEquals("2.1.0", sarif.getValue("version").jsonPrimitive.content)
    }

    @Test
    fun sarifOutWritesSideFileAlongsideJsonOutput() {
        val binary = tmp.newFile("side-json-binary.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("side-json.js").also {
            it.writeText("print('ok');")
        }.toPath()
        val sarifFile = tmp.root.toPath().resolve("output-json.sarif")

        val out = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.JSON,
                sarifOut = sarifFile,
                baselinesDir = tmp.root.toPath().resolve("baselines2"),
            )
        )

        // JSON stdout must still parse
        Json.parseToJsonElement(out.stdout)
        assertEquals(0, out.exitCode)
        assertTrue(Files.exists(sarifFile), "SARIF side-file must exist for JSON format too")
    }

    // ---------------------------------------------------------------------------
    // Exit codes: 0 / 1 (new ERROR finding) / 2 (usage error)
    // ---------------------------------------------------------------------------

    @Test
    fun exitCode0WhenNoErrorFindings() {
        val binary = tmp.newFile("exit0.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("exit0.js").also {
            it.writeText("hexana.defineCheck({ id: 'user:info-only', severity: 'info', run: function(b, r) {} }); print('ok');")
        }.toPath()

        val out = runScriptCli(request(binary, script, baselinesDir = tmp.root.toPath().resolve("baselines-exit0")))

        assertEquals(0, out.exitCode)
    }

    @Test
    fun exitCode1WhenNewErrorFinding() {
        val binary = tmp.newFile("exit1.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("exit1.js").also {
            it.writeText(
                """
                hexana.defineCheck({ id: 'user:my-err', severity: 'error', run: function(b, r) { r.fail('bad thing'); } });
                """.trimIndent()
            )
        }.toPath()

        val out = runScriptCli(request(binary, script, baselinesDir = tmp.root.toPath().resolve("baselines-exit1")))

        assertEquals(1, out.exitCode)
    }

    @Test
    fun exitCode0WhenErrorFindingIsBaselined() {
        val binary = tmp.newFile("exit0-baseline.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("exit0-baseline.js").also {
            it.writeText(
                """
                hexana.defineCheck({ id: 'user:my-err', severity: 'error', run: function(b, r) { r.fail('baselined thing'); } });
                """.trimIndent()
            )
        }.toPath()
        val baselinesDir = tmp.root.toPath().resolve("baselines-err-baseline")

        // First run with --update-baselines: baseline the finding, exit 0
        val updateRun = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.RAW,
                baselinesDir = baselinesDir,
                updateBaselines = true,
            )
        )
        assertEquals(0, updateRun.exitCode, "update-baselines run must exit 0: ${updateRun.stderr}")

        // Second run without --update-baselines: finding is UNCHANGED, exit 0
        val checkRun = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.RAW,
                baselinesDir = baselinesDir,
            )
        )
        assertEquals(0, checkRun.exitCode, "UNCHANGED finding must exit 0: ${checkRun.stderr}")
    }

    @Test
    fun updateBaselinesExits0AndWritesBaselineFiles() {
        val binary = tmp.newFile("update-bin.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("update.js").also {
            it.writeText(
                """
                hexana.defineCheck({ id: 'user:my-check', severity: 'warning', run: function(b, r) { r.warn('first finding'); } });
                """.trimIndent()
            )
        }.toPath()
        val baselinesDir = tmp.root.toPath().resolve("baselines-update")

        val out = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.RAW,
                baselinesDir = baselinesDir,
                updateBaselines = true,
            )
        )

        assertEquals(0, out.exitCode, out.stderr)
        val baselineFile = baselinesDir.resolve("user:my-check.json")
        assertTrue(Files.exists(baselineFile), "Baseline file must exist after --update-baselines")
        val content = baselineFile.readText()
        assertTrue(content.contains("fingerprints"), content)
    }

    @Test
    fun exitCode2WhenScriptHasUsageError() {
        val binary = tmp.newFile("usage-err.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("usage-err.js").also {
            it.writeText(
                """
                hexana.defineFlags({ known: { long: '--known', type: 'string', default: '' } });
                """.trimIndent()
            )
        }.toPath()
        // Pass --undeclared-key as a script arg so it is in argv but not declared
        val out = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.RAW,
                scriptArgs = listOf("--undeclared-key", "value"),
                baselinesDir = tmp.root.toPath().resolve("baselines-usage"),
            )
        )

        assertEquals(2, out.exitCode, "Usage error must exit 2: stderr=${out.stderr}")
        assertTrue(out.stderr.contains("undeclared-key"), "stderr must name the undeclared flag: stderr=${out.stderr}")
    }

    // ---------------------------------------------------------------------------
    // BaselineState round-trip: NEW -> UNCHANGED in SARIF output
    // ---------------------------------------------------------------------------

    @Test
    fun baselineStateRoundTripNewThenUnchangedInSarif() {
        val binary = tmp.newFile("rt-binary.bin").also { it.writeBytes(smallBinary) }.toPath()
        val script = tmp.newFile("rt.js").also {
            it.writeText(
                """
                hexana.defineCheck({ id: 'user:rt-check', severity: 'warning', run: function(b, r) { r.warn('rt finding'); } });
                """.trimIndent()
            )
        }.toPath()
        val baselinesDir = tmp.root.toPath().resolve("baselines-rt")

        // First run: NEW baselineState in SARIF
        val first = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.SARIF,
                baselinesDir = baselinesDir,
            )
        )
        val firstSarif = Json.parseToJsonElement(first.stdout).jsonObject
        val firstResults = firstSarif.getValue("runs").jsonArray[0].jsonObject.getValue("results").jsonArray
        assertEquals("new", firstResults[0].jsonObject.getValue("baselineState").jsonPrimitive.content)
        // WARNING severity NEW finding does not trigger exit 1 (only ERROR does)
        assertEquals(0, first.exitCode, "NEW WARNING finding must exit 0")

        // Baseline the finding
        runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.SARIF,
                baselinesDir = baselinesDir,
                updateBaselines = true,
            )
        )

        // Second run: UNCHANGED baselineState in SARIF
        val second = runScriptCli(
            RunScriptCliRequest(
                sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
                params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
                format = ToolOutputFormat.SARIF,
                baselinesDir = baselinesDir,
            )
        )
        val secondSarif = Json.parseToJsonElement(second.stdout).jsonObject
        val secondResults = secondSarif.getValue("runs").jsonArray[0].jsonObject.getValue("results").jsonArray
        assertEquals("unchanged", secondResults[0].jsonObject.getValue("baselineState").jsonPrimitive.content)
        assertEquals(0, second.exitCode, "UNCHANGED WARNING finding must exit 0")
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun request(
        binary: Path,
        script: Path,
        format: ToolOutputFormat = ToolOutputFormat.RAW,
        scriptArgs: List<String> = emptyList(),
        extraInputPaths: List<String> = emptyList(),
        baselinesDir: Path = tmp.root.toPath().resolve("baselines-default"),
    ) = RunScriptCliRequest(
        sourceSelections = listOf(ToolInputSourceSelection.Path(binary.toString(), "binary")),
        params = mapOf("scriptPath" to JsonPrimitive(script.toString())),
        format = format,
        scriptArgs = scriptArgs,
        extraInputPaths = extraInputPaths,
        baselinesDir = baselinesDir,
    )
}
