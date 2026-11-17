package org.jetbrains.hexana.run

import com.intellij.testFramework.UsefulTestCase
import org.jetbrains.hexana.shared.scripting.FlagSpec
import org.jetbrains.hexana.shared.scripting.FlagType

/**
 * Tests for [buildCliCommand] and [buildFlagArgvTokens].
 * Pure-function tests: no platform runtime needed.
 */
class ScriptRunConfigurationCliTest : UsefulTestCase() {

    // ----- buildCliCommand -----

    fun testBuildCliCommandMinimal() {
        val cmd = buildCliCommand(
            scriptPath = "/path/to/check.js",
            inputPaths = listOf("/path/to/app.wasm"),
            baselinesDir = "",
            flagTokens = emptyList(),
        )
        assertEquals(
            listOf("hexana-cli-script", "run-script", "/path/to/app.wasm", "--script", "/path/to/check.js"),
            cmd,
        )
    }

    fun testBuildCliCommandWithBaselineDir() {
        val cmd = buildCliCommand(
            scriptPath = "/path/to/check.js",
            inputPaths = listOf("/path/to/app.wasm"),
            baselinesDir = "/tmp/baselines",
            flagTokens = emptyList(),
        )
        assertTrue(cmd.contains("--baselines-dir"))
        val idx = cmd.indexOf("--baselines-dir")
        assertEquals("/tmp/baselines", cmd[idx + 1])
    }

    fun testBuildCliCommandWithMultipleInputs() {
        val cmd = buildCliCommand(
            scriptPath = "/s.js",
            inputPaths = listOf("/a.wasm", "/b.wasm", "/c.wasm"),
            baselinesDir = "",
            flagTokens = emptyList(),
        )
        // primary is positional
        assertEquals("/a.wasm", cmd[2])
        // extras via --input
        val inputIdx1 = cmd.indexOf("--input")
        assertEquals("/b.wasm", cmd[inputIdx1 + 1])
        val inputIdx2 = cmd.lastIndexOf("--input")
        assertEquals("/c.wasm", cmd[inputIdx2 + 1])
    }

    fun testBuildCliCommandInputOrdering() {
        val cmd = buildCliCommand(
            scriptPath = "/s.js",
            inputPaths = listOf("/first.wasm", "/second.wasm"),
            baselinesDir = "",
            flagTokens = emptyList(),
        )
        // Exactly one --input for the second file
        assertEquals(1, cmd.count { it == "--input" })
        val idx = cmd.indexOf("--input")
        assertEquals("/second.wasm", cmd[idx + 1])
    }

    fun testBuildCliCommandWithFlagTokens() {
        val cmd = buildCliCommand(
            scriptPath = "/s.js",
            inputPaths = listOf("/a.wasm"),
            baselinesDir = "",
            flagTokens = listOf("--mode", "fast", "--limit", "42"),
        )
        assertTrue(cmd.contains("--"))
        val dashIdx = cmd.indexOf("--")
        assertEquals("--mode", cmd[dashIdx + 1])
        assertEquals("fast", cmd[dashIdx + 2])
        assertEquals("--limit", cmd[dashIdx + 3])
        assertEquals("42", cmd[dashIdx + 4])
    }

    fun testBuildCliCommandBoolFlag() {
        val cmd = buildCliCommand(
            scriptPath = "/s.js",
            inputPaths = listOf("/a.wasm"),
            baselinesDir = "",
            flagTokens = listOf("--verbose"),
        )
        val dashIdx = cmd.indexOf("--")
        assertEquals("--verbose", cmd[dashIdx + 1])
    }

    fun testBuildCliCommandNoPrimaryInput() {
        // No inputs: no positional arg
        val cmd = buildCliCommand(
            scriptPath = "/s.js",
            inputPaths = emptyList(),
            baselinesDir = "",
            flagTokens = emptyList(),
        )
        assertEquals(listOf("hexana-cli-script", "run-script", "--script", "/s.js"), cmd)
    }

    // ----- buildFlagArgvTokens -----

