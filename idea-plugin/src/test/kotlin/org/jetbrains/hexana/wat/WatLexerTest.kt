package org.jetbrains.hexana.wat

import com.intellij.lexer.Lexer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.tree.IElementType
import com.intellij.testFramework.LexerTestCase
import org.jetbrains.hexana.wat.psi.WatLexer
import org.jetbrains.hexana.wat.psi.WatToken
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

    fun testFloatLiterals() {
        doTest()
    }

    /**
     * `keywords.wat` doubles as the inventory of every keyword the lexer knows, so a keyword added
     * to [WatToken.ALL_KEYWORDS] without a matching line there would silently go untested. It has
     * drifted before: the component-model keywords (`canon`, `lift`, `component`, ...) were added
     * to the lexer but never to the test data.
     */
    fun testKeywordsTestDataIsComplete() {
        val listed = Path.of(dirPath, "keywords.wat").toFile()
            .readLines()
            .filter { it.isNotBlank() && !it.contains('=') }
            .toSet()
        val declared = WatToken.ALL_KEYWORDS.map { it.name }.toSet()
        assertEquals("keywords.wat does not list every keyword", emptySet<String>(), declared - listed)
        assertEquals("keywords.wat lists words that are not keywords", emptySet<String>(), listed - declared)
    }

    /** Every keyword must lex back to itself as a single token. */
    fun testEveryKeywordRoundTrips() {
        for (keyword in WatToken.ALL_KEYWORDS) {
            val text = keyword.name
            val tokens = mutableListOf<Pair<IElementType, String>>()
            val lexer = createLexer()
            lexer.start(text, 0, text.length, 0)
            while (lexer.tokenType != null) {
                tokens.add(lexer.tokenType!! to text.substring(lexer.tokenStart, lexer.tokenEnd))
                lexer.advance()
            }
            assertEquals("keyword '$text' must lex as one token", listOf(keyword as IElementType to text), tokens)
        }
    }

    fun testTrailingSpaces() {
        val fileName = getPathToTestDataFile(".wat")
        val fileText = FileUtil.loadFile(File(fileName), true)
        doTest(fileText)
    }
}
