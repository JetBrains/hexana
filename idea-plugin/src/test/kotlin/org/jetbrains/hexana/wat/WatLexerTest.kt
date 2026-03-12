package org.jetbrains.hexana.wat

import com.intellij.lexer.Lexer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.LexerTestCase
import org.jetbrains.hexana.wat.psi.WatLexer
import java.io.File
import java.nio.file.Path

class WatLexerTest : LexerTestCase() {
    override fun createLexer(): Lexer {
        return WatLexer()
    }

    override fun getDirPath(): String {
        return "src/test/data/lexer"
    }

    override fun getPathToTestDataFile(extension: String): String {
        val relativePath = dirPath + "/" + getTestName(true) + extension
        return Path.of(relativePath).toAbsolutePath().toString()
    }

    fun doTest() {
        doFileTest("wat")
    }

    fun testNumbers() {
        doTest()
    }

    fun testComments() {
        doTest()
    }

    fun testStrings() {
        doTest()
    }

    fun testIdentifiers() {
        doTest()
    }

    fun testAnnotations() {
        doTest()
    }

    fun testKeywords() {
        doTest()
    }

    fun testTrailingSpaces() {
        val fileName = getPathToTestDataFile(".wat")
        val fileText = FileUtil.loadFile(File(fileName), true)
        doTest(fileText)
    }
}
