package org.jetbrains.hexana.cli.scripting

import org.jetbrains.hexana.shared.scripting.SuiteNodeKind
import org.jetbrains.hexana.shared.scripting.SuiteNodeResult
import org.jetbrains.hexana.shared.scripting.SuiteNodeStatus
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the `hexana-cli-script test` command internals:
 * file discovery, report rendering, JUnit XML output, and exit-code mapping.
 *
 * These tests exercise the production code paths directly (resolveInputs,
 * buildReport, JUnitXmlWriter) without invoking the GraalJS engine.
 */
class TestSuiteCliRuntimeTest {
    @Rule
    @JvmField
    val tmp = TemporaryFolder()

    // -------------------------------------------------------------------------
    // resolveInputs
    // -------------------------------------------------------------------------

    @Test
    fun resolveInputsReturnsPassThroughForExplicitFiles() {
        val a = tmp.newFile("a.test.js").path
        val b = tmp.newFile("b.test.js").path
        assertEquals(listOf(a, b), resolveInputs(listOf(a, b)))
    }

    @Test
    fun resolveInputsWalksDirectoryForTestJsFiles() {
        val dir = tmp.newFolder("suite")
        Files.writeString(dir.toPath().resolve("b.test.js"), "// b")
        Files.writeString(dir.toPath().resolve("a.test.js"), "// a")
        val subDir = dir.toPath().resolve("sub").also { Files.createDirectories(it) }
        Files.writeString(subDir.resolve("c.test.js"), "// c")
        // non-matching file must be excluded
        Files.writeString(dir.toPath().resolve("helper.js"), "// helper")

        val result = resolveInputs(listOf(dir.path))
        // sorted alphabetically, non-test excluded
        assertEquals(3, result.size)
        assertTrue(result[0].endsWith("a.test.js"))
        assertTrue(result[1].endsWith("b.test.js"))
        assertTrue(result[2].endsWith("c.test.js"))
    }

    @Test
    fun resolveInputsReturnsEmptyListForEmptyDirectory() {
        val dir = tmp.newFolder("empty")
        assertEquals(emptyList(), resolveInputs(listOf(dir.path)))
    }

    @Test
    fun resolveInputsDoesNotWalkDirectoryWhenMultipleInputsGiven() {
        val dir = tmp.newFolder("notwalked")
        Files.writeString(dir.toPath().resolve("x.test.js"), "")
        val b = tmp.newFile("b.test.js").path
        // Two paths: not treated as directory walk
        val result = resolveInputs(listOf(dir.path, b))
        assertEquals(listOf(dir.path, b), result)
    }

    // -------------------------------------------------------------------------
    // buildReport
    // -------------------------------------------------------------------------

    @Test
    fun buildReportRendersPassSkipFailWithSummary() {
        val suiteRoot = SuiteNodeResult(
            name = "my.test.js",
            kind = SuiteNodeKind.SUITE,
            status = SuiteNodeStatus.FAIL,
            durationMillis = 100,
            children = listOf(
                SuiteNodeResult("passes", SuiteNodeKind.TEST, SuiteNodeStatus.PASS, 10),
                SuiteNodeResult("skips", SuiteNodeKind.TEST, SuiteNodeStatus.SKIP, 5),
                SuiteNodeResult(
                    name = "fails",
                    kind = SuiteNodeKind.TEST,
                    status = SuiteNodeStatus.FAIL,
                    durationMillis = 85,
                    failureMessage = "expected 1 to equal 2",
                    failureStackLine = "my.test.js:5",
                ),
            ),
        )

        val report = buildReport(listOf("path/my.test.js" to suiteRoot))

        assertTrue(report.contains("Suite: my.test.js"), report)
        assertTrue(report.contains("[PASS] passes (10 ms)"), report)
        assertTrue(report.contains("[SKIP] skips (5 ms)"), report)
        assertTrue(report.contains("[FAIL] fails (85 ms)"), report)
        assertTrue(report.contains("expected 1 to equal 2"), report)
        assertTrue(report.contains("at my.test.js:5"), report)
        // summary line
        assertTrue(report.trimEnd().endsWith("1 passed, 1 failed, 1 skipped (100 ms)"), report)
    }

