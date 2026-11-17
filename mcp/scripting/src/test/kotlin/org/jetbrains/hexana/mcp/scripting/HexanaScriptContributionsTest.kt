package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.io.toCommonByteBuffer
import org.jetbrains.hexana.providers.GenericBinaryProvider
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for hexana.defineSlice registration, console separation, session paging,
 * page timeout, ASYNC_OUTPUT_LOST detection, and back-compat pin.
 */
class HexanaScriptContributionsTest {

    // -------------------------------------------------------------------------
    // Registration collection
    // -------------------------------------------------------------------------

    @Test
    fun defineSliceCollectsStaticMetadataWithoutCallingRows() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineSlice({
                    name: "user:my-slice",
                    formats: ["wasm"],
                    columns: [
                        { name: "Name", type: "string" },
                        { name: "Offset", type: "offset" },
                        { name: "Index", type: "funcidx" }
                    ],
                    params: [
                        { name: "limit", type: "int", default: 10 }
                    ],
                    rows: function(binary, params, paging) {
                        throw new Error("rows must not be called during static metadata extraction");
                    }
                });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(1, result.slices.size, "expected 1 slice registration")
        val slice = result.slices[0]
        assertEquals("user:my-slice", slice.name)
        assertEquals(listOf("wasm"), slice.formats)
        assertEquals(3, slice.columns.size)
        assertEquals("Name", slice.columns[0].name)
        assertEquals("Offset", slice.columns[1].name)
        assertEquals("Index", slice.columns[2].name)
        assertEquals(1, slice.params.size)
        assertEquals("limit", slice.params[0].name)
    }

    @Test
    fun defineSliceDuplicateNameThrows() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineSlice({ name: "user:dup", columns: [{ name: "x", type: "string" }], rows: function() {} });
                hexana.defineSlice({ name: "user:dup", columns: [{ name: "y", type: "int" }], rows: function() {} });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertTrue(result.diagnostics.any { it.message.contains("duplicate") || it.code == "RUNTIME_ERROR" })
    }

    @Test
    fun defineSliceMissingUserPrefixThrows() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                hexana.defineSlice({ name: "no-prefix", columns: [{ name: "x", type: "string" }], rows: function() {} });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertTrue(result.diagnostics.any { it.code == "RUNTIME_ERROR" })
    }

    // -------------------------------------------------------------------------
    // Console separation
    // -------------------------------------------------------------------------

    @Test
    fun consoleSeparationPrintGoesToLinesConsoleGoesToConsoleStream() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                print("a");
                console.log("b");
                console.error("c");
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("a"), result.output.lines, "lines must contain only print output")
        assertEquals(2, result.console.size, "console must have 2 entries")
        assertEquals(org.jetbrains.hexana.shared.scripting.ConsoleSeverity.LOG, result.console[0].severity)
        assertEquals("b", result.console[0].text)
        assertEquals(org.jetbrains.hexana.shared.scripting.ConsoleSeverity.ERROR, result.console[1].severity)
        assertEquals("c", result.console[1].text)
    }

    @Test
    fun consoleDoesNotLeakIntoStdout() {
        // GraalJS ships a built-in console that writes to stdout. Our binding
        // should shadow it — console output must appear in result.console, not
        // in result.output.lines.
        val result = runner().run(
            HexanaScriptSource.Inline("console.log('should-not-be-in-lines');"),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertTrue(result.output.lines.none { "should-not-be-in-lines" in it })
        assertTrue(result.console.any { "should-not-be-in-lines" in it.text })
    }

    // -------------------------------------------------------------------------
    // Session paging
    // -------------------------------------------------------------------------

    @Test
    fun sessionPagingFetchesTwoPagesWithNextOffsetChaining() {
        runner().openSession(
            HexanaScriptSource.Inline(
                """
                hexana.defineSlice({
                    name: "user:counted",
                    columns: [{ name: "val", type: "int" }],
                    rows: function(binary, params, paging) {
                        var rows = [];
                        var end = Math.min(paging.offset + paging.limit, 10);
                        for (var i = paging.offset; i < end; i++) {
                            rows.push({ anchor: i, cells: [i] });
                        }
                        var nextOffset = end < 10 ? end : null;
                        return { rows: rows, nextOffset: nextOffset, total: 10 };
                    }
                });
                """.trimIndent()
            ),
            testProvider(),
        ).use { session ->
            assertTrue(session.isAlive, "session must be alive after eval")
            assertEquals(HexanaScriptStatus.SUCCESS, session.initialResult.status)
            assertEquals(1, session.initialResult.slices.size)

            val page1 = session.fetchPage("user:counted", 0, 5)
            assertTrue(page1 is SlicePageResult.Ok, "page1 must succeed")
            val p1 = (page1 as SlicePageResult.Ok).page
            assertEquals(5, p1.rows.size)
            assertEquals(5, p1.nextOffset)
            assertEquals(10, p1.total)
            assertEquals(0L, p1.rows[0].anchor)
            assertEquals("0", p1.rows[0].cells[0].text)

            val page2 = session.fetchPage("user:counted", 5, 5)
            assertTrue(page2 is SlicePageResult.Ok, "page2 must succeed")
            val p2 = (page2 as SlicePageResult.Ok).page
            assertEquals(5, p2.rows.size)
            assertEquals(null, p2.nextOffset, "last page must return null nextOffset")
            assertEquals(5L, p2.rows[0].anchor)
        }
    }

    // -------------------------------------------------------------------------
    // Page timeout
    // -------------------------------------------------------------------------

    @Test
    fun pageTimeoutKillsSessionWithTimeoutDiagnostic() {
        val shortLimits = HexanaScriptLimits(timeoutMillis = 300)
        val session = HexanaScriptRunner(limits = shortLimits).openSession(
            HexanaScriptSource.Inline(
                """
                hexana.defineSlice({
                    name: "user:loop",
                    columns: [{ name: "x", type: "int" }],
                    rows: function(b, p, pg) { while(true) {} }
                });
                """.trimIndent()
            ),
            testProvider(),
        )

        assertTrue(session.isAlive, "session must start alive")
        val result = session.fetchPage("user:loop", 0, 10)
        assertTrue(result is SlicePageResult.Err)
        assertEquals("TIMEOUT", (result as SlicePageResult.Err).error.diagnostic.code)
        assertFalse(session.isAlive, "session must be dead after page timeout")
        // Close is idempotent.
        session.close()
    }

    // -------------------------------------------------------------------------
    // ASYNC_OUTPUT_LOST
    // -------------------------------------------------------------------------

    @Test
    fun asyncOutputLostFiredForNeverResolvingPromise() {
        val result = runner().run(
            HexanaScriptSource.Inline("new Promise(function() {})"),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        val asyncDiag = result.diagnostics.find { it.code == "ASYNC_OUTPUT_LOST" }
        assertNotNull(asyncDiag, "ASYNC_OUTPUT_LOST diagnostic must be present")
        assertEquals(
            org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnosticSeverity.WARNING,
            asyncDiag.severity,
        )
    }

    @Test
    fun asyncOutputLostAbsentForSyncScript() {
        val result = runner().run(
            HexanaScriptSource.Inline("print('sync');"),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertTrue(result.diagnostics.none { it.code == "ASYNC_OUTPUT_LOST" })
    }

    // -------------------------------------------------------------------------
    // Back-compat pin
    // -------------------------------------------------------------------------

    @Test
    fun v1ScriptRunHasEmptySlicesAndConsole() {
        val result = runner().run(
            HexanaScriptSource.Inline("print('hello'); table('A'); row('1');"),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertEquals(listOf("hello", "A", "1"), result.output.lines)
        assertTrue(result.slices.isEmpty(), "v1 script must produce no slices")
        assertTrue(result.console.isEmpty(), "v1 script must produce no console entries")
    }

    @Test
    fun hexanaApiVersionIsTwoAndHasReturnsSlices() {
        val result = runner().run(
            HexanaScriptSource.Inline(
                """
                print(hexana.api);
                print(hexana.has("slices"));
                print(hexana.has("unknown-feature"));
                """.trimIndent()
            ),
            testProvider(),
        )

        assertEquals(HexanaScriptStatus.SUCCESS, result.status, "status: ${result.diagnostics}")
        assertEquals(listOf("2", "true", "false"), result.output.lines)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun runner() = HexanaScriptRunner()

    private fun testProvider(): GenericBinaryProvider =
        GenericBinaryProvider(byteArrayOf(0, 1, 2, 3, 4).toCommonByteBuffer())
}
