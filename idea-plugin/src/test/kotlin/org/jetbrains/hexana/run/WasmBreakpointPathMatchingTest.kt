package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.cuPathEndsWithProjectRelative
import org.jetbrains.hexana.shared.cuPathMatchesIdePath
import org.jetbrains.hexana.shared.extractCrateName
import org.jetbrains.hexana.shared.functionNameMatchesIdePath
import org.jetbrains.hexana.shared.parseLldbFunctionName
import org.jetbrains.hexana.shared.pathMatchesBreakpointLocation
import org.jetbrains.hexana.shared.stripCuHashSuffix

/**
 * Tests for the path-matching logic used by the debugger to decide
 * whether a stop location belongs to a registered IDE breakpoint.
 *
 * These tests exercise [pathMatchesBreakpointLocation]
 * which is the core predicate that prevents false breakpoint hits when
 * multiple compilation units share the same relative DWARF filename
 * (e.g. `calculator/src/lib.rs` vs `adder/src/lib.rs`).
 */
class WasmBreakpointPathMatchingTest : UsefulTestCase() {

    // ---------------------------------------------------------------
    // Exact match
    // ---------------------------------------------------------------

    fun testExactPathMatch() {
        assertTrue(
            pathMatchesBreakpointLocation(
                "/Users/dev/calculator/src/lib.rs",
                "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    // ---------------------------------------------------------------
    // DWARF relative path as suffix of IDE absolute path
    // ---------------------------------------------------------------

    fun testDwarfRelativeMatchesIdeAbsolute() {
        assertTrue(
            pathMatchesBreakpointLocation(
                "src/lib.rs",
                "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    fun testDwarfRelativeDoesNotMatchDifferentDirectory() {
        // "src/lib.rs" should NOT match a breakpoint whose full path
        // doesn't end with "/src/lib.rs" preceded by a separator.
        assertFalse(
            pathMatchesBreakpointLocation(
                "src/lib.rs",
                "/Users/dev/other/main.rs",
            )
        )
    }

    // ---------------------------------------------------------------
    // IDE absolute path as suffix of resolved stopped path
    // ---------------------------------------------------------------

    fun testRelativeBreakpointPathAsSuffixOfStoppedPath() {
        // When the breakpoint was registered with a relative path
        // and the stop location has a longer resolved path.
        assertTrue(
            pathMatchesBreakpointLocation(
                "/Users/dev/calculator/src/lib.rs",
                "calculator/src/lib.rs",
            )
        )
    }

    // ---------------------------------------------------------------
    // Basename collision: same filename, different directories
    // ---------------------------------------------------------------

    fun testDifferentDirectoriesSameBasenameDoNotMatch() {
        // The critical case: calculator's lib.rs should not match
        // a breakpoint set in adder's lib.rs.
        assertFalse(
            pathMatchesBreakpointLocation(
                "/Users/dev/adder/src/lib.rs",
                "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    fun testDifferentDirectoriesSameBasenameReversed() {
        assertFalse(
            pathMatchesBreakpointLocation(
                "/Users/dev/calculator/src/lib.rs",
                "/Users/dev/adder/src/lib.rs",
            )
        )
    }

    // ---------------------------------------------------------------
    // Edge cases
    // ---------------------------------------------------------------

    fun testPartialBasenameSuffixDoesNotMatch() {
        // "lib.rs" should not match "mylib.rs" via suffix trick.
        assertFalse(
            pathMatchesBreakpointLocation(
                "lib.rs",
                "/Users/dev/project/src/mylib.rs",
            )
        )
    }

    fun testSameFilenameInNestedDirectories() {
        // Even with similar directory structure, only exact suffix
        // at a path-separator boundary should match.
        assertTrue(
            pathMatchesBreakpointLocation(
                "calculator/src/lib.rs",
                "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    fun testSameFilenameInDifferentNestedDirectories() {
        assertFalse(
            pathMatchesBreakpointLocation(
                "adder/src/lib.rs",
                "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    fun testCompilationUnitPathMatchesBreakpoint() {
        // Simulates the case where queryCompilationUnitPath returns
        // the full DWARF comp_dir + filename that matches the IDE
        // breakpoint path exactly.
        assertTrue(
            pathMatchesBreakpointLocation(
                "/Users/dev/calculator/src/lib.rs",
                "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    fun testCompilationUnitPathDoesNotMatchWrongBreakpoint() {
        // Simulates the case where the compilation-unit path reveals
        // the stop is in adder, not calculator.
        assertFalse(
            pathMatchesBreakpointLocation(
                "/Users/dev/adder/src/lib.rs",
                "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    // ---------------------------------------------------------------
    // cuPathMatchesIdePath — heuristic CU/IDE path comparison
    // (used as fallback when project base path is unavailable)
    // ---------------------------------------------------------------

    fun testCuPathMatchesSameCrateDirectory() {
        // CU and IDE paths share "calculator/src/lib.rs" (3 matching
        // trailing components) → consistent.
        assertTrue(
            cuPathMatchesIdePath(
                cuPath = "/build/calculator/src/lib.rs",
                idePath = "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    fun testCuPathMismatchDifferentCrateDirectory() {
        // CU says "adder" but IDE says "calculator" — only 2 trailing
        // components match ("src/lib.rs") → inconsistent.
        assertFalse(
            cuPathMatchesIdePath(
                cuPath = "/build/adder/src/lib.rs",
                idePath = "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    fun testCuPathMatchesShortCuPath() {
        // CU path has ≤ 2 components → optimistic true.
        assertTrue(
            cuPathMatchesIdePath(
                cuPath = "src/lib.rs",
                idePath = "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    fun testCuPathMatchesDeepNesting() {
        // Both paths share "calculator/src/lib.rs" despite different
        // multi-component roots.
        assertTrue(
            cuPathMatchesIdePath(
                cuPath = "/tmp/workspace/calculator/src/lib.rs",
                idePath = "/home/user/projects/calculator/src/lib.rs",
            )
        )
    }

    fun testCuPathMismatchDeepNesting() {
        // Different crate names with deep nesting.
        assertFalse(
            cuPathMatchesIdePath(
                cuPath = "/tmp/workspace/adder/src/lib.rs",
                idePath = "/home/user/projects/calculator/src/lib.rs",
            )
        )
    }

    fun testCuPathMatchesLongerInternalStructure() {
        // Crates with deeper internal structure still share at
        // least "calculator/rust/src/lib.rs" (4 components).
        assertTrue(
            cuPathMatchesIdePath(
                cuPath = "/build/calculator/rust/src/lib.rs",
                idePath = "/Users/dev/calculator/rust/src/lib.rs",
            )
        )
    }

    fun testCuPathMatchesWindowsPaths() {
        // Backslashes should be normalised; same crate shares
        // "calculator/src/lib.rs" (3 components).
        assertTrue(
            cuPathMatchesIdePath(
                cuPath = "C:\\build\\calculator\\src\\lib.rs",
                idePath = "C:\\Users\\dev\\calculator\\src\\lib.rs",
            )
        )
    }

    fun testCuPathMatchesExactPaths() {
        assertTrue(
            cuPathMatchesIdePath(
                cuPath = "/Users/dev/calculator/src/lib.rs",
                idePath = "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    fun testCuPathMatchesCuIsSuffixOfIde() {
        assertTrue(
            cuPathMatchesIdePath(
                cuPath = "calculator/src/lib.rs",
                idePath = "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    // ---------------------------------------------------------------
    // cuPathEndsWithProjectRelative — precise CU verification
    // (used at runtime with actual project base path)
    // ---------------------------------------------------------------

    fun testCuEndsWithProjectRelativeSameCrate() {
        assertTrue(
            cuPathEndsWithProjectRelative(
                cuPath = "/build/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    fun testCuEndsWithProjectRelativeDifferentCrate() {
        assertFalse(
            cuPathEndsWithProjectRelative(
                cuPath = "/build/adder/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    fun testCuEndsWithProjectRelativeDeepRoot() {
        assertTrue(
            cuPathEndsWithProjectRelative(
                cuPath = "/tmp/workspace/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    fun testCuEndsWithProjectRelativeDifferentCrateDeepRoot() {
        assertFalse(
            cuPathEndsWithProjectRelative(
                cuPath = "/tmp/workspace/adder/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    fun testCuEndsWithProjectRelativeBasenameOnly() {
        // CU for adder does not end with "calculator/src/lib.rs"
        assertFalse(
            cuPathEndsWithProjectRelative(
                cuPath = "/build/adder/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    fun testCuEndsWithProjectRelativeWindowsPaths() {
        assertTrue(
            cuPathEndsWithProjectRelative(
                cuPath = "C:\\build\\calculator\\src\\lib.rs",
                projectRelative = "calculator\\src\\lib.rs",
            )
        )
    }

    fun testCuEndsWithProjectRelativeDeeperInternal() {
        // Deeper internal structure: "adder/rust/src/lib.rs"
        // doesn't match "calculator/rust/src/lib.rs"
        assertFalse(
            cuPathEndsWithProjectRelative(
                cuPath = "/build/adder/rust/src/lib.rs",
                projectRelative = "calculator/rust/src/lib.rs",
            )
        )
    }

    fun testCuEndsWithProjectRelativeDeeperInternalSameCrate() {
        assertTrue(
            cuPathEndsWithProjectRelative(
                cuPath = "/build/calculator/rust/src/lib.rs",
                projectRelative = "calculator/rust/src/lib.rs",
            )
        )
    }

    fun testCuEndsWithProjectRelativeExactMatch() {
        // CU path equals the project-relative path (no root).
        assertTrue(
            cuPathEndsWithProjectRelative(
                cuPath = "calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    // ---------------------------------------------------------------
    // Combined verification: cuPathEndsWithProjectRelative with
    // cuPathMatchesIdePath fallback — mirrors the logic in
    // handleStopImpl for the usedSuffixMatching branch.
    //
    // The runtime code does:
    //   cuPathEndsWithProjectRelative(cu, projRel) ||
    //       cuPathMatchesIdePath(cu, idePath)
    //
    // These tests verify that short CU paths (like bare "lib.rs")
    // do NOT cause legitimate breakpoint hits to be rejected.
    // ---------------------------------------------------------------

    /**
     * Helper that mirrors the combined verification logic from
     * [WasmDebugProcess.handleStopImpl]: precise check first, then
     * heuristic fallback.
     */
    private fun combinedVerification(cuPath: String, idePath: String, projectRelative: String): Boolean =
        cuPathEndsWithProjectRelative(cuPath, projectRelative) ||
            cuPathMatchesIdePath(cuPath, idePath)

    fun testCombinedVerificationShortCuPathBasenameOnly() {
        // CU path is just "lib.rs" (DWARF basename only) — too short
        // for precise check but heuristic should be optimistic.
        // This is the exact scenario from the bug report.
        assertTrue(
            combinedVerification(
                cuPath = "lib.rs",
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    fun testCombinedVerificationShortCuPathSrcRelative() {
        // CU path is "src/lib.rs" — still too short for precise check,
        // heuristic should be optimistic (≤ 2 components).
        assertTrue(
            combinedVerification(
                cuPath = "src/lib.rs",
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    fun testCombinedVerificationLongCuPathSameCrate() {
        // CU path includes crate dir — precise check succeeds directly.
        assertTrue(
            combinedVerification(
                cuPath = "/build/calculator/src/lib.rs",
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    fun testCombinedVerificationLongCuPathDifferentCrate() {
        // CU path says adder, IDE says calculator — both precise and
        // heuristic reject this (3-component match fails).
        assertFalse(
            combinedVerification(
                cuPath = "/build/adder/src/lib.rs",
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    fun testCombinedVerificationCuPathIsCrateSrcFile() {
        // CU path is exactly "calculator/src/lib.rs" — precise check
        // succeeds (exact match).
        assertTrue(
            combinedVerification(
                cuPath = "calculator/src/lib.rs",
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    fun testCombinedVerificationCuPathIsDifferentCrateSrcFile() {
        // CU path is "adder/src/lib.rs" — precise check fails
        // (doesn't end with "calculator/src/lib.rs"), heuristic also
        // fails (3 components, first mismatch at crate level → 2
        // matching components < 3).
        assertFalse(
            combinedVerification(
                cuPath = "adder/src/lib.rs",
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
            )
        )
    }

    // ---------------------------------------------------------------
    // extractCrateName: crate name extraction from Rust mangled
    // function symbols as reported by LLDB.
    // ---------------------------------------------------------------

    fun testExtractCrateNameComponentModelCalculator() {
        assertEquals(
            "calculator",
            extractCrateName(
                "_\$LT\$calculator..Component\$u20\$as\$u20\$calculator" +
                    "..bindings..exports..component..calculator..calculate" +
                    "..Guest\$GT\$::calculate::hfae709776712b47f"
            )
        )
    }

    fun testExtractCrateNameComponentModelAdder() {
        assertEquals(
            "adder",
            extractCrateName(
                "_\$LT\$adder..Component\$u20\$as\$u20\$adder" +
                    "..bindings..exports..component..adder..add" +
                    "..Guest\$GT\$::add::h1c828aaccb5c9012"
            )
        )
    }

    fun testExtractCrateNamePlainRustSymbol() {
        assertEquals(
            "some_module",
            extractCrateName("some_module::some_function::h123456")
        )
    }

    fun testExtractCrateNameShortPlainSymbol() {
        assertEquals(
            "add",
            extractCrateName("add::hdeadbeef")
        )
    }

    fun testExtractCrateNameNoMatch() {
        assertNull(
            extractCrateName("just_a_bare_function")
        )
    }

    // ---------------------------------------------------------------
    // functionNameMatchesIdePath: verify the crate name from the
    // function symbol appears as a directory in the IDE path.
    // ---------------------------------------------------------------

    fun testFunctionNameMatchesIdePathCalculator() {
        assertTrue(
            functionNameMatchesIdePath(
                "_\$LT\$calculator..Component\$u20\$as\$u20\$calculator" +
                    "..bindings..exports..component..calculator..calculate" +
                    "..Guest\$GT\$::calculate::hfae709776712b47f",
                "/Users/dev/project/calculator/src/lib.rs",
            )
        )
    }

    fun testFunctionNameDoesNotMatchDifferentCrate() {
        // Function says "adder" but IDE path is calculator.
        assertFalse(
            functionNameMatchesIdePath(
                "_\$LT\$adder..Component\$u20\$as\$u20\$adder" +
                    "..bindings..exports..component..adder..add" +
                    "..Guest\$GT\$::add::h1c828aaccb5c9012",
                "/Users/dev/project/calculator/src/lib.rs",
            )
        )
    }

    fun testFunctionNameMatchesIdePathPlainSymbol() {
        assertTrue(
            functionNameMatchesIdePath(
                "calculator::calculate::h123456",
                "/Users/dev/project/calculator/src/lib.rs",
            )
        )
    }

    fun testFunctionNameNoMatchWhenNoPatternRecognised() {
        // Bare function name without :: or $LT$ → cannot extract
        // crate → returns false (conservative).
        assertFalse(
            functionNameMatchesIdePath(
                "bare_function",
                "/Users/dev/project/calculator/src/lib.rs",
            )
        )
    }

    fun testFunctionNameMatchesCaseInsensitive() {
        assertTrue(
            functionNameMatchesIdePath(
                "_\$LT\$Calculator..Component\$GT\$::calc::h123",
                "/Users/dev/project/calculator/src/lib.rs",
            )
        )
    }

    fun testFunctionNameMatchesWindowsPath() {
        assertTrue(
            functionNameMatchesIdePath(
                "_\$LT\$calculator..Component\$GT\$::calc::h123",
                "C:\\Users\\dev\\project\\calculator\\src\\lib.rs",
            )
        )
    }

    // ---------------------------------------------------------------
    // Full combined verification with function-name fallback:
    // mirrors the three-strategy logic in handleStopImpl where
    // CU path checks fail but function name saves the day.
    // ---------------------------------------------------------------

    /**
     * Helper that mirrors the full three-strategy verification logic
     * from [WasmDebugProcess.handleStopImpl]: CU path checks first,
     * then function name fallback.
     */
    private fun fullVerification(
        cuPath: String?,
        idePath: String,
        projectRelative: String?,
        functionName: String?,
    ): Boolean {
        var verified = false
        if (cuPath != null) {
            verified = if (projectRelative != null) {
                cuPathEndsWithProjectRelative(cuPath, projectRelative) ||
                    cuPathMatchesIdePath(cuPath, idePath)
            } else {
                cuPathMatchesIdePath(cuPath, idePath)
            }
        }
        if (!verified && functionName != null) {
            verified = functionNameMatchesIdePath(functionName, idePath)
        }
        return verified
    }

    fun testFullVerificationCuPathFailsFunctionNameSaves() {
        // The exact bug scenario: CU path from a different build root
        // doesn't match the IDE project layout, but function name
        // contains the correct crate name.
        assertTrue(
            fullVerification(
                cuPath = "/tmp/rust-build/src/lib.rs",
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
                functionName = "_\$LT\$calculator..Component\$u20\$as\$u20\$calculator" +
                    "..bindings..exports..component..calculator..calculate" +
                    "..Guest\$GT\$::calculate::hfae709776712b47f",
            )
        )
    }

    fun testFullVerificationCuPathFailsFunctionNameRejectsWrongCrate() {
        // CU path inconclusive AND function name says wrong crate →
        // correctly rejected.
        assertFalse(
            fullVerification(
                cuPath = "/tmp/rust-build/src/lib.rs",
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
                functionName = "_\$LT\$adder..Component\$u20\$as\$u20\$adder" +
                    "..bindings..exports..component..adder..add" +
                    "..Guest\$GT\$::add::h1c828aaccb5c9012",
            )
        )
    }

    fun testFullVerificationCuPathSucceedsDirectly() {
        // CU path matches — function name is not even needed.
        assertTrue(
            fullVerification(
                cuPath = "/build/calculator/src/lib.rs",
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
                functionName = null,
            )
        )
    }

    fun testFullVerificationNoCuPathFunctionNameSaves() {
        // No CU path available at all — function name is the only
        // verification signal.
        assertTrue(
            fullVerification(
                cuPath = null,
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
                functionName = "_\$LT\$calculator..Component\$GT\$::calc::h123",
            )
        )
    }

    fun testFullVerificationNoCuPathNoFunctionName() {
        // Neither CU path nor function name available → not verified.
        assertFalse(
            fullVerification(
                cuPath = null,
                idePath = "/Users/dev/project/calculator/src/lib.rs",
                projectRelative = "calculator/src/lib.rs",
                functionName = null,
            )
        )
    }

    // ---------------------------------------------------------------
    // parseFunctionName: extracting the function name from LLDB
    // frame output, including lines with parenthesized arguments.
    // ---------------------------------------------------------------

    fun testParseFunctionNameWithArgs() {
        // The exact LLDB output from the bug report — function name
        // followed by (args) before "at".
        val lines = listOf(
            "frame #0: 0x000000010806061c JIT(0x58d000000)" +
                "`_\$LT\$calculator..Component\$u20\$as\$u20\$calculator" +
                "..bindings..exports..component..calculator..calculate" +
                "..Guest\$GT\$::calculate::hfae709776712b47f" +
                "(op=<unavailable>, x=<unavailable>, y=<unavailable>) at lib.rs:11:9"
        )
        assertEquals(
            "_\$LT\$calculator..Component\$u20\$as\$u20\$calculator" +
                "..bindings..exports..component..calculator..calculate" +
                "..Guest\$GT\$::calculate::hfae709776712b47f",
            parseLldbFunctionName(lines),
        )
    }

    fun testParseFunctionNameWithArgsAdder() {
        // The adder variant from the bug report.
        val lines = listOf(
            "frame #0: 0x0000000108071ffc JIT(0x497000000)" +
                "`_\$LT\$adder..Component\$u20\$as\$u20\$adder" +
                "..bindings..exports..component..adder..add" +
                "..Guest\$GT\$::add::h1c828aaccb5c9012" +
                "(x=<unavailable>, y=<unavailable>) at lib.rs:11:6"
        )
        assertEquals(
            "_\$LT\$adder..Component\$u20\$as\$u20\$adder" +
                "..bindings..exports..component..adder..add" +
                "..Guest\$GT\$::add::h1c828aaccb5c9012",
            parseLldbFunctionName(lines),
        )
    }

    fun testParseFunctionNameWithoutArgs() {
        // No parenthesized arguments — function name directly before "at".
        val lines = listOf(
            "frame #0: 0x... JIT(0x...)`some_func at lib.rs:11:9"
        )
        assertEquals("some_func", parseLldbFunctionName(lines))
    }

    fun testParseFunctionNameWithEmptyArgs() {
        val lines = listOf(
            "frame #0: 0x... JIT(0x...)`some_func() at lib.rs:1"
        )
        assertEquals("some_func", parseLldbFunctionName(lines))
    }

    fun testParseFunctionNameWithOffset() {
        val lines = listOf(
            "frame #0: 0x... JIT(0x...)`some_func + 42"
        )
        assertEquals("some_func", parseLldbFunctionName(lines))
    }

    fun testParseFunctionNameNoMatch() {
        val lines = listOf("some random output without frame info")
        assertNull(parseLldbFunctionName(lines))
    }

    // ---------------------------------------------------------------
    // parseFunctionName: classic wasm modules (no backtick between
    // JIT module identifier and function name).
    // ---------------------------------------------------------------

    fun testParseFunctionNameClassicWasmWithArgs() {
        // Classic wasm module frame — no backtick separator.
        val lines = listOf(
            "frame #0: 0x0000000107f20308 JIT(0x991400000)" +
                "hello_world::add::hcfbbdf8271862188" +
                "(a=<unavailable>, b=<unavailable>) at lib.rs:10:12"
        )
        assertEquals(
            "hello_world::add::hcfbbdf8271862188",
            parseLldbFunctionName(lines),
        )
    }

    fun testParseFunctionNameClassicWasmWithoutArgs() {
        // Classic wasm module frame without parenthesized arguments.
        val lines = listOf(
            "frame #0: 0x0000000107f20308 JIT(0x991400000)" +
                "hello_world::add::hcfbbdf8271862188 at lib.rs:10:12"
        )
        assertEquals(
            "hello_world::add::hcfbbdf8271862188",
            parseLldbFunctionName(lines),
        )
    }

    fun testParseFunctionNameClassicWasmWithOffset() {
        // Classic wasm module frame with offset instead of source location.
        val lines = listOf(
            "frame #0: 0x0000000107f20308 JIT(0x991400000)" +
                "hello_world::add::hcfbbdf8271862188 + 42"
        )
        assertEquals(
            "hello_world::add::hcfbbdf8271862188",
            parseLldbFunctionName(lines),
        )
    }

    fun testExtractCrateNameClassicWasm() {
        // Plain Rust symbol from classic wasm module: crate::func::hash.
        assertEquals(
            "hello_world",
            extractCrateName("hello_world::add::hcfbbdf8271862188"),
        )
    }

    // ---------------------------------------------------------------
    // stripCuHashSuffix: LLDB appends `/@/<hash>` to CU paths for
    // classic wasm JIT compilation units.
    // ---------------------------------------------------------------

    fun testStripCuHashSuffix() {
        assertEquals(
            "/Users/dev/project/src/lib.rs",
            stripCuHashSuffix(
                "/Users/dev/project/src/lib.rs/@/9le4nom1b1hdoeh9b7lbjqyuq"
            ),
        )
    }

    fun testStripCuHashSuffixNoHash() {
        // Normal CU paths without hash suffix are returned unchanged.
        assertEquals(
            "/Users/dev/project/src/lib.rs",
            stripCuHashSuffix("/Users/dev/project/src/lib.rs"),
        )
    }

    fun testStripCuHashSuffixPreservesAtInPath() {
        // Literal '@' not followed by `/<hash>` pattern is preserved.
        assertEquals(
            "/Users/user@host/src/lib.rs",
            stripCuHashSuffix("/Users/user@host/src/lib.rs"),
        )
    }

    // ---------------------------------------------------------------
    // CU path verification with hash-stripped paths: after stripping
    // the hash, cuPathMatchesIdePath should accept the path.
    // ---------------------------------------------------------------

    fun testCuPathMatchesAfterHashStrip() {
        val cuRaw = "/Users/dev/project/src/lib.rs/@/9le4nom1b1hdoeh9b7lbjqyuq"
        val stripped = stripCuHashSuffix(cuRaw)
        val idePath = "/Users/dev/project/src/lib.rs"
        assertTrue(cuPathMatchesIdePath(stripped, idePath))
    }

    // ---------------------------------------------------------------
    // functionNameMatchesIdePath: Rust crate name hyphen/underscore
    // normalisation. Cargo crate "hello-world" becomes `hello_world`
    // in symbol names; the IDE path retains the original hyphen.
    // ---------------------------------------------------------------

    fun testFunctionNameMatchesIdePathWithHyphenatedCrate() {
        assertTrue(
            functionNameMatchesIdePath(
                "hello_world::add::hcfbbdf8271862188",
                "/Users/dev/hello-world/src/lib.rs",
            )
        )
    }

    fun testFunctionNameMatchesIdePathWithUnderscoreCrate() {
        // When the directory already uses underscores, the direct
        // match should still work.
        assertTrue(
            functionNameMatchesIdePath(
                "hello_world::add::hcfbbdf8271862188",
                "/Users/dev/hello_world/src/lib.rs",
            )
        )
    }

    fun testFunctionNameDoesNotMatchDifferentCrateClassicWasm() {
        assertFalse(
            functionNameMatchesIdePath(
                "hello_world::add::hcfbbdf8271862188",
                "/Users/dev/other_crate/src/lib.rs",
            )
        )
    }

    // ---------------------------------------------------------------
    // Full combined verification for classic wasm: CU hash suffix
    // stripped + hyphenated crate name normalisation.
    // ---------------------------------------------------------------

    fun testFullVerificationClassicWasmWithCuHashAndHyphenatedCrate() {
        // Exact scenario from the bug report: CU path has /@/<hash>,
        // crate is "hello-world" (hyphen) but symbol uses "hello_world".
        val cuRaw = "/Users/dev/hello-world/src/lib.rs/@/9le4nom1b1hdoeh9b7lbjqyuq"
        val cuStripped = stripCuHashSuffix(cuRaw)
        assertTrue(
            fullVerification(
                cuPath = cuStripped,
                idePath = "/Users/dev/hello-world/src/lib.rs",
                projectRelative = "hello-world/src/lib.rs",
                functionName = "hello_world::add::hcfbbdf8271862188",
            )
        )
    }
}
