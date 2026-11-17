package org.jetbrains.hexana.cli.scripting

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * In-process end-to-end tests for the `hexana-cli-script test` command runtime:
 * real .test.js suites written into a temp dir, executed through
 * [runSuiteCli] with the real GraalJS engine.
 *
 * Covers exit-code mapping, the exact stdout report shape (durations
 * normalized), stderr console prefixes, JUnit XML emission, the
 * unmapped-artifact fail-fast path, and seeded determinism.
 */
class TestSuiteCliE2eTest {
    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    private val passingSuiteText = """
        describe("math", () => {
          test("adds", () => {
            console.log("hello-log");
            if (1 + 1 !== 2) throw new Error("bad math");
          });
          test("adds async", async () => {
            await new Promise((resolve) => setTimeout(resolve, 0));
            console.error("hello-err");
          });
        });
    """.trimIndent()

    private val failingSuiteText = """
        test("breaks", () => { throw new Error("boom"); });
    """.trimIndent()

    private fun writeSuite(dir: Path, name: String, text: String): Path {
        val file = dir.resolve(name)
        Files.writeString(file, text)
        return file
    }

    /** Replaces variable durations so report text can be compared exactly. */
    private fun normalizeDurations(text: String): String =
        text.replace(Regex("""\(\d+ ms\)"""), "(0 ms)")

    // -------------------------------------------------------------------------
    // Passing directory run: exit 0, exact report, console prefixes
    // -------------------------------------------------------------------------

    @Test
    fun passingDirectoryRunExitsZeroWithExactReportAndConsolePrefixes() {
        val dir = tmp.newFolder("passing").toPath()
        writeSuite(dir, "a.test.js", passingSuiteText)

        val result = runSuiteCli(RunSuiteCliRequest(suiteFiles = listOf(dir.toString())))

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertEquals(
            """
            Suite: a.test.js
            [PASS] math > adds (0 ms)
            [PASS] math > adds async (0 ms)

            2 passed, 0 failed, 0 skipped (0 ms)
            """.trimIndent() + "\n",
            normalizeDurations(result.stdout),
        )
        assertEquals("[log] hello-log\n[error] hello-err\n", result.stderr)
    }

    // -------------------------------------------------------------------------
    // Mixed directory run: exit 1, failure lines, JUnit written
    // -------------------------------------------------------------------------

