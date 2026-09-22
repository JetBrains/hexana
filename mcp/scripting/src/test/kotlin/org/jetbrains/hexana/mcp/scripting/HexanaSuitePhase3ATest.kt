package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.shared.scripting.SuiteNodeKind
import org.jetbrains.hexana.shared.scripting.SuiteNodeResult
import org.jetbrains.hexana.shared.scripting.SuiteNodeStatus
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HexanaSuitePhase3ATest {

    // Gradle sets the test working directory to the module directory (mcp/scripting/).
    private val appDebugWasm: Path =
        Path.of("../../examples/scripting-playground/bin/app-debug.wasm").toAbsolutePath().normalize()

    private val fixedSeed = "00112233445566778899aabbccddeeff"

    private fun runner() = HexanaSuiteRunner(
        limits = HexanaScriptLimits.interactive(),
        allowedFilePolicy = AllowedFilePolicy.allowAll,
    )

    private fun writeSuite(text: String, dir: Path = Files.createTempDirectory("hexana-suite-test")): Path {
        val file = dir.resolve("suite.test.mjs")
        Files.writeString(file, text)
        return file
    }

    private fun consoleTexts(result: HexanaScriptResult): List<String> = result.console.map { it.text }

    // ----- Registration tree + hook order -----

    @Test
    fun registrationTreeAndHookOrder() {
        val suite = writeSuite(
            """
            beforeEach(() => { console.log("beforeEach:outer"); });
            afterEach(() => { console.log("afterEach:outer"); });
            describe("outer", () => {
              beforeEach(() => { console.log("beforeEach:inner"); });
              afterEach(() => { console.log("afterEach:inner"); });
              test("t1", () => { console.log("body:t1"); });
              describe("deep", () => {
                test("t2", () => { console.log("body:t2"); });
              });
            });
            test("top", () => { console.log("body:top"); });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString())

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val root = assertNotNull(result.suite)
        assertEquals(SuiteNodeKind.SUITE, root.kind)
        assertEquals(SuiteNodeStatus.PASS, root.status)
        assertEquals("suite.test.mjs", root.name)
        assertEquals(listOf("outer", "top"), root.children.map { it.name })
        val outer = root.children[0]
        assertEquals(SuiteNodeKind.SUITE, outer.kind)
        assertEquals(listOf("t1", "deep"), outer.children.map { it.name })
        assertEquals(SuiteNodeKind.TEST, outer.children[0].kind)
        assertEquals(SuiteNodeKind.SUITE, outer.children[1].kind)
        assertEquals(listOf("t2"), outer.children[1].children.map { it.name })
        assertEquals(SuiteNodeKind.TEST, root.children[1].kind)

        // Outer beforeEach first, inner afterEach first — pinned as the exact
        // console sequence over all three tests in registration order.
        assertEquals(
            listOf(
                "beforeEach:outer", "beforeEach:inner", "body:t1", "afterEach:inner", "afterEach:outer",
                "beforeEach:outer", "beforeEach:inner", "body:t2", "afterEach:inner", "afterEach:outer",
                "beforeEach:outer", "body:top", "afterEach:outer",
            ),
            consoleTexts(result),
        )
    }

    @Test
    fun describeCollectionErrorFailsTheWholeRun() {
        val suite = writeSuite(
            """
            describe("broken", () => { throw new Error("collect boom"); });
            test("never", () => { console.log("SHOULD_NOT_RUN"); });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertNull(result.suite)
        assertTrue(result.diagnostics.any { it.message.contains("collect boom") },
            "diagnostic must carry the collection error: ${result.diagnostics}")
        assertFalse(consoleTexts(result).contains("SHOULD_NOT_RUN"))
    }

    // ----- Async execution, drain, per-test timeout -----

    @Test
    fun asyncTestWithTimerAndMicrotaskPasses() {
        val suite = writeSuite(
            """
            test("async", async () => {
              let value = 0;
              await new Promise((resolve) => setTimeout(() => { value = 1; resolve(); }, 0));
              await new Promise((resolve) => queueMicrotask(resolve));
              if (value !== 1) throw new Error("timer did not run, value=" + value);
              console.log("async-done");
            });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val test = assertNotNull(result.suite).children.single()
        assertEquals(SuiteNodeStatus.PASS, test.status)
        assertEquals(listOf("async-done"), consoleTexts(result))
    }

    @Test
    fun neverResolvingTestTimesOutAndSubsequentTestsStillRun() {
        val suite = writeSuite(
            """
            test("hangs", { timeout: 200 }, () => new Promise(() => {}));
            test("after", () => { console.log("after-ran"); });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val root = assertNotNull(result.suite)
        assertEquals(SuiteNodeStatus.FAIL, root.status)

        val hangs = root.children[0]
        assertEquals(SuiteNodeStatus.FAIL, hangs.status)
        assertEquals("Test timed out after 200 ms", hangs.failureMessage)

        val after = root.children[1]
        assertEquals(SuiteNodeStatus.PASS, after.status)
        assertEquals(listOf("after-ran"), consoleTexts(result))
    }

    @Test
    fun perTestDeadlineArmsAndFiresForTimerResolvedBeyondDeadline() {
        // The test body creates a promise that resolves via setTimeout well past the
        // per-test deadline. The drain must give up and report the timeout message.
        val suite = writeSuite(
            """
            test("slow", { timeout: 200 }, () => new Promise((resolve) => setTimeout(resolve, 10000)));
            test("sibling", () => { console.log("sibling-ran"); });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val root = assertNotNull(result.suite)
        assertEquals(SuiteNodeStatus.FAIL, root.status)
        val slow = root.children[0]
        assertEquals(SuiteNodeStatus.FAIL, slow.status)
        assertTrue(slow.failureMessage!!.contains("200"), "timeout message must cite the deadline: ${slow.failureMessage}")
        // Subsequent test still runs.
        assertEquals(SuiteNodeStatus.PASS, root.children[1].status)
        assertEquals(listOf("sibling-ran"), consoleTexts(result))
    }

    @Test
    fun wholeRunTimeoutYieldsStatusTimeoutViaCancelledContext() {
        // A global HexanaScriptLimits timeout (300 ms) shorter than the setTimeout delay (2 s,
        // within the 5 s per-test deadline so the drain loop spins) keeps the drain loop
        // spinning with Thread.sleep(1) until the executor's future.get() deadline fires,
        // cancelling the context; the result must carry TIMEOUT status.
        val runner = HexanaSuiteRunner(
            limits = HexanaScriptLimits(timeoutMillis = 300),
            allowedFilePolicy = AllowedFilePolicy.allowAll,
        )
        val suite = writeSuite(
            """
            test("hang", () => new Promise((resolve) => setTimeout(resolve, 2000)));
            """.trimIndent()
        )
        val result = runner.runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.TIMEOUT, result.status)
    }

    @Test
    fun failingAndRejectedTestsReportFailuresAndAfterEachStillRuns() {
        val suite = writeSuite(
            """
            afterEach(() => { console.log("cleanup"); });
            test("throws", () => { throw new Error("sync boom"); });
            test("rejects", async () => { throw new Error("async boom"); });
            test("fine", () => {});
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val root = assertNotNull(result.suite)
        val (throws, rejects, fine) = root.children
        assertEquals(SuiteNodeStatus.FAIL, throws.status)
        assertTrue(throws.failureMessage!!.contains("sync boom"))
        assertEquals(SuiteNodeStatus.FAIL, rejects.status)
        assertTrue(rejects.failureMessage!!.contains("async boom"))
        assertEquals(SuiteNodeStatus.PASS, fine.status)
        // afterEach ran for every test, including the failed ones.
        assertEquals(listOf("cleanup", "cleanup", "cleanup"), consoleTexts(result))
    }

    // ----- Artifacts -----

    @Test
    fun requiredUnmappedArtifactFailsFastBeforeAnyTest() {
        val suite = writeSuite(
            """
            const primary = hexana.artifact("primary");
            test("never", () => { console.log("SHOULD_NOT_RUN"); });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString(), artifacts = emptyMap())
        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertNull(result.suite)
        val message = result.diagnostics.joinToString { it.message }
        assertTrue(message.contains("\"primary\""), "message must name the role: $message")
        assertTrue(message.contains("--artifact primary=<path>"), "message must show the syntax: $message")
        assertFalse(consoleTexts(result).contains("SHOULD_NOT_RUN"))
    }

    @Test
    fun artifactFormatFilterRejectsMismatchedFilePreRun() {
        val suite = writeSuite(
            """
            const primary = hexana.artifact("primary", { formats: ["elf"] });
            test("never", () => { console.log("SHOULD_NOT_RUN"); });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString(), artifacts = mapOf("primary" to appDebugWasm.toString()))
        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertNull(result.suite)
        val message = result.diagnostics.joinToString { it.message }
        assertTrue(message.contains("\"primary\"") && message.contains("\"wasm\"") && message.contains("elf"),
            "message must name role, actual format, and expected formats: $message")
        assertFalse(consoleTexts(result).contains("SHOULD_NOT_RUN"))
    }

    @Test
    fun optionalUnmappedArtifactResolvesToNull() {
        val suite = writeSuite(
            """
            const extra = hexana.artifact("extra", { optional: true });
            test("optional-null", () => {
              if (extra !== null) throw new Error("expected null, got " + extra);
            });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(SuiteNodeStatus.PASS, assertNotNull(result.suite).children.single().status)
    }

    @Test
    fun artifactHandleReadsRealBytes() {
        val suite = writeSuite(
            """
            const app = hexana.artifact("app", { formats: ["wasm"] });
            test("bytes", () => {
              if (app.size !== 4703) throw new Error("size " + app.size);
              if (app.bytes.at(0) !== 0) throw new Error("magic[0] " + app.bytes.at(0));
              if (app.bytes.ascii(1, 3) !== "asm") throw new Error("magic " + app.bytes.ascii(1, 3));
              if (app.name !== "app-debug.wasm") throw new Error("name " + app.name);
            });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString(), artifacts = mapOf("app" to appDebugWasm.toString()))
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val test = assertNotNull(result.suite).children.single()
        assertEquals(SuiteNodeStatus.PASS, test.status)
    }

    // ----- test.skip -----

    @Test
    fun skippedTestIsNotExecutedAndSiblingsStillRun() {
        val suite = writeSuite(
            """
            test.skip("skipped", () => { throw new Error("MUST_NOT_RUN"); });
            test("runs", () => { console.log("ran"); });
            test.skip("also-skipped", { timeout: 100 }, () => { throw new Error("MUST_NOT_RUN_2"); });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val root = assertNotNull(result.suite)
        assertEquals(SuiteNodeStatus.PASS, root.status)

        val (skipped1, runs, skipped2) = root.children
        assertEquals(SuiteNodeStatus.SKIP, skipped1.status)
        assertEquals(SuiteNodeStatus.PASS, runs.status)
        assertEquals(SuiteNodeStatus.SKIP, skipped2.status)

        // The body of skipped tests must never execute.
        assertEquals(listOf("ran"), consoleTexts(result))
    }

    @Test
    fun artifactOutsideAllowedRootsIsRejectedByPolicy() {
        // A restrictive policy that allows only a single temp directory must reject
        // an artifact whose path lies outside it, even when the path is a real file.
        // The suite file itself must be inside the allowed dir so the runner can read it;
        // only the artifact path (appDebugWasm) lies outside, triggering the policy check.
        val allowedDir = Files.createTempDirectory("hexana-suite-allowed")
        val restrictivePolicy = AllowedFilePolicy.requiringAllowedRoots(listOf(allowedDir.toString()))

        val suite = writeSuite(
            """
            const app = hexana.artifact("app");
            test("never", () => { console.log("SHOULD_NOT_RUN"); });
            """.trimIndent(),
            dir = allowedDir,
        )
        val result = HexanaSuiteRunner(
            limits = HexanaScriptLimits.interactive(),
            allowedFilePolicy = restrictivePolicy,
        ).runSuite(suite.toString(), artifacts = mapOf("app" to appDebugWasm.toString()))

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertNull(result.suite)
        val message = result.diagnostics.joinToString { it.message }
        assertTrue(message.isNotBlank(), "policy rejection must produce a diagnostic: $message")
        assertFalse(consoleTexts(result).contains("SHOULD_NOT_RUN"))
    }

    // ----- Seeded determinism -----

    private val rngSuiteText = """
        test("rng", () => {
          const bytes = new Uint8Array(8);
          crypto.getRandomValues(bytes);
          console.log("rng:" + Array.from(bytes).join(","));
          console.log("uuid:" + crypto.randomUUID());
        });
    """.trimIndent()

    @Test
    fun fixedSeedPinsTheExactRandomBytes() {
        val suite = writeSuite(rngSuiteText)
        val result = runner().runSuite(suite.toString(), seed = fixedSeed)
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        // First 8 bytes of SHA-256(seed || 0x0000000000000000), pinned.
        assertEquals("rng:83,40,100,189,29,121,77,192", consoleTexts(result)[0])
        val uuid = consoleTexts(result)[1].removePrefix("uuid:")
        assertTrue(
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(uuid),
            "randomUUID must be v4-formatted: $uuid",
        )
    }

    @Test
    fun sameSeedIsByteIdenticalDifferentSeedDiverges() {
        val suite = writeSuite(rngSuiteText)
        val first = runner().runSuite(suite.toString(), seed = fixedSeed)
        val second = runner().runSuite(suite.toString(), seed = fixedSeed)
        assertEquals(HexanaScriptStatus.SUCCESS, first.status)
        assertEquals(HexanaScriptStatus.SUCCESS, second.status)
        assertEquals(consoleTexts(first), consoleTexts(second))
        assertEquals(
            assertNotNull(first.suite).normalizedForDeterminism(),
            assertNotNull(second.suite).normalizedForDeterminism(),
        )

        val other = runner().runSuite(suite.toString(), seed = "ff00ff00ff00ff00")
        assertEquals(HexanaScriptStatus.SUCCESS, other.status)
        assertNotEquals(consoleTexts(first)[0], consoleTexts(other)[0])
    }

    @Test
    fun derivedSeedIsDeterministicForSameScriptAndArtifacts() {
        val suite = writeSuite(rngSuiteText)
        val artifacts = mapOf("app" to appDebugWasm.toString())
        val first = runner().runSuite(suite.toString(), artifacts = artifacts)
        val second = runner().runSuite(suite.toString(), artifacts = artifacts)
        assertEquals(HexanaScriptStatus.SUCCESS, first.status)
        assertEquals(consoleTexts(first), consoleTexts(second))
        assertEquals(
            assertNotNull(first.suite).normalizedForDeterminism(),
            assertNotNull(second.suite).normalizedForDeterminism(),
        )
    }

    // ----- crypto.subtle.digest -----

    @Test
    fun subtleDigestMatchesKnownVectorsAndRejectsOtherAlgorithms() {
        val suite = writeSuite(
            """
            const encoder = new TextEncoder();
            async function hexDigest(algorithm) {
              const buffer = await crypto.subtle.digest(algorithm, encoder.encode("abc"));
              return Array.from(new Uint8Array(buffer)).map((b) => b.toString(16).padStart(2, "0")).join("");
            }
            test("sha256", async () => {
              const h = await hexDigest("SHA-256");
              if (h !== "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") throw new Error(h);
            });
            test("sha384", async () => {
              const h = await hexDigest("SHA-384");
              if (h !== "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed" +
                        "8086072ba1e7cc2358baeca134c825a7") throw new Error(h);
            });
            test("sha512", async () => {
              const h = await hexDigest("SHA-512");
              if (h !== "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                        "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f") throw new Error(h);
            });
            test("md5-rejects", async () => {
              let rejected = false;
              try { await crypto.subtle.digest("MD5", encoder.encode("abc")); }
              catch (error) { rejected = true; }
              if (!rejected) throw new Error("MD5 digest must reject");
            });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val root = assertNotNull(result.suite)
        assertEquals(SuiteNodeStatus.PASS, root.status,
            "all digest tests must pass: " + root.children.map { "${it.name}=${it.status}:${it.failureMessage}" })
        assertEquals(4, root.children.size)
    }

    // ----- ESM module loading -----

    @Test
    fun relativeImportOfSiblingModuleWorks() {
        val dir = Files.createTempDirectory("hexana-suite-esm")
        Files.writeString(dir.resolve("helper.mjs"), "export function double(x) { return x * 2; }\n")
        val suite = writeSuite(
            """
            import { double } from "./helper.mjs";
            test("sibling", () => {
              if (double(21) !== 42) throw new Error("double(21)=" + double(21));
            });
            """.trimIndent(),
            dir,
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(SuiteNodeStatus.PASS, assertNotNull(result.suite).children.single().status)
    }

    @Test
    fun bareSpecifierResolvesThroughSyntheticNodeModules() {
        val dir = Files.createTempDirectory("hexana-suite-esm-lib")
        val nodeModules = Files.createDirectories(dir.resolve("node_modules"))
        val pkg = Files.createDirectories(nodeModules.resolve("wasmkit"))
        Files.writeString(
            pkg.resolve("package.json"),
            """{"name": "wasmkit", "exports": {".": {"import": "./index.mjs"}}}""",
        )
        Files.writeString(pkg.resolve("util.mjs"), "export const BASE = 40;\n")
        Files.writeString(
            pkg.resolve("index.mjs"),
            "import { BASE } from \"./util.mjs\";\nexport function answer() { return BASE + 2; }\n",
        )
        val suite = writeSuite(
            """
            import { answer } from "wasmkit";
            test("bare", () => {
              if (answer() !== 42) throw new Error("answer()=" + answer());
            });
            """.trimIndent(),
            dir,
        )
        val result = runner().runSuite(suite.toString(), libRoot = nodeModules.toString())
        assertEquals(HexanaScriptStatus.SUCCESS, result.status,
            "bare import must resolve: ${result.diagnostics}")
        assertEquals(SuiteNodeStatus.PASS, assertNotNull(result.suite).children.single().status)
    }

    @Test
    fun cjsOnlyPackageIsRejectedAsEsmOnly() {
        val dir = Files.createTempDirectory("hexana-suite-esm-cjs")
        val nodeModules = Files.createDirectories(dir.resolve("node_modules"))
        val pkg = Files.createDirectories(nodeModules.resolve("cjspkg"))
        Files.writeString(
            pkg.resolve("package.json"),
            """{"name": "cjspkg", "main": "index.js", "type": "commonjs"}""",
        )
        Files.writeString(pkg.resolve("index.js"), "module.exports = { x: 1 };\n")
        val suite = writeSuite("import { x } from \"cjspkg\";\ntest(\"t\", () => {});\n", dir)
        val result = runner().runSuite(suite.toString(), libRoot = nodeModules.toString())
        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        val message = result.diagnostics.joinToString { it.message }
        assertTrue(message.contains("suite libraries are ESM-only"), "must state the ESM-only policy: $message")
        assertTrue(message.contains("cjspkg"), "must name the package: $message")
    }

    @Test
    fun parentEscapeImportIsDenied() {
        val outerDir = Files.createTempDirectory("hexana-suite-esm-escape")
        val suiteDir = Files.createDirectories(outerDir.resolve("inner"))
        Files.writeString(outerDir.resolve("outside.mjs"), "export const leaked = 1;\n")
        val suite = writeSuite(
            "import { leaked } from \"../outside.mjs\";\ntest(\"t\", () => {});\n",
            suiteDir,
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertNull(result.suite)
        assertTrue(
            result.diagnostics.joinToString { it.message }.contains("outside.mjs"),
            "denial must reference the module: ${result.diagnostics}",
        )
    }

    @Test
    fun absolutePathImportOutsideRootsIsDenied() {
        val otherDir = Files.createTempDirectory("hexana-suite-esm-abs")
        Files.writeString(otherDir.resolve("abs.mjs"), "export const leaked = 1;\n")
        val suite = writeSuite(
            "import { leaked } from \"${otherDir.toRealPath()}/abs.mjs\";\ntest(\"t\", () => {});\n",
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertNull(result.suite)
        assertTrue(
            result.diagnostics.joinToString { it.message }.contains("abs.mjs"),
            "denial must reference the module: ${result.diagnostics}",
        )
    }

    // ----- Suite globals visibility -----

    @Test
    fun suiteProfileReportsHasSuiteAndTextEncoder() {
        val suite = writeSuite(
            """
            test("profile", () => {
              if (hexana.has("suite") !== true) throw new Error("has(suite) must be true");
              if (typeof TextEncoder !== "function") throw new Error("TextEncoder missing");
              if (typeof URL !== "function") throw new Error("URL missing");
              if (typeof structuredClone !== "function") throw new Error("structuredClone missing");
              if (typeof atob !== "function" || typeof btoa !== "function") throw new Error("base64 missing");
              if (atob(btoa("hexana")) !== "hexana") throw new Error("base64 roundtrip");
              const clone = structuredClone({ a: [1, 2], when: new Date(0) });
              if (clone.a[1] !== 2 || clone.when.getTime() !== 0) throw new Error("clone");
              let cloneError = null;
              try { structuredClone(() => {}); } catch (error) { cloneError = error; }
              if (!cloneError || cloneError.name !== "DataCloneError") throw new Error("DataCloneError expected");
              const params = new URL("https://example.org/a/b?x=1&y=2#frag").searchParams;
              if (params.get("y") !== "2") throw new Error("searchParams");
            });
            """.trimIndent()
        )
        val result = runner().runSuite(suite.toString())
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val test = assertNotNull(result.suite).children.single()
        assertEquals(SuiteNodeStatus.PASS, test.status, "profile test failed: ${test.failureMessage}")
    }

    // ----- Base-profile pins: the v1 runner must be unaffected -----

    @Test
    fun baseProfileHasNoTextEncoderAndNoSuiteFeature() {
        val baseRunner = HexanaScriptRunner(
            limits = HexanaScriptLimits(),
            allowedFilePolicy = AllowedFilePolicy.allowAll,
        )
        val stubWasm = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val result = baseRunner.run(
            BinaryInput.RawBytes(stubWasm),
            HexanaScriptSource.Inline(
                text = """
                print(typeof TextEncoder);
                print(hexana.has("suite"));
                print(typeof describe);
                """.trimIndent(),
                name = "base-pin.js",
            ),
        )
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("undefined", "false", "undefined"), result.output.lines)
        assertNull(result.suite)
    }
}
