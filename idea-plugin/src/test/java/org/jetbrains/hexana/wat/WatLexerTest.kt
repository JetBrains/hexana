package org.jetbrains.hexana.wat

import com.intellij.lexer.Lexer
import com.intellij.testFramework.LexerTestCase
import org.jetbrains.hexana.wat.psi.WatLexer
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
}