    @Test
    fun buildReportAggregatesCountsAcrossFiles() {
        fun pass(name: String) = SuiteNodeResult(name, SuiteNodeKind.TEST, SuiteNodeStatus.PASS, 1)
        fun fail(name: String) = SuiteNodeResult(name, SuiteNodeKind.TEST, SuiteNodeStatus.FAIL, 1, "oops")

        val suite1 = SuiteNodeResult("a.test.js", SuiteNodeKind.SUITE, SuiteNodeStatus.PASS, 10,
            children = listOf(pass("t1"), pass("t2")))
        val suite2 = SuiteNodeResult("b.test.js", SuiteNodeKind.SUITE, SuiteNodeStatus.FAIL, 20,
            children = listOf(pass("t3"), fail("t4")))

        val report = buildReport(listOf("a.test.js" to suite1, "b.test.js" to suite2))
        assertTrue(report.trimEnd().endsWith("3 passed, 1 failed, 0 skipped (30 ms)"), report)
    }

    @Test
    fun buildReportEmitsBlankLineBetweenSuites() {
        fun pass(name: String) = SuiteNodeResult(name, SuiteNodeKind.TEST, SuiteNodeStatus.PASS, 1)
        val s1 = SuiteNodeResult("a.test.js", SuiteNodeKind.SUITE, SuiteNodeStatus.PASS, 5, children = listOf(pass("x")))
        val s2 = SuiteNodeResult("b.test.js", SuiteNodeKind.SUITE, SuiteNodeStatus.PASS, 5, children = listOf(pass("y")))
        val report = buildReport(listOf("a.test.js" to s1, "b.test.js" to s2))
        // The empty line separating suites must appear between the two Suite: headers.
        val lines = report.lines()
        val firstSuite = lines.indexOfFirst { it.startsWith("Suite: a") }
        val secondSuite = lines.indexOfFirst { it.startsWith("Suite: b") }
        assertTrue(secondSuite > firstSuite + 1, "expected blank line between suites")
        assertEquals("", lines[secondSuite - 1], "line before second suite header must be blank")
    }

    // -------------------------------------------------------------------------
    // JUnitXmlWriter
    // -------------------------------------------------------------------------