    @Test
    fun failingSuiteInDirectoryExitsOneAndWritesJUnitReport() {
        val dir = tmp.newFolder("mixed").toPath()
        writeSuite(dir, "a.test.js", passingSuiteText)
        writeSuite(dir, "b.test.js", failingSuiteText)
        val junit = tmp.root.toPath().resolve("report.xml")

        val result = runSuiteCli(
            RunSuiteCliRequest(
                suiteFiles = listOf(dir.toString()),
                junitReportPath = junit,
            )
        )

        assertEquals(1, result.exitCode)

        val report = normalizeDurations(result.stdout)
        val lines = report.lines()
        assertEquals("Suite: a.test.js", lines[0])
        assertEquals("[PASS] math > adds (0 ms)", lines[1])
        assertEquals("[PASS] math > adds async (0 ms)", lines[2])
        assertEquals("", lines[3])
        assertEquals("Suite: b.test.js", lines[4])
        assertEquals("[FAIL] breaks (0 ms)", lines[5])
        assertTrue(lines[6].startsWith("       ") && lines[6].contains("boom"), "failure message line: ${lines[6]}")
        assertTrue(report.trimEnd().endsWith("2 passed, 1 failed, 0 skipped (0 ms)"), report)

        // JUnit report: well-formed, counts match.
        assertTrue(Files.isRegularFile(junit))
        val xml = Files.readString(junit)
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), xml)
        assertTrue(xml.contains("<testsuites>") && xml.contains("</testsuites>"), xml)
        assertTrue(xml.contains("<testsuite name=\"a.test.js\" tests=\"2\" failures=\"0\" skipped=\"0\""), xml)
        assertTrue(xml.contains("<testsuite name=\"b.test.js\" tests=\"1\" failures=\"1\" skipped=\"0\""), xml)
        assertTrue(xml.contains("classname=\"math\""), xml)
        assertTrue(xml.contains("<failure message="), xml)
    }

    // -------------------------------------------------------------------------
    // Unmapped required artifact: pre-run fail-fast
    // -------------------------------------------------------------------------

    @Test
    fun unmappedRequiredArtifactExitsOneNamesRoleAndEmitsNoTestcases() {
        val dir = tmp.newFolder("artifact").toPath()
        writeSuite(
            dir,
            "needs-artifact.test.js",
            """
            const app = hexana.artifact("app");
            test("never", () => { console.log("SHOULD_NOT_RUN"); });
            """.trimIndent(),
        )
        val junit = tmp.root.toPath().resolve("artifact-report.xml")

        val result = runSuiteCli(
            RunSuiteCliRequest(
                suiteFiles = listOf(dir.toString()),
                junitReportPath = junit,
            )
        )

        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("\"app\""), "stderr must name the role: ${result.stderr}")
        assertTrue(
            result.stderr.contains("--artifact app=<path>"),
            "stderr must show the --artifact syntax: ${result.stderr}",
        )
        assertFalse(result.stderr.contains("SHOULD_NOT_RUN"), result.stderr)

        // JUnit file written: one synthetic failing testcase named "(collection)".
        assertTrue(Files.isRegularFile(junit))
        val xml = Files.readString(junit)
        assertTrue(xml.contains("tests=\"1\" failures=\"1\""), "must report 1 test, 1 failure: $xml")
        assertTrue(xml.contains("name=\"(collection)\""), "synthetic testcase must be named (collection): $xml")
        assertTrue(xml.contains("<failure message="), "synthetic testcase must carry failure: $xml")
    }

    // -------------------------------------------------------------------------
    // test.skip: [SKIP] line + skipped count in stdout
    // -------------------------------------------------------------------------

    @Test
    fun testSkipProducesSkipLineAndSkippedCountAndExitsZero() {
        val dir = tmp.newFolder("skip").toPath()
        writeSuite(
            dir,
            "skip.test.js",
            """
            test("runs", () => { console.log("ran"); });
            test.skip("nope", () => { throw new Error("MUST_NOT_RUN"); });
            """.trimIndent(),
        )

        val result = runSuiteCli(RunSuiteCliRequest(suiteFiles = listOf(dir.toString())))

        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        val report = normalizeDurations(result.stdout)
        assertTrue(report.contains("[PASS] runs"), report)
        assertTrue(report.contains("[SKIP] nope"), report)
        assertTrue(report.trimEnd().endsWith("1 passed, 0 failed, 1 skipped (0 ms)"), report)
        // The skipped body must not execute.
        assertFalse(result.stderr.contains("MUST_NOT_RUN"), result.stderr)
    }

    // -------------------------------------------------------------------------
    // Seeded determinism
    // -------------------------------------------------------------------------

    private val rngSuiteText = """
        test("rng", () => {
          const bytes = new Uint8Array(8);
          crypto.getRandomValues(bytes);
          console.log("rng:" + Array.from(bytes).join(","));
          console.log("uuid:" + crypto.randomUUID());
        });
    """.trimIndent()

    @Test
    fun sameSeedProducesIdenticalConsoleDifferentSeedDiverges() {
        val dir = tmp.newFolder("seeded").toPath()
        writeSuite(dir, "rng.test.js", rngSuiteText)
        val fixedSeed = "00112233445566778899aabbccddeeff"

        val first = runSuiteCli(RunSuiteCliRequest(suiteFiles = listOf(dir.toString()), seed = fixedSeed))
        val second = runSuiteCli(RunSuiteCliRequest(suiteFiles = listOf(dir.toString()), seed = fixedSeed))
        assertEquals(0, first.exitCode, first.stderr)
        assertEquals(0, second.exitCode, second.stderr)
        // Exact string comparison of the two runs' captured console.
        assertEquals(first.stderr, second.stderr)
        assertTrue(first.stderr.startsWith("[log] rng:"), first.stderr)

        val other = runSuiteCli(RunSuiteCliRequest(suiteFiles = listOf(dir.toString()), seed = "ff00ff00ff00ff00"))
        assertEquals(0, other.exitCode, other.stderr)
        assertNotEquals(first.stderr.lines()[0], other.stderr.lines()[0])
    }
}
