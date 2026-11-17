package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase

/**
 * Pins for [buildSuiteCliCommand], [parseArtifactEntries], and [proxyPathOf].
 * Pure-function tests: no platform runtime, no GraalJS.
 */
class SuiteRunConfigurationCliTest : UsefulTestCase() {

    // ----- buildSuiteCliCommand -----

    fun testMinimalCommand() {
        val cmd = buildSuiteCliCommand(
            suitePath = "/suites/math.test.js",
            artifacts = emptyList(),
            libRoot = null,
            seed = null,
            testTimeoutMillis = null,
        )
        assertEquals(listOf("hexana-cli-script", "test", "/suites/math.test.js"), cmd)
    }

    fun testAllFlagsPinnedOrder() {
        val cmd = buildSuiteCliCommand(
            suitePath = "/suites",
            artifacts = listOf("app" to "/bin/app.wasm", "baseline" to "/bin/base.wasm"),
            libRoot = "/libs",
            seed = "c0ffee",
            testTimeoutMillis = 12_000,
        )
        assertEquals(
            listOf(
                "hexana-cli-script", "test", "/suites",
                "--artifact", "app=/bin/app.wasm",
                "--artifact", "baseline=/bin/base.wasm",
                "--lib-root", "/libs",
                "--seed", "c0ffee",
                "--test-timeout", "12000",
            ),
            cmd,
        )
    }

    fun testDefaultsOmitted() {
        val cmd = buildSuiteCliCommand(
            suitePath = "/s.test.js",
            artifacts = emptyList(),
            libRoot = "",
            seed = "",
            testTimeoutMillis = null,
        )
        // Empty libRoot/seed and null timeout emit no flags at all.
        assertEquals(listOf("hexana-cli-script", "test", "/s.test.js"), cmd)
    }

    fun testSingleArtifactOnly() {
        val cmd = buildSuiteCliCommand(
            suitePath = "/s.test.js",
            artifacts = listOf("app" to "/a.wasm"),
            libRoot = null,
            seed = null,
            testTimeoutMillis = null,
        )
        assertEquals(
            listOf("hexana-cli-script", "test", "/s.test.js", "--artifact", "app=/a.wasm"),
            cmd,
        )
    }

    // ----- parseArtifactEntries -----

    fun testParseArtifactEntriesMergesStoredAndFreeForm() {
        val entries = parseArtifactEntries(
            listOf("app=/a.wasm"),
            "baseline=/b.wasm\n\n  extra=/c.elf  \n",
        )
        assertEquals(
            listOf("app" to "/a.wasm", "baseline" to "/b.wasm", "extra" to "/c.elf"),
            entries,
        )
    }

    fun testParseArtifactEntriesLaterWinsAndSkipsMalformed() {
        val entries = parseArtifactEntries(
            listOf("app=/old.wasm", "=nopath", "norole"),
            "app=/new.wasm\ndangling=",
        )
        assertEquals(listOf("app" to "/new.wasm"), entries)
    }

    fun testParseArtifactEntriesPathMayContainEquals() {
        val entries = parseArtifactEntries(emptyList(), "app=/dir/name=1/a.wasm")
        assertEquals(listOf("app" to "/dir/name=1/a.wasm"), entries)
    }

    fun testParseArtifactEntriesEmpty() {
        assertEquals(emptyList<Pair<String, String>>(), parseArtifactEntries(emptyList(), ""))
    }

    // ----- proxyPathOf (rerun-failed describe-path mapping) -----

    fun testProxyPathOfTopLevelTest() {
        assertEquals(listOf("adds"), proxyPathOf(listOf("math.test.js", "adds")))
    }

    fun testProxyPathOfNestedTest() {
        assertEquals(
            listOf("math", "vectors", "adds"),
            proxyPathOf(listOf("math.test.js", "math", "vectors", "adds")),
        )
    }

    fun testProxyPathOfFileSuiteOnlyIsNull() {
        assertNull(proxyPathOf(listOf("math.test.js")))
        assertNull(proxyPathOf(emptyList()))
    }
}
