package org.jetbrains.hexana.wit

import com.intellij.testFramework.ParsingTestCase
import org.jetbrains.hexana.wit.psi.WitParserDefinition
import java.nio.file.Path

class WitParserTest : ParsingTestCase("wit/parser", "wit", true, WitParserDefinition()) {

    override fun getTestDataPath(): String {
        val relativePath = "src/test/data"
        return Path.of(relativePath).toAbsolutePath().toString()
    }

    fun doTest() {
        doTest(true)
    }

    fun testCalculatorSample() { doTest() }

    fun testPackages() { doTest() }

    fun testSimpleInterface() { doTest() }
    fun testMoreComplicatedInterface() { doTest() }

    fun testSimpleWorld() { doTest() }
    fun testMoreAdvancedWorld() { doTest() }
    fun testWorldOfImports() { doTest() }
    fun testWorldUnion() { doTest() }
    fun testWorldIncludeWith() { doTest() }

    fun testSimpleUse() { doTest() }

    fun testFunctions() { doTest() }

    fun testTypes() { doTest() }

    fun testGates() { doTest() }

    fun testMonotonicClockIssue() { doTest() }
}