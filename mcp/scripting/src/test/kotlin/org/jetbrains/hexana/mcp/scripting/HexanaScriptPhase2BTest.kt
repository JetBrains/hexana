package org.jetbrains.hexana.mcp.scripting

import org.jetbrains.hexana.mcp.AllowedFilePolicy
import org.jetbrains.hexana.mcp.BinaryInput
import org.jetbrains.hexana.shared.scripting.CheckSeverity
import org.jetbrains.hexana.shared.scripting.FlagType
import org.jetbrains.hexana.shared.scripting.ScriptInvocation
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine-level tests for Scripting v2 Phase 2B (IDE run configuration).
 *
 * Tests the collection-pass (defineFlags) and execution-layer (defineCheck + findings)
 * against the playground fixtures. These tests run with a real GraalJS engine, not
 * mocks, and cover the same code paths used by HexanaScriptCommandState.runScript().
 */
class HexanaScriptPhase2BTest {

    // Paths to playground fixtures.
    // Gradle sets the test working directory to the module directory (mcp/scripting/),
    // so we need two levels up to reach the project root.
    private val playgroundDir = "../../examples/scripting-playground"
    private val flagsDemoScript = "$playgroundDir/hexana-script/scripts/flags-demo.js"
    private val checkDemoScript = "$playgroundDir/hexana-script/scripts/check-demo.js"
    private val helloWasm = "$playgroundDir/bin/hello.wasm"
    private val appDebugWasm = "$playgroundDir/bin/app-debug.wasm"

    private fun runner() = HexanaScriptRunner(
        limits = HexanaScriptLimits.interactive(),
        allowedFilePolicy = AllowedFilePolicy.allowAll,
    )

    // ----- Collection pass: flags-demo.js -----

    @Test
    fun collectionPassFlagsDemoDeclarations() {
        val stubWasm = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val result = runner().run(BinaryInput.RawBytes(stubWasm), HexanaScriptSource.FilePath(flagsDemoScript))
        // The runner may return SCRIPT_ERROR because the stub binary has no functions,
        // but flagDeclarations must still be populated from the defineFlags call.
        val decls = result.flagDeclarations
        assertTrue("mode" in decls, "flags-demo must declare 'mode'")
        assertTrue("limit" in decls, "flags-demo must declare 'limit'")
        assertTrue("file" in decls, "flags-demo must declare 'file'")
    }

    @Test
    fun collectionPassFlagsDemoTypes() {
        val stubWasm = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val result = runner().run(BinaryInput.RawBytes(stubWasm), HexanaScriptSource.FilePath(flagsDemoScript))
        val decls = result.flagDeclarations

        val mode = decls["mode"]
        assertNotNull(mode)
        assertEquals(FlagType.VALUES, mode.type)
        assertEquals(listOf("fast", "slow", "auto"), mode.values)
        assertEquals("auto", mode.default)

        val limit = decls["limit"]
        assertNotNull(limit)
        assertEquals(FlagType.INT, limit.type)
        assertEquals("10", limit.default)

        val file = decls["file"]
        assertNotNull(file)
        assertEquals(FlagType.PATH, file.type)
        assertNull(file.default)
    }

    @Test
    fun collectionPassRuntimeErrorStillYieldsDeclarations() {
        // A script with a runtime error after defineFlags should still return the
        // declarations collected before the error (flagDeclarations survive SCRIPT_ERROR).
        // Note: a syntax error is caught at parse time before any JS executes, so
        // defineFlags would not run. This test uses a runtime error instead.
        val runner = HexanaScriptRunner(
            limits = HexanaScriptLimits(),
            allowedFilePolicy = AllowedFilePolicy.allowAll,
        )
        val brokenScript = HexanaScriptSource.Inline(
            text = """
            hexana.defineFlags({
                mode: { long: "mode", values: ["a", "b"], default: "a" }
            });
            throw new Error("runtime error after defineFlags");
            """.trimIndent(),
            name = "broken.js",
        )
        val stubWasm = byteArrayOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)
        val result = runner.run(BinaryInput.RawBytes(stubWasm), brokenScript)
        assertEquals(HexanaScriptStatus.SCRIPT_ERROR, result.status)
        assertTrue(result.flagDeclarations.isNotEmpty(), "flagDeclarations must survive SCRIPT_ERROR")
        assertEquals(FlagType.VALUES, result.flagDeclarations["mode"]!!.type)
    }

    // ----- Execution layer: check-demo.js -----

    @Test
    fun checkDemoProducesFindings() {
        val result = runner().run(
            BinaryInput.FilePath(appDebugWasm),
            HexanaScriptSource.FilePath(checkDemoScript),
        )
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertTrue(result.findings.isNotEmpty(), "check-demo must produce at least one finding")
    }

    @Test
    fun checkDemoHasErrorFinding() {
        val result = runner().run(
            BinaryInput.FilePath(appDebugWasm),
            HexanaScriptSource.FilePath(checkDemoScript),
        )
        val errorFindings = result.findings.filter { it.severity == CheckSeverity.ERROR }
        assertTrue(errorFindings.isNotEmpty(),
            "check-demo must produce at least one ERROR finding against app-debug.wasm")
    }

    @Test
    fun checkDemoExitCodeIsOneWhenErrorFindingPresent() {
        val result = runner().run(
            BinaryInput.FilePath(appDebugWasm),
            HexanaScriptSource.FilePath(checkDemoScript),
        )
        val hasError = result.findings.any { it.severity == CheckSeverity.ERROR }
        val expectedCode = if (!result.success || hasError) 1 else 0
        assertEquals(1, expectedCode, "exit code must be 1 when ERROR findings present")
    }

    @Test
    fun checkDemoConsoleEntriesAreNonEmpty() {
        val result = runner().run(
            BinaryInput.FilePath(appDebugWasm),
            HexanaScriptSource.FilePath(checkDemoScript),
        )
        assertTrue(result.console.isNotEmpty(), "check-demo must emit at least one console entry")
    }

    @Test
    fun checkDemoWithExtraHelloWasmFindings() {
        val invocation = ScriptInvocation(
            argv = listOf(checkDemoScript),
            extraInputPaths = listOf(helloWasm),
        )
        val result = runner().run(
            BinaryInput.FilePath(appDebugWasm),
            HexanaScriptSource.FilePath(checkDemoScript),
            invocation = invocation,
        )
        assertEquals(HexanaScriptStatus.SUCCESS, result.status)
        assertTrue(result.findings.isNotEmpty())
    }
}