    fun testFlagArgvTokensFromDegradedModel() {
        val model = FlagsFormModel.Empty
        val tokens = buildFlagArgvTokens(model, "--mode fast --limit 10")
        assertEquals(listOf("--mode", "fast", "--limit", "10"), tokens)
    }

    fun testFlagArgvTokensEmptyFreeForm() {
        val model = FlagsFormModel.Empty
        val tokens = buildFlagArgvTokens(model, "")
        assertEquals(emptyList<String>(), tokens)
    }

    fun testFlagArgvTokensStringFlag() {
        val rows = mutableListOf(
            FlagRow("mode", FlagSpec(short = null, long = "mode", type = FlagType.STRING, values = emptyList(), default = "auto"), "fast"),
        )
        val model = FlagsFormModel(rows = rows, degraded = false)
        val tokens = buildFlagArgvTokens(model, "")
        assertEquals(listOf("--mode", "fast"), tokens)
    }

    fun testFlagArgvTokensBoolTrueFlag() {
        val rows = mutableListOf(
            FlagRow("verbose", FlagSpec(short = null, long = "verbose", type = FlagType.BOOL, values = emptyList(), default = "false"), "true"),
        )
        val model = FlagsFormModel(rows = rows, degraded = false)
        val tokens = buildFlagArgvTokens(model, "")
        assertEquals(listOf("--verbose"), tokens)
    }

    fun testFlagArgvTokensBoolFalseFlag() {
        val rows = mutableListOf(
            FlagRow("verbose", FlagSpec(short = null, long = "verbose", type = FlagType.BOOL, values = emptyList(), default = "false"), "false"),
        )
        val model = FlagsFormModel(rows = rows, degraded = false)
        val tokens = buildFlagArgvTokens(model, "")
        // bool=false -> no token emitted
        assertEquals(emptyList<String>(), tokens)
    }

    fun testFlagArgvTokensIntFlag() {
        val rows = mutableListOf(
            FlagRow("limit", FlagSpec(short = null, long = "limit", type = FlagType.INT, values = emptyList(), default = "10"), "42"),
        )
        val model = FlagsFormModel(rows = rows, degraded = false)
        val tokens = buildFlagArgvTokens(model, "")
        assertEquals(listOf("--limit", "42"), tokens)
    }

    fun testFlagArgvTokensValuesFlag() {
        val rows = mutableListOf(
            FlagRow("mode", FlagSpec(short = null, long = "mode", type = FlagType.VALUES, values = listOf("fast", "slow", "auto"), default = "auto"), "slow"),
        )
        val model = FlagsFormModel(rows = rows, degraded = false)
        val tokens = buildFlagArgvTokens(model, "")
        assertEquals(listOf("--mode", "slow"), tokens)
    }

    fun testFlagArgvTokensSkipsNullValue() {
        val rows = mutableListOf(
            FlagRow("file", FlagSpec(short = null, long = "file", type = FlagType.PATH, values = emptyList(), default = null), null),
        )
        val model = FlagsFormModel(rows = rows, degraded = false)
        val tokens = buildFlagArgvTokens(model, "")
        assertEquals(emptyList<String>(), tokens)
    }

    // ----- FlagsFormModel -----

    fun testFlagsFormModelFromDeclarations() {
        val declarations = mapOf(
            "mode" to FlagSpec(short = null, long = "mode", type = FlagType.VALUES, values = listOf("a", "b"), default = "a"),
            "limit" to FlagSpec(short = null, long = "limit", type = FlagType.INT, values = emptyList(), default = "10"),
        )
        val model = FlagsFormModel.fromDeclarations(declarations)
        assertFalse(model.degraded)
        assertEquals(2, model.rows.size)
        val modeRow = model.rows.find { it.name == "mode" }
        assertNotNull(modeRow)
        assertEquals("a", modeRow!!.value)
    }

    fun testFlagsFormModelEmptyDeclarations() {
        val model = FlagsFormModel.fromDeclarations(emptyMap())
        assertTrue(model.degraded)
        assertEquals(0, model.rows.size)
    }
}
