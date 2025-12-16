package org.jetbrains.hexana.wit

import com.intellij.lexer.Lexer
import com.intellij.testFramework.LexerTestCase
import org.jetbrains.hexana.wit.psi.WitLexer
import java.nio.file.Path

class WitLexerTest : LexerTestCase() {
    override fun createLexer(): Lexer {
        return WitLexer()
    }

    override fun getDirPath(): String {
        return "src/test/data/wit/lexer"
    }

    override fun getPathToTestDataFile(extension: String): String {
        val relativePath = dirPath + "/" + getTestName(true) + extension
        return Path.of(relativePath).toAbsolutePath().toString()
    }

    fun doTest() {
        doFileTest("wit")
    }

    fun testComments() {
        doTest()
    }

    fun testPunctuation() {
        doTest()
    }

    fun testIdentifiers() {
        doTest()
    }
}