package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.mcp.scripting.HexanaScriptDiagnostic
import org.jetbrains.hexana.mcp.scripting.HexanaScriptOutput
import org.jetbrains.hexana.mcp.scripting.HexanaScriptResult
import org.jetbrains.hexana.mcp.scripting.HexanaScriptStatus
import org.jetbrains.hexana.shared.scripting.CheckFinding
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.ScriptUsageError

/**
 * Tests for the execution-seam helpers in [HexanaScriptCommandState] that do
 * not require a live GraalJS engine.
 *
 * GraalJS engine tests (collection-pass + execution-layer against playground fixtures)
 * live in `:mcp:scripting:test` as [HexanaScriptPhase2BTest], where the Polyglot
 * context works reliably.
 */
class ScriptRunConfigurationEngineTest : UsefulTestCase() {

    // ----- exitCodeFor -----

    fun testExitCodeForSuccessNoFindings() {
        val result = makeResult(status = HexanaScriptStatus.SUCCESS)
        assertEquals(0, exitCodeFor(result))
    }

    fun testExitCodeForSuccessWithErrorFinding() {
        val result = makeResult(
            status = HexanaScriptStatus.SUCCESS,
            findings = listOf(CheckFinding("user:c", CheckSeverity.ERROR, "boom")),
        )
        assertEquals(1, exitCodeFor(result))
    }

    fun testExitCodeForScriptError() {
        val result = makeResult(status = HexanaScriptStatus.SCRIPT_ERROR)
        assertEquals(1, exitCodeFor(result))
    }

    fun testExitCodeForUsageErrorIsTwo() {
        // A result with a usage error must yield exit code 2 even when status == SUCCESS
        // and an ERROR finding is also present (2 wins over 1).
        val result = makeResult(
            status = HexanaScriptStatus.SUCCESS,
            findings = listOf(CheckFinding("user:c", CheckSeverity.ERROR, "also an error")),
            usageErrors = listOf(ScriptUsageError.UndeclaredFlag("--unknown")),
        )
        assertEquals(2, exitCodeFor(result))
    }

    fun testExitCodeForUsageErrorTakesPrecedenceOverScriptError() {
        val result = makeResult(
            status = HexanaScriptStatus.SCRIPT_ERROR,
            usageErrors = listOf(ScriptUsageError.UndeclaredFlag("--bad-flag")),
        )
        assertEquals(2, exitCodeFor(result))
    }

    // ----- decodeStoredFlagTokens: stored flag values (name=value pairs) -----

    fun testDecodeStoredFlagTokensString() {
        val tokens = decodeStoredFlagTokens(listOf("mode=fast"))
        assertEquals(listOf("--mode", "fast"), tokens)
    }

    fun testDecodeStoredFlagTokensBoolTrue() {
        val tokens = decodeStoredFlagTokens(listOf("verbose=__BOOL_TRUE__"))
        assertEquals(listOf("--verbose"), tokens)
    }

    fun testDecodeStoredFlagTokensMultiple() {
        val tokens = decodeStoredFlagTokens(listOf("mode=fast", "limit=42", "verbose=__BOOL_TRUE__"))
        assertTrue(tokens.contains("--mode"))
        assertEquals("fast", tokens[tokens.indexOf("--mode") + 1])
        assertTrue(tokens.contains("--limit"))
        assertEquals("42", tokens[tokens.indexOf("--limit") + 1])
        assertTrue(tokens.contains("--verbose"))
        val verboseIdx = tokens.indexOf("--verbose")
        // bool flag: the next token (if any) must be another flag, not a value
        if (verboseIdx < tokens.size - 1) {
            assertTrue("next token after --verbose must be a flag", tokens[verboseIdx + 1].startsWith("--"))
        }
    }

    fun testDecodeStoredFlagTokensEmptyList() {
        val tokens = decodeStoredFlagTokens(emptyList())
        assertEquals(emptyList<String>(), tokens)
    }

    // ----- helpers -----

    private fun makeResult(
        status: HexanaScriptStatus,
        findings: List<CheckFinding> = emptyList(),
        usageErrors: List<ScriptUsageError> = emptyList(),
    ): HexanaScriptResult = HexanaScriptResult(
        status = status,
        output = HexanaScriptOutput(
            lines = emptyList(),
            truncated = false,
            emittedBytes = 0,
            maxLines = 1000,
            maxBytes = 1024 * 1024,
        ),
        diagnostics = emptyList(),
        binary = null,
        sourceName = "<test>",
        sourcePath = null,
        durationMillis = 0L,
        findings = findings,
        usageErrors = usageErrors,
    )
}
