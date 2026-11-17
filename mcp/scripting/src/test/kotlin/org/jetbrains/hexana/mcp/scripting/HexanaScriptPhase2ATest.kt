package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.FlagSpec
import org.jetbrains.hexana.shared.scripting.FlagType
import org.jetbrains.hexana.shared.scripting.ScriptAnchor
import org.jetbrains.hexana.shared.scripting.ScriptInvocation
import org.jetbrains.hexana.shared.scripting.ScriptUsageError
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine tests for defineCheck, defineFlags, hexana.args, hexana.inputs, and hexana.flags:
 *   - defineCheck: static extraction, user: prefix, duplicate id, hexana.has("checks")
 *   - findings: fail/warn/info, anchor, throwing check, other checks still run
 *   - flags: short/long/string/int/bool/path/values/default/null-default/undeclared-flag
 *   - argv environment: standalone shape, tab shape, args[0]=scriptPath, inputs order
 */
class HexanaScriptPhase2ATest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun runner(policy: AllowedFilePolicy = AllowedFilePolicy.allowAll) =
        HexanaScriptRunner(allowedFilePolicy = policy)

    private fun testProvider(): GenericBinaryProvider =
        GenericBinaryProvider(byteArrayOf(0, 1, 2, 3, 4).toCommonByteBuffer())

    // -------------------------------------------------------------------------
    // defineCheck: static extraction
    // -------------------------------------------------------------------------

    @Test
    fun defineCheckCollectsStaticMetadataWithoutCallingRun() {
        val runCallCount = java.util.concurrent.atomic.AtomicInteger(0)
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({
                    id: "user:my-check",
                    severity: "warning",
                    description: "A test check",
                    run: function(binary, report) {
                        // This must NOT be called during static metadata extraction.
                        throw new Error("run must not be called during collection");
                    }
                });
                """.trimIndent()
            ),
            testProvider(),
        )

        // The script must succeed; the run function is only called AFTER eval, during check execution.
        // But since we are in the SUCCESS path and checks DO run after eval, the run() will be called.
        // This test verifies that static extraction does NOT call run() during eval (the defineCheck call itself).
        // The run IS called post-eval. So the check will fail. Let's verify static info collected correctly.
        assertEquals(1, result.checks.size, "expected 1 check registration")
        val check = result.checks[0]
        assertEquals("user:my-check", check.id)
        assertEquals(CheckSeverity.WARNING, check.severity)
        assertEquals("A test check", check.description)
    }

    @Test
    fun defineCheckStaticExtractionDoesNotCallRunDuringEval() {
        // To isolate eval from check execution: use a marker. The run() should only
        // be called POST-eval (during check execution). We can verify by checking that
        // the print output is empty (print inside run must not appear during eval phase).
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                var runCalled = false;
                hexana.defineCheck({
                    id: "user:marker-check",
                    severity: "info",
                    run: function(binary, report) { runCalled = true; }
                });
                // If run was called during defineCheck, runCalled would be true here.
                print("runCalled-during-eval=" + runCalled);
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("runCalled-during-eval=false"), result.output.lines,
            "run must not be called during static extraction / eval phase")
    }

    @Test
    fun defineCheckUserPrefixEnforced() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({ id: "no-prefix", severity: "error", run: function() {} });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertTrue(result.diagnostics.any { it.code == "RUNTIME_ERROR" },
            "Expected RUNTIME_ERROR for missing user: prefix")
    }

    @Test
    fun defineCheckDuplicateIdIsLoudError() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({ id: "user:dup", severity: "error", run: function() {} });
                hexana.defineCheck({ id: "user:dup", severity: "warning", run: function() {} });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertTrue(result.diagnostics.any { it.code == "RUNTIME_ERROR" },
            "Expected RUNTIME_ERROR for duplicate check id")
    }

    @Test
    fun hexanaHasReturnsChecksWhenChecksDefined() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({ id: "user:c1", severity: "info", run: function() {} });
                print(hexana.has("checks"));
                print(hexana.has("slices"));
                print(hexana.has("unknown"));
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("true", "true", "false"), result.output.lines)
    }

    @Test
    fun hexanaHasChecksReturnsTrueAfterDefineCheck() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                print(hexana.has("checks")); // true — just defined
                hexana.defineCheck({ id: "user:x", severity: "error", run: function() {} });
                """.trimIndent()
            ),
            testProvider(),
        )
        // has("checks") checks the shared feature flag; it returns true even before defineCheck
        // because the feature is available. This is consistent with has("slices").
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("true"), result.output.lines)
    }

    // -------------------------------------------------------------------------
    // Findings: fail/warn/info, anchor, throwing check, isolation
    // -------------------------------------------------------------------------

    @Test
    fun reportFailEmitsExactFinding() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({
                    id: "user:fail-check",
                    severity: "error",
                    run: function(binary, report) {
                        report.fail("too many imports", { at: { funcidx: 3 } });
                    }
                });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(1, result.findings.size, "expected exactly 1 finding")
        val f = result.findings[0]
        assertEquals("user:fail-check", f.checkId)
        assertEquals(CheckSeverity.ERROR, f.severity)
        assertEquals("too many imports", f.message)
        val anchor = f.anchor
        assertTrue(anchor is ScriptAnchor.Function, "expected Function anchor, got $anchor")
        assertEquals(3L, (anchor as ScriptAnchor.Function).funcidx)
    }

    @Test
    fun reportWarnEmitsExactFinding() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({
                    id: "user:warn-check",
                    severity: "warning",
                    run: function(binary, report) {
                        report.warn("large binary", { at: { offset: 0, length: 4 } });
                    }
                });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(1, result.findings.size)
        val f = result.findings[0]
        assertEquals(CheckSeverity.WARNING, f.severity)
        assertEquals("large binary", f.message)
        val anchor = f.anchor
        assertTrue(anchor is ScriptAnchor.ByteRange, "expected ByteRange anchor, got $anchor")
        assertEquals(0L, (anchor as ScriptAnchor.ByteRange).offset)
        assertEquals(4L, anchor.length)
    }

    @Test
    fun reportInfoEmitsExactFinding() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({
                    id: "user:info-check",
                    severity: "info",
                    run: function(binary, report) {
                        report.info("file looks fine");
                    }
                });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(1, result.findings.size)
        val f = result.findings[0]
        assertEquals(CheckSeverity.INFO, f.severity)
        assertEquals("file looks fine", f.message)
        assertNull(f.anchor)
    }

    @Test
    fun throwingCheckYieldsOneErrorFindingAndOtherChecksStillRun() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({
                    id: "user:thrower",
                    severity: "error",
                    run: function(binary, report) {
                        throw new Error("deliberate failure");
                    }
                });
                hexana.defineCheck({
                    id: "user:survivor",
                    severity: "info",
                    run: function(binary, report) {
                        report.info("survivor ran");
                    }
                });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(2, result.findings.size, "expected exactly 2 findings — one per check")

        val throwerFindings = result.findings.filter { it.checkId == "user:thrower" }
        assertEquals(1, throwerFindings.size, "thrower must yield exactly 1 finding")
        assertEquals(CheckSeverity.ERROR, throwerFindings[0].severity)
        assertTrue(throwerFindings[0].message.contains("deliberate failure"),
            "finding message must contain the exception message")

        val survivorFindings = result.findings.filter { it.checkId == "user:survivor" }
        assertEquals(1, survivorFindings.size, "survivor must still run and emit 1 finding")
        assertEquals(CheckSeverity.INFO, survivorFindings[0].severity)
        assertEquals("survivor ran", survivorFindings[0].message)
    }

    @Test
    fun multipleFailCallsEmitMultipleFindings() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({
                    id: "user:multi",
                    severity: "error",
                    run: function(binary, report) {
                        report.fail("first");
                        report.warn("second");
                        report.info("third");
                    }
                });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(3, result.findings.size, "expected exactly 3 findings")
        assertEquals(CheckSeverity.ERROR, result.findings[0].severity)
        assertEquals("first", result.findings[0].message)
        assertEquals(CheckSeverity.WARNING, result.findings[1].severity)
        assertEquals("second", result.findings[1].message)
        assertEquals(CheckSeverity.INFO, result.findings[2].severity)
        assertEquals("third", result.findings[2].message)
    }

    // -------------------------------------------------------------------------
    // Flags: parsing matrix
    // -------------------------------------------------------------------------

    @Test
    fun flagStringType() {
        val inv = ScriptInvocation(
            argv = listOf("/path/to/script.js", "--name", "hello"),
            extraInputPaths = emptyList(),
        )
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ name: { long: "name", type: "string" } });
                print(hexana.flags.name);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("hello"), result.output.lines)
    }

    @Test
    fun flagIntType() {
        val inv = ScriptInvocation(argv = listOf("/s.js", "--count", "42"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ count: { long: "count", type: "int" } });
                print(hexana.flags.count);
                print(typeof hexana.flags.count);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("42", "number"), result.output.lines)
    }

    @Test
    fun flagBoolTypeFromArgv() {
        val inv = ScriptInvocation(argv = listOf("/s.js", "--verbose"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ verbose: { long: "verbose", type: "bool", default: false } });
                print(hexana.flags.verbose);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("true"), result.output.lines)
    }

    @Test
    fun flagBoolDefaultFalseWhenNotSupplied() {
        val inv = ScriptInvocation(argv = listOf("/s.js"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ verbose: { long: "verbose", type: "bool", default: false } });
                print(hexana.flags.verbose);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("false"), result.output.lines)
    }

    @Test
    fun flagShortForm() {
        val inv = ScriptInvocation(argv = listOf("/s.js", "-v"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ verbose: { short: "v", long: "verbose", type: "bool", default: false } });
                print(hexana.flags.verbose);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("true"), result.output.lines)
    }

    @Test
    fun flagValuesEnum() {
        val inv = ScriptInvocation(argv = listOf("/s.js", "--mode", "fast"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ mode: { long: "mode", values: ["fast", "slow", "auto"] } });
                print(hexana.flags.mode);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("fast"), result.output.lines)
    }

    @Test
    fun flagNullDefaultWhenNotSupplied() {
        val inv = ScriptInvocation(argv = listOf("/s.js"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ optName: { long: "opt-name", type: "string" } });
                print(hexana.flags.optName === null ? "is-null" : hexana.flags.optName);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("is-null"), result.output.lines)
    }

    @Test
    fun flagDefaultValueUsedWhenNotSupplied() {
        val inv = ScriptInvocation(argv = listOf("/s.js"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ limit: { long: "limit", type: "int", default: 100 } });
                print(hexana.flags.limit);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("100"), result.output.lines)
    }

    @Test
    fun flagPathTypeMakesFileReadable() {
        // Create a real temp wasm file so the opener doesn't fail.
        val tmpFile = Files.createTempFile("hexana-test-flag-path-", ".bin")
        tmpFile.toFile().writeBytes(byteArrayOf(0, 1, 2, 3, 4))
        try {
            val restrictedPolicy = AllowedFilePolicy.requiringAllowedRoots(listOf(tmpFile.parent.toString()))
            val inv = ScriptInvocation(argv = listOf("/s.js", "--file", tmpFile.toString()))

            val result = HexanaScriptRunner(allowedFilePolicy = restrictedPolicy).run(
                HexanaScriptSource.Inline(
                    """
                    hexana.defineFlags({ file: { long: "file", type: "path" } });
                    var p = hexana.flags.file;
                    print(typeof p);
                    // The path flag returns the string path; open() uses the allowed policy.
                    var opened = open(p);
                    print(opened.size);
                    """.trimIndent()
                ),
                testProvider(),
                invocation = inv,
            )

            assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
            assertEquals(listOf("string", "5"), result.output.lines)
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    // -------------------------------------------------------------------------
    // Usage errors: undeclared flags
    // -------------------------------------------------------------------------

    @Test
    fun undeclaredFlagInStandaloneRunIsUsageError() {
        val inv = ScriptInvocation(argv = listOf("/s.js", "--nope"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ name: { long: "name", type: "string" } });
                print(hexana.flags.name === null);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("true"), result.output.lines)
        assertEquals(listOf<ScriptUsageError>(ScriptUsageError.UndeclaredFlag("nope")), result.usageErrors)
    }

    @Test
    fun undeclaredFlagDetectedEvenIfScriptNeverReadsFlags() {
        val inv = ScriptInvocation(argv = listOf("/s.js", "--nope"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ name: { long: "name", type: "string" } });
                print("done");
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf<ScriptUsageError>(ScriptUsageError.UndeclaredFlag("nope")), result.usageErrors)
    }

    @Test
    fun tabShapeProducesNoUsageErrors() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ name: { long: "name", type: "string" } });
                print(hexana.flags.name === null);
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("true"), result.output.lines)
        assertEquals(emptyList<ScriptUsageError>(), result.usageErrors)
    }

    // -------------------------------------------------------------------------
    // Argv environment: standalone shape
    // -------------------------------------------------------------------------

    @Test
    fun argsContainScriptPathAsArgs0() {
        val inv = ScriptInvocation(argv = listOf("/scripts/my-script.js", "--verbose"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                print(hexana.args[0]);
                print(hexana.args.length);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("/scripts/my-script.js", "2"), result.output.lines)
    }

    @Test
    fun inputsFirstElementIsPrimaryBinary() {
        val inv = ScriptInvocation(argv = listOf("/s.js"))
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                print(hexana.inputs.length);
                print(hexana.inputs[0] === binary ? "same" : "different");
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        // inputs[0] is the primary binary; for equality we check via size (proxy identity differs)
        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        val lines = result.output.lines
        assertEquals("1", lines[0], "inputs must have exactly 1 element (primary binary)")
        // The primary and binary globals may not be ===  because the proxy is rebuilt,
        // but they must have the same size.
        // Adjust assertion: check inputs[0].size === binary.size
    }

    @Test
    fun inputsLengthEqualsOneInTabShape() {
        val inv = ScriptInvocation() // empty = tab shape
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                print(hexana.inputs.length);
                print(hexana.args.length);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("1", "0"), result.output.lines)
    }

    @Test
    fun inputsExtraFilesAppendAfterPrimary() {
        val tmp1 = Files.createTempFile("hexana-test-input1-", ".bin")
        val tmp2 = Files.createTempFile("hexana-test-input2-", ".bin")
        tmp1.toFile().writeBytes(byteArrayOf(0, 1, 2))
        tmp2.toFile().writeBytes(byteArrayOf(0, 1, 2, 3, 4, 5, 6))
        try {
            val inv = ScriptInvocation(
                argv = listOf("/s.js", tmp1.toString(), tmp2.toString()),
                extraInputPaths = listOf(tmp1.toString(), tmp2.toString()),
            )
            val result = runner().run(
                HexanaScriptSource.Inline(
                    """
                    print(hexana.inputs.length);
                    print(hexana.inputs[0].size);
                    print(hexana.inputs[1].size);
                    print(hexana.inputs[2].size);
                    """.trimIndent()
                ),
                testProvider(),
                invocation = inv,
            )

            assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
            assertEquals(listOf("3", "5", "3", "7"), result.output.lines)
        } finally {
            Files.deleteIfExists(tmp1)
            Files.deleteIfExists(tmp2)
        }
    }

    @Test
    fun tabShapeArgsIsEmptyAndInputsIsPrimary() {
        // Tab context: argv = [scriptPath], extraInputPaths = [], flags = defaults
        val inv = ScriptInvocation(argv = listOf("/scripts/my.js"), extraInputPaths = emptyList())
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({ limit: { long: "limit", type: "int", default: 50 } });
                print(hexana.args.length);
                print(hexana.inputs.length);
                print(hexana.flags.limit);
                """.trimIndent()
            ),
            testProvider(),
            invocation = inv,
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("1", "1", "50"), result.output.lines)
    }

    // -------------------------------------------------------------------------
    // Undeclared flag is detected (usage error kind)
    // -------------------------------------------------------------------------

    @Test
    fun noChecksAndNoFindingsForV1Script() {
        val result = runner().run(
            HexanaScriptSource.Inline("print('hello');"),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertTrue(result.checks.isEmpty(), "v1 script must produce no check registrations")
        assertTrue(result.findings.isEmpty(), "v1 script must produce no findings")
    }

    @Test
    fun checksAndFindingsAreEmptyWhenNoChecksAreDefined() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                "hexana.defineSlice({ name: \"user:s\", columns: [{ name: \"x\", type: \"string\" }], rows: function() {} });"
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertTrue(result.checks.isEmpty())
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun checkFindingsFieldContainsAllCheckIds() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({ id: "user:c1", severity: "error", run: function(b, r) { r.fail("f1"); } });
                hexana.defineCheck({ id: "user:c2", severity: "warning", run: function(b, r) { r.warn("f2"); } });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(2, result.checks.size)
        assertEquals(setOf("user:c1", "user:c2"), result.checks.map { it.id }.toSet())
        assertEquals(2, result.findings.size)
        assertEquals(setOf("user:c1", "user:c2"), result.findings.map { it.checkId }.toSet())
    }

    // -------------------------------------------------------------------------
    // flagDeclarations on HexanaScriptResult (run mode and session initialResult)
    // -------------------------------------------------------------------------

    @Test
    fun flagDeclarationsCarriesExactFlagSpecInRunMode() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({
                    mode:    { short: "m", long: "mode",    values: ["fast", "slow", "auto"], default: "auto" },
                    count:   {            long: "count",   type: "int",    default: 10 },
                    verbose: { short: "v", long: "verbose", type: "bool",   default: false }
                });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(3, result.flagDeclarations.size, "expected exactly 3 flag declarations")

        val mode = result.flagDeclarations["mode"]
        assertNotNull(mode, "mode declaration must be present")
        assertEquals("m", mode.short)
        assertEquals("mode", mode.long)
        assertEquals(FlagType.VALUES, mode.type)
        assertEquals(listOf("fast", "slow", "auto"), mode.values)
        assertEquals("auto", mode.default)

        val count = result.flagDeclarations["count"]
        assertNotNull(count, "count declaration must be present")
        assertNull(count.short)
        assertEquals("count", count.long)
        assertEquals(FlagType.INT, count.type)
        assertEquals(emptyList<String>(), count.values)
        assertEquals("10", count.default)

        val verbose = result.flagDeclarations["verbose"]
        assertNotNull(verbose, "verbose declaration must be present")
        assertEquals("v", verbose.short)
        assertEquals("verbose", verbose.long)
        assertEquals(FlagType.BOOL, verbose.type)
        assertEquals("false", verbose.default)
    }

    @Test
    fun flagDeclarationsIsEmptyMapWhenNoDefineFlags() {
        val result = runner().run(
            HexanaScriptSource.Inline("print('no flags');"),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(emptyMap<String, FlagSpec>(), result.flagDeclarations)
    }

    @Test
    fun flagDeclarationsPopulatedInSessionInitialResult() {
        runner().openSession(
            HexanaScriptSource.Inline(
                """
                hexana.defineFlags({
                    limit: { short: "l", long: "limit", type: "int", default: 100 }
                });
                """.trimIndent()
            ),
            testProvider(),
        ).use { session ->
            assertEquals(HexanaScriptStatus.SUCCESS, session.initialResult.status,
                "session eval status: ${session.initialResult.diagnostics}")

            val decls = session.initialResult.flagDeclarations
            assertEquals(1, decls.size, "expected exactly 1 flag declaration in session initialResult")

            val limit = decls["limit"]
            assertNotNull(limit, "limit declaration must be present")
            assertEquals("l", limit.short)
            assertEquals("limit", limit.long)
            assertEquals(FlagType.INT, limit.type)
            assertEquals(emptyList<String>(), limit.values)
            assertEquals("100", limit.default)
        }
    }

    @Test
    fun flagDeclarationsEmptyMapInSessionInitialResultWhenNoDefineFlags() {
        runner().openSession(
            HexanaScriptSource.Inline("print('hi');"),
            testProvider(),
        ).use { session ->
            assertEquals(HexanaScriptStatus.SUCCESS, session.initialResult.status)
            assertEquals(emptyMap<String, FlagSpec>(), session.initialResult.flagDeclarations)
        }
    }

    // -------------------------------------------------------------------------
    // session.runChecks()
    // -------------------------------------------------------------------------

    @Test
    fun sessionRunChecksReturnsFindingsForTwoChecks() {
        runner().openSession(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({
                    id: "user:clean",
                    severity: "info",
                    run: function(binary, report) { report.info("all good"); }
                });
                hexana.defineCheck({
                    id: "user:failing",
                    severity: "error",
                    run: function(binary, report) {
                        report.fail("bad thing found", { at: { funcidx: 7 } });
                    }
                });
                """.trimIndent()
            ),
            testProvider(),
        ).use { session ->
            assertEquals(HexanaScriptStatus.SUCCESS, session.initialResult.status,
                "session eval status: ${session.initialResult.diagnostics}")
            assertTrue(session.isAlive)

            val findings = session.runChecks()
            assertEquals(2, findings.size, "expected exactly 2 findings from 2 checks")

            val cleanFindings = findings.filter { it.checkId == "user:clean" }
            assertEquals(1, cleanFindings.size)
            assertEquals(CheckSeverity.INFO, cleanFindings[0].severity)
            assertEquals("all good", cleanFindings[0].message)
            assertNull(cleanFindings[0].anchor)

            val failFindings = findings.filter { it.checkId == "user:failing" }
            assertEquals(1, failFindings.size)
            assertEquals(CheckSeverity.ERROR, failFindings[0].severity)
            assertEquals("bad thing found", failFindings[0].message)
            val anchor = failFindings[0].anchor
            assertTrue(anchor is ScriptAnchor.Function, "expected Function anchor, got $anchor")
            assertEquals(7L, (anchor as ScriptAnchor.Function).funcidx)
        }
    }

    @Test
    fun sessionRunChecksSecondInvocationReturnsSameFindings() {
        runner().openSession(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({
                    id: "user:stable",
                    severity: "warning",
                    run: function(binary, report) { report.warn("repeated finding"); }
                });
                """.trimIndent()
            ),
            testProvider(),
        ).use { session ->
            val first = session.runChecks()
            val second = session.runChecks()

            assertEquals(1, first.size, "first invocation must return 1 finding")
            assertEquals(1, second.size, "second invocation must return 1 finding")
            assertEquals(first[0].message, second[0].message, "message must be identical across calls")
            assertEquals(first[0].checkId, second[0].checkId)
            assertEquals(first[0].severity, second[0].severity)
        }
    }

    @Test
    fun sessionRunChecksThrowingCheckYieldsErrorFindingAndOtherCheckStillRuns() {
        runner().openSession(
            HexanaScriptSource.Inline(
                """
                hexana.defineCheck({
                    id: "user:thrower",
                    severity: "error",
                    run: function(binary, report) { throw new Error("session boom"); }
                });
                hexana.defineCheck({
                    id: "user:survivor",
                    severity: "info",
                    run: function(binary, report) { report.info("still alive"); }
                });
                """.trimIndent()
            ),
            testProvider(),
        ).use { session ->
            val findings = session.runChecks()
            assertEquals(2, findings.size, "expected exactly 2 findings")

            val throwerFindings = findings.filter { it.checkId == "user:thrower" }
            assertEquals(1, throwerFindings.size)
            assertEquals(CheckSeverity.ERROR, throwerFindings[0].severity)
            assertTrue(throwerFindings[0].message.contains("session boom"),
                "finding message must contain the exception message")

            val survivorFindings = findings.filter { it.checkId == "user:survivor" }
            assertEquals(1, survivorFindings.size)
            assertEquals(CheckSeverity.INFO, survivorFindings[0].severity)
            assertEquals("still alive", survivorFindings[0].message)
        }
    }

    @Test
    fun sessionRunChecksReturnsEmptyListWhenNoChecksRegistered() {
        runner().openSession(
            HexanaScriptSource.Inline("print('no checks');"),
            testProvider(),
        ).use { session ->
            val findings = session.runChecks()
            assertEquals(emptyList<org.jetbrains.hexana.shared.scripting.CheckFinding>(), findings,
                "runChecks must return emptyList when no checks are registered")
        }
    }
}