    @Test
    fun junitXmlWriterProducesWellFormedXml() {
        val pass = SuiteNodeResult("passes", SuiteNodeKind.TEST, SuiteNodeStatus.PASS, 123)
        val skip = SuiteNodeResult("skips", SuiteNodeKind.TEST, SuiteNodeStatus.SKIP, 0)
        val fail = SuiteNodeResult(
            name = "fails",
            kind = SuiteNodeKind.TEST,
            status = SuiteNodeStatus.FAIL,
            durationMillis = 456,
            failureMessage = "expected true",
        )
        val root = SuiteNodeResult(
            name = "suite.test.js",
            kind = SuiteNodeKind.SUITE,
            status = SuiteNodeStatus.FAIL,
            durationMillis = 600,
            children = listOf(pass, skip, fail),
        )
        val output = tmp.root.toPath().resolve("report.xml")
        JUnitXmlWriter.write(listOf("suite.test.js" to root), output)
        val xml = Files.readString(output)

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), xml)
        assertTrue(xml.contains("<testsuites>"), xml)
        assertTrue(xml.contains("</testsuites>"), xml)
        assertTrue(xml.contains("name=\"suite.test.js\""), xml)
        assertTrue(xml.contains("tests=\"3\""), xml)
        assertTrue(xml.contains("failures=\"1\""), xml)
        assertTrue(xml.contains("skipped=\"1\""), xml)
        // PASS: self-closing
        assertTrue(xml.contains("name=\"passes\""), xml)
        // SKIP element
        assertTrue(xml.contains("<skipped/>"), xml)
        // FAIL element with message
        assertTrue(xml.contains("<failure message=\"expected true\""), xml)
    }

    @Test
    fun junitXmlWriterMatchesGoldenFile() {
        val pass = SuiteNodeResult("passes", SuiteNodeKind.TEST, SuiteNodeStatus.PASS, 123)
        val skip = SuiteNodeResult("skips", SuiteNodeKind.TEST, SuiteNodeStatus.SKIP, 0)
        val fail = SuiteNodeResult(
            name = "fails",
            kind = SuiteNodeKind.TEST,
            status = SuiteNodeStatus.FAIL,
            durationMillis = 456,
            failureMessage = "expected true",
            failureStackLine = "sample.test.js:7",
        )
        val root = SuiteNodeResult(
            name = "sample.test.js",
            kind = SuiteNodeKind.SUITE,
            status = SuiteNodeStatus.FAIL,
            durationMillis = 600,
            children = listOf(pass, skip, fail),
        )
        val output = tmp.root.toPath().resolve("golden-out.xml")
        JUnitXmlWriter.write(listOf("sample.test.js" to root), output)

        val actual = Files.readString(output)
        val normalizedActual = actual.replace(Regex("""time="[^"]*""""), """time="0.000"""")

        val goldenStream = checkNotNull(
            TestSuiteCliRuntimeTest::class.java.getResourceAsStream("/junit-golden.xml")
        ) { "junit-golden.xml not found in test resources" }
        val golden = goldenStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val normalizedGolden = golden.replace(Regex("""time="[^"]*""""), """time="0.000"""")

        assertEquals(normalizedGolden, normalizedActual)
    }

    @Test
    fun junitXmlWriterFormatsTimeCorrectly() {
        assertEquals("0.000", JUnitXmlWriter.formatSeconds(0))
        assertEquals("0.001", JUnitXmlWriter.formatSeconds(1))
        assertEquals("0.123", JUnitXmlWriter.formatSeconds(123))
        assertEquals("1.000", JUnitXmlWriter.formatSeconds(1000))
        assertEquals("1.001", JUnitXmlWriter.formatSeconds(1001))
        assertEquals("65.500", JUnitXmlWriter.formatSeconds(65500))
    }

    @Test
    fun junitXmlWriterEscapesXmlSpecialCharacters() {
        val fail = SuiteNodeResult(
            name = "test <name> & \"more'",
            kind = SuiteNodeKind.TEST,
            status = SuiteNodeStatus.FAIL,
            durationMillis = 0,
            failureMessage = "a < b & \"c\"",
        )
        val root = SuiteNodeResult("s.test.js", SuiteNodeKind.SUITE, SuiteNodeStatus.FAIL, 0, children = listOf(fail))
        val output = tmp.root.toPath().resolve("escaped.xml")
        JUnitXmlWriter.write(listOf("s.test.js" to root), output)
        val xml = Files.readString(output)
        // Attribute escaping (name)
        assertTrue(xml.contains("&lt;name&gt;"), xml)
        assertTrue(xml.contains("&amp;"), xml)
        assertTrue(xml.contains("&quot;more&apos;"), xml)
        // Element body must be single-escaped: message "a < b & \"c\"" ->
        // body text "a &lt; b &amp; &quot;c&quot;" appears exactly once.
        assertTrue(xml.contains("a &lt; b &amp; &quot;c&quot;"), "body must be single-escaped: $xml")
        // Double-escaped form must NOT appear.
        assertTrue(!xml.contains("&amp;lt;"), "double-escaped &amp;lt; must not appear: $xml")
        assertTrue(!xml.contains("&amp;amp;"), "double-escaped &amp;amp; must not appear: $xml")
    }

    @Test
    fun junitXmlWriterReplacesXmlInvalidCodePointsWithReplacementChar() {
        val fail = SuiteNodeResult(
            name = "nul \u0000 bell \u0007 test",
            kind = SuiteNodeKind.TEST,
            status = SuiteNodeStatus.FAIL,
            durationMillis = 0,
            failureMessage = "boom \u0000\u000B\u001F end",
        )
        val root = SuiteNodeResult("s.test.js", SuiteNodeKind.SUITE, SuiteNodeStatus.FAIL, 0, children = listOf(fail))
        val output = tmp.root.toPath().resolve("invalid-cp.xml")
        JUnitXmlWriter.write(listOf("s.test.js" to root), output)
        val xml = Files.readString(output)

        // Invalid code points are replaced with U+FFFD, never emitted raw.
        assertTrue(xml.contains("nul \uFFFD bell \uFFFD test"), "invalid chars must become U+FFFD: " + xml)
        assertTrue(xml.contains("boom \uFFFD\uFFFD\uFFFD end"), xml)
        assertTrue(!xml.contains('\u0000'), "raw NUL must not appear in the report")
        assertTrue(!xml.contains('\u0007'), "raw BEL must not appear in the report")

        // The resulting document parses as valid XML under a strict parser.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(output.toFile())
        assertEquals("testsuites", doc.documentElement.tagName)
    }

    @Test
    fun junitXmlWriterCreatesParentDirectories() {
        val output = tmp.root.toPath().resolve("nested/dir/report.xml")
        val root = SuiteNodeResult("s.test.js", SuiteNodeKind.SUITE, SuiteNodeStatus.PASS, 0)
        JUnitXmlWriter.write(listOf("s.test.js" to root), output)
        assertTrue(Files.isRegularFile(output))
    }

    // -------------------------------------------------------------------------
    // Exit code mapping
    // -------------------------------------------------------------------------

    @Test
    fun emptyResolvedFilesReturnsExitCode2() {
        // Point at a directory with no .test.js files -> resolveInputs returns empty
        val emptyDir = tmp.newFolder("empty")
        val result = runSuiteCli(
            RunSuiteCliRequest(suiteFiles = listOf(emptyDir.path))
        )
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("no suite files found"), result.stderr)
    }
}
