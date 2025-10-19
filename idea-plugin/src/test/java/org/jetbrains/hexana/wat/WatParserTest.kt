package org.jetbrains.hexana.wat

import com.intellij.testFramework.ParsingTestCase
import org.jetbrains.hexana.wat.psi.WatParserDefinition
import java.nio.file.Path


class WatParserTest : ParsingTestCase("parser", "wat", true, WatParserDefinition()) {

    override fun getTestDataPath(): String {
        val relativePath = "src/test/data"
        return Path.of(relativePath).toAbsolutePath().toString()
    }

    fun doTest() {
        doTest(true)
    }

    fun testModule() {
        doTest()
    }

    fun testType() {
        doTest()
    }

    fun testImport() {
        doTest()
    }

    fun testExport() {
        doTest()
    }

    fun testGlobal() {
        doTest()
    }

    fun testMemory() {
        doTest()
    }

    fun testData() {
        doTest()
    }

    fun testTable() {
        doTest()
    }

    fun testElem() {
        doTest()
    }

    fun testTag() {
        doTest()
    }

    fun testFunc() {
        doTest()
    }

    fun testUnbalancedCloseParen() {
        doTest()
    }

    fun testUnbalancedOpenParen() {
        doTest()
    }

    fun testSexpr() {
        doTest()
    }
}