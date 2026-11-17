package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase

class WasmDebugRunnerLldbResolutionTest : UsefulTestCase() {
    fun testAppleSiliconStandaloneLldbWinsOverLegacyLlvmAndAppleLldb() {
        val installed = setOf(
            "/opt/homebrew/opt/lldb/bin/lldb",
            "/opt/homebrew/opt/llvm/bin/lldb",
            "/usr/bin/lldb",
        )

        assertEquals("/opt/homebrew/opt/lldb/bin/lldb", WasmDebugRunner.resolveMacOsLldbPath(installed::contains))
    }

    fun testIntelStandaloneLldbWinsOverLegacyLlvmAndAppleLldb() {
        val installed = setOf(
            "/usr/local/opt/lldb/bin/lldb",
            "/usr/local/opt/llvm/bin/lldb",
            "/usr/bin/lldb",
        )

        assertEquals("/usr/local/opt/lldb/bin/lldb", WasmDebugRunner.resolveMacOsLldbPath(installed::contains))
    }

    fun testFreshCiInstallationUsesStandaloneLldbWhenLlvmNoLongerBundlesIt() {
        val installed = setOf("/opt/homebrew/opt/lldb/bin/lldb", "/usr/bin/lldb")

        assertEquals("/opt/homebrew/opt/lldb/bin/lldb", WasmDebugRunner.resolveMacOsLldbPath(installed::contains))
    }

    fun testLegacyHomebrewLlvmRemainsSupported() {
        for (prefix in listOf("/opt/homebrew", "/usr/local")) {
            val expected = "$prefix/opt/llvm/bin/lldb"
            val installed = setOf(expected, "/usr/bin/lldb")

            assertEquals(expected, WasmDebugRunner.resolveMacOsLldbPath(installed::contains))
        }
    }

    fun testAppleLldbRemainsTheLastKnownLocation() {
        assertEquals("/usr/bin/lldb", WasmDebugRunner.resolveMacOsLldbPath { it == "/usr/bin/lldb" })
    }

    fun testMissingKnownLocationsFallBackToPathLookup() {
        assertEquals("lldb", WasmDebugRunner.resolveMacOsLldbPath { false })
    }
}
