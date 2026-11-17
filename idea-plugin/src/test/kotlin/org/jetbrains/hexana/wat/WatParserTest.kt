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

    fun testUnbalancedOpenParen2() {
        doTest()
    }

    fun testSexpr() {
        doTest()
    }

    fun testInstructions() {
        doTest()
    }

    fun testBlocks() {
        doTest()
    }

    fun testUnbalancedBlock() {
        doTest()
    }

    fun testUnbalancedIf() {
        doTest()
    }

    /**
     * `try_table` from the exception-handling proposal. `$nested` is the regression case: an
     * unfolded `try_table` inside a `block` must be closed by its own `end`, not steal the
     * block's.
     */
    fun testTryTable() {
        doTest()
    }

    /**
     * Legacy (pre-`try_table`) exception handling. `$delegating` covers `delegate` standing in
     * for `end`, which is the only way a block instruction terminates on a token other than `end`.
     */
    fun testLegacyTry() {
        doTest()
    }

    fun testAtomics() {
        doTest()
    }

    fun testStackSwitching() {
        doTest()
    }

    fun testComponentTypes() {
        doTest()
    }

    /**
     * `wasm-tools`-shaped component output. It is the structure resolve depends on: nested scopes
     * that each restart their ids, `core`-prefixed sorts, `alias`, `canon` and `instantiate`.
     */
    fun testComponent() {
        doTest()
    }

    /**
     * Custom annotations. Each `(@... )` is one element whose contents stay free-form text, so
     * nothing inside a `(@producers ...)` section is taken for a declaration or a reference.
     */
    fun testAnnotations() {
        doTest()
    }
}