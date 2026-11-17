package org.jetbrains.hexana.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DwarfPathMatcherTest {

    // ── pathMatchesBreakpointLocation ────────────────────────────────

    @Test
    fun exactMatch() {
        assertTrue(pathMatchesBreakpointLocation("/home/user/src/lib.rs", "/home/user/src/lib.rs"))
    }

    @Test
    fun dwarfRelativeMatchesIdeAbsolute() {
        assertTrue(
            pathMatchesBreakpointLocation(
                "src/lib.rs",
                "/home/user/calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun ideAbsoluteMatchesDwarfPath() {
        assertTrue(
            pathMatchesBreakpointLocation(
                "/home/user/calculator/src/lib.rs",
                "src/lib.rs",
            )
        )
    }

    @Test
    fun noMatch() {
        assertFalse(
            pathMatchesBreakpointLocation(
                "other/lib.rs",
                "/home/user/calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun windowsBackslashMatchesForwardSlash() {
        assertTrue(
            pathMatchesBreakpointLocation(
                "E:\\project\\src\\lib.rs",
                "E:/project/src/lib.rs",
            )
        )
    }

    @Test
    fun windowsBackslashSuffixMatch() {
        assertTrue(
            pathMatchesBreakpointLocation(
                "src\\lib.rs",
                "E:/project/src/lib.rs",
            )
        )
    }

    // ── cuPathMatchesIdePath ────────────────────────────────────────

    @Test
    fun cuExactMatch() {
        assertTrue(
            cuPathMatchesIdePath(
                "/build/calculator/src/lib.rs",
                "/build/calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun cuSameFilesDifferentRoots() {
        assertTrue(
            cuPathMatchesIdePath(
                "/build/calculator/src/lib.rs",
                "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun cuDifferentCrates() {
        assertFalse(
            cuPathMatchesIdePath(
                "/build/adder/src/lib.rs",
                "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun cuCrateRootLayoutSameCrateDir() {
        // Crate-root layout (no src/): the directory above the file is
        // crate-identifying, so a 2-component tail confirms identity.
        // Regression: dwarf-memvars fixture built in /private/tmp but
        // debugged from a copy under the project's .cache.
        assertTrue(
            cuPathMatchesIdePath(
                "/private/tmp/dwarf-memvars/lib.rs",
                "/Users/dev/project/.cache/nodejs-debug-test/dwarf-memvars/lib.rs",
            )
        )
    }

    @Test
    fun cuCrateRootLayoutGenericDirStillRejected() {
        // A 2-component tail whose directory is a generic source dir
        // (src, tests, ...) identifies nothing — must stay rejected.
        assertFalse(
            cuPathMatchesIdePath(
                "/build/adder/tests/common.rs",
                "/Users/dev/calculator/tests/common.rs",
            )
        )
    }

    @Test
    fun cuShortPathOptimistic() {
        assertTrue(
            cuPathMatchesIdePath(
                "src/lib.rs",
                "/Users/dev/calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun cuOneSuffix() {
        assertTrue(
            cuPathMatchesIdePath(
                "calculator/src/lib.rs",
                "/Users/dev/project/calculator/src/lib.rs",
            )
        )
    }

    // ── cuPathEndsWithProjectRelative ────────────────────────────────

    @Test
    fun cuEndsWithProjectRelative() {
        assertTrue(
            cuPathEndsWithProjectRelative(
                "/build/calculator/src/lib.rs",
                "calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun cuDoesNotEndWithProjectRelative() {
        assertFalse(
            cuPathEndsWithProjectRelative(
                "/build/adder/src/lib.rs",
                "calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun cuExactMatchProjectRelative() {
        assertTrue(
            cuPathEndsWithProjectRelative(
                "calculator/src/lib.rs",
                "calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun cuWindowsBackslashes() {
        assertTrue(
            cuPathEndsWithProjectRelative(
                "C:\\build\\calculator\\src\\lib.rs",
                "calculator\\src\\lib.rs",
            )
        )
    }

    // ── extractCrateName ────────────────────────────────────────────

    @Test
    fun extractCrateFromComponentSymbol() {
        assertEquals(
            "calculator",
            extractCrateName(
                "_\$LT\$calculator..Component\$u20\$as\$u20\$bindings..Guest\$GT\$::calculate::h123"
            )
        )
    }

    @Test
    fun extractCrateFromPlainSymbol() {
        assertEquals(
            "some_module",
            extractCrateName("some_module::some_function::h123456")
        )
    }

    @Test
    fun extractCrateFromSimpleSymbol() {
        assertEquals(
            "add",
            extractCrateName("add::hdeadbeef")
        )
    }

    @Test
    fun extractCrateNullForBareFunction() {
        assertNull(extractCrateName("just_a_bare_function"))
    }

    // ── functionNameMatchesIdePath ───────────────────────────────────

    @Test
    fun functionNameMatchesCrate() {
        assertTrue(
            functionNameMatchesIdePath(
                "_\$LT\$calculator..Component\$u20\$as\$u20\$...\$GT\$::calculate::h123",
                "/Users/dev/project/calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun functionNameDoesNotMatch() {
        assertFalse(
            functionNameMatchesIdePath(
                "_\$LT\$adder..Component\$u20\$as\$u20\$...\$GT\$::add::h123",
                "/Users/dev/project/calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun functionNameHyphenatedCrate() {
        assertTrue(
            functionNameMatchesIdePath(
                "hello_world::add::hcfbbdf8271862188",
                "/Users/dev/project/hello-world/src/lib.rs",
            )
        )
    }

    // ── stripCuHashSuffix ───────────────────────────────────────────

    @Test
    fun stripHashSuffix() {
        assertEquals(
            "/path/src/lib.rs",
            stripCuHashSuffix("/path/src/lib.rs/@/9le4nom1b1hdoeh9b7lbjqyuq")
        )
    }

    @Test
    fun noHashSuffix() {
        assertEquals(
            "/Users/dev/project/src/lib.rs",
            stripCuHashSuffix("/Users/dev/project/src/lib.rs")
        )
    }

    @Test
    fun atSignInPath() {
        assertEquals(
            "/Users/user@host/src/lib.rs",
            stripCuHashSuffix("/Users/user@host/src/lib.rs")
        )
    }

    @Test
    fun stripRustCguSuffix() {
        assertEquals(
            "/Users/dev/project/debug_add.rs",
            stripCuHashSuffix(
                "/Users/dev/project/debug_add.rs/@/debug_add.d8f51c9c58bd83ee-cgu.0"
            )
        )
    }

    // ── extractProjectRelativePath ──────────────────────────────────

    @Test
    fun extractRelativePath() {
        assertEquals(
            "calculator/src/lib.rs",
            extractProjectRelativePath(
                "/Users/dev/project",
                "/Users/dev/project/calculator/src/lib.rs",
            )
        )
    }

    @Test
    fun extractRelativePathNotUnderBase() {
        assertNull(
            extractProjectRelativePath(
                "/Users/dev/project",
                "/home/other/file.rs",
            )
        )
    }
}
