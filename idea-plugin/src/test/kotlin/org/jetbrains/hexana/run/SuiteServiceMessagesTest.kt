package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.scripting.SuiteNodeKind
import org.jetbrains.hexana.shared.scripting.SuiteNodeResult
import org.jetbrains.hexana.shared.scripting.SuiteNodeStatus

/**
 * Exact-output tests for the TeamCity service-message rendering: [tcEscape],
 * [tcMessage], [suiteTreeToServiceMessages], and
 * [collectionErrorServiceMessages]. Pure functions, no platform runtime,
 * no GraalJS.
 */
class SuiteServiceMessagesTest : UsefulTestCase() {

    // ----- tcEscape: the full TeamCity escaping table -----

    fun testEscapePipe() = assertEquals("a||b", tcEscape("a|b"))
    fun testEscapeApostrophe() = assertEquals("|'quoted|'", tcEscape("'quoted'"))
    fun testEscapeOpenBracket() = assertEquals("|[x", tcEscape("[x"))
    fun testEscapeCloseBracket() = assertEquals("x|]", tcEscape("x]"))
    fun testEscapeNewline() = assertEquals("a|nb", tcEscape("a\nb"))
    fun testEscapeCarriageReturn() = assertEquals("a|rb", tcEscape("a\rb"))
    fun testEscapeNextLine() = assertEquals("a|xb", tcEscape("a\u0085b"))
    fun testEscapeLineSeparator() = assertEquals("a|lb", tcEscape("a\u2028b"))
    fun testEscapeParagraphSeparator() = assertEquals("a|pb", tcEscape("a\u2029b"))
    fun testEscapePlainTextUntouched() = assertEquals("plain text 123", tcEscape("plain text 123"))
    fun testEscapeEmpty() = assertEquals("", tcEscape(""))

    fun testEscapeEverythingAtOnce() {
        assertEquals(
            "||_|'_|[_|]_|n_|r_|x_|l_|p",
            tcEscape("|_'_[_]_\n_\r_\u0085_\u2028_\u2029"),
        )
    }

    fun testMessageBuilderEscapesHostileValues() {
        assertEquals(
            "##teamcity[testStarted name='a||b |'c|' |[d|]|ne']",
            tcMessage("testStarted", listOf("name" to "a|b 'c' [d]\ne")),
        )
    }

    // ----- suiteTreeToServiceMessages -----

    private fun test(
        name: String,
        status: SuiteNodeStatus,
        durationMillis: Long = 0,
        failureMessage: String? = null,
        failureStackLine: String? = null,
        sourceFile: String? = null,
        sourceLine: Int? = null,
    ) = SuiteNodeResult(
        name = name,
        kind = SuiteNodeKind.TEST,
        status = status,
        durationMillis = durationMillis,
        failureMessage = failureMessage,
        failureStackLine = failureStackLine,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
    )

    private fun suite(
        name: String,
        status: SuiteNodeStatus,
        children: List<SuiteNodeResult>,
        sourceFile: String? = null,
        sourceLine: Int? = null,
    ) = SuiteNodeResult(
        name = name,
        kind = SuiteNodeKind.SUITE,
        status = status,
        durationMillis = 0,
        children = children,
        sourceFile = sourceFile,
        sourceLine = sourceLine,
    )

    fun testNestedTreeExactMessages() {
        val root = suite(
            "math.test.js", SuiteNodeStatus.FAIL,
            children = listOf(
                test(
                    "top-level pass", SuiteNodeStatus.PASS, durationMillis = 7,
                    sourceFile = "/abs/math.test.js", sourceLine = 3,
                ),
                suite(
                    "vectors", SuiteNodeStatus.FAIL,
                    sourceFile = "/abs/math.test.js", sourceLine = 10,
                    children = listOf(
                        test(
                            "adds", SuiteNodeStatus.FAIL, durationMillis = 12,
                            failureMessage = "expected 3, got 4",
                            failureStackLine = "at /abs/math.test.js:12:5",
                            sourceFile = "/abs/math.test.js", sourceLine = 11,
                        ),
                        test("skipped one", SuiteNodeStatus.SKIP),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf(
                "##teamcity[testSuiteStarted name='math.test.js']",
                "##teamcity[testStarted name='top-level pass' locationHint='hexana-script://" +
                    "/abs/math.test.js:3']",
                "##teamcity[testFinished name='top-level pass' duration='7']",
                "##teamcity[testSuiteStarted name='vectors' locationHint='hexana-script://" +
                    "/abs/math.test.js:10']",
                "##teamcity[testStarted name='adds' locationHint='hexana-script:///abs/math.test.js:11']",
                "##teamcity[testFailed name='adds' message='expected 3, got 4' " +
                    "details='at /abs/math.test.js:12:5']",
                "##teamcity[testFinished name='adds' duration='12']",
                "##teamcity[testIgnored name='skipped one']",
                "##teamcity[testSuiteFinished name='vectors']",
                "##teamcity[testSuiteFinished name='math.test.js']",
            ),
            suiteTreeToServiceMessages("math.test.js", root),
        )
    }

    fun testHostileNamesAreEscaped() {
        val root = suite(
            "weird.test.js", SuiteNodeStatus.FAIL,
            children = listOf(
                test(
                    "a|b 'c' [d]\ne", SuiteNodeStatus.FAIL, durationMillis = 1,
                    failureMessage = "boom [1|2]",
                ),
            ),
        )
        assertEquals(
            listOf(
                "##teamcity[testSuiteStarted name='weird.test.js']",
                "##teamcity[testStarted name='a||b |'c|' |[d|]|ne']",
                "##teamcity[testFailed name='a||b |'c|' |[d|]|ne' message='boom |[1||2|]']",
                "##teamcity[testFinished name='a||b |'c|' |[d|]|ne' duration='1']",
                "##teamcity[testSuiteFinished name='weird.test.js']",
            ),
            suiteTreeToServiceMessages("weird.test.js", root),
        )
    }

    fun testFailedWithoutMessageOrStack() {
        val root = suite(
            "f.test.js", SuiteNodeStatus.FAIL,
            children = listOf(test("boom", SuiteNodeStatus.FAIL, durationMillis = 2)),
        )
        assertEquals(
            listOf(
                "##teamcity[testSuiteStarted name='f.test.js']",
                "##teamcity[testStarted name='boom']",
                "##teamcity[testFailed name='boom' message='Test failed']",
                "##teamcity[testFinished name='boom' duration='2']",
                "##teamcity[testSuiteFinished name='f.test.js']",
            ),
            suiteTreeToServiceMessages("f.test.js", root),
        )
    }

    fun testNoLocationHintWhenLineMissing() {
        val root = suite(
            "n.test.js", SuiteNodeStatus.PASS,
            children = listOf(
                test("only file", SuiteNodeStatus.PASS, durationMillis = 1, sourceFile = "/abs/n.test.js"),
            ),
        )
        assertEquals(
            listOf(
                "##teamcity[testSuiteStarted name='n.test.js']",
                "##teamcity[testStarted name='only file']",
                "##teamcity[testFinished name='only file' duration='1']",
                "##teamcity[testSuiteFinished name='n.test.js']",
            ),
            suiteTreeToServiceMessages("n.test.js", root),
        )
    }

    fun testEmptySuiteFile() {
        val root = suite("empty.test.js", SuiteNodeStatus.PASS, children = emptyList())
        assertEquals(
            listOf(
                "##teamcity[testSuiteStarted name='empty.test.js']",
                "##teamcity[testSuiteFinished name='empty.test.js']",
            ),
            suiteTreeToServiceMessages("empty.test.js", root),
        )
    }

    // ----- SCRIPT_ERROR synthetic collection entry -----

    fun testCollectionErrorMessages() {
        assertEquals(
            listOf(
                "##teamcity[testSuiteStarted name='broken.test.js']",
                "##teamcity[testStarted name='(collection)']",
                "##teamcity[testFailed name='(collection)' message='SyntaxError: unexpected token |[line 3|]']",
                "##teamcity[testFinished name='(collection)' duration='0']",
                "##teamcity[testSuiteFinished name='broken.test.js']",
            ),
            collectionErrorServiceMessages("broken.test.js", "SyntaxError: unexpected token [line 3]"),
        )
    }
}